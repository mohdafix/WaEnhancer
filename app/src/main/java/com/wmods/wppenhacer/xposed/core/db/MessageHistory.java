package com.wmods.wppenhacer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

public class MessageHistory extends SQLiteOpenHelper {
    private static MessageHistory mInstance;
    private SQLiteDatabase dbWrite;

    private static final int MESSAGE_CACHE_SIZE = 100;
    private static final int SEEN_MESSAGE_CACHE_SIZE = 200;
    private static final int SEEN_MESSAGES_LIST_CACHE_SIZE = 50;
    private final LruCache<Long, ArrayList<MessageItem>> messagesCache;
    private final LruCache<String, MessageSeenItem> seenMessageCache;
    private final LruCache<String, List<MessageSeenItem>> seenMessagesListCache;

    public enum MessageType {
        MESSAGE_TYPE,
        VIEW_ONCE_TYPE
    }

    public MessageHistory(Context context) {
        super(context, "MessageHistory.db", null, 2);
        messagesCache = new LruCache<>(MESSAGE_CACHE_SIZE);
        seenMessageCache = new LruCache<>(SEEN_MESSAGE_CACHE_SIZE);
        seenMessagesListCache = new LruCache<>(SEEN_MESSAGES_LIST_CACHE_SIZE);
        XposedBridge.log("MessageHistory: Constructor called.");
    }

    public static MessageHistory getInstance() {
        synchronized (MessageHistory.class) {
            XposedBridge.log("MessageHistory: getInstance called.");
            // Check if instance is null or if the database is not open
            if (mInstance == null || mInstance.dbWrite == null || !mInstance.dbWrite.isOpen()) {
                XposedBridge.log("MessageHistory: Instance is null or DB is not open. Attempting initialization.");
                try {
                    Context appContext = Utils.getApplication();
                    if (appContext == null) {
                        XposedBridge.log("MessageHistory: Application context is null. Cannot initialize.");
                        return null; // Or handle error appropriately
                    }

                    mInstance = new MessageHistory(appContext);

                    // Explicitly get writable database and check if it's open
                    mInstance.dbWrite = mInstance.getWritableDatabase();
                    if (mInstance.dbWrite == null || !mInstance.dbWrite.isOpen()) {
                        XposedBridge.log("MessageHistory: Failed to get writable database. Resetting instance.");
                        mInstance = null; // Reset instance if DB is not open
                        return null;
                    }

                    XposedBridge.log("MessageHistory: Instance initialized successfully. DB is open.");

                } catch (Exception e) {
                    XposedBridge.log("MessageHistory: Exception during initialization: " + e.getMessage());
                    e.printStackTrace();
                    mInstance = null; // Ensure instance is null if any error occurs
                }
            } else {
                XposedBridge.log("MessageHistory: Instance is valid and DB is open.");
            }
        }
        return mInstance;
    }

