package com.wmods.wppenhacer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Database for storing auto-reply rules.
 * 
 * Features:
 * - Pattern matching (exact, contains, regex)
 * - Reply delay (in seconds)
 * - Target filtering (all, contacts only, groups only, specific
 * contacts/groups)
 * - Active time windows (start/end time)
 * - Enable/disable individual rules
 * 
 * The database is stored in a shared location accessible by both WhatsApp and
 * WaEnhancer app.
 */
public class AutoReplyDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "AutoReply.db";
    private static final int DATABASE_VERSION = 1;

    // Shared database path in WaEnhancer app's data directory
    private static final String SHARED_DB_PATH = "/data/data/com.wmods.wppenhacer/databases/" + DATABASE_NAME;

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
    private boolean isSharedMode = false;

    public enum MatchType {
        ALL(0), // Match all messages
        CONTAINS(1), // Message contains pattern
        EXACT(2), // Message equals pattern exactly
        REGEX(3); // Pattern is a regex

        public final int value;

        MatchType(int value) {
            this.value = value;
        }

        public static MatchType fromInt(int value) {
            for (MatchType t : values()) {
                if (t.value == value)
                    return t;
            }
            return CONTAINS;
        }
    }

    public enum TargetType {
        ALL(0), // Reply to everyone
        CONTACTS(1), // Reply only to contacts (not groups)
        GROUPS(2), // Reply only to groups
        SPECIFIC(3); // Reply only to specific JIDs

        public final int value;

        TargetType(int value) {
            this.value = value;
        }

        public static TargetType fromInt(int value) {
            for (TargetType t : values()) {
                if (t.value == value)
                    return t;
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
        public String endTime; // HH:mm
        public boolean enabled;
        public long createdAt;

        public AutoReplyRule() {
        }

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
        if (instance == null) {
            Context appContext = Utils.getApplication();
            String currentPackage = appContext.getPackageName();

            // Check if we're running in WhatsApp (Xposed context)
            if (currentPackage.contains("whatsapp")) {
                // Try to open the shared database directly
                instance = new AutoReplyDatabase(appContext);
                try {
                    File sharedDbFile = new File(SHARED_DB_PATH);
                    if (sharedDbFile.exists() && sharedDbFile.canRead()) {
                        instance.dbWrite = SQLiteDatabase.openDatabase(
                                SHARED_DB_PATH,
                                null,
                                SQLiteDatabase.OPEN_READONLY);
                        instance.isSharedMode = true;
                        XposedBridge.log("AutoReplyDatabase: Opened shared database from WaEnhancer app");
                    } else {
                        XposedBridge.log(
                                "AutoReplyDatabase: Shared database not found or not readable at " + SHARED_DB_PATH);
                        // Fallback to local database
                        instance.dbWrite = instance.getWritableDatabase();
                    }
                } catch (Exception e) {
                    XposedBridge.log("AutoReplyDatabase: Failed to open shared database: " + e.getMessage());
                    // Fallback to local database
                    instance.dbWrite = instance.getWritableDatabase();
                }
            } else {
                // Running in WaEnhancer app - use normal database
                instance = new AutoReplyDatabase(appContext);
                instance.dbWrite = instance.getWritableDatabase();
            }
        } else if (!instance.dbWrite.isOpen()) {
            // Reopen if closed
            Context appContext = Utils.getApplication();
            String currentPackage = appContext.getPackageName();

            if (currentPackage.contains("whatsapp") && instance.isSharedMode) {
                try {
                    instance.dbWrite = SQLiteDatabase.openDatabase(
                            SHARED_DB_PATH,
                            null,
                            SQLiteDatabase.OPEN_READONLY);
                } catch (Exception e) {
                    instance.dbWrite = instance.getWritableDatabase();
                }
            } else {
                instance.dbWrite = instance.getWritableDatabase();
            }
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

    /**
     * Make the database files world-readable so WhatsApp can access them.
     * Also sync rules to SharedPreferences for XSharedPreferences access.
     * Only needed when running in WaEnhancer app, not in Xposed context.
     */
    private void makeDatabaseWorldReadable() {
        try {
            Context appContext = Utils.getApplication();
            if (appContext.getPackageName().contains("whatsapp")) {
                return; // Don't do this in WhatsApp context
            }

            // Sync rules to SharedPreferences for cross-app access
            syncRulesToPreferences();

            File dbFile = appContext.getDatabasePath(DATABASE_NAME);
            if (dbFile.exists()) {
                dbFile.setReadable(true, false); // world-readable

                // Also make the WAL and SHM files readable if they exist
                File walFile = new File(dbFile.getPath() + "-wal");
                File shmFile = new File(dbFile.getPath() + "-shm");
                if (walFile.exists())
                    walFile.setReadable(true, false);
                if (shmFile.exists())
                    shmFile.setReadable(true, false);

                // Make databases directory readable
                File dbDir = dbFile.getParentFile();
                if (dbDir != null && dbDir.exists()) {
                    dbDir.setReadable(true, false);
                    dbDir.setExecutable(true, false);
                }
            }
        } catch (Exception e) {
            // Ignore permission errors
        }
    }

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
            long result = dbWrite.insert(TABLE_RULES, null, values);
            makeDatabaseWorldReadable();
            return result;
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
                    new String[] { String.valueOf(rule.id) });
            makeDatabaseWorldReadable();
            return rows > 0;
        }
    }

    public boolean deleteRule(long id) {
        synchronized (this) {
            int rows = dbWrite.delete(TABLE_RULES, COL_ID + "=?",
                    new String[] { String.valueOf(id) });
            return rows > 0;
        }
    }

    public void setRuleEnabled(long id, boolean enabled) {
        synchronized (this) {
            ContentValues values = new ContentValues();
            values.put(COL_ENABLED, enabled ? 1 : 0);
            dbWrite.update(TABLE_RULES, values, COL_ID + "=?",
                    new String[] { String.valueOf(id) });
            makeDatabaseWorldReadable();
        }
    }

    public AutoReplyRule getRule(long id) {
        Cursor cursor = dbWrite.query(TABLE_RULES, null, COL_ID + "=?",
                new String[] { String.valueOf(id) }, null, null, null);
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

    // ==================== JSON Sync for Cross-App Access ====================

    private static final String PREFS_KEY_RULES = "auto_reply_rules_json";

    /**
     * Sync all enabled rules to SharedPreferences as JSON.
     * This allows the Xposed module to read the rules via XSharedPreferences.
     */
    public void syncRulesToPreferences() {
        try {
            List<AutoReplyRule> enabledRules = getEnabledRules();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < enabledRules.size(); i++) {
                if (i > 0)
                    json.append(",");
                json.append(ruleToJson(enabledRules.get(i)));
            }
            json.append("]");

            // Save to default SharedPreferences (which XSharedPreferences can read)
            Context context = Utils.getApplication();
            context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREFS_KEY_RULES, json.toString())
                    .apply();
        } catch (Exception e) {
            XposedBridge.log("AutoReplyDatabase: Error syncing rules to prefs: " + e.getMessage());
        }
    }

    private String ruleToJson(AutoReplyRule rule) {
        return "{" +
                "\"id\":" + rule.id + "," +
                "\"pattern\":\"" + escapeJson(rule.pattern) + "\"," +
                "\"replyMessage\":\"" + escapeJson(rule.replyMessage) + "\"," +
                "\"matchType\":" + rule.matchType.value + "," +
                "\"targetType\":" + rule.targetType.value + "," +
                "\"specificJids\":\"" + escapeJson(rule.specificJids != null ? rule.specificJids : "") + "\"," +
                "\"delaySeconds\":" + rule.delaySeconds + "," +
                "\"startTime\":\"" + (rule.startTime != null ? rule.startTime : "") + "\"," +
                "\"endTime\":\"" + (rule.endTime != null ? rule.endTime : "") + "\"," +
                "\"enabled\":" + rule.enabled + "," +
                "\"createdAt\":" + rule.createdAt +
                "}";
    }

    private String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Get enabled rules from XSharedPreferences (for use in Xposed module context).
     * This is a static method that doesn't require database access.
     */
    public static List<AutoReplyRule> getEnabledRulesFromPrefs() {
        List<AutoReplyRule> rules = new ArrayList<>();
        try {
            if (Utils.xprefs == null) {
                XposedBridge.log("AutoReplyDatabase: xprefs is NULL - rules cannot be loaded");
                return rules;
            }

            Utils.xprefs.reload();
            String json = Utils.xprefs.getString(PREFS_KEY_RULES, null);

            if (json == null || json.isEmpty()) {
                XposedBridge.log(
                        "AutoReplyDatabase: No rules found in prefs (json is null/empty). Did you save rules in WA Enhancer app?");
                return rules;
            }

            rules = parseRulesJson(json);
            XposedBridge.log("AutoReplyDatabase: Loaded " + rules.size() + " rules from prefs");
        } catch (Exception e) {
            XposedBridge.log("AutoReplyDatabase: Error reading rules from prefs: " + e.getMessage());
        }
        return rules;
    }

    private static List<AutoReplyRule> parseRulesJson(String json) {
        List<AutoReplyRule> rules = new ArrayList<>();
        if (json == null || json.isEmpty() || json.equals("[]")) {
            return rules;
        }

        try {
            // Simple JSON array parsing
            json = json.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                return rules;
            }

            // Remove outer brackets
            json = json.substring(1, json.length() - 1);

            // Split by "},{" pattern (being careful with nested braces)
            int depth = 0;
            int start = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{')
                    depth++;
                else if (c == '}')
                    depth--;
                else if (c == ',' && depth == 0) {
                    String ruleJson = json.substring(start, i).trim();
                    AutoReplyRule rule = parseRuleJson(ruleJson);
                    if (rule != null)
                        rules.add(rule);
                    start = i + 1;
                }
            }
            // Parse last rule
            if (start < json.length()) {
                String ruleJson = json.substring(start).trim();
                AutoReplyRule rule = parseRuleJson(ruleJson);
                if (rule != null)
                    rules.add(rule);
            }
        } catch (Exception e) {
            XposedBridge.log("AutoReplyDatabase: Error parsing rules JSON: " + e.getMessage());
        }
        return rules;
    }

    private static AutoReplyRule parseRuleJson(String json) {
        try {
            if (json == null || json.isEmpty())
                return null;

            AutoReplyRule rule = new AutoReplyRule();
            rule.id = getJsonLong(json, "id", 0);
            rule.pattern = getJsonString(json, "pattern", "");
            rule.replyMessage = getJsonString(json, "replyMessage", "");
            rule.matchType = MatchType.fromInt(getJsonInt(json, "matchType", 1));
            rule.targetType = TargetType.fromInt(getJsonInt(json, "targetType", 0));
            rule.specificJids = getJsonString(json, "specificJids", "");
            rule.delaySeconds = getJsonInt(json, "delaySeconds", 0);
            rule.startTime = getJsonString(json, "startTime", "");
            rule.endTime = getJsonString(json, "endTime", "");
            rule.enabled = getJsonBoolean(json, "enabled", true);
            rule.createdAt = getJsonLong(json, "createdAt", 0);
            return rule;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getJsonString(String json, String key, String defaultValue) {
        try {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start < 0)
                return defaultValue;
            start += pattern.length();
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '"' && json.charAt(end - 1) != '\\')
                    break;
                end++;
            }
            return unescapeJson(json.substring(start, end));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int getJsonInt(String json, String key, int defaultValue) {
        try {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0)
                return defaultValue;
            start += pattern.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static long getJsonLong(String json, String key, long defaultValue) {
        try {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0)
                return defaultValue;
            start += pattern.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return Long.parseLong(json.substring(start, end));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean getJsonBoolean(String json, String key, boolean defaultValue) {
        try {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0)
                return defaultValue;
            start += pattern.length();
            if (json.substring(start).startsWith("true"))
                return true;
            if (json.substring(start).startsWith("false"))
                return false;
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String unescapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
