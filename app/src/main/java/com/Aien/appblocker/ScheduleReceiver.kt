package com.Aien.appblocker

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USAGE_STATS_SERVICE
import android.content.Intent
import android.util.Log
import java.util.Calendar

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionType = intent.getIntExtra("actionType", 0) 
        val cooldown = intent.getIntExtra("cooldown", 30)
        val targetGroupId = intent.getIntExtra("targetGroupId", -1)
        val daysOfWeek = intent.getStringExtra("daysOfWeek") ?: "1,2,3,4,5,6,7"
        
        reschedule(context, intent)

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
        if (!daysOfWeek.split(",").contains(today)) {
            Log.d("AppBlocker", "HARMONOGRAM: Pinięto (Dziś ($today) nie jest w $daysOfWeek)")
            return
        }
        
        val prefs = context.getSharedPreferences("appblocker", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        if (actionType == 0) {
            Log.d("AppBlocker", "HARMONOGRAM: Aktywacja blokady (Group: $targetGroupId)")
            editor.putLong("activation_timestamp", System.currentTimeMillis())
            editor.putInt("required_cooldown_mins", cooldown)
            editor.putBoolean("hardcore_mode", true) 
            editor.putBoolean("blocking_enabled", true)
            
            try {
                val usm = context.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val cal = Calendar.getInstance().apply { 
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) 
                }
                val stats = usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())
                
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

                Log.d("AppBlocker", "Zapisano zużycie początkowe dla wszystkich grup")
            } catch (e: Exception) {
                Log.e("AppBlocker", "Błąd zapisu zużycia w harmonogramie: ${e.message}")
            }
            
            if (targetGroupId != -1) {
                val activeGroups = prefs.getStringSet("active_scheduled_groups", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                activeGroups.add(targetGroupId.toString())
                editor.putStringSet("active_scheduled_groups", activeGroups)
            }
        } else {
            Log.d("AppBlocker", "HARMONOGRAM: Dezaktywacja blokady (Group: $targetGroupId)")
            if (targetGroupId == -1) {
                editor.putBoolean("blocking_enabled", false)
                editor.putLong("activation_timestamp", 0)
                editor.putStringSet("active_scheduled_groups", emptySet())
            } else {
                val activeGroups = prefs.getStringSet("active_scheduled_groups", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                activeGroups.remove(targetGroupId.toString())
                editor.putStringSet("active_scheduled_groups", activeGroups)
            }
        }
        
        editor.apply()
        context.sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE"))
    }

    private fun reschedule(context: Context, intent: Intent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val scheduleId = intent.getIntExtra("scheduleId", -1)
        if (scheduleId == -1) return

        val pi = android.app.PendingIntent.getBroadcast(
            context, 
            scheduleId, 
            intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, intent.getIntExtra("hour", 0))
            set(Calendar.MINUTE, intent.getIntExtra("minute", 0))
            set(Calendar.SECOND, 0)
            add(Calendar.DATE, 1)
        }
        
        try {
            if (!am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }
}