    public final void insertMessage(long id, String message, long timestamp) {
        synchronized (this) {
            if (dbWrite == null || !dbWrite.isOpen()) {
                XposedBridge.log("MessageHistory: insertMessage - dbWrite is null or closed. Cannot insert.");
                return;
            }
            XposedBridge.log("MessageHistory: Inserting message for row_id=" + id);
            ContentValues contentValues0 = new ContentValues();
            contentValues0.put("row_id", id);
            contentValues0.put("text_data", message);
            contentValues0.put("editTimestamp", timestamp);
            try {
                dbWrite.insert("MessageHistory", null, contentValues0);
                XposedBridge.log("MessageHistory: Inserted message for row_id=" + id);
                // Invalidate cache for this message ID
                messagesCache.remove(id);
            } catch (SQLiteException e) {
                XposedBridge.log("MessageHistory: SQLiteException during insertMessage for row_id=" + id + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public ArrayList<MessageItem> getMessages(long v) {
        if (dbWrite == null || !dbWrite.isOpen()) {
            XposedBridge.log("MessageHistory: getMessages - dbWrite is null or closed. Cannot query for row_id=" + v);
            return null;
        }

        // Check cache first
        ArrayList<MessageItem> cachedMessages = messagesCache.get(v);
        if (cachedMessages != null) {
            XposedBridge.log("MessageHistory: Cache hit for row_id=" + v);
            return cachedMessages;
        }

        XposedBridge.log("MessageHistory: Cache miss for row_id=" + v + ". Querying database.");
        // If not in cache, query database
        Cursor history = null;
        try {
            history = dbWrite.query("MessageHistory", new String[]{"_id", "row_id", "text_data", "editTimestamp"}, "row_id=?", new String[]{String.valueOf(v)}, null, null, null);
            if (history != null && !history.moveToFirst()) {
                XposedBridge.log("MessageHistory: No history found in DB for row_id=" + v);
                history.close();
                return null;
            }
            ArrayList<MessageItem> messages = new ArrayList<>();
            do {
                long id = history.getLong(history.getColumnIndexOrThrow("row_id"));
                long timestamp = history.getLong(history.getColumnIndexOrThrow("editTimestamp"));
                String message = history.getString(history.getColumnIndexOrThrow("text_data"));
                messages.add(new MessageItem(id, message, timestamp));
            }
            while (history.moveToNext());
            XposedBridge.log("MessageHistory: Found " + messages.size() + " history items in DB for row_id=" + v);

            // Store in cache
            messagesCache.put(v, messages);
            return messages;
        } catch (SQLiteException e) {
            XposedBridge.log("MessageHistory: SQLiteException in getMessages for row_id=" + v + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (history != null) {
                history.close();
                XposedBridge.log("MessageHistory: Cursor closed for getMessages row_id=" + v);
            }
        }
    }

    public final void insertHideSeenMessage(String jid, String message_id, MessageType type, boolean viewed) {
        synchronized (this) {
            if (dbWrite == null || !dbWrite.isOpen()) {
                XposedBridge.log("MessageHistory: insertHideSeenMessage - dbWrite is null or closed.");
                return;
            }
            XposedBridge.log("MessageHistory: Inserting hideSeenMessage for jid=" + jid + ", msg_id=" + message_id);
            if (updateViewedMessage(jid, message_id, type, viewed)) {
                return;
            }
            ContentValues content = new ContentValues();
            content.put("jid", jid);
            content.put("message_id", message_id);
            content.put("type", type.ordinal());
            try {
                dbWrite.insert("hide_seen_messages", null, content);
                XposedBridge.log("MessageHistory: Inserted hideSeenMessage.");
                // Invalidate caches
                String cacheKey = createSeenMessageCacheKey(jid, message_id, type);
                seenMessageCache.remove(cacheKey);
                invalidateSeenMessagesListCache(jid, type);
            } catch (SQLiteException e) {
                XposedBridge.log("MessageHistory: SQLiteException during insertHideSeenMessage: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public boolean updateViewedMessage(String jid, String message_id, MessageType type, boolean viewed) {
        if (dbWrite == null || !dbWrite.isOpen()) {
            XposedBridge.log("MessageHistory: updateViewedMessage - dbWrite is null or closed.");
            return false;
        }
        Cursor cursor = null;
        try {
            cursor = dbWrite.query("hide_seen_messages", new String[]{"_id"}, "jid=? AND message_id=? AND type =?", new String[]{jid, message_id, String.valueOf(type.ordinal())}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                if (cursor != null) cursor.close();
                return false;
            }
            synchronized (this) {
                ContentValues content = new ContentValues();
                content.put("viewed", viewed ? 1 : 0);
                dbWrite.update("hide_seen_messages", content, "_id=?", new String[]{cursor.getString(cursor.getColumnIndexOrThrow("_id"))});
                XposedBridge.log("MessageHistory: Updated viewed status for " + jid + "/" + message_id);

                // Update cache or invalidate
                String cacheKey = createSeenMessageCacheKey(jid, message_id, type);
                MessageSeenItem cachedItem = seenMessageCache.get(cacheKey);
                if (cachedItem != null && cachedItem.viewed != viewed) {
                    seenMessageCache.remove(cacheKey); // Invalidate if viewed status changed
                }
                invalidateSeenMessagesListCache(jid, type);
            }
        } catch (SQLiteException e) {
            XposedBridge.log("MessageHistory: SQLiteException in updateViewedMessage: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return true;
    }

    public MessageSeenItem getHideSeenMessage(String jid, String message_id, MessageType type) {
        if (dbWrite == null || !dbWrite.isOpen()) {
            XposedBridge.log("MessageHistory: getHideSeenMessage - dbWrite is null or closed.");
            return null;
        }
        // Check cache first
        String cacheKey = createSeenMessageCacheKey(jid, message_id, type);
        MessageSeenItem cachedItem = seenMessageCache.get(cacheKey);
        if (cachedItem != null) {
            XposedBridge.log("MessageHistory: Cache hit for hideSeenMessage: " + cacheKey);
            return cachedItem;
        }

        XposedBridge.log("MessageHistory: Cache miss for hideSeenMessage: " + cacheKey + ". Querying DB.");
        // If not in cache, query database
        Cursor cursor = null;
        try {
            cursor = dbWrite.query("hide_seen_messages", new String[]{"viewed"}, "jid=? AND message_id=? AND type=?", new String[]{jid, message_id, String.valueOf(type.ordinal())}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                XposedBridge.log("MessageHistory: No hideSeenMessage found in DB for: " + cacheKey);
                if (cursor != null) cursor.close();
                return null;
            }
            var viewed = cursor.getInt(cursor.getColumnIndexOrThrow("viewed")) == 1;
            var message = new MessageSeenItem(jid, message_id, viewed);
            XposedBridge.log("MessageHistory: Found hideSeenMessage in DB for " + cacheKey + ", viewed=" + viewed);

            // Store in cache
            seenMessageCache.put(cacheKey, message);
            return message;
        } catch (SQLiteException e) {
            XposedBridge.log("MessageHistory: SQLiteException in getHideSeenMessage for " + cacheKey + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
                XposedBridge.log("MessageHistory: Cursor closed for getHideSeenMessage " + cacheKey);
            }
        }
    }

    public List<MessageSeenItem> getHideSeenMessages(String jid, MessageType type, boolean viewed) {
        if (dbWrite == null || !dbWrite.isOpen()) {
            XposedBridge.log("MessageHistory: getHideSeenMessages - dbWrite is null or closed.");
            return null;
        }
        // Check cache first
        String cacheKey = createSeenMessagesListCacheKey(jid, type, viewed);
        List<MessageSeenItem> cachedList = seenMessagesListCache.get(cacheKey);
        if (cachedList != null) {
            XposedBridge.log("MessageHistory: Cache hit for hideSeenMessages list: " + cacheKey);
            return cachedList;
        }

        XposedBridge.log("MessageHistory: Cache miss for hideSeenMessages list: " + cacheKey + ". Querying DB.");
        // If not in cache, query database
        Cursor cursor = null;
        try {
            cursor = dbWrite.query("hide_seen_messages", new String[]{"jid", "message_id", "viewed"}, "jid=? AND type=? AND viewed=?", new String[]{jid, String.valueOf(type.ordinal()), viewed ? "1" : "0"}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                XposedBridge.log("MessageHistory: No hideSeenMessages list found in DB for: " + cacheKey);
                if (cursor != null) cursor.close();
                return null;
            }
            ArrayList<MessageSeenItem> messages = new ArrayList<>();
            do {
                var message_id = cursor.getString(cursor.getColumnIndexOrThrow("message_id"));
                var message = new MessageSeenItem(jid, message_id, viewed);
                messages.add(message);

                // Also cache individual messages
                String msgCacheKey = createSeenMessageCacheKey(jid, message_id, type);
                seenMessageCache.put(msgCacheKey, message);
            } while (cursor.moveToNext());
            XposedBridge.log("MessageHistory: Found " + messages.size() + " items in hideSeenMessages list for " + cacheKey);

            // Store in cache
            seenMessagesListCache.put(cacheKey, messages);
            return messages;
        } catch (SQLiteException e) {
            XposedBridge.log("MessageHistory: SQLiteException in getHideSeenMessages for " + cacheKey + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
                XposedBridge.log("MessageHistory: Cursor closed for getHideSeenMessages list " + cacheKey);
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        XposedBridge.log("MessageHistory: onCreate called. Creating tables.");
        try {
            sqLiteDatabase.execSQL("create table MessageHistory(_id INTEGER PRIMARY KEY AUTOINCREMENT, row_id INTEGER NOT NULL, text_data TEXT NOT NULL, editTimestamp BIGINT DEFAULT 0 );");
            sqLiteDatabase.execSQL("create table hide_seen_messages(_id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL, message_id TEXT NOT NULL,type INT NOT NULL, viewed INT DEFAULT 0);");
            XposedBridge.log("MessageHistory: Tables created successfully.");
        } catch (SQLiteException e) {
            XposedBridge.log("MessageHistory: SQLiteException during table creation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        XposedBridge.log("MessageHistory: onUpgrade called. Old version: " + oldVersion + ", New version: " + newVersion);
        if (oldVersion < 2) {
            try {
                sqLiteDatabase.execSQL("create table hide_seen_messages(_id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL, message_id TEXT NOT NULL,type INT NOT NULL, viewed INT DEFAULT 0);");
                XposedBridge.log("MessageHistory: Table 'hide_seen_messages' created during upgrade.");
            } catch (SQLiteException e) {
                XposedBridge.log("MessageHistory: SQLiteException during table creation in onUpgrade: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private String createSeenMessageCacheKey(String jid, String message_id, MessageType type) {
        return jid + "_" + message_id + "_" + type.ordinal();
    }

    private String createSeenMessagesListCacheKey(String jid, MessageType type, boolean viewed) {
        return jid + "_" + type.ordinal() + "_" + (viewed ? "1" : "0");
    }

    private void invalidateSeenMessagesListCache(String jid, MessageType type) {
        XposedBridge.log("MessageHistory: Invalidating seenMessagesListCache for " + jid + " type " + type);
        seenMessagesListCache.remove(createSeenMessagesListCacheKey(jid, type, true));
        seenMessagesListCache.remove(createSeenMessagesListCacheKey(jid, type, false));
    }

    public void clearCaches() {
        XposedBridge.log("MessageHistory: Clearing all caches.");
        messagesCache.evictAll();
        seenMessageCache.evictAll();
        seenMessagesListCache.evictAll();
    }

    @AllArgsConstructor
    public static class MessageItem {
        public long id;
        public String message;
        public long timestamp;
    }

    @RequiredArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
    public static class MessageSeenItem {
        public final String jid;
        public final String message;
        public final boolean viewed;
        private FMessageWpp fMessageWpp;

        @Nullable
        public FMessageWpp getFMessage() {
            if (fMessageWpp == null) {
                try {
                    var userJid = new FMessageWpp.UserJid(jid);
                    if (userJid.isNull()) return null;
                    fMessageWpp = new FMessageWpp.Key(message, userJid, false).getFMessage();
                } catch (Exception ignored) {
                }
            }
            return fMessageWpp;
        }
    }
}
