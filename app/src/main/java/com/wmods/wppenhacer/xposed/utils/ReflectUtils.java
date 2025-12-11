package com.wmods.wppenhacer.xposed.utils;

import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectUtils {
    private static final String TAG = "WaEnhancer:ReflectUtils";
    private ReflectUtils() {}

    public static boolean hasMethod(Object obj, String methodName, Class<?>... params) {
        if (obj == null) return false;
        try {
            obj.getClass().getMethod(methodName, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object invokeSafely(Object target, String methodName, Object... args) {
        if (target == null) return null;
        try {
            Method m = null;
            for (Method mm : target.getClass().getMethods()) {
                if (!mm.getName().equals(methodName)) continue;
                m = mm; break;
            }
            if (m == null) return null;
            return m.invoke(target, args);
        } catch (Throwable t) {
            Log.w(TAG, "invokeSafely failed: " + methodName + " on " + target.getClass().getName() + " : " + t.getMessage());
            return null;
        }
    }

    public static String methodListSnippet(Object obj, int max) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Method m : obj.getClass().getDeclaredMethods()) {
            sb.append(m.getName());
            if (++count >= max) break;
            sb.append(",");
        }
        return sb.toString();
    }

    // Simple cached lookup for common boolean method patterns (example)
    private static final ConcurrentHashMap<Class<?>, Method> IS_GROUP_CACHE = new ConcurrentHashMap<>();
    private static final String[] IS_GROUP_CANDIDATES = {"isGroup","isgroup","a","isg","isGroupChat"};

    public static boolean cachedTryIsGroup(Object userJid) {
        if (userJid == null) return false;
        Class<?> c = userJid.getClass();
        Method m = IS_GROUP_CACHE.get(c);
        if (m == null) {
            for (String candidate : IS_GROUP_CANDIDATES) {
                try {
                    Method mm = c.getMethod(candidate);
                    mm.setAccessible(true);
                    IS_GROUP_CACHE.putIfAbsent(c, mm);
                    m = mm; break;
                } catch (Throwable ignore) {}
            }
        }
        if (m != null) {
            try {
                Object res = m.invoke(userJid);
                return Boolean.TRUE.equals(res);
            } catch (Throwable t) {
                Log.w(TAG, "cachedTryIsGroup invoke failed: " + t.getMessage());
            }
        }
        return false;
    }
}
