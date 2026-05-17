package com.Aien.appblocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton

class AccessibilityEnforcerActivity : AppCompatActivity() {

    private var ignoreLeaveHint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLocale()
        super.onCreate(savedInstanceState)
        
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        
        setContentView(R.layout.activity_accessibility_enforcer)
        
        createNotificationChannel()
        showStickyNotification()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            }
        })

        findViewById<MaterialButton>(R.id.btnFixAccessibility).setOnClickListener {
            ignoreLeaveHint = true
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnLockNow).setOnClickListener {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(this, AppBlockerDeviceAdmin::class.java)
            if (dpm.isAdminActive(adminName)) {
                dpm.lockNow()
            } else {
                Toast.makeText(this, getString(R.string.toast_no_admin), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyLocale() {
        val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "pl") ?: "pl"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onResume() {
        super.onResume()
        ignoreLeaveHint = false
        if (isAccessibilityEnabled()) {
            val prefs = getSharedPreferences("appblocker", MODE_PRIVATE)
            prefs.edit().putLong("accessibility_off_timestamp", 0).apply()
            cancelNotification()
            finish()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(packageName) && enabledServices.contains("AppBlockerService")
    }



    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isAccessibilityEnabled() && !ignoreLeaveHint) {
            val intent = Intent(this, AccessibilityEnforcerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ENFORCER",
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showStickyNotification() {
        val intent = Intent(this, AccessibilityEnforcerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "ENFORCER")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }

    private fun cancelNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1001)
    }
}
