package com.wmods.wppenhacer.xposed.core.components;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * @noinspection unused
 */
public class FMessageWpp {

    private static final String TAG = "FMessageWpp";

    public static Class<?> TYPE;
    private static final List<Class<?>> SUPPORTED_TYPES = new ArrayList<>();

    private static Method userJidMethod;
    private static Field keyMessage;
    private static Field getFieldIdMessage;
    private static Field deviceJidField;
    private static Method messageMethod;
    private static Method messageWithMediaMethod;
    private static Field mediaTypeField;
    private static Method getOriginalMessageKey;
    private static Class abstractMediaMessageClass;
    private static Field broadcastField;

    // New fields for robust obfuscation handling
    private static Method jidGetRawStringMethod;
    private static Field keyMessageIdField;
    private static Field keyRemoteJidField;
    private static Field keyFromMeField;

    private final Object fmessage;
    private final Object userJid;
    private final boolean valid;
    private Key key;
    private static final Set<String> VALID_DOMAINS = new HashSet<>(Arrays.asList(
            "s.whatsapp.net", "newsletter", "lid", "g.us", "broadcast", "status"
    ));

    public FMessageWpp(Object rawMsg) {
        // Aggressive unwrapping in constructor
        Object unwrapped = findBaseMessage(rawMsg);

        this.fmessage = unwrapped;
        Object tmpJid;
        boolean ok;

        // Check if the unwrapped object is valid
        if (unwrapped == null || !isFMessage(unwrapped)) {
            tmpJid = null;
            ok = false;
        } else {
            try {
                tmpJid = safeGetUserJid(unwrapped);
                ok = (tmpJid != null);
            } catch (Throwable t) {
                Log.w(TAG, "FMessageWpp ctor unexpected input: " + t.getMessage());
                tmpJid = null;
                ok = false;
            }
        }
        this.userJid = tmpJid;
        this.valid = ok;
    }

