package com.wmods.wppenhacer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class ScheduledMessageStore extends SQLiteOpenHelper {
    private static final String COLUMN_CONTACT_JIDS = "contact_jids";
    private static final String COLUMN_CONTACT_NAMES = "contact_names";
    private static final String COLUMN_CREATED_TIME = "created_time";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_IS_ACTIVE = "is_active";
    private static final String COLUMN_IS_SENT = "is_sent";
    private static final String COLUMN_LAST_SENT_TIME = "last_sent_time";
    private static final String COLUMN_MESSAGE = "message";
    private static final String COLUMN_REPEAT_DAYS = "repeat_days";
    private static final String COLUMN_REPEAT_TYPE = "repeat_type";
    private static final String COLUMN_SCHEDULED_TIME = "scheduled_time";
    private static final String COLUMN_WHATSAPP_TYPE = "whatsapp_type";
    private static final String DATABASE_NAME = "scheduled_messages.db";
    private static final int DATABASE_VERSION = 6;
    private static final String TABLE_NAME = "scheduled_messages";
    private static ScheduledMessageStore instance;

    private ScheduledMessageStore(Context context) {
        super(context, DATABASE_NAME, (SQLiteDatabase.CursorFactory) null, 6);
    }

    public static synchronized ScheduledMessageStore getInstance(Context context) {
        try {
            if (instance == null) {
                instance = new ScheduledMessageStore(context.getApplicationContext());
            }
        } catch (Throwable th) {
            throw th;
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) throws SQLException {
        db.execSQL("CREATE TABLE scheduled_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, contact_jids TEXT NOT NULL, contact_names TEXT NOT NULL, message TEXT NOT NULL, scheduled_time INTEGER NOT NULL, repeat_type INTEGER NOT NULL DEFAULT 0, repeat_days INTEGER NOT NULL DEFAULT 0, is_active INTEGER NOT NULL DEFAULT 1, is_sent INTEGER NOT NULL DEFAULT 0, last_sent_time INTEGER NOT NULL DEFAULT 0, created_time INTEGER NOT NULL, whatsapp_type INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) throws SQLException {
        if (oldVersion < 6) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }

    public long insertMessage(ScheduledMessage scheduledMessage) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_CONTACT_JIDS, ScheduledMessage.listToJson(scheduledMessage.getContactJids()));
        contentValues.put(COLUMN_CONTACT_NAMES, ScheduledMessage.listToJson(scheduledMessage.getContactNames()));
        contentValues.put(COLUMN_MESSAGE, scheduledMessage.getMessage());
        contentValues.put(COLUMN_SCHEDULED_TIME, Long.valueOf(scheduledMessage.getScheduledTime()));
        contentValues.put(COLUMN_REPEAT_TYPE, Integer.valueOf(scheduledMessage.getRepeatType()));
        contentValues.put(COLUMN_REPEAT_DAYS, Integer.valueOf(scheduledMessage.getRepeatDays()));
        contentValues.put(COLUMN_IS_ACTIVE, Integer.valueOf(scheduledMessage.isActive() ? 1 : 0));
        contentValues.put(COLUMN_IS_SENT, Integer.valueOf(scheduledMessage.isSent() ? 1 : 0));
        contentValues.put(COLUMN_LAST_SENT_TIME, Long.valueOf(scheduledMessage.getLastSentTime()));
        contentValues.put(COLUMN_CREATED_TIME, Long.valueOf(scheduledMessage.getCreatedTime()));
        contentValues.put(COLUMN_WHATSAPP_TYPE, Integer.valueOf(scheduledMessage.getWhatsappType()));
        long jInsert = writableDatabase.insert(TABLE_NAME, null, contentValues);
        scheduledMessage.setId(jInsert);
        return jInsert;
    }

    public boolean updateMessage(ScheduledMessage scheduledMessage) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_CONTACT_JIDS, ScheduledMessage.listToJson(scheduledMessage.getContactJids()));
        contentValues.put(COLUMN_CONTACT_NAMES, ScheduledMessage.listToJson(scheduledMessage.getContactNames()));
        contentValues.put(COLUMN_MESSAGE, scheduledMessage.getMessage());
        contentValues.put(COLUMN_SCHEDULED_TIME, Long.valueOf(scheduledMessage.getScheduledTime()));
        contentValues.put(COLUMN_REPEAT_TYPE, Integer.valueOf(scheduledMessage.getRepeatType()));
        contentValues.put(COLUMN_REPEAT_DAYS, Integer.valueOf(scheduledMessage.getRepeatDays()));
        contentValues.put(COLUMN_IS_ACTIVE, Integer.valueOf(scheduledMessage.isActive() ? 1 : 0));
        contentValues.put(COLUMN_IS_SENT, Integer.valueOf(scheduledMessage.isSent() ? 1 : 0));
        contentValues.put(COLUMN_LAST_SENT_TIME, Long.valueOf(scheduledMessage.getLastSentTime()));
        contentValues.put(COLUMN_WHATSAPP_TYPE, Integer.valueOf(scheduledMessage.getWhatsappType()));
        return writableDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(scheduledMessage.getId())}) > 0;
    }

    public boolean deleteMessage(long id) {
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.delete(TABLE_NAME, "id = ?", new String[]{String.valueOf(id)});
        return rows > 0;
    }

    public ScheduledMessage getMessage(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        ScheduledMessage message = null;
        if (cursor.moveToFirst()) {
            message = cursorToMessage(cursor);
        }
        cursor.close();
        return message;
    }

    public List<ScheduledMessage> getAllMessages() {
        List<ScheduledMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, null, null, null, null, "scheduled_time ASC");
        if (cursor.moveToFirst()) {
            do {
                messages.add(cursorToMessage(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return messages;
    }

    public List<ScheduledMessage> getActiveMessages() {
        List<ScheduledMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "is_active = 1", null, null, null, "scheduled_time ASC");
        if (cursor.moveToFirst()) {
            do {
                messages.add(cursorToMessage(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return messages;
    }

    public List<ScheduledMessage> getPendingMessages() {
        List<ScheduledMessage> messages = new ArrayList<>();
        List<ScheduledMessage> activeMessages = getActiveMessages();
        for (ScheduledMessage message : activeMessages) {
            if (message.shouldSendNow()) {
                messages.add(message);
            }
        }
        return messages;
    }

    public List<ScheduledMessage> getSentMessages() {
        List<ScheduledMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "is_sent = 1 AND repeat_type = 0", null, null, null, "last_sent_time DESC");
        if (cursor.moveToFirst()) {
            do {
                messages.add(cursorToMessage(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return messages;
    }

    public void markAsSent(long id) {
        SQLiteDatabase db = getWritableDatabase();
        
        // Check repeat type to see if we should deactivate
        int repeatType = 0;
        Cursor cursor = db.query(TABLE_NAME, new String[]{COLUMN_REPEAT_TYPE}, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                repeatType = cursor.getInt(0);
            }
            cursor.close();
        }

        ContentValues values = new ContentValues();
        values.put(COLUMN_IS_SENT, 1);
        values.put(COLUMN_LAST_SENT_TIME, System.currentTimeMillis());
        
        // Automatic deactivation for "Once" type messages
        if (repeatType == 0) {
            values.put(COLUMN_IS_ACTIVE, 0);
        }
        
        db.update(TABLE_NAME, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public void toggleActive(long j, boolean z) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_IS_ACTIVE, Integer.valueOf(z ? 1 : 0));
        writableDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(j)});
    }

    private ScheduledMessage cursorToMessage(Cursor cursor) {
        String jidsJson;
        int i;
        int i2;
        boolean z;
        int idIndex = cursor.getColumnIndex("id");
        int jidsIndex = cursor.getColumnIndex(COLUMN_CONTACT_JIDS);
        int namesIndex = cursor.getColumnIndex(COLUMN_CONTACT_NAMES);
        int messageIndex = cursor.getColumnIndex(COLUMN_MESSAGE);
        int timeIndex = cursor.getColumnIndex(COLUMN_SCHEDULED_TIME);
        int repeatIndex = cursor.getColumnIndex(COLUMN_REPEAT_TYPE);
        int repeatDaysIndex = cursor.getColumnIndex(COLUMN_REPEAT_DAYS);
        int activeIndex = cursor.getColumnIndex(COLUMN_IS_ACTIVE);
        int sentIndex = cursor.getColumnIndex(COLUMN_IS_SENT);
        int lastSentIndex = cursor.getColumnIndex(COLUMN_LAST_SENT_TIME);
        int createdIndex = cursor.getColumnIndex(COLUMN_CREATED_TIME);
        int whatsappTypeIndex = cursor.getColumnIndex(COLUMN_WHATSAPP_TYPE);
        String namesJson = "[]";
        if (jidsIndex >= 0) {
            jidsJson = cursor.getString(jidsIndex);
        } else {
            jidsJson = "[]";
        }
        if (namesIndex >= 0) {
            namesJson = cursor.getString(namesIndex);
        }
        long j = cursor.getLong(idIndex);
        List<String> listJsonToList = ScheduledMessage.jsonToList(jidsJson);
        List<String> listJsonToList2 = ScheduledMessage.jsonToList(namesJson);
        String string = cursor.getString(messageIndex);
        long j2 = cursor.getLong(timeIndex);
        int i3 = cursor.getInt(repeatIndex);
        if (repeatDaysIndex >= 0) {
            i = cursor.getInt(repeatDaysIndex);
        } else {
            i = 0;
        }
        int idIndex2 = cursor.getInt(activeIndex);
        if (idIndex2 == 1) {
            i2 = i;
            z = true;
        } else {
            i2 = i;
            z = false;
        }
        return new ScheduledMessage(j, listJsonToList, listJsonToList2, string, j2, i3, i2, z, cursor.getInt(sentIndex) == 1, cursor.getLong(lastSentIndex), cursor.getLong(createdIndex), whatsappTypeIndex >= 0 ? cursor.getInt(whatsappTypeIndex) : 0);
    }
}