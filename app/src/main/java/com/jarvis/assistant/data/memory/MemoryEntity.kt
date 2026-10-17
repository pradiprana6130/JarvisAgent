package com.jarvis.assistant.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val userScope: String = SCOPE_OWNER,
    val dateSemantics: String? = null,
    val status: String = STATUS_ACTIVE
) {
    companion object {
        const val SCOPE_OWNER = "owner"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_ARCHIVED = "ARCHIVED"

        const val SEMANTICS_RECURRING_ANNUAL = "RECURRING_ANNUAL"
        const val SEMANTICS_EXACT_DATE = "EXACT_DATE"

        const val KEY_USER_BIRTHDAY = "user_birthday"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_LOCATION = "user_location"
        const val KEY_USER_AGE = "user_age"
        const val KEY_FAVORITE_SONG = "favorite_song"
        const val KEY_DAILY_ROUTINE = "daily_routine"

        const val CATEGORY_PERSONAL = "Personal"
        const val CATEGORY_PREFERENCES = "Preferences"
        const val CATEGORY_CONTACTS = "Contacts"
        const val CATEGORY_ROUTINES = "Routines"
        const val CATEGORY_NOTES = "Notes"

        val ALL_CATEGORIES = listOf(
            CATEGORY_PERSONAL,
            CATEGORY_PREFERENCES,
            CATEGORY_CONTACTS,
            CATEGORY_ROUTINES,
            CATEGORY_NOTES
        )
    }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
