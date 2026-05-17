package com.Aien.appblocker

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.*

class AccessibilityGuardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("appblocker", Context.MODE_PRIVATE)
        val isBlocking = prefs.getBoolean("blocking_enabled", false)
        
        if (!isBlocking) return Result.success()

        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            scheduleNextRun()
            return Result.success()
        }
        
        val isServiceEnabled = isAccessibilityEnabled()
        Log.d("AppBlockerGuard", "Worker sprawdza stan: Accessibility=$isServiceEnabled")
        
        if (!isServiceEnabled) {
            val offT = prefs.getLong("accessibility_off_timestamp", 0L)
            
            val hasRecentCheating = checkRecentBlockedAppUsage()

            if (offT == 0L) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("accessibility_off_timestamp", now).apply()
                Log.d("AppBlockerGuard", "Wykryto brak Accessibility. Rozpoczęto odliczanie.")
            } else {
                val elapsed = System.currentTimeMillis() - offT
                val baseThreshold = 5 * 60 * 1000L 
                val activeThreshold = if (hasRecentCheating) 1 * 60 * 1000L else baseThreshold
                
                Log.d("AppBlockerGuard", "Od wyłączenia: ${elapsed / 1000}s. Próg: ${activeThreshold / 1000}s. Cheating: $hasRecentCheating")
                
                if (elapsed > activeThreshold) {
                    Log.d("AppBlockerGuard", "ALARM! Przekroczono próg. Start Enforcera.")
                    
                    if (hasRecentCheating) {
                        val currentExtra = prefs.getInt("extra_penalty_mins", 0)
                        prefs.edit().putInt("extra_penalty_mins", currentExtra + 15).apply()
                        Log.d("AppBlockerGuard", "PRZYŁAPANY! +15 min kary. Łączna kara dodatkowa: ${currentExtra + 15} min")
                    }

                    val intent = Intent(applicationContext, AccessibilityEnforcerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    applicationContext.startActivity(intent)
                }
            }
        } else {
            if (prefs.getLong("accessibility_off_timestamp", 0L) != 0L) {
                Log.d("AppBlockerGuard", "Accessibility przywrócone. Reset timera.")
                prefs.edit().putLong("accessibility_off_timestamp", 0L).apply()
            }
        }
        
        scheduleNextRun()
        
        return Result.success()
    }

    private fun scheduleNextRun() {
        val nextWork = androidx.work.OneTimeWorkRequestBuilder<AccessibilityGuardWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        androidx.work.WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("AccessibilityGuard", androidx.work.ExistingWorkPolicy.REPLACE, nextWork)
    }

    private fun checkRecentBlockedAppUsage(): Boolean {
        val usm = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - (15 * 60 * 1000L), now)
        
        if (stats == null || stats.isEmpty()) {
            Log.d("AppBlockerGuard", "Brak statystyk użycia (sprawdź uprawnienia)")
            return false
        }
        
        val db = AppDatabase.get(applicationContext)
        val blockedPkgs = db.dao().getAllBlockedApps().map { it.packageName }.toSet()
        
        val recentCheating = stats.any { it.packageName in blockedPkgs && (now - it.lastTimeUsed) < (5 * 60 * 1000L) }
        
        if (recentCheating) Log.d("AppBlockerGuard", "Wykryto niedawne użycie zablokowanej aplikacji!")
        return recentCheating
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        Log.d("AppBlockerGuard", "Włączone usługi: $enabledServices")
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService.packageName == applicationContext.packageName) {
                if (enabledService.className.endsWith("AppBlockerService")) return true
            }
        }
        return false
    }
}
