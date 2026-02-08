package com.wmods.wppenhacer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Database for storing auto-reply rules.
 * 
 * Features:
 * - Pattern matching (exact, contains, regex)
 * - Reply delay (in seconds)
 * - Target filtering (all, contacts only, groups only, specific contacts/groups)
 * - Active time windows (start/end time)
 * - Enable/disable individual rules
 */
public class AutoReplyDatabase extends SQLiteOpenHelper {
    
    private static final String DATABASE_NAME = "AutoReply.db";
    private static final int DATABASE_VERSION = 1;
    
    private static final String TABLE_RULES = "auto_reply_rules";
    
    // Column names
    private static final String COL_ID = "_id";
    private static final String COL_PATTERN = "pattern";
    private static final String COL_REPLY = "reply_message";
    private static final String COL_MATCH_TYPE = "match_type"; // 0=all, 1=contains, 2=exact, 3=regex
    private static final String COL_TARGET_TYPE = "target_type"; // 0=all, 1=contacts, 2=groups, 3=specific
    private static final String COL_SPECIFIC_JIDS = "specific_jids"; // comma-separated JIDs
    private static final String COL_DELAY_SECONDS = "delay_seconds";
    private static final String COL_START_TIME = "start_time"; // HH:mm format
    private static final String COL_END_TIME = "end_time"; // HH:mm format
    private static final String COL_ENABLED = "enabled";
    private static final String COL_CREATED_AT = "created_at";
    
    private static AutoReplyDatabase instance;
    private SQLiteDatabase dbWrite;
    
    public enum MatchType {
        ALL(0),          // Match all messages
        CONTAINS(1),     // Message contains pattern
        EXACT(2),        // Message equals pattern exactly
        REGEX(3);        // Pattern is a regex
        
        public final int value;
        MatchType(int value) { this.value = value; }
        
        public static MatchType fromInt(int value) {
            for (MatchType t : values()) {
                if (t.value == value) return t;
            }
            return CONTAINS;
        }
    }
    
    public enum TargetType {
        ALL(0),          // Reply to everyone
        CONTACTS(1),     // Reply only to contacts (not groups)
        GROUPS(2),       // Reply only to groups
        SPECIFIC(3);     // Reply only to specific JIDs
        
        public final int value;
        TargetType(int value) { this.value = value; }
        
        public static TargetType fromInt(int value) {
            for (TargetType t : values()) {
                if (t.value == value) return t;
            }
            return ALL;
        }
    }
    
    public static class AutoReplyRule {
        public long id;
        public String pattern;
        public String replyMessage;
        public MatchType matchType;
        public TargetType targetType;
        public String specificJids; // comma-separated
        public int delaySeconds;
        public String startTime; // HH:mm
        public String endTime;   // HH:mm
        public boolean enabled;
        public long createdAt;
        
        public AutoReplyRule() {}
        
        public AutoReplyRule(String pattern, String replyMessage, MatchType matchType) {
            this.pattern = pattern;
            this.replyMessage = replyMessage;
            this.matchType = matchType;
            this.targetType = TargetType.ALL;
            this.delaySeconds = 0;
            this.enabled = true;
            this.createdAt = System.currentTimeMillis();
        }
    }
    
    private AutoReplyDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    public static synchronized AutoReplyDatabase getInstance() {
        if (instance == null || !instance.getReadableDatabase().isOpen()) {
            instance = new AutoReplyDatabase(Utils.getApplication());
            instance.dbWrite = instance.getWritableDatabase();
        }
        return instance;
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_RULES + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_PATTERN + " TEXT NOT NULL, " +
                COL_REPLY + " TEXT NOT NULL, " +
                COL_MATCH_TYPE + " INTEGER DEFAULT 1, " +
                COL_TARGET_TYPE + " INTEGER DEFAULT 0, " +
                COL_SPECIFIC_JIDS + " TEXT, " +
                COL_DELAY_SECONDS + " INTEGER DEFAULT 0, " +
                COL_START_TIME + " TEXT, " +
                COL_END_TIME + " TEXT, " +
                COL_ENABLED + " INTEGER DEFAULT 1, " +
                COL_CREATED_AT + " INTEGER DEFAULT 0" +
                ");";
        db.execSQL(createTable);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
    }
    
    // ==================== CRUD Operations ====================
    
