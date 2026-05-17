package com.Aien.appblocker

import android.app.*
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.*
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.*
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var isAddingCard = false
    private var isUnlocking = false
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private var unlockTimer: CountDownTimer? = null
    private var installedAppsCache: List<android.content.pm.ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLocale()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        executor = ContextCompat.getMainExecutor(this)

        setupBiometrics()
        checkPermissions()
        setupNavigation()
        setupUI()
        loadApps()
        setupSchedules()
        setupWebsites()
        setupGroups()
        updateHomeStats()
        
        if (getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("blocking_enabled", false)) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<AccessibilityGuardWorker>().build()
            androidx.work.WorkManager.getInstance(this).enqueueUniqueWork("AccessibilityGuard", androidx.work.ExistingWorkPolicy.KEEP, workRequest)
        }
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val lHome = findViewById<View>(R.id.layout_home); val lApps = findViewById<View>(R.id.layout_apps)
        val lWeb = findViewById<View>(R.id.layout_websites); val lSched = findViewById<View>(R.id.layout_schedules)

        nav.setOnItemSelectedListener { item ->
            listOf(lHome, lApps, lWeb, lSched).forEach { it.visibility = View.GONE }
            when (item.itemId) {
                R.id.nav_home -> { lHome.visibility = View.VISIBLE; updateHomeStats(); true }
                R.id.nav_apps -> { lApps.visibility = View.VISIBLE; loadApps(); loadGroups(); true }
                R.id.nav_websites -> { lWeb.visibility = View.VISIBLE; loadWebsites(); true }
                R.id.nav_schedules -> { lSched.visibility = View.VISIBLE; loadSchedules(); true }
                else -> false
            }
        }
        
        findViewById<RadioGroup>(R.id.rgAppToggle).setOnCheckedChangeListener { _, id ->
            findViewById<View>(R.id.view_apps).visibility = if (id == R.id.rbApps) View.VISIBLE else View.GONE
            findViewById<View>(R.id.view_groups).visibility = if (id == R.id.rbGroups) View.VISIBLE else View.GONE
        }
    }

    private fun setupBiometrics() {
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(res: BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(res); startUnlockSequence() }
        })
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_unlock_title))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()
    }
    
    private fun startUnlockSequence() {
        runOnUiThread {
            val hardcore = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("hardcore_mode", false)
            if (hardcore) {
                isUnlocking = false
                val lbl = findViewById<TextView>(R.id.tvToggleLabel); val sub = findViewById<TextView>(R.id.tvStatusSub)
                val btn = findViewById<View>(R.id.btnToggleContainer)
                btn.isEnabled = false; btn.alpha = 0.8f
                unlockTimer?.cancel()
                unlockTimer = object : CountDownTimer(60000, 1000) {
                    override fun onTick(ms: Long) { lbl.text = "${ms / 1000}s"; sub.text = getString(R.string.patience_msg) }
                    override fun onFinish() { btn.isEnabled = true; btn.alpha = 1.0f; performUnlock() }
                }.start()
            } else { performUnlock() }
        }
    }
    
    private fun performUnlock() {
        AppBlockerService.setBlocking(applicationContext, false)
        getSharedPreferences("appblocker", MODE_PRIVATE).edit().putInt("extra_penalty_mins", 0).apply()
        updateToggleState(); updateOverlayState()
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        if (!hasUsageStatsPermission()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        if (!isAccessibilityEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    val genericIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(genericIntent)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AppBlockerDeviceAdmin::class.java)
        if (!dpm.isAdminActive(admin)) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Ochrona") })
        }
    }

    private fun setupUI() {
        val btnT = findViewById<View>(R.id.btnToggleContainer)
        val swF = findViewById<Switch>(R.id.swFocusMode); val swG = findViewById<Switch>(R.id.swAutoGrayscale)
        val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
        
        prefs.edit().putBoolean("hardcore_mode", true).apply()
        
        swF.isChecked = prefs.getBoolean("focus_mode", false)
        swF.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("focus_mode", isChecked).apply(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }
        swG.isChecked = prefs.getBoolean("auto_grayscale", false)
        swG.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("auto_grayscale", isChecked).apply(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }
        
        updateToggleState(); updateOverlayState()

        btnT.setOnClickListener {
            val current = prefs.getBoolean("blocking_enabled", false)
            if (current) {
                if (!isAccessibilityEnabled()) {
                    Toast.makeText(this, getString(R.string.toast_enable_accessibility), Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }

                val actTime = prefs.getLong("activation_timestamp", 0L)
                val baseCoolM = prefs.getInt("required_cooldown_mins", 60).toLong()
                val usageAtStart = prefs.getLong("activation_usage_millis", 0L)
                
                val currentTotalUsage = calculateCurrentBlockedAppsUsage()
                val cheatingMillis = Math.max(0L, currentTotalUsage - usageAtStart)
                val cheatingMins = cheatingMillis / 1000 / 60
                
                val penaltyMins = Math.max(0L, (cheatingMins - 5) * 2) 
                val extraPenalty = prefs.getInt("extra_penalty_mins", 0).toLong()
                val totalCoolM = baseCoolM + penaltyMins + extraPenalty
                
                val now = System.currentTimeMillis()
                if (now < actTime + (totalCoolM * 60 * 1000L)) {
                    val rem = (actTime + (totalCoolM * 60 * 1000L) - now) / 1000 / 60
                    val msg = if (extraPenalty > 0) 
                        getString(R.string.penalty_recidivism_msg, penaltyMins, extraPenalty, rem)
                        else getString(R.string.penalty_cheating_msg, penaltyMins, rem)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                
                isUnlocking = true; Toast.makeText(this, getString(R.string.toast_tap_card), Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putInt("required_cooldown_mins", 60).apply()
                prefs.edit().putInt("extra_penalty_mins", 0).apply() 
                AppBlockerService.setBlocking(this, true); updateToggleState(); updateOverlayState()
                
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<AccessibilityGuardWorker>().build()
                androidx.work.WorkManager.getInstance(this).enqueueUniqueWork("AccessibilityGuard", androidx.work.ExistingWorkPolicy.KEEP, workRequest)
            }
        }

        findViewById<Button>(R.id.btnAddCard).setOnClickListener { isAddingCard = true; isUnlocking = false; Toast.makeText(this, getString(R.string.toast_tap_card), Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnRemoveCard).setOnClickListener { AppDatabase.get(this).dao().clearCards(); updateCardStatus(findViewById(R.id.tvCardStatus)) }
        updateCardStatus(findViewById(R.id.tvCardStatus))

        findViewById<Button>(R.id.btnChangeLanguage).setOnClickListener {
            val currentLang = prefs.getString("app_lang", "pl")
            val nextLang = if (currentLang == "pl") "en" else "pl"
            prefs.edit().putString("app_lang", nextLang).apply()
            recreate()
        }
    }

    private fun applyLocale() {
        val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "pl") ?: "pl"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun calculateCurrentBlockedAppsUsage(): Long {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val stats = usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())
        val blockedPkgs = AppDatabase.get(this).dao().getAllBlockedApps().map { it.packageName }
        
        var sum = 0L
        blockedPkgs.forEach { sum += stats[it]?.totalTimeInForeground ?: 0L }
        return sum
    }

    private fun setupSchedules() {
        val rv = findViewById<RecyclerView>(R.id.rvSchedules); rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ScheduleAdapter(emptyList(), AppDatabase.get(this)) { AppDatabase.get(this).dao().deleteSchedule(it); loadSchedules() }
        findViewById<Button>(R.id.btnAddSchedule).setOnClickListener { showAddScheduleDialog() }
    }

    private fun loadSchedules() {
        val list = AppDatabase.get(this).dao().getAllSchedules()
        (findViewById<RecyclerView>(R.id.rvSchedules).adapter as? ScheduleAdapter)?.updateData(list)
    }

    private fun showAddScheduleDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add_schedule, null)
        val et = v.findViewById<EditText>(R.id.etCooldown)
        val rg = v.findViewById<RadioGroup>(R.id.rgActionType)
        val lay = v.findViewById<View>(R.id.layoutStartOptions)
        val spTarget = v.findViewById<Spinner>(R.id.spGroupTarget)
        var h = 8; var m = 0; val btnT = v.findViewById<Button>(R.id.btnPickTime)
        
        val cbDays = listOf(
            v.findViewById<CheckBox>(R.id.cbSun) to 1,
            v.findViewById<CheckBox>(R.id.cbMon) to 2,
            v.findViewById<CheckBox>(R.id.cbTue) to 3,
            v.findViewById<CheckBox>(R.id.cbWed) to 4,
            v.findViewById<CheckBox>(R.id.cbThu) to 5,
            v.findViewById<CheckBox>(R.id.cbFri) to 6,
            v.findViewById<CheckBox>(R.id.cbSat) to 7
        )

        val groups = AppDatabase.get(this).dao().getAllGroups()
        val options = mutableListOf("Wszystko")
        options.addAll(groups.map { it.name })
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTarget.adapter = adapter
        rg.setOnCheckedChangeListener { _, id -> lay.visibility = if (id == R.id.rbStart) View.VISIBLE else View.GONE }
        btnT.setOnClickListener { TimePickerDialog(this, { _, hour, min -> h = hour; m = min; btnT.text = String.format("%02d:%02d", h, m) }, h, m, true).show() }
        
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_schedule_title)).setView(v).setPositiveButton("OK") { _, _ ->
            val type = if (rg.checkedRadioButtonId == R.id.rbStart) 0 else 1
            val selectedIdx = spTarget.selectedItemPosition
            val targetGroupId = if (selectedIdx == 0) null else groups[selectedIdx - 1].id
            
            val selectedDays = cbDays.filter { it.first.isChecked }.map { it.second }.joinToString(",")
            
            val sched = BlockedSchedule(
                hour = h, 
                minute = m, 
                cooldownMinutes = et.text.toString().toIntOrNull() ?: 30, 
                actionType = type, 
                targetGroupId = targetGroupId,
                daysOfWeek = selectedDays
            )
            val id = AppDatabase.get(this).dao().insertSchedule(sched)
            scheduleAlarm(sched.copy(id = id.toInt()))
            loadSchedules()
        }.show()
    }

    private fun scheduleAlarm(s: BlockedSchedule) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ScheduleReceiver::class.java).apply {
            putExtra("scheduleId", s.id)
            putExtra("actionType", s.actionType)
            putExtra("cooldown", s.cooldownMinutes)
            putExtra("targetGroupId", s.targetGroupId ?: -1)
            putExtra("daysOfWeek", s.daysOfWeek)
            putExtra("hour", s.hour)
            putExtra("minute", s.minute)
        }
        val pi = PendingIntent.getBroadcast(this, s.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.hour)
            set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun setupWebsites() {
        val rv = findViewById<RecyclerView>(R.id.rvWebsites); rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = WebsiteAdapter(emptyList()) { AppDatabase.get(this).dao().deleteWebsite(it); loadWebsites(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }
        findViewById<Button>(R.id.btnAddWebsite).setOnClickListener { showAddWebsiteDialog() }
    }

    private fun loadWebsites() {
        val list = AppDatabase.get(this).dao().getAllWebsites()
        (findViewById<RecyclerView>(R.id.rvWebsites).adapter as? WebsiteAdapter)?.updateData(list)
    }

    private fun showAddWebsiteDialog() {
        val et = EditText(this); et.hint = "tiktok.com"
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_website_title)).setView(et).setPositiveButton(getString(R.string.btn_add)) { _, _ ->
            val url = et.text.toString().trim().lowercase()
            if (url.isNotEmpty()) { AppDatabase.get(this).dao().insertWebsite(BlockedWebsite(url)); loadWebsites(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }
        }.show()
    }

    private fun setupGroups() {
        val rv = findViewById<RecyclerView>(R.id.rvGroups); rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = AppGroupAdapter(emptyList(), { AppDatabase.get(this).dao().deleteGroup(it); loadGroups(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }, { showGroupAppsDialog(it) })
        findViewById<Button>(R.id.btnAddGroup).setOnClickListener { showAddGroupDialog() }
    }

    private fun loadGroups() {
        val list = AppDatabase.get(this).dao().getAllGroups()
        (findViewById<RecyclerView>(R.id.rvGroups).adapter as? AppGroupAdapter)?.updateData(list)
    }

    private fun showAddGroupDialog() {
        val v = LinearLayout(this); v.orientation = LinearLayout.VERTICAL; v.setPadding(32, 32, 32, 32)
        val etN = EditText(this); etN.hint = getString(R.string.group_name_hint); v.addView(etN)
        val etL = EditText(this); etL.hint = getString(R.string.daily_limit_hint); etL.inputType = android.text.InputType.TYPE_CLASS_NUMBER; v.addView(etL)
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_new_group_title)).setView(v).setPositiveButton("OK") { _, _ ->
            val name = etN.text.toString(); val limit = etL.text.toString().toIntOrNull() ?: 0
            if (name.isNotEmpty()) { AppDatabase.get(this).dao().insertGroup(AppGroup(name = name, limitMinutes = limit)); loadGroups() }
        }.show()
    }

    private fun showGroupAppsDialog(group: AppGroup) {
        Toast.makeText(this, getString(R.string.toast_create_group), Toast.LENGTH_LONG).show()
    }

    private fun updateToggleState() {
        val enabled = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("blocking_enabled", false)
        val lbl = findViewById<TextView>(R.id.tvToggleLabel); val sub = findViewById<TextView>(R.id.tvStatusSub); val cnt = findViewById<View>(R.id.btnToggleContainer)
        if (enabled) { lbl.text = getString(R.string.status_on); sub.text = getString(R.string.status_active); cnt.background.setTint(Color.parseColor("#CF6679")) } 
        else { lbl.text = getString(R.string.status_off); sub.text = getString(R.string.status_disabled); cnt.background.setTint(Color.parseColor("#BB86FC")) }
    }

    private fun updateOverlayState() {
        val enabled = getSharedPreferences("appblocker", MODE_PRIVATE).getBoolean("blocking_enabled", false)
        findViewById<View>(R.id.appsOverlay).visibility = if (enabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvOverlayMsg).visibility = if (enabled) View.VISIBLE else View.GONE
        
        findViewById<Switch>(R.id.swFocusMode).isEnabled = !enabled
        findViewById<Switch>(R.id.swAutoGrayscale).isEnabled = !enabled

        val web = findViewById<ViewGroup>(R.id.layout_websites); val sch = findViewById<ViewGroup>(R.id.layout_schedules)
        if (enabled) { web.alpha = 0.2f; sch.alpha = 0.2f; web.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; sch.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        else { web.alpha = 1.0f; sch.alpha = 1.0f; web.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS; sch.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS }
        findViewById<View>(R.id.btnAddWebsite).isEnabled = !enabled; findViewById<View>(R.id.btnAddSchedule).isEnabled = !enabled
    }

    private fun loadApps() {
        val pm = packageManager; val db = AppDatabase.get(this); val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val now = System.currentTimeMillis()
        val statsToday = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)
        val statsLife = usm.queryAndAggregateUsageStats(Calendar.getInstance().apply { set(2020, 0, 1) }.timeInMillis, now)
        
        val blockedApps = db.dao().getAllBlockedApps().map { it.packageName }.toSet()
        val whitelist = db.dao().getFocusWhitelist().map { it.packageName }.toSet()

        if (installedAppsCache == null) {
            installedAppsCache = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
        }

        val apps = installedAppsCache!!.map { info ->
                val tT = (statsToday[info.packageName]?.totalTimeInForeground ?: 0L) / 1000 / 60
                val tL = (statsLife[info.packageName]?.totalTimeInForeground ?: 0L) / 1000 / 60
                AppItem(
                    info.packageName, 
                    pm.getApplicationLabel(info).toString(), 
                    blockedApps.contains(info.packageName), 
                    pm.getApplicationIcon(info), 
                    getString(R.string.stats_today_prefix, tT), 
                    getString(R.string.stats_lifetime_prefix, formatMinutes(tL)), 
                    tT
                ) 
            }.sortedByDescending { it.rawTimeToday }

        findViewById<TextView>(R.id.tvAppCount).text = "${apps.size} ${getString(R.string.app_count_suffix)}"
        val rv = findViewById<RecyclerView>(R.id.rvApps); rv.layoutManager = LinearLayoutManager(this); rv.adapter = AppListAdapter(apps, db, { onAppClicked(it) }, { onAppLongClicked(it) })
        updateOverlayState()
        findViewById<SearchView>(R.id.searchView).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(t: String?): Boolean { (rv.adapter as AppListAdapter).filter(t ?: ""); return true }
        })
    }

    private fun onAppClicked(app: AppItem) {
        val groups = AppDatabase.get(this).dao().getAllGroups()
        if (groups.isEmpty()) { Toast.makeText(this, "Stwórz grupę!", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Grupa").setItems(groups.map { it.name }.toTypedArray()) { _, w -> AppDatabase.get(this).dao().insertBlockedApp(BlockedApp(app.packageName, app.appName, groups[w].id)); loadApps(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE")) }.show()
    }

    private fun onAppLongClicked(app: AppItem) {
        val db = AppDatabase.get(this).dao(); if (db.isInFocusWhitelist(app.packageName)) db.removeFromFocusWhitelist(FocusWhitelist(app.packageName)) else db.addToFocusWhitelist(FocusWhitelist(app.packageName))
        loadApps(); sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE"))
    }

    private fun updateHomeStats() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager; val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val tT = (usm.queryAndAggregateUsageStats(cal.timeInMillis, now)[packageName]?.totalTimeInForeground ?: 0L) / 1000 / 60
        val tL = (usm.queryAndAggregateUsageStats(Calendar.getInstance().apply { set(2020, 0, 1) }.timeInMillis, now)[packageName]?.totalTimeInForeground ?: 0L) / 1000 / 60
        findViewById<TextView>(R.id.tvHomeTodayStats).text = getString(R.string.home_today_stats, tT.toString())
        findViewById<TextView>(R.id.tvHomeLifetimeStats).text = getString(R.string.home_lifetime_stats, formatMinutes(tL))
    }

    private fun formatMinutes(t: Long) = if (t / 60 > 0) "${t / 60}h ${t % 60}min" else "${t}min"

    override fun onResume() { 
        super.onResume() 
        nfcAdapter?.enableReaderMode(this, { handleTag(it) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        updateOverlayState(); updateToggleState(); updateHomeStats()
        val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
        if (prefs.getBoolean("blocking_enabled", false) && !isAccessibilityEnabled()) {
            val offT = prefs.getLong("accessibility_off_timestamp", 0L)
            if (offT == 0L) prefs.edit().putLong("accessibility_off_timestamp", System.currentTimeMillis()).apply()
            else if (System.currentTimeMillis() > offT + (15 * 60 * 1000L)) startActivity(Intent(this, AccessibilityEnforcerActivity::class.java))
        } else { prefs.edit().putLong("accessibility_off_timestamp", 0L).apply() }
    }

    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
    override fun onDestroy() { super.onDestroy(); unlockTimer?.cancel() }

    private fun handleTag(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }; val db = AppDatabase.get(applicationContext)
        when {
            isAddingCard -> { db.dao().insertCard(NfcCard(uid = uid)); isAddingCard = false; runOnUiThread { updateCardStatus(findViewById(R.id.tvCardStatus)); Toast.makeText(this, getString(R.string.toast_card_added), Toast.LENGTH_SHORT).show() } }
            isUnlocking -> { val sc = db.dao().getCard(); if (sc == null || sc.uid == uid) { isUnlocking = false; startUnlockSequence() } else runOnUiThread { Toast.makeText(this, getString(R.string.toast_wrong_card), Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun updateCardStatus(tv: TextView) { 
        val c = AppDatabase.get(this).dao().getCard()
        tv.text = if (c != null) "${getString(R.string.card_status_prefix)}${c.uid}" else "${getString(R.string.card_status_prefix)}${getString(R.string.card_none)}" 
    }
    private fun hasUsageStatsPermission() = (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService.packageName == packageName) {
                if (enabledService.className.endsWith("AppBlockerService")) return true
            }
        }
        return false
    }
}

data class AppItem(val packageName: String, val appName: String, var isBlocked: Boolean = false, val icon: android.graphics.drawable.Drawable? = null, val usageTime: String = "Dzisiaj: 0 min", val lifeTimeTime: String = "Łącznie: 0 min", val rawTimeToday: Long = 0)
