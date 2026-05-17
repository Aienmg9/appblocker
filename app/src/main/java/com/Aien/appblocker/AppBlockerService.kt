package com.Aien.appblocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import java.util.*
import kotlin.collections.HashSet

class AppBlockerService : AccessibilityService() {

    private var blockedCache = HashSet<String>()
    private var groupCache = HashMap<String, Int>() 
    private var groupLimits = HashMap<Int, Int>() 
    private var appsInGroupCache = HashMap<Int, List<String>>()
    private var focusWhitelist = HashSet<String>()
    private var blockedWebsites = HashSet<String>()
    
    private var lastBlockedPackage: String? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentOverlayPackage: String? = null
    private var lastForegroundCheckTime = 0L
    private var activeGroupId: Int? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isBlockingEnabled()) {
                checkCurrentForegroundApp() 
                updateSessionTimer()       
            }
            handler.postDelayed(this, 5000)
        }
    }

    private fun updateSessionTimer() {
        val now = System.currentTimeMillis()
        if (lastForegroundCheckTime == 0L) {
            lastForegroundCheckTime = now
            return
        }

        val elapsed = now - lastForegroundCheckTime
        lastForegroundCheckTime = now

        activeGroupId?.let { groupId ->
            val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
            val currentSpent = prefs.getLong("session_spent_group_$groupId", 0L)
            val newSpent = currentSpent + elapsed
            prefs.edit().putLong("session_spent_group_$groupId", newSpent).apply()
            
            val limitMins = groupLimits[groupId] ?: 0
            if (limitMins > 0 && (newSpent / 1000 / 60) >= limitMins) {
                Log.d("AppBlocker", "STOPERY: Grupa $groupId przekroczyła limit sesji!")
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadCaches()
            if (isBlockingEnabled()) {
                checkCurrentForegroundApp()
                lastForegroundCheckTime = System.currentTimeMillis()
            }
            updateGrayscale()
        }
    }

    override fun onServiceConnected() {
        applyLocale()
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadCaches()
        
        val filter = IntentFilter("com.Aien.appblocker.REFRESH_CACHE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refreshReceiver, filter)
        }
        
        updateGrayscale()
        handler.post(checkRunnable) 
    }

    private fun loadCaches() {
        val db = AppDatabase.get(applicationContext)
        val apps = db.dao().getAllBlockedApps()
        
        blockedCache = apps.map { it.packageName }.toHashSet()
        
        groupCache.clear()
        appsInGroupCache.clear()
        apps.forEach { 
            if (it.groupId != null) {
                groupCache[it.packageName] = it.groupId
                val list = (appsInGroupCache[it.groupId] as? MutableList<String>) ?: mutableListOf<String>().also { l -> appsInGroupCache[it.groupId] = l }
                list.add(it.packageName)
            }
        }
        
        groupLimits.clear()
        db.dao().getAllGroups().forEach { groupLimits[it.id] = it.limitMinutes }
        
        focusWhitelist = db.dao().getFocusWhitelist().map { it.packageName }.toHashSet()
        blockedWebsites = db.dao().getAllWebsites().map { it.url.lowercase() }.toHashSet()
        
        Log.d("AppBlocker", "Caches odświeżone. Apki w grupach: ${groupCache.size}, Limity grup: ${groupLimits.size}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            processPackageChange(packageName, className)
        }
        
        if (isBrowser(packageName)) {
            checkBrowserUrl(event.source)
        }
    }

    private fun isBrowser(pkg: String) = pkg == "com.android.chrome" || pkg == "com.sec.android.app.sbrowser" || pkg == "org.mozilla.firefox" || pkg == "com.opera.browser"

    private fun checkBrowserUrl(node: AccessibilityNodeInfo?) {
        if (node == null || !isBlockingEnabled()) return
        val urlNodes = node.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
            .ifEmpty { node.findAccessibilityNodeInfosByViewId("com.sec.android.app.sbrowser:id/location_bar_edit_text") }
        
        val url = if (urlNodes.isNotEmpty()) urlNodes[0].text?.toString()?.lowercase() else findUrlRecursively(node)

        if (url != null) {
            for (blocked in blockedWebsites) {
                if (url.contains(blocked)) {
                    showOverlay(getString(R.string.blocked_browser_overlay, blocked))
                    return
                }
            }
        }
    }

    private fun findUrlRecursively(node: AccessibilityNodeInfo): String? {
        if (node.className == "android.widget.EditText" || node.className == "android.view.View") {
            val text = node.text?.toString()
            if (text != null && (text.contains(".") || text.contains("http"))) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlRecursively(child)
            if (result != null) return result
        }
        return null
    }

    private fun checkCurrentForegroundApp() {
        val rootNode = rootInActiveWindow
        val packageName = rootNode?.packageName?.toString()
        if (packageName != null) processPackageChange(packageName, "")
    }

    private fun processPackageChange(packageName: String, className: String) {
        val ignored = setOf("com.android.systemui", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
        
        val newGroupId = groupCache[packageName]
        if (newGroupId != activeGroupId) {
            updateSessionTimer() 
            activeGroupId = newGroupId
            lastForegroundCheckTime = System.currentTimeMillis()
        }

        if (packageName == "com.android.settings" || packageName == "com.google.android.settings" || packageName == getPackageName()) {
            if (packageName == getPackageName() && className.contains("MainActivity")) hideOverlay()
            return
        }

        if (!isBlockingEnabled()) {
            hideOverlay()
            return
        }

        val groupId = groupCache[packageName]
        if (groupId != null) {
            val limit = groupLimits[groupId] ?: 0
            if (limit > 0) {
                if (isGroupLimitExceeded(groupId, limit)) {
                    Log.d("AppBlocker", "Blokada: Limit grupy przekroczony ($packageName)")
                    showOverlay(packageName)
                } else {
                    if (currentOverlayPackage == packageName) hideOverlay()
                }
                return 
            }
        }

        val isFocusMode = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("focus_mode", false)
        if (isFocusMode && !focusWhitelist.contains(packageName) && packageName !in ignored) {
            Log.d("AppBlocker", "Blokada: Focus Mode ($packageName)")
            showOverlay(packageName)
            return
        }
        
        if (blockedCache.contains(packageName)) {
            Log.d("AppBlocker", "Blokada: Lista ogólna ($packageName)")
            showOverlay(packageName)
        } else if (packageName.contains(".") && packageName !in ignored) {
            hideOverlay()
        }
    }

    private fun isGroupLimitExceeded(groupId: Int, limitMinutes: Int): Boolean {
        val spentMillis = getSharedPreferences("appblocker", MODE_PRIVATE).getLong("session_spent_group_$groupId", 0L)
        val spentMins = spentMillis / 1000 / 60
        
        if (spentMins >= limitMinutes) {
            Log.d("AppBlocker", "LIMIT SESJI PRZEKROCZONY: Grupa $groupId ($spentMins/$limitMinutes min)")
            return true
        }
        return false
    }

    private fun updateGrayscale() {
        val hardcore = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("hardcore_mode", false)
        val blocking = isBlockingEnabled()
        val autoGrayscale = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("auto_grayscale", false)
        setGrayscale(autoGrayscale && blocking && hardcore)
    }

    private fun setGrayscale(enabled: Boolean) {
        try {
            if (enabled) {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 1)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0)
            } else {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
            }
        } catch (e: Exception) {
            Log.e("AppBlocker", "Błąd Grayscale: ${e.message}")
        }
    }

    private fun showOverlay(packageName: String) {
        if (overlayView != null && currentOverlayPackage == packageName) return
        
        if (overlayView != null) hideOverlay()
        
        currentOverlayPackage = packageName
        Log.d("AppBlocker", "Pokazywanie nakładki dla: $packageName")
        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_Appblocker)
        val inflater = LayoutInflater.from(contextThemeWrapper)
        overlayView = inflater.inflate(R.layout.activity_lock_screen, null)
        val appName = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) { packageName }
        overlayView?.findViewById<TextView>(R.id.tvAppName)?.text = appName
        overlayView?.findViewById<Button>(R.id.btnBack)?.setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            startActivity(home)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(packageName)
            } catch (e: Exception) {}
            hideOverlay()
        }
        try { 
            windowManager?.addView(overlayView, layoutParams) 
        } catch (e: Exception) { 
            Log.e("AppBlocker", "Błąd dodawania nakładki: ${e.message}")
            overlayView = null 
        }
    }

    private fun hideOverlay() {
        currentOverlayPackage = null
        overlayView?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} ; overlayView = null }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { 
        super.onDestroy() 
        handler.removeCallbacks(checkRunnable)
        unregisterReceiver(refreshReceiver) 
        hideOverlay() 
    }
    private fun isBlockingEnabled() = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("blocking_enabled", false)

    private fun applyLocale() {
        val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "pl") ?: "pl"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    companion object {
        fun setBlocking(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("appblocker", MODE_PRIVATE)
            val editor = prefs.edit()
            
            if (enabled) {
                val now = System.currentTimeMillis()
                editor.putLong("activation_timestamp", now)
                
                prefs.all.keys.filter { it.startsWith("session_spent_group_") }.forEach { editor.remove(it) }
                
                val usm = context.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
                val stats = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)
                
                val blockedPkgs = AppDatabase.get(context).dao().getAllBlockedApps()
                var currentTotalMillis = 0L
                blockedPkgs.forEach { currentTotalMillis += stats[it.packageName]?.totalTimeInForeground ?: 0L }
                editor.putLong("activation_usage_millis", currentTotalMillis)
                
                val groups = AppDatabase.get(context).dao().getAllGroups()
                groups.forEach { group ->
                    val appsInGroup = AppDatabase.get(context).dao().getAppsInGroup(group.id)
                    var groupTotal = 0L
                    appsInGroup.forEach { groupTotal += stats[it.packageName]?.totalTimeInForeground ?: 0L }
                    editor.putLong("activation_usage_group_${group.id}", groupTotal)
                }
            }
            
            editor.putBoolean("blocking_enabled", enabled).apply()
            context.sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE"))
        }
    }
}