    public long insertRule(AutoReplyRule rule) {
        synchronized (this) {
            ContentValues values = new ContentValues();
            values.put(COL_PATTERN, rule.pattern);
            values.put(COL_REPLY, rule.replyMessage);
            values.put(COL_MATCH_TYPE, rule.matchType.value);
            values.put(COL_TARGET_TYPE, rule.targetType.value);
            values.put(COL_SPECIFIC_JIDS, rule.specificJids);
            values.put(COL_DELAY_SECONDS, rule.delaySeconds);
            values.put(COL_START_TIME, rule.startTime);
            values.put(COL_END_TIME, rule.endTime);
            values.put(COL_ENABLED, rule.enabled ? 1 : 0);
            values.put(COL_CREATED_AT, System.currentTimeMillis());
            return dbWrite.insert(TABLE_RULES, null, values);
        }
    }
    
    public boolean updateRule(AutoReplyRule rule) {
        synchronized (this) {
            ContentValues values = new ContentValues();
            values.put(COL_PATTERN, rule.pattern);
            values.put(COL_REPLY, rule.replyMessage);
            values.put(COL_MATCH_TYPE, rule.matchType.value);
            values.put(COL_TARGET_TYPE, rule.targetType.value);
            values.put(COL_SPECIFIC_JIDS, rule.specificJids);
            values.put(COL_DELAY_SECONDS, rule.delaySeconds);
            values.put(COL_START_TIME, rule.startTime);
            values.put(COL_END_TIME, rule.endTime);
            values.put(COL_ENABLED, rule.enabled ? 1 : 0);
            int rows = dbWrite.update(TABLE_RULES, values, COL_ID + "=?", 
                    new String[]{String.valueOf(rule.id)});
            return rows > 0;
        }
    }
    
    public boolean deleteRule(long id) {
        synchronized (this) {
            int rows = dbWrite.delete(TABLE_RULES, COL_ID + "=?", 
                    new String[]{String.valueOf(id)});
            return rows > 0;
        }
    }
    
    public void setRuleEnabled(long id, boolean enabled) {
        synchronized (this) {
            ContentValues values = new ContentValues();
            values.put(COL_ENABLED, enabled ? 1 : 0);
            dbWrite.update(TABLE_RULES, values, COL_ID + "=?", 
                    new String[]{String.valueOf(id)});
        }
    }
    
    public AutoReplyRule getRule(long id) {
        Cursor cursor = dbWrite.query(TABLE_RULES, null, COL_ID + "=?", 
                new String[]{String.valueOf(id)}, null, null, null);
        if (!cursor.moveToFirst()) {
            cursor.close();
            return null;
        }
        AutoReplyRule rule = cursorToRule(cursor);
        cursor.close();
        return rule;
    }
    
    public List<AutoReplyRule> getAllRules() {
        List<AutoReplyRule> rules = new ArrayList<>();
        Cursor cursor = dbWrite.query(TABLE_RULES, null, null, null, null, null, 
                COL_CREATED_AT + " DESC");
        if (cursor.moveToFirst()) {
            do {
                rules.add(cursorToRule(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return rules;
    }
    
    public List<AutoReplyRule> getEnabledRules() {
        List<AutoReplyRule> rules = new ArrayList<>();
        Cursor cursor = dbWrite.query(TABLE_RULES, null, COL_ENABLED + "=1", 
                null, null, null, COL_CREATED_AT + " DESC");
        if (cursor.moveToFirst()) {
            do {
                rules.add(cursorToRule(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return rules;
    }
    
    private AutoReplyRule cursorToRule(Cursor cursor) {
        AutoReplyRule rule = new AutoReplyRule();
        rule.id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
        rule.pattern = cursor.getString(cursor.getColumnIndexOrThrow(COL_PATTERN));
        rule.replyMessage = cursor.getString(cursor.getColumnIndexOrThrow(COL_REPLY));
        rule.matchType = MatchType.fromInt(cursor.getInt(cursor.getColumnIndexOrThrow(COL_MATCH_TYPE)));
        rule.targetType = TargetType.fromInt(cursor.getInt(cursor.getColumnIndexOrThrow(COL_TARGET_TYPE)));
        rule.specificJids = cursor.getString(cursor.getColumnIndexOrThrow(COL_SPECIFIC_JIDS));
        rule.delaySeconds = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DELAY_SECONDS));
        rule.startTime = cursor.getString(cursor.getColumnIndexOrThrow(COL_START_TIME));
        rule.endTime = cursor.getString(cursor.getColumnIndexOrThrow(COL_END_TIME));
        rule.enabled = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ENABLED)) == 1;
        rule.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT));
        return rule;
    }
}
