package com.wmods.wppenhacer.xposed.core.components;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * @noinspection unused
 */
public class FMessageWpp {

    private static final String TAG = "FMessageWpp";

    // 1. Keep public static Class<?> TYPE for compatibility
    public static Class<?> TYPE;

    // 2. Internal list to support multiple types (X.1P0, X.8kV, etc)
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

    private final Object fmessage;
    private final Object userJid;
    private final boolean valid;
    private Key key;

    public FMessageWpp(Object rawMsg) {
        this.fmessage = rawMsg;
        Object tmpJid;
        boolean ok;

        // Use the helper method to check if instance is supported
        boolean isInstance = isFMessage(rawMsg);

        if (rawMsg == null || !isInstance) {
            if (rawMsg == null) {
                Log.w(TAG, "FMessageWpp constructed with null rawMsg");
            } else {
                Log.w(TAG, "FMessageWpp constructed with invalid type: " + rawMsg.getClass().getName());
            }
            tmpJid = null;
            ok = false;
        } else {
            try {
                tmpJid = safeGetUserJid(rawMsg);
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
                // If found, and it's not the same as main, add it
                if (altType != null && !altType.equals(mainType)) {
                    SUPPORTED_TYPES.add(altType);
                    XposedBridge.log("FMessageWpp: Added support for " + altType.getName());
                }
            } catch (Exception ignored) {}

            // 3. Load methods/fields using the main TYPE
            var userJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.UserJid");
            userJidMethod = ReflectionUtils.findMethodUsingFilter(TYPE, method -> method.getParameterCount() == 0 && method.getReturnType() == userJidClass);

            keyMessage = Unobfuscator.loadMessageKeyField(classLoader);
            if (keyMessage != null) Key.TYPE = keyMessage.getType();

            messageMethod = Unobfuscator.loadNewMessageMethod(classLoader);
            messageWithMediaMethod = Unobfuscator.loadNewMessageWithMediaMethod(classLoader);
            getFieldIdMessage = Unobfuscator.loadSetEditMessageField(classLoader);

            var deviceJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid");
            deviceJidField = ReflectionUtils.findFieldUsingFilter(TYPE, field -> field.getType() == deviceJidClass);

            mediaTypeField = Unobfuscator.loadMediaTypeField(classLoader);
            getOriginalMessageKey = Unobfuscator.loadOriginalMessageKey(classLoader);
            abstractMediaMessageClass = Unobfuscator.loadAbstractMediaMessageClass(classLoader);
            broadcastField = Unobfuscator.loadBroadcastTagField(classLoader);

        } catch (Exception e) {
            XposedBridge.log("FMessageWpp init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper to check if object is any supported FMessage type
    public static boolean isFMessage(Object obj) {
        if (obj == null) return false;
        for (Class<?> type : SUPPORTED_TYPES) {
            if (type != null && type.isInstance(obj)) return true;
        }
        return false;
    }

    // Helper to check if a class is an FMessage type
    public static boolean isFMessageClass(Class<?> clazz) {
        for (Class<?> type : SUPPORTED_TYPES) {
            if (type != null && type.isAssignableFrom(clazz)) return true;
        }
        return false;
    }

    public static boolean checkUnsafeIsFMessage(ClassLoader classLoader, Class<?> clazz) throws Exception {
        return isFMessageClass(clazz);
    }

    private Object safeGetUserJid(Object rawMsg) {
        try {
            if (userJidMethod != null) {
                return userJidMethod.invoke(rawMsg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to invoke userJidMethod: " + t.getMessage());
        }
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
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public long getRowId() {
        if (getFieldIdMessage == null) return 0;
        try {
            return getFieldIdMessage.getLong(fmessage);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return 0;
    }

    public Key getKey() {
        if (keyMessage == null) return null;
        try {
            if (this.key == null) {
                Object rawKey = keyMessage.get(fmessage);
                if (rawKey != null) {
                    this.key = new Key(rawKey, this);
                }
            }
            return this.key;
        } catch (Exception e) {
            XposedBridge.log("FMessageWpp getKey error: " + e.getMessage());
            return null;
        }
    }

    public Key getOriginalKey() {
        if (getOriginalMessageKey == null) return null;
        try {
            Object rawKey = getOriginalMessageKey.invoke(fmessage);
            if (rawKey != null) {
                return new Key(rawKey, this);
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public boolean isBroadcast() {
        if (broadcastField == null) return false;
        try {
            return broadcastField.getBoolean(fmessage);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return false;
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
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public boolean isMediaFile() {
        if (abstractMediaMessageClass == null) return false;
        try {
            return abstractMediaMessageClass.isInstance(fmessage);
        } catch (Exception e) {
            return false;
        }
    }

    public File getMediaFile() {
        if (!isMediaFile()) return null;
        try {
            for (var field : abstractMediaMessageClass.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                var fileField = ReflectionUtils.getFieldByType(field.getType(), File.class);
                if (fileField != null) {
                    var mediaObject = ReflectionUtils.getObjectField(field, fmessage);
                    if (mediaObject == null) continue;
                    var mediaFile = (File) fileField.get(mediaObject);
                    if (mediaFile != null) return mediaFile;
                    var filePath = MessageStore.getInstance().getMediaFromID(getRowId());
                    if (filePath == null) return null;
                    return new File(filePath);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public int getMediaType() {
        if (mediaTypeField == null) return -1;
        try {
            return mediaTypeField.getInt(fmessage);
        } catch (Exception e) {
            XposedBridge.log(e);
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

            if (key == null) return;

            // Dynamic field resolution
            try {
                // 1. Find MessageID (String)
                String foundId = null;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        foundId = (String) f.get(key);
                        if (foundId != null && foundId.length() > 5) {
                            this.messageID = foundId;
                            break;
                        }
                    }
                }
                if (foundId == null) {
                    // Safe fallback
                    this.messageID = (String) XposedHelpers.getObjectField(key, "A01");
                }

                // 2. Find isFromMe (boolean)
                boolean foundFromMe = false;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType() == boolean.class) {
                        f.setAccessible(true);
                        foundFromMe = f.getBoolean(key);
                        this.isFromMe = foundFromMe;
                        break; // Take the first one
                    }
                }
                if (this.messageID == null) {
                    // Fallback if loop failed
                    this.isFromMe = XposedHelpers.getBooleanField(key, "A02");
                }

                // 3. Find RemoteJid
                Object foundJid = null;
                for (Field f : key.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("Jid")) {
                        f.setAccessible(true);
                        foundJid = f.get(key);
                        if (foundJid != null) {
                            this.remoteJid = new UserJid(foundJid);
                            break;
                        }
                    }
                }
                if (foundJid == null) {
                    this.remoteJid = new UserJid(XposedHelpers.getObjectField(key, "A00"));
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
                } catch (Exception e) {
                    // Ignore
                }
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

        public UserJid(@Nullable String rawjid) {
            if (isNonValidJid(rawjid)) return;
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

public UserJid(@Nullable Object lidOrJid) {
    if (lidOrJid == null) return;

    String raw = null;
    try {
        raw = (String) XposedHelpers.callMethod(lidOrJid, "getRawString");
    } catch (Exception e) {
        XposedBridge.log("UserJid ctor error: " + e.getMessage());
    }

    // If we cannot obtain a raw string or it is not a valid JID, just leave both null
    if (raw == null || isNonValidJid(raw)) return;

    if (checkValidLID(raw)) {
        // lidOrJid is a LID-style user JID
        this.userJid = lidOrJid;
        this.phoneJid = WppCore.getPhoneJidFromUserJid(this.userJid);
    } else {
        // lidOrJid is a phone JID
        this.phoneJid = lidOrJid;
        this.userJid = WppCore.getUserJidFromPhoneJid(this.phoneJid);
    }
}

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
                String raw = (String) XposedHelpers.callMethod(this.phoneJid, "getRawString");
                if (raw == null) return null;
                return raw.replaceFirst("\\.[\\d:]+@", "@");
            } catch (Exception e) { return null; }
        }

        @Nullable
        public String getUserRawString() {
            if (this.userJid == null) return null;
            try {
                String raw = (String) XposedHelpers.callMethod(this.userJid, "getRawString");
                if (raw == null) return null;
                return raw.replaceFirst("\\.[\\d:]+@", "@");
            } catch (Exception e) { return null; }
        }

        @Nullable
        public String getPhoneNumber() {
            var str = getPhoneRawString();
            try {
                if (str == null) return null;
                if (str.contains(".") && str.contains("@") && str.indexOf(".") < str.indexOf("@")) {
                    return str.substring(0, str.indexOf("."));
                } else if (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast") || str.contains("@lid")) {
                    return str.substring(0, str.indexOf("@"));
                }
                return str;
            } catch (Exception e) {
                return str;
            }
        }

        private boolean isNonValidJid(String rawjid) {
            if (rawjid == null) {
                return false;
            }
            if (!rawjid.contains("@")) {
                return false;
            }
            String[] split = rawjid.split("@");
            if (split.length != 2) {
                return false;
            }
            return split[1].equals("s.whatsapp.net") || split[1].equals("lid") || split[1].equals("g.us");
        }

        public boolean isStatus() {
            return Objects.equals(getPhoneNumber(), "status");
        }

        public boolean isNewsletter() {
            String raw = getPhoneRawString();
            if (raw == null) return false;
            return raw.contains("@newsletter");
        }

        public boolean isGroup() {
            if (this.phoneJid == null) return false;
            String str = getPhoneRawString();
            if (str == null) return false;
            return str.contains("-") || str.contains("@g.us") || (!str.contains("@") && str.length() > 16);
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
