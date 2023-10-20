// NotificationDbHelper.kt

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotificationDeletedDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "notificationsDeleted.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NOTIFICATIONS = "notificationsDeleted"
        const val COLUMN_ID = "id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_TEXT = "text"
        const val COLUMN_BIG_TEXT = "bigText"
        const val COLUMN__ID = "_id"
        const val COLUMN_CHANNEL_ID = "channelId"
        const val COLUMN_TIMESTAMP = "timestamp"
        // Add other columns here
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = "CREATE TABLE $TABLE_NOTIFICATIONS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_TITLE TEXT," +
                "$COLUMN_PACKAGE_NAME TEXT," +
                "$COLUMN_TEXT TEXT," +
                "$COLUMN_BIG_TEXT TEXT," +
                "$COLUMN__ID TEXT," +
                "$COLUMN_CHANNEL_ID TEXT," +
                "$COLUMN_TIMESTAMP INTEGER" +
                // Add other columns here
                ")"
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
        onCreate(db)
    }
}
