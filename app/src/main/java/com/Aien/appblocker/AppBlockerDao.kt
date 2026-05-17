package com.Aien.appblocker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppBlockerDao {

    @Query("SELECT * FROM nfc_cards LIMIT 1")
    fun getCard(): NfcCard?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCard(card: NfcCard)

    @Query("DELETE FROM nfc_cards")
    fun clearCards()

    @Query("SELECT * FROM blocked_apps")
    fun getAllBlockedApps(): List<BlockedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlockedApp(app: BlockedApp)

    @Delete
    fun deleteBlockedApp(app: BlockedApp)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_apps WHERE packageName = :packageName)")
    fun isBlocked(packageName: String): Boolean

    @Query("SELECT * FROM schedules ORDER BY hour, minute")
    fun getAllSchedules(): List<BlockedSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSchedule(schedule: BlockedSchedule): Long

    @Delete
    fun deleteSchedule(schedule: BlockedSchedule)

    @Query("SELECT * FROM app_groups")
    fun getAllGroups(): List<AppGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: AppGroup): Long

    @Delete
    fun deleteGroup(group: AppGroup)

    @Query("UPDATE blocked_apps SET groupId = :groupId WHERE packageName = :packageName")
    fun setAppGroup(packageName: String, groupId: Int?)

    @Query("SELECT * FROM blocked_apps WHERE groupId = :groupId")
    fun getAppsInGroup(groupId: Int): List<BlockedApp>

    @Query("SELECT * FROM blocked_websites")
    fun getAllWebsites(): List<BlockedWebsite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertWebsite(website: BlockedWebsite)

    @Delete
    fun deleteWebsite(website: BlockedWebsite)

    @Query("SELECT * FROM focus_whitelist")
    fun getFocusWhitelist(): List<FocusWhitelist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addToFocusWhitelist(item: FocusWhitelist)

    @Delete
    fun removeFromFocusWhitelist(item: FocusWhitelist)

    @Query("SELECT EXISTS(SELECT 1 FROM focus_whitelist WHERE packageName = :packageName)")
    fun isInFocusWhitelist(packageName: String): Boolean
}