    public static void initialize(ClassLoader classLoader) {
        try {
            // 1. Load the standard FMessage class
            Class<?> mainType = Unobfuscator.loadFMessageClass(classLoader);
            if (mainType == null) return;

            TYPE = mainType;
            SUPPORTED_TYPES.add(mainType);

            // 2. Try to find the alternative type (X.8kV)
            Class<?> altType = null;
            try {
                altType = XposedHelpers.findClassIfExists("X.8kV", classLoader);
                if (altType != null && !altType.equals(mainType)) {
                    SUPPORTED_TYPES.add(altType);
                    XposedBridge.log("FMessageWpp: Added support for " + altType.getName());
                }
            } catch (Exception ignored) {}

            // 3. Load methods/fields using the main TYPE
            Class<?> userJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.UserJid");
            for (Method m : TYPE.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType().equals(userJidClass)) {
                    userJidMethod = m;
                    userJidMethod.setAccessible(true);
                    break;
                }
            }

            keyMessage = Unobfuscator.loadMessageKeyField(classLoader);
            if (keyMessage != null) Key.TYPE = keyMessage.getType();

            messageMethod = Unobfuscator.loadNewMessageMethod(classLoader);
            messageWithMediaMethod = Unobfuscator.loadNewMessageWithMediaMethod(classLoader);
            getFieldIdMessage = Unobfuscator.loadSetEditMessageField(classLoader);

            Class<?> deviceJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid");
            for (Field f : TYPE.getDeclaredFields()) {
                if (f.getType().equals(deviceJidClass)) {
                    deviceJidField = f;
                    deviceJidField.setAccessible(true);
                    break;
                }
            }

            mediaTypeField = Unobfuscator.loadMediaTypeField(classLoader);
            getOriginalMessageKey = Unobfuscator.loadOriginalMessageKey(classLoader);
            abstractMediaMessageClass = Unobfuscator.loadAbstractMediaMessageClass(classLoader);
            broadcastField = Unobfuscator.loadBroadcastTagField(classLoader);

            try {
                jidGetRawStringMethod = Unobfuscator.loadJidGetRawStringMethod(classLoader);
                keyMessageIdField = Unobfuscator.loadMessageKeyIdField(classLoader);
                keyMessageIdField.setAccessible(true);
                keyRemoteJidField = Unobfuscator.loadMessageKeyRemoteJidField(classLoader);
                keyRemoteJidField.setAccessible(true);
                keyFromMeField = Unobfuscator.loadMessageKeyFromMeField(classLoader);
                keyFromMeField.setAccessible(true);
            } catch (Exception e) {
                XposedBridge.log("FMessageWpp extra init error: " + e.getMessage());
            }

        } catch (Exception e) {
            XposedBridge.log("FMessageWpp init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * CORE FIX: Recursive search for the valid message object.
     */
    private static Object findBaseMessage(Object rawMsg) {
        if (rawMsg == null) return null;
        if (isFMessage(rawMsg)) return rawMsg;

        try {
            for (Field field : rawMsg.getClass().getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getType().equals(String.class)) continue;
                field.setAccessible(true);
                Object potential = field.get(rawMsg);
                if (potential != null && isFMessage(potential)) {
                    return potential;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static boolean isFMessage(Object obj) {
        if (obj == null) return false;
        for (Class<?> type : SUPPORTED_TYPES) {
            if (type != null && type.isInstance(obj)) return true;
        }
        return false;
    }

    public static boolean isFMessageClass(Class<?> clazz) {
        if (clazz == null) return false;
        for (Class<?> type : SUPPORTED_TYPES) {
            if (type != null && type.isAssignableFrom(clazz)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------
    // METHOD ADDED BACK TO FIX Unobfuscator.java COMPILE ERROR
    // ---------------------------------------------------------
    public static boolean checkUnsafeIsFMessage(ClassLoader classLoader, Class<?> clazz) throws Exception {
        return isFMessageClass(clazz);
    }

    // Sometimes Unobfuscator needs the list directly
    public static List<Class<?>> getSupportedTypes() {
        return SUPPORTED_TYPES;
    }
    // ---------------------------------------------------------

    private Object safeGetUserJid(Object rawMsg) {
        try {
            if (userJidMethod != null) {
                return userJidMethod.invoke(rawMsg);
            }
        } catch (Throwable t) { }
        return null;
    }

    public boolean isValid() {
        return valid;
    }

    public UserJid getUserJid() {
        if (userJid == null) return null;
        return new UserJid(userJid);
    }

    public Object getDeviceJid() {
        if (deviceJidField == null) return null;
        try {
            return deviceJidField.get(fmessage);
        } catch (Exception ignored) { return null; }
    }

    public long getRowId() {
        if (getFieldIdMessage == null) return 0;
        try {
            return getFieldIdMessage.getLong(fmessage);
        } catch (Exception ignored) { return 0; }
    }

    public Key getKey() {
        if (keyMessage == null && fmessage != null) {
            try {
                keyMessage = XposedHelpers.findField(fmessage.getClass(), "key");
                keyMessage.setAccessible(true);
            } catch (Exception ignored) {}
        }

        if (keyMessage == null) {
            if (fmessage != null) return new Key(null, this);
            return null;
        }

        if (this.key != null) return this.key;

        try {
            Object rawKey = keyMessage.get(fmessage);
            if (rawKey != null) {
                this.key = new Key(rawKey, this);
                return this.key;
            }
        } catch (Exception e) {
            // Unwrap logic should prevent this, but safe fallback
            // Log.e(TAG, "getKey failed: " + e.getMessage());
        }
        return new Key(null, this);
    }

    public Key getOriginalKey() {
        if (getOriginalMessageKey == null) return null;
        try {
            Object rawKey = getOriginalMessageKey.invoke(fmessage);
            if (rawKey != null) {
                return new Key(rawKey, this);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public boolean isBroadcast() {
        if (broadcastField == null) return false;
        try {
            return broadcastField.getBoolean(fmessage);
        } catch (Exception ignored) { return false; }
    }

    public Object getObject() {
        return fmessage;
    }

    public String getMessageStr() {
        if (messageMethod == null && messageWithMediaMethod == null) return null;
        try {
            if (messageMethod != null) {
                var message = (String) messageMethod.invoke(fmessage);
                if (message != null) return message;
            }
            if (messageWithMediaMethod != null) {
                return (String) messageWithMediaMethod.invoke(fmessage);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public boolean isMediaFile() {
        if (abstractMediaMessageClass == null) return false;
        try {
            return abstractMediaMessageClass.isInstance(fmessage);
        } catch (Exception ignored) { return false; }
    }

    public File getMediaFile() {
        if (!isMediaFile()) return null;
        try {
            for (var field : abstractMediaMessageClass.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;

                Field fileField = null;
                for (Field f : field.getType().getDeclaredFields()) {
                    if (f.getType().equals(File.class)) {
                        fileField = f;
                        break;
                    }
                }

                if (fileField != null) {
                    field.setAccessible(true);
                    var mediaObject = field.get(fmessage);
                    if (mediaObject == null) continue;

                    fileField.setAccessible(true);
                    var mediaFile = (File) fileField.get(mediaObject);
                    if (mediaFile != null) return mediaFile;

                    var filePath = MessageStore.getInstance().getMediaFromID(getRowId());
                    if (filePath == null) return null;
                    return new File(filePath);
                }
            }
        } catch (Exception e) { XposedBridge.log(e); }
        return null;
    }

    public int getMediaType() {
        if (mediaTypeField == null) return -1;
        try {
            return mediaTypeField.getInt(fmessage);
        } catch (Exception ignored) { return -1; }
    }

    public int getStatus() {
        if (fmessage == null) return -1;
        try {
            // Usually 'status' or a similar int field.
            // Heuristic: Status is an int, and common obfuscated names often repeat.
            // We can search for an int field that is typically 0, 1, 4, 5, 13.
            Field f = XposedHelpers.findField(fmessage.getClass(), "status");
            return f.getInt(fmessage);
        } catch (Throwable t) {
            // Try common obfuscated names if needed, but 'status' is very common in FMessage
            try {
                // Search all int fields for one that matches status values? Too slow.
                // Just use reflection to find "status" field even if it's protected/private
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public boolean isViewOnce() {
        var media_type = getMediaType();
        return (media_type == 82 || media_type == 42 || media_type == 43);
    }

    /* Key Class */
    public static class Key {

        public static Class<?> TYPE;

        private FMessageWpp fmessage;
        public Object thisObject;
        public String messageID;
        public boolean isFromMe;
        public UserJid remoteJid;

        public Key(Object key) {
            this(key, null);
        }

        public Key(Object key, FMessageWpp fmessage) {
            this.thisObject = key;
            this.fmessage = fmessage;

            this.messageID = "";
            this.isFromMe = false;

            if (key == null) return;

            try {
                // 1. Find MessageID (String)
                boolean foundId = false;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        try {
                            String val = (String) f.get(key);
                            if (val != null && val.length() > 5) {
                                this.messageID = val;
                                foundId = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // 2. Find isFromMe (boolean)
                boolean foundFromMe = false;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType() == boolean.class) {
                        f.setAccessible(true);
                        try {
                            this.isFromMe = f.getBoolean(key);
                            foundFromMe = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                }

                // 3. Find RemoteJid
                boolean foundJid = false;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("Jid")) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(key);
                            if (val != null) {
                                this.remoteJid = new UserJid(val);
                                foundJid = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // Fallbacks
                // Fallbacks using dynamic fields
                if (!foundId && keyMessageIdField != null) {
                    try {
                        this.messageID = (String) keyMessageIdField.get(key);
                    } catch (Exception e) {
                        // Fallback to hardcoded if dynamic fails (unlikely if found)
                        try {
                            Object val = XposedHelpers.getObjectField(key, "A01");
                            if (val instanceof String) this.messageID = (String) val;
                        } catch (Exception ignored) {}
                    }
                } else if (!foundId) {
                     // Last resort hardcoded
                     try {
                        Object val = XposedHelpers.getObjectField(key, "A01");
                        if (val instanceof String) this.messageID = (String) val;
                    } catch (Exception ignored) {}
                }

                if (!foundFromMe && keyFromMeField != null) {
                    try {
                        this.isFromMe = keyFromMeField.getBoolean(key);
                    } catch (Exception e) {
                         try {
                            this.isFromMe = XposedHelpers.getBooleanField(key, "A02");
                        } catch (Exception ignored) {}
                    }
                } else if (!foundFromMe) {
                    try {
                        this.isFromMe = XposedHelpers.getBooleanField(key, "A02");
                    } catch (Exception ignored) {}
                }

                if (!foundJid && keyRemoteJidField != null) {
                    try {
                        Object val = keyRemoteJidField.get(key);
                        if (val != null) this.remoteJid = new UserJid(val);
                    } catch (Exception e) {
                        try {
                            Object val = XposedHelpers.getObjectField(key, "A00");
                            if (val != null) this.remoteJid = new UserJid(val);
                        } catch (Exception ignored) {}
                    }
                } else if (!foundJid) {
                     try {
                        Object val = XposedHelpers.getObjectField(key, "A00");
                        if (val != null) this.remoteJid = new UserJid(val);
                    } catch (Exception ignored) {}
                }

            } catch (Exception e) {
                XposedBridge.log("Key constructor error: " + e.getMessage());
            }
        }

        public Key(String messageID, UserJid remoteJid, boolean isFromMe) {
            this.messageID = messageID;
            this.isFromMe = isFromMe;
            this.remoteJid = remoteJid;

            if (TYPE != null) {
                try {
                    var key = XposedHelpers.newInstance(TYPE, remoteJid.userJid != null ? remoteJid.userJid : remoteJid.phoneJid, messageID, false);
                    var fmessage = WppCore.getFMessageFromKey(key);
                    if (fmessage != null) {
                        this.thisObject = key;
                        this.fmessage = new FMessageWpp(fmessage);
                    }
                } catch (Exception ignored) {}
            }
        }

        public FMessageWpp getFMessage() {
            return fmessage;
        }

        @NonNull
        @Override
        public String toString() {
            return "Key{" +
                    "messageID='" + messageID + '\'' +
                    ", isFromMe=" + isFromMe +
                    ", remoteJid=" + remoteJid +
                    '}';
        }
    }

    public static class UserJid {

        public Object phoneJid;
        public Object userJid;

        public UserJid() {
        }

        public UserJid(@Nullable String rawjid) {
            if (isInvalidJid(rawjid)) return;
            if (checkValidLID(rawjid)) {
                this.userJid = WppCore.createUserJid(rawjid);
                this.phoneJid = WppCore.getPhoneJidFromUserJid(this.userJid);
            } else {
                this.phoneJid = WppCore.createUserJid(rawjid);
                this.userJid = WppCore.getUserJidFromPhoneJid(this.phoneJid);
            }
        }

        public UserJid(@Nullable Object lidOrJid) {
            if (lidOrJid == null) return;

            String raw = null;
            try {
                if (jidGetRawStringMethod != null) {
                    raw = (String) jidGetRawStringMethod.invoke(lidOrJid);
                } else {
                    raw = (String) XposedHelpers.callMethod(lidOrJid, "getRawString");
                }
            } catch (Throwable ignored) {
                // XposedBridge.log("UserJid ctor error: " + ignored.getMessage());
            }

            if (raw == null || isInvalidJid(raw)) return;

            if (checkValidLID(raw)) {
                this.userJid = lidOrJid;
                this.phoneJid = WppCore.getPhoneJidFromUserJid(this.userJid);
            } else {
                this.phoneJid = lidOrJid;
                this.userJid = WppCore.getUserJidFromPhoneJid(this.phoneJid);
            }
        }

        public UserJid(@Nullable Object userJid, Object phoneJid) {
            this.userJid = userJid;
            this.phoneJid = phoneJid;
        }

        @Nullable
        public String getPhoneRawString() {
            if (this.phoneJid == null) return null;
            try {
                String raw;
                if (jidGetRawStringMethod != null) {
                    raw = (String) jidGetRawStringMethod.invoke(this.phoneJid);
                } else {
                    raw = (String) XposedHelpers.callMethod(this.phoneJid, "getRawString");
                }
                if (raw == null) return null;
                return raw.replaceFirst("\\.[\\d:]+@", "@");
            } catch (Exception e) {
                return null;
            }
        }

        @Nullable
        public String getUserRawString() {
            if (this.userJid == null) return null;
            try {
                String raw;
                if (jidGetRawStringMethod != null) {
                    raw = (String) jidGetRawStringMethod.invoke(this.userJid);
                } else {
                    raw = (String) XposedHelpers.callMethod(this.userJid, "getRawString");
                }
                if (raw == null) return null;
                return raw.replaceFirst("\\.[\\d:]+@", "@");
            } catch (Exception e) {
                return null;
            }
        }

        @Nullable
        public String getPhoneNumber() {
            String str = getPhoneRawString();
            try {
                if (str == null) return null;
                if (str.contains(".") && str.contains("@") && str.indexOf(".") < str.indexOf("@")) {
                    return str.substring(0, str.indexOf("."));
                } else if (str.contains("@g.us") || str.contains("@s.whatsapp.net")
                        || str.contains("@broadcast") || str.contains("@lid")) {
                    return str.substring(0, str.indexOf("@"));
                }
                return str;
            } catch (Exception e) {
                return str;
            }
        }

        private boolean isInvalidJid(String rawjid) {
            if (rawjid == null) return false;
            int atIndex = rawjid.indexOf('@');
            if (atIndex == -1 || atIndex == rawjid.length() - 1) {
                return false;
            }
            if (!rawjid.contains("@")) {
                return false;
            }
            String[] split = rawjid.split("@");
            if (split.length != 2) {
                return false;
            }

            // valid JIDs: s.whatsapp.net, lid, g.us, broadcast, status, newsletter
            return !split[1].equals("s.whatsapp.net")
                    && !split[1].equals("lid")
                    && !split[1].equals("g.us")
                    && !split[1].equals("broadcast")
                    && !split[1].equals("status")
                    && !split[1].equals("newsletter");
        }

        public boolean isStatus() {
            return Objects.equals(getPhoneNumber(), "status");
        }

        public boolean isNewsletter() {
            String raw = getPhoneRawString();
            if (raw == null) return false;
            return raw.contains("@newsletter");
        }

        public boolean isBroadcast() {
            String raw = getPhoneRawString();
            if (raw == null) return false;
            return raw.contains("@broadcast");
        }

        public boolean isGroup() {
            if (this.phoneJid == null) return false;
            String str = getPhoneRawString();
            if (str == null) return false;
            return str.contains("-") || str.contains("@g.us")
                    || (!str.contains("@") && str.length() > 16);
        }

        public boolean isContact() {
            if (this.userJid != null) {
                var raw = getUserRawString();
                return raw != null && raw.contains("@lid");
            }
            String str = getPhoneRawString();
            return str != null && str.contains("@s.whatsapp.net");
        }

        public boolean isNull() {
            return this.phoneJid == null && this.userJid == null;
        }

        private static boolean checkValidLID(String lid) {
            if (lid != null && lid.contains("@lid")) {
                String id = lid.split("@")[0];
                return lid.length() > 14;
            }
            return false;
        }

        @NonNull
        @Override
        public String toString() {
            return "UserJid{" + "PhoneJid=" + phoneJid + ", UserJid=" + userJid + '}';
        }
    }
}
