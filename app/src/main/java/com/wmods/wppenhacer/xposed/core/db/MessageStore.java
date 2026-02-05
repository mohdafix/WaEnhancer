package com.wmods.wppenhacer.xposed.core.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import android.util.LruCache;
import de.robv.android.xposed.XposedBridge;

public class MessageStore {

    private static MessageStore mInstance;
    private SQLiteDatabase sqLiteDatabase;
    private final LruCache<Long, String> messageIdCache = new LruCache<>(200);
    private final LruCache<String, String> messageKeyCache = new LruCache<>(200);
    private final LruCache<String, Long> keyToIdCache = new LruCache<>(200);


    private MessageStore() {
        try {
            var dataDir = Utils.getApplication().getFilesDir().getParentFile();
            var dbFile = new File(dataDir, "/databases/msgstore.db");
            
            XposedBridge.log("MessageStore: Initializing. DB Path: " + dbFile.getAbsolutePath());

            if (dbFile.exists()) {
                sqLiteDatabase = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                XposedBridge.log("MessageStore: Database opened successfully.");
            } else {
                XposedBridge.log("MessageStore: msgstore.db does not exist yet.");
            }
        } catch (Exception e) {
            XposedBridge.log("MessageStore: Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static MessageStore getInstance() {
        synchronized (MessageStore.class) {
            if (mInstance == null) {
                mInstance = new MessageStore();
            } else {
                // If instance exists but DB is closed or null, try to reopen
                if (mInstance.sqLiteDatabase == null || !mInstance.sqLiteDatabase.isOpen()) {
                    XposedBridge.log("MessageStore: Instance exists but DB closed/null. Retrying init.");
                    mInstance = new MessageStore();
                }
            }
        }
        return mInstance;
    }

    public String getMessageById(long id) {
        synchronized (messageIdCache) {
            String cached = messageIdCache.get(id);
            if (cached != null) return cached;
        }
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) {
            XposedBridge.log("MessageStore: DB not open when getting message by ID");
            return "";
        }
        String message = "";
        Cursor cursor = null;
        try {
            String[] columns = new String[]{"c0content"};
            String selection = "docid=?";
            String[] selectionArgs = new String[]{String.valueOf(id)};

            cursor = sqLiteDatabase.query("message_ftsv2_content", columns, selection, selectionArgs, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                message = cursor.getString(cursor.getColumnIndexOrThrow("c0content"));
                if (message != null) {
                    synchronized (messageIdCache) {
                        messageIdCache.put(id, message);
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log("MessageStore: Error getting message by ID: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return message;
    }


    public String getCurrentMessageByKey(String message_key) {
        if (message_key == null) return "";
        synchronized (messageKeyCache) {
            String cached = messageKeyCache.get(message_key);
            if (cached != null) return cached;
        }
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return "";
        String[] columns = new String[]{"text_data"};
        String selection = "key_id=?";
        String[] selectionArgs = new String[]{message_key};
        try (Cursor cursor = sqLiteDatabase.query("message", columns, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String text = cursor.getString(0);
                if (text != null) {
                    synchronized (messageKeyCache) {
                        messageKeyCache.put(message_key, text);
                    }
                }
                return text;
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return "";
    }


    public long getIdfromKey(String message_key) {
        if (message_key == null) return -1;
        synchronized (keyToIdCache) {
            Long cached = keyToIdCache.get(message_key);
            if (cached != null) return cached;
        }
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return -1;
        String[] columns = new String[]{"_id"};
        String selection = "key_id=?";
        String[] selectionArgs = new String[]{message_key};
        try (Cursor cursor = sqLiteDatabase.query("message", columns, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                synchronized (keyToIdCache) {
                    keyToIdCache.put(message_key, id);
                }
                return id;
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return -1;
    }


    public String getMediaFromID(long id) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return null;
        String[] columns = new String[]{"file_path"};
        String selection = "message_row_id=?";
        String[] selectionArgs = new String[]{String.valueOf(id)};
        try (Cursor cursor = sqLiteDatabase.query("message_media", columns, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public String getCurrentMessageByID(long row_id) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return "";
        String[] columns = new String[]{"text_data"};
        String selection = "_id=?";
        String[] selectionArgs = new String[]{String.valueOf(row_id)};
        try (Cursor cursor = sqLiteDatabase.query("message", columns, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return "";
    }

    public String getOriginalMessageKey(long id) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return "";
        String message = "";
        try (Cursor cursor = sqLiteDatabase.rawQuery("SELECT parent_message_row_id, key_id FROM message_add_on WHERE parent_message_row_id=\"" + id + "\"", null)) {
            if (cursor != null && cursor.moveToFirst()) {
                message = cursor.getString(1);
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return message;
    }

    public List<MessageHistory.MessageItem> getWAEditHistory(long rowId) {
        List<MessageHistory.MessageItem> history = new ArrayList<>();
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return history;

        // Query message_add_on for edits (type 7)
        // Usually message_add_on stores previous versions
        String sql = "SELECT text_data, timestamp FROM message_add_on WHERE parent_message_row_id = ? AND message_add_on_type = 7 ORDER BY timestamp ASC";
        try (Cursor cursor = sqLiteDatabase.rawQuery(sql, new String[]{String.valueOf(rowId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                int textIdx = cursor.getColumnIndexOrThrow("text_data");
                int tsIdx = cursor.getColumnIndexOrThrow("timestamp");
                do {
                    String text = cursor.getString(textIdx);
                    long ts = cursor.getLong(tsIdx);
                    history.add(new MessageHistory.MessageItem(rowId, text, ts));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            XposedBridge.log("MessageStore: Error getting edit history: " + e.getMessage());
        }
        return history;
    }

    public List<String> getAudioListByMessageList(List<String> messageList) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen() || messageList == null || messageList.isEmpty()) {
            return new ArrayList<>();
        }

        var list = new ArrayList<String>();
        var placeholders = messageList.stream().map(m -> "?").collect(Collectors.joining(","));
        var sql = "SELECT message_type FROM message WHERE key_id IN (" + placeholders + ")";
        try (Cursor cursor = sqLiteDatabase.rawQuery(sql, messageList.toArray(new String[0]))) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (cursor.getInt(0) == 2) {
                        list.add(cursor.getString(0));
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }

        return list;
    }

    public synchronized void executeSQL(String sql) {
        try {
            if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return;
            sqLiteDatabase.execSQL(sql);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    public void storeMessageRead(String messageId) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return;
        XposedBridge.log("storeMessageRead: " + messageId);
        sqLiteDatabase.execSQL("UPDATE message SET status = 1 WHERE key_id = \"" + messageId + "\"");
    }

    public boolean isReadMessageStatus(String messageId) {
        if (sqLiteDatabase == null || !sqLiteDatabase.isOpen()) return false;
        boolean result = false;
        Cursor cursor = null;
        try {
            String[] columns = new String[]{"status"};
            String selection = "key_id=?";
            String[] selectionArgs = new String[]{messageId};

            cursor = sqLiteDatabase.query("message", columns, selection, selectionArgs, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getInt(cursor.getColumnIndexOrThrow("status")) == 1;
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    public SQLiteDatabase getDatabase() {
        return sqLiteDatabase;
    }
}
