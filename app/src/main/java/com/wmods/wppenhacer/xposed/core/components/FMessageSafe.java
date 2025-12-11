package com.wmods.wppenhacer.xposed.core.components;

import android.util.Log;
import de.robv.android.xposed.XposedHelpers;

public final class FMessageSafe {
    private static final String TAG = "WaEnhancer:FMessageSafe";

    private final Object raw;
    private final String rawClassName;
    private final Object userJid;
    private final boolean valid;

    private FMessageSafe(Object raw, Object userJid, boolean valid) {
        this.raw = raw;
        this.rawClassName = raw == null ? "null" : raw.getClass().getName();
        this.userJid = userJid;
        this.valid = valid;
    }

    public static FMessageSafe from(Object rawMsg) {
        if (rawMsg == null) {
            Log.i(TAG, "rawMsg == null");
            return new FMessageSafe(null, null, false);
        }

        try {
            Object uid = null;
            // Try common patterns safely
            try {
                uid = XposedHelpers.getObjectField(rawMsg, "userJid");
            } catch (Throwable t1) {
                // fallback to method getUserJid()
                try {
                    uid = rawMsg.getClass().getMethod("getUserJid").invoke(rawMsg);
                } catch (Throwable t2) {
                    // fallback: scan for candidate methods
                    for (java.lang.reflect.Method m : rawMsg.getClass().getDeclaredMethods()) {
                        String mn = m.getName().toLowerCase();
                        if (mn.contains("userid") || mn.contains("userjid") || mn.contains("getuser") || mn.contains("getjid")) {
                            try {
                                m.setAccessible(true);
                                uid = m.invoke(rawMsg);
                                break;
                            } catch (Throwable ignore) {}
                        }
                    }
                }
            }

            boolean ok = uid != null;
            return new FMessageSafe(rawMsg, uid, ok);
        } catch (Throwable t) {
            Log.w(TAG, "FMessageSafe ctor error for " + rawMsg.getClass().getName() + " : " + t.getMessage());
            // Probe methods to help mapping obfuscated classes
            try {
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (java.lang.reflect.Method m : rawMsg.getClass().getDeclaredMethods()) {
                    if (count++ >= 50) break;
                    sb.append(m.getName()).append(",");
                }
                Log.i(TAG, "Probe methods: " + sb.toString());
            } catch (Throwable ignore) {}
            return new FMessageSafe(rawMsg, null, false);
        }
    }

    public boolean isValid() { return valid; }
    public Object getRaw() { return raw; }
    public String getRawClassName() { return rawClassName; }
    public Object getUserJid() { return userJid; }

    public boolean isViewOnce() {
        if (!valid || userJid == null) return false;
        try {
            java.lang.reflect.Method m = userJid.getClass().getMethod("isViewOnce");
            return (Boolean) m.invoke(userJid);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m2 = raw.getClass().getMethod("isViewOnce");
                return (Boolean) m2.invoke(raw);
            } catch (Throwable ignore) {
                return false;
            }
        }
    }
}
