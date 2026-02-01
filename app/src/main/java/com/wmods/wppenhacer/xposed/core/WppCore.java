package com.wmods.wppenhacer.xposed.core;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Pair;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.views.dialog.BottomDialogWpp;
import com.wmods.wppenhacer.xposed.bridge.WaeIIFace;
import com.wmods.wppenhacer.xposed.bridge.client.BaseClient;
import com.wmods.wppenhacer.xposed.bridge.client.BridgeClient;
import com.wmods.wppenhacer.xposed.bridge.client.ProviderClient;
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONObject;
import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WppCore {

    // Guard XposedBridge usage for environments where Xposed is not loaded
    private static boolean sHasXposedBridge = false;

    static {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            sHasXposedBridge = true;
        } catch (Throwable t) {
            sHasXposedBridge = false;
        }
    }

    private static void logX(String msg) {
        if (sHasXposedBridge) {
            try {
                Class<?> xb = Class.forName("de.robv.android.xposed.XposedBridge");
                var m = xb.getMethod("log", String.class);
                m.invoke(null, msg);
            } catch (Throwable t) {
                android.util.Log.w("WaEnhancer", "XposedBridge log failed (via reflection)", t);
                android.util.Log.d("WaEnhancer", msg);
            }
        } else {
            android.util.Log.d("WaEnhancer", msg);
        }
    }

    static final HashSet<ActivityChangeState> listenerAcitivity = new HashSet<>();
    @SuppressLint("StaticFieldLeak")
    static Activity mCurrentActivity;
    static LinkedHashSet<Activity> activities = new LinkedHashSet<>();
    private static Class<?> mGenJidClass;
    private static Method mGenJidMethod;
    private static Class bottomDialog;
    private static Field convChatField;
    private static Field chatJidField;
    private static SharedPreferences privPrefs;
    private static Object mStartUpConfig;
    private static Object mActionUser;
    private static SQLiteDatabase mWaDatabase;
    public static BaseClient client;
    private static Object mCachedMessageStore;
    private static Class<?> mSettingsNotificationsClass;
    private static Method convertLidToJid;

    private static Object mWaJidMapRepository;
    private static Method convertJidToLid;
    private static Object mUserActionSend;
    private static Method userActionSendMethod;
    private static Class actionUser;
    private static Method cachedMessageStoreKey;
    
    // Media sending support
    private static Object mMediaActionUser;
    private static Method mMediaActionUserMethod;

    private static final Map<FMessageWpp.UserJid, String> contactNameCache = new LinkedHashMap<>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<FMessageWpp.UserJid, String> eldest) {
            return size() > 100;
        }
    };
    private static final AtomicReference<Class<?>> homeActivityClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> tabsPagerClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> viewOnceViewerActivityClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> aboutActivityClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> dataUsageActivityClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> textStatusComposerFragmentClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> voipManagerClassRef = new AtomicReference<>();
    private static final AtomicReference<Class<?>> voipCallInfoClassRef = new AtomicReference<>();


    public static void Initialize(ClassLoader loader, XSharedPreferences pref) throws Exception {
        privPrefs = Utils.getApplication().getSharedPreferences("WaGlobal", Context.MODE_PRIVATE);
        // init UserJID
        try {
            var mSendReadClass = Unobfuscator.findFirstClassUsingName(loader, StringMatchType.EndsWith, "SendReadReceiptJob");
            var subClass = ReflectionUtils.findConstructorUsingFilter(mSendReadClass, (constructor) -> constructor.getParameterCount() == 8).getParameterTypes()[0];
            mGenJidClass = ReflectionUtils.findFieldUsingFilter(subClass, (field) -> Modifier.isStatic(field.getModifiers())).getType();
            mGenJidMethod = ReflectionUtils.findMethodUsingFilter(mGenJidClass, (method) -> method.getParameterCount() == 1 && !Modifier.isStatic(method.getModifiers()));
        } catch (Throwable t) {
            try {
                // Fallback: Try to find static 'get' in UserJid class
                Class<?> localUserJidClass = Unobfuscator.findFirstClassUsingName(loader, StringMatchType.EndsWith, "jid.UserJid");
                if (localUserJidClass != null) {
                    for (Method m : localUserJidClass.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 
                                && m.getParameterTypes()[0] == String.class && m.getReturnType() == localUserJidClass) {
                            mGenJidMethod = m;
                            mGenJidClass = localUserJidClass;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        // Bottom Dialog
        bottomDialog = Unobfuscator.loadDialogViewClass(loader);

        convChatField = Unobfuscator.loadAntiRevokeConvChatField(loader);
        chatJidField = Unobfuscator.loadAntiRevokeChatJidField(loader);

        // Settings notifications activity (required for ActivityController.EXPORTED_ACTIVITY)
        mSettingsNotificationsClass = getSettingsNotificationsActivityClass(loader);

        // StartUpPrefs
        var startPrefsConfig = Unobfuscator.loadStartPrefsConfig(loader);
        XposedBridge.hookMethod(startPrefsConfig, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                mStartUpConfig = param.thisObject;
            }
        });

        // UserActionSend
        userActionSendMethod = Unobfuscator.loadUserActionsTextMessageSending(loader);
        XposedBridge.hookAllConstructors(userActionSendMethod.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mUserActionSend = param.thisObject;
            }
        });

        // ActionUser
        actionUser = Unobfuscator.loadActionUser(loader);
        XposedBridge.log("ActionUser: " + actionUser.getName());
        XposedBridge.hookAllConstructors(actionUser, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mActionUser = param.thisObject;
            }
        });

        // CachedMessageStore
        cachedMessageStoreKey = Unobfuscator.loadCachedMessageStoreKey(loader);
        XposedBridge.hookAllConstructors(cachedMessageStoreKey.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mCachedMessageStore = param.thisObject;
            }
        });

        // WaJidMap
        convertLidToJid = Unobfuscator.loadConvertLidToJid(loader);
        convertJidToLid = Unobfuscator.loadConvertJidToLid(loader);
        XposedBridge.hookAllConstructors(convertLidToJid.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mWaJidMapRepository = param.thisObject;
            }
        });

        // MediaActionUser for sending images/videos
        try {
            mMediaActionUserMethod = Unobfuscator.loadSendMediaUserAction(loader);
            XposedBridge.log("MediaActionUser method: " + mMediaActionUserMethod.getName());
            XposedBridge.hookAllConstructors(mMediaActionUserMethod.getDeclaringClass(), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mMediaActionUser = param.thisObject;
                }
            });
        } catch (Exception e) {
            XposedBridge.log("Warning: Could not load MediaActionUser: " + e.getMessage());
        }


        // Load wa database
        loadWADatabase();

        if (!pref.getBoolean("lite_mode", false)) {
            initBridge(Utils.getApplication());
        }

    }

    public static Object getPhoneJidFromUserJid(Object lid) {
        if (lid == null) return null;
        try {
            var rawString = (String) XposedHelpers.callMethod(lid, "getRawString");
            if (rawString == null || !rawString.contains("@lid")) return lid;
            rawString = rawString.replaceFirst("\\.[\\d:]+@", "@");
            var newUser = WppCore.createUserJid(rawString);
            var result = ReflectionUtils.callMethod(convertLidToJid, mWaJidMapRepository, newUser);
            return result == null ? lid : result;
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return lid;
    }

    public static Object getUserJidFromPhoneJid(Object userJid) {
        if (userJid == null) return null;
        try {
            var rawString = (String) XposedHelpers.callMethod(userJid, "getRawString");
            if (rawString == null || rawString.contains("@lid")) return userJid;
            rawString = rawString.replaceFirst("\\.[\\d:]+@", "@");
            var newUser = WppCore.createUserJid(rawString);
            var result = ReflectionUtils.callMethod(convertJidToLid, mWaJidMapRepository, newUser);
            return result == null ? userJid : result;
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return userJid;
    }

    public static void initBridge(Context context) throws Exception {
        var prefsCacheHooks = UnobfuscatorCache.getInstance().sPrefsCacheHooks;
        int preferredOrder = prefsCacheHooks.getInt("preferredOrder", 1); // 0 for ProviderClient first, 1 for BridgeClient first

        boolean connected = false;
        if (preferredOrder == 0) {
            if (tryConnectBridge(new ProviderClient(context))) {
                connected = true;
            } else if (tryConnectBridge(new BridgeClient(context))) {
                connected = true;
                preferredOrder = 1; // Update preference to BridgeClient first
            }
        } else {
            if (tryConnectBridge(new BridgeClient(context))) {
                connected = true;
            } else if (tryConnectBridge(new ProviderClient(context))) {
                connected = true;
                preferredOrder = 0; // Update preference to ProviderClient first
            }
        }

        if (!connected) {
            throw new Exception(context.getString(ResId.string.bridge_error));
        }

        // Update the preferred order if it changed
        prefsCacheHooks.edit().putInt("preferredOrder", preferredOrder).apply();
    }


    private static boolean tryConnectBridge(BaseClient baseClient) throws Exception {
        try {
            XposedBridge.log("Trying to connect to " + baseClient.getClass().getSimpleName());
            client = baseClient;
            CompletableFuture<Boolean> canLoadFuture = baseClient.connect();
            Boolean canLoad = canLoadFuture.get();
            if (!canLoad) throw new Exception();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public static void sendMessage(String number, String message) {
        sendMessage(Collections.singletonList(number + "@s.whatsapp.net"), message, -1);
    }

    public static void sendMessage(List<String> jids, String message, long messageId) {
        XposedBridge.log("WaEnhancer: sendMessage called for messageId: " + messageId + " with " + jids.size() + " JIDs");
        try {
            if (userActionSendMethod == null) {
                XposedBridge.log("WaEnhancer: userActionSendMethod is null!");
                return;
            }
            XposedBridge.log("WaEnhancer: Using senderMethod: " + userActionSendMethod.getName() + " from class: " + userActionSendMethod.getDeclaringClass().getName());
            
            List<Object> userJidList = new ArrayList<>();
            for (String jid : jids) {
                Object userJid = createUserJid(jid);
                if (userJid != null) {
                    userJidList.add(userJid);
                } else {
                    XposedBridge.log("WaEnhancer: Failed to create UserJID for: " + jid);
                }
            }

            if (userJidList.isEmpty()) {
                XposedBridge.log("WaEnhancer: No valid JIDs found after creation");
                Utils.showToast("No valid JIDs found", Toast.LENGTH_SHORT);
                return;
            }

            Class<?>[] params = userActionSendMethod.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = ReflectionUtils.getDefaultValue(params[i]);
            }
            
            int msgIndex = ReflectionUtils.findIndexOfType(params, String.class);
            if (msgIndex != -1) args[msgIndex] = message;
            
            int listIndex = ReflectionUtils.findIndexOfType(params, List.class);
            if (listIndex != -1) args[listIndex] = userJidList;
            
            Object userActionInstance = getUserActionSend();
            if (userActionInstance == null) {
                XposedBridge.log("WaEnhancer: userActionInstance is null!");
                throw new Exception("UserActionSend instance is null");
            }
            
            XposedBridge.log("WaEnhancer: Invoking senderMethod...");
            userActionSendMethod.invoke(userActionInstance, args);
            XposedBridge.log("WaEnhancer: senderMethod invoked successfully");
            
            // Success callback to the app
            if (messageId != -1) {
                Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
                intent.putExtra("message_id", messageId);
                intent.putExtra("success", true);
                intent.setPackage("com.wmods.wppenhacer");
                Utils.getApplication().sendBroadcast(intent);
                XposedBridge.log("WaEnhancer: Broadcasted success for messageId: " + messageId);
            }
            
            Utils.showToast("Message sent to " + userJidList.size() + " contacts", Toast.LENGTH_SHORT);
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Exception in sendMessage: " + e.getMessage());
            XposedBridge.log(e);
            Utils.showToast("Error in sending message:" + e.getMessage(), Toast.LENGTH_SHORT);
            
            // Failure callback to the app
            if (messageId != -1) {
                Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
                intent.putExtra("message_id", messageId);
                intent.putExtra("success", false);
                intent.setPackage("com.wmods.wppenhacer");
                Utils.getApplication().sendBroadcast(intent);
            }
        }
    }

    public static Object getUserActionSend() {
        try {
            if (mUserActionSend == null) {
                XposedBridge.log("WaEnhancer: mUserActionSend is null, attempting to instantiate...");
                Class<?> clazz = userActionSendMethod.getDeclaringClass();
                Constructor<?>[] constructors = clazz.getConstructors();
                if (constructors.length == 0) {
                    XposedBridge.log("WaEnhancer: No public constructors found for UserActionSend");
                    return null;
                }
                mUserActionSend = constructors[0].newInstance();
                XposedBridge.log("WaEnhancer: New UserActionSend instance created");
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Failed to instantiate UserActionSend: " + e.getMessage());
            XposedBridge.log(e);
        }
        return mUserActionSend;
    }

    public static boolean sendImageMessage(List<String> jids, String caption, File imageFile, long messageId) {
        XposedBridge.log("WaEnhancer: sendImageMessage called for messageId: " + messageId);
        try {
            if (mMediaActionUserMethod == null) {
                XposedBridge.log("WaEnhancer: mMediaActionUserMethod is null!");
                Utils.showToast("Media sending not available", Toast.LENGTH_SHORT);
                return false;
            }

            if (imageFile == null || !imageFile.exists()) {
                XposedBridge.log("WaEnhancer: Image file is null or doesn't exist");
                Utils.showToast("Image file not found", Toast.LENGTH_SHORT);
                return false;
            }

            XposedBridge.log("WaEnhancer: Using media method: " + mMediaActionUserMethod.getName());

            // Create UserJid list
            List<Object> userJidList = new ArrayList<>();
            for (String jid : jids) {
                Object userJid = createUserJid(jid);
                if (userJid != null) {
                    userJidList.add(userJid);
                } else {
                    XposedBridge.log("WaEnhancer: Failed to create UserJID for: " + jid);
                }
            }

            if (userJidList.isEmpty()) {
                XposedBridge.log("WaEnhancer: No valid JIDs found");
                Utils.showToast("No valid contacts", Toast.LENGTH_SHORT);
                return false;
            }

            // Prepare method parameters
            Class<?>[] params = mMediaActionUserMethod.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = ReflectionUtils.getDefaultValue(params[i]);
            }

            // Set parameters based on type
            int listIndex = ReflectionUtils.findIndexOfType(params, List.class);
            if (listIndex != -1) args[listIndex] = userJidList;

            int stringIndex = ReflectionUtils.findIndexOfType(params, String.class);
            if (stringIndex != -1) args[stringIndex] = caption != null ? caption : "";

            int fileIndex = ReflectionUtils.findIndexOfType(params, File.class);
            if (fileIndex != -1) args[fileIndex] = imageFile;

            // Get or create media action instance
            Object mediaActionInstance = getMediaActionUser();
            if (mediaActionInstance == null) {
                XposedBridge.log("WaEnhancer: mediaActionInstance is null!");
                throw new Exception("MediaActionUser instance is null");
            }

            XposedBridge.log("WaEnhancer: Invoking media method...");
            mMediaActionUserMethod.invoke(mediaActionInstance, args);
            XposedBridge.log("WaEnhancer: Media sent successfully");

            // Success callback
            if (messageId != -1) {
                Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
                intent.putExtra("message_id", messageId);
                intent.putExtra("success", true);
                intent.setPackage("com.wmods.wppenhacer");
                Utils.getApplication().sendBroadcast(intent);
            }

            Utils.showToast("Media sent to " + userJidList.size() + " contacts", Toast.LENGTH_SHORT);
            return true;

        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Exception in sendImageMessage: " + e.getMessage());
            XposedBridge.log(e);
            Utils.showToast("Error sending media: " + e.getMessage(), Toast.LENGTH_SHORT);

            // Failure callback
            if (messageId != -1) {
                Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
                intent.putExtra("message_id", messageId);
                intent.putExtra("success", false);
                intent.setPackage("com.wmods.wppenhacer");
                Utils.getApplication().sendBroadcast(intent);
            }
            return false;
        }
    }

    private static Object getMediaActionUser() {
        try {
            if (mMediaActionUser == null) {
                XposedBridge.log("WaEnhancer: mMediaActionUser is null, attempting to instantiate...");
                Class<?> clazz = mMediaActionUserMethod.getDeclaringClass();
                Constructor<?>[] constructors = clazz.getConstructors();
                if (constructors.length == 0) {
                    XposedBridge.log("WaEnhancer: No public constructors found for MediaActionUser");
                    return null;
                }
                mMediaActionUser = constructors[0].newInstance();
                XposedBridge.log("WaEnhancer: New MediaActionUser instance created");
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Failed to instantiate MediaActionUser: " + e.getMessage());
            XposedBridge.log(e);
        }
        return mMediaActionUser;
    }

    public static void sendReaction(String s, Object objMessage) {
        try {
            var senderMethod = ReflectionUtils.findMethodUsingFilter(actionUser, (method) -> method.getParameterCount() == 3 && Arrays.equals(method.getParameterTypes(), new Class[]{FMessageWpp.TYPE, String.class, boolean.class}));
            senderMethod.invoke(getActionUser(), objMessage, s, !TextUtils.isEmpty(s));
        } catch (Exception e) {
            Utils.showToast("Error in sending reaction:" + e.getMessage(), Toast.LENGTH_SHORT);
            XposedBridge.log(e);
        }
    }

    public static Object getActionUser() {
        try {
            if (mActionUser == null) {
                mActionUser = actionUser.getConstructors()[0].newInstance();
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return mActionUser;
    }


    public static void loadWADatabase() {
        if (mWaDatabase != null) return;
        var dataDir = Utils.getApplication().getFilesDir().getParentFile();
        var database = new File(dataDir, "databases/wa.db");
        if (database.exists()) {
            mWaDatabase = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            logX("WaEnhancer: WA DB opened at " + database.getAbsolutePath());
        } else {
            logX("WaEnhancer: WA DB not found at " + database.getAbsolutePath());
        }
    }


    public static Activity getCurrentActivity() {
        return mCurrentActivity;
    }

    public static ActivityChangeState.ChangeType getActivityState(Activity activity) {
        return ActivityStateRegistry.getState(activity);
    }

    public static ActivityChangeState.ChangeType getActivityStateBySimpleName(String simpleName) {
        return ActivityStateRegistry.getStateBySimpleName(simpleName);
    }

    public static boolean isConversationResumed() {
        var state = ActivityStateRegistry.getStateBySimpleName("Conversation");
        return state == ActivityChangeState.ChangeType.RESUMED;
    }

    public static boolean isHomeActivityResumed() {
        var state = ActivityStateRegistry.getStateBySimpleName("HomeActivity");
        return state == ActivityChangeState.ChangeType.RESUMED;
    }

    public static ActivityChangeState.ChangeType getCurrentActivityState() {
        return ActivityStateRegistry.getState(mCurrentActivity);
    }

    public static Activity getActivityBySimpleName(String simpleName) {
        return ActivityStateRegistry.getActivityBySimpleName(simpleName);
    }

    public static Class getHomeActivityClass(@NonNull ClassLoader loader) {
        if (homeActivityClassRef.get() != null) return homeActivityClassRef.get();
        synchronized (homeActivityClassRef) {
            if (homeActivityClassRef.get() != null) return homeActivityClassRef.get();
            Class oldHomeClass = XposedHelpers.findClassIfExists("com.whatsapp.HomeActivity", loader);
            Class result = oldHomeClass != null ? oldHomeClass : XposedHelpers.findClass("com.whatsapp.home.ui.HomeActivity", loader);
            homeActivityClassRef.set(result);
            return result;
        }
    }

    public static Class getTabsPagerClass(@NonNull ClassLoader loader) {
        if (tabsPagerClassRef.get() != null) return tabsPagerClassRef.get();
        synchronized (tabsPagerClassRef) {
            if (tabsPagerClassRef.get() != null) return tabsPagerClassRef.get();
            Class oldHomeClass = XposedHelpers.findClassIfExists("com.whatsapp.TabsPager", loader);
            Class result = oldHomeClass != null ? oldHomeClass : XposedHelpers.findClass("com.whatsapp.home.ui.TabsPager", loader);
            tabsPagerClassRef.set(result);
            return result;
        }
    }

    public static Class getViewOnceViewerActivityClass(@NonNull ClassLoader loader) {
        if (viewOnceViewerActivityClassRef.get() != null) return viewOnceViewerActivityClassRef.get();
        synchronized (viewOnceViewerActivityClassRef) {
            if (viewOnceViewerActivityClassRef.get() != null) return viewOnceViewerActivityClassRef.get();
            Class oldClass = XposedHelpers.findClassIfExists("com.whatsapp.messaging.ViewOnceViewerActivity", loader);
            Class result = oldClass != null ? oldClass : XposedHelpers.findClass("com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity", loader);
            viewOnceViewerActivityClassRef.set(result);
            return result;
        }
    }

    public static Class getAboutActivityClass(@NonNull ClassLoader loader) {
        if (aboutActivityClassRef.get() != null) return aboutActivityClassRef.get();
        synchronized (aboutActivityClassRef) {
            if (aboutActivityClassRef.get() != null) return aboutActivityClassRef.get();
            Class oldClass = XposedHelpers.findClassIfExists("com.whatsapp.settings.About", loader);
            Class result = oldClass != null ? oldClass : XposedHelpers.findClass("com.whatsapp.settings.ui.About", loader);
            aboutActivityClassRef.set(result);
            return result;
        }
    }

    public synchronized static Class getSettingsNotificationsActivityClass(@NonNull ClassLoader loader) {
        if (mSettingsNotificationsClass != null)
            return mSettingsNotificationsClass;

        Class oldClass = XposedHelpers.findClassIfExists("com.whatsapp.settings.SettingsNotifications", loader);

        return oldClass != null
                ? oldClass
                : XposedHelpers.findClass("com.whatsapp.settings.ui.SettingsNotifications", loader);
    }

    public static Class getDataUsageActivityClass(@NonNull ClassLoader loader) {
        if (dataUsageActivityClassRef.get() != null) return dataUsageActivityClassRef.get();
        synchronized (dataUsageActivityClassRef) {
            if (dataUsageActivityClassRef.get() != null) return dataUsageActivityClassRef.get();
            Class oldClass = XposedHelpers.findClassIfExists("com.whatsapp.settings.SettingsDataUsageActivity", loader);
            Class result = oldClass != null ? oldClass : XposedHelpers.findClass("com.whatsapp.settings.ui.SettingsDataUsageActivity", loader);
            dataUsageActivityClassRef.set(result);
            return result;
        }
    }

    public static Class getTextStatusComposerFragmentClass(@NonNull ClassLoader loader) throws Exception {
        if (textStatusComposerFragmentClassRef.get() != null) return textStatusComposerFragmentClassRef.get();
        synchronized (textStatusComposerFragmentClassRef) {
            if (textStatusComposerFragmentClassRef.get() != null) return textStatusComposerFragmentClassRef.get();
            var classes = new String[]{
                    "com.whatsapp.status.composer.TextStatusComposerFragment",
                    "com.whatsapp.statuscomposer.composer.TextStatusComposerFragment"
            };
            Class<?> result = null;
            for (var clazz : classes) {
                if ((result = XposedHelpers.findClassIfExists(clazz, loader)) != null) {
                    textStatusComposerFragmentClassRef.set(result);
                    return result;
                }
            }
            throw new Exception("TextStatusComposerFragmentClass not found");
        }
    }

    public static Class getVoipManagerClass(@NonNull ClassLoader loader) throws Exception {
        if (voipManagerClassRef.get() != null) return voipManagerClassRef.get();
        synchronized (voipManagerClassRef) {
            if (voipManagerClassRef.get() != null) return voipManagerClassRef.get();
            var classes = new String[]{
                    "com.whatsapp.voipcalling.Voip",
                    "com.whatsapp.calling.voipcalling.Voip"
            };
            Class<?> result = null;
            for (var clazz : classes) {
                if ((result = XposedHelpers.findClassIfExists(clazz, loader)) != null) {
                    voipManagerClassRef.set(result);
                    return result;
                }
            }
            throw new Exception("VoipManagerClass not found");
        }
    }

    public static Class getVoipCallInfoClass(@NonNull ClassLoader loader) throws Exception {
        if (voipCallInfoClassRef.get() != null) return voipCallInfoClassRef.get();
        synchronized (voipCallInfoClassRef) {
            if (voipCallInfoClassRef.get() != null) return voipCallInfoClassRef.get();
            var classes = new String[]{
                    "com.whatsapp.voipcalling.CallInfo",
                    "com.whatsapp.calling.infra.voipcalling.CallInfo"
            };
            Class<?> result = null;
            for (var clazz : classes) {
                if ((result = XposedHelpers.findClassIfExists(clazz, loader)) != null) {
                    voipCallInfoClassRef.set(result);
                    return result;
                }
            }
            throw new Exception("VoipCallInfoClass not found");
        }
    }

//    public static Activity getActivityBySimpleName(String name) {
//        for (var activity : activities) {
//            if (activity.getClass().getSimpleName().equals(name)) {
//                return activity;
//            }
//        }
//        return null;
//    }


    public static int getDefaultTheme() {
        if (mStartUpConfig != null) {
            var result = ReflectionUtils.findMethodUsingFilterIfExists(mStartUpConfig.getClass(), (method) -> method.getParameterCount() == 0 && method.getReturnType() == int.class);
            if (result != null) {
                var value = ReflectionUtils.callMethod(result, mStartUpConfig);
                if (value != null) return (int) value;
            }
        }
        var startup_prefs = Utils.getApplication().getSharedPreferences("startup_prefs", Context.MODE_PRIVATE);
        return startup_prefs.getInt("night_mode", 0);
    }

    @NonNull
    public static String getContactName(FMessageWpp.UserJid userJid) {
        synchronized (contactNameCache) {
            String cached = contactNameCache.get(userJid);
            if (cached != null) return cached;
        }
        loadWADatabase();
        if (mWaDatabase == null || userJid.isNull()) return "Whatsapp Contact";
        String name = getSContactName(userJid, false);
        if (!TextUtils.isEmpty(name)) {
            synchronized (contactNameCache) {
                contactNameCache.put(userJid, name);
            }
            return name;
        }
        name = getWppContactName(userJid);
        synchronized (contactNameCache) {
            contactNameCache.put(userJid, name);
        }
        return name;
    }

    @NonNull
    public static String getSContactName(FMessageWpp.UserJid userJid, boolean saveOnly) {
        loadWADatabase();
        if (mWaDatabase == null || userJid == null) return "";
        String selection;
        if (saveOnly) {
            selection = "jid = ? AND raw_contact_id > 0";
        } else {
            selection = "jid = ?";
        }
        String name = null;
        var rawJid = userJid.getPhoneRawString();
        var cursor = mWaDatabase.query("wa_contacts", new String[]{"display_name"}, selection, new String[]{rawJid}, null, null, null);
        if (cursor.moveToFirst()) {
            name = cursor.getString(0);
            cursor.close();
        }
        return name == null ? "" : name;
    }

    @NonNull
    public static String getWppContactName(FMessageWpp.UserJid userJid) {
        loadWADatabase();
        if (mWaDatabase == null || userJid.isNull()) return "";
        String name = null;
        var rawJid = userJid.getPhoneRawString();
        var cursor2 = mWaDatabase.query("wa_vnames", new String[]{"verified_name"}, "jid = ?", new String[]{rawJid}, null, null, null);
        if (cursor2.moveToFirst()) {
            name = cursor2.getString(0);
            cursor2.close();
        }
        return name == null ? "" : name;
    }

    public static Object getFMessageFromKey(Object messageKey) {
        if (messageKey == null) return null;
        try {
            if (mCachedMessageStore == null) {
                XposedBridge.log("CachedMessageStore is null");
                return null;
            }
            return cachedMessageStoreKey.invoke(mCachedMessageStore, messageKey);
        } catch (Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }


    @Nullable
    public static Object createUserJid(@Nullable String rawjid) {
        if (rawjid == null || mGenJidMethod == null) return null;
        try {
            if (java.lang.reflect.Modifier.isStatic(mGenJidMethod.getModifiers())) {
                return mGenJidMethod.invoke(null, rawjid);
            }
            var genInstance = XposedHelpers.newInstance(mGenJidClass);
            return mGenJidMethod.invoke(genInstance, rawjid);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    @Nullable
    public static FMessageWpp.UserJid getCurrentUserJid() {
        try {
            var conversation = getCurrentConversation();
            if (conversation == null) return null;
            Object chatField;
            if (conversation.getClass().getSimpleName().equals("HomeActivity")) {
                // tablet mode found
                var convFragmentMethod = Unobfuscator.loadHomeConversationFragmentMethod(conversation.getClassLoader());
                var convFragment = convFragmentMethod.invoke(null, conversation);
                var convField = Unobfuscator.loadAntiRevokeConvFragmentField(conversation.getClassLoader());
                chatField = convField.get(convFragment);
            } else {
                chatField = convChatField.get(conversation);
            }
            var chatJidObj = chatJidField.get(chatField);
            return new FMessageWpp.UserJid(chatJidObj);
        } catch (Exception e) {
            XposedBridge.log(e);
            return new FMessageWpp.UserJid();
        }
    }

    public static String stripJID(String str) {
        try {
            if (str == null) return null;
            if (str.contains(".") && str.contains("@") && str.indexOf(".") < str.indexOf("@")) {
                return str.substring(0, str.indexOf("."));
            } else if (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast") || str.contains("@lid")) {
                return str.substring(0, str.indexOf("@"));
            }
            return str;
        } catch (Exception e) {
            XposedBridge.log(e);
            return str;
        }
    }

    @Nullable
    public static Drawable getContactPhotoDrawable(String jid) {
        if (jid == null) return null;
        var file = getContactPhotoFile(jid);
        if (file == null) return null;
        return Drawable.createFromPath(file.getAbsolutePath());
    }

    public static File getContactPhotoFile(String jid) {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "/cache/" + "Profile Pictures" + "/" + stripJID(jid) + ".jpg");
        if (!file.exists())
            file = new File(datafolder + "files" + "/" + "Avatars" + "/" + jid + ".j");
        if (file.exists()) return file;
        return null;
    }

    public static String getMyName() {
        var startup_prefs = Utils.getApplication().getSharedPreferences("startup_prefs", Context.MODE_PRIVATE);
        return startup_prefs.getString("push_name", "WhatsApp");
    }

//    public static String getMyNumber() {
//        var mainPrefs = getMainPrefs();
//        return mainPrefs.getString("registration_jid", "");
//    }

    public static SharedPreferences getMainPrefs() {
        return Utils.getApplication().getSharedPreferences(Utils.getApplication().getPackageName() + "_preferences_light", Context.MODE_PRIVATE);
    }


    public static String getMyBio() {
        var mainPrefs = getMainPrefs();
        return mainPrefs.getString("my_current_status", "");
    }

    public static Drawable getMyPhoto() {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "files" + "/" + "me.jpg");
        if (file.exists()) return Drawable.createFromPath(file.getAbsolutePath());
        return null;
    }

    public static BottomDialogWpp createBottomDialog(Context context) {
        return new BottomDialogWpp((Dialog) XposedHelpers.newInstance(bottomDialog, context, 0));
    }

    @Nullable
    public static Activity getCurrentConversation() {
        if (mCurrentActivity == null) return null;
        Class<?> conversation = XposedHelpers.findClass("com.whatsapp.Conversation", mCurrentActivity.getClassLoader());
        if (conversation.isInstance(mCurrentActivity)) return mCurrentActivity;

        // for tablet UI, they're using HomeActivity instead of Conversation
        // TODO: Add more checks for ConversationFragment
        Class<?> home = getHomeActivityClass(mCurrentActivity.getClassLoader());
        if (mCurrentActivity.getResources().getConfiguration().smallestScreenWidthDp >= 600 && home.isInstance(mCurrentActivity))
            return mCurrentActivity;
        return null;
    }

    public static SharedPreferences getPrivPrefs() {
        return privPrefs;
    }

    @SuppressLint("ApplySharedPref")
    public static void setPrivString(String key, String value) {
        privPrefs.edit().putString(key, value).apply();
    }

    public static String getPrivString(String key, String defaultValue) {
        return privPrefs.getString(key, defaultValue);
    }

    public static JSONObject getPrivJSON(String key, JSONObject defaultValue) {
        var jsonStr = privPrefs.getString(key, null);
        if (jsonStr == null) return defaultValue;
        try {
            return new JSONObject(jsonStr);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @SuppressLint("ApplySharedPref")
    public static void setPrivJSON(String key, JSONObject value) {
        privPrefs.edit().putString(key, value == null ? null : value.toString()).apply();
    }

    @SuppressLint("ApplySharedPref")
    public static void removePrivKey(String s) {
        if (s != null && privPrefs.contains(s))
            privPrefs.edit().remove(s).apply();
    }


    @SuppressLint("ApplySharedPref")
    public static void setPrivBoolean(String key, boolean value) {
        privPrefs.edit().putBoolean(key, value).apply();
    }

    public static boolean getPrivBoolean(String key, boolean defaultValue) {
        return privPrefs.getBoolean(key, defaultValue);
    }

    public static void addListenerActivity(ActivityChangeState listener) {
        listenerAcitivity.add(listener);
    }

    public static WaeIIFace getClientBridge() throws Exception {
        if (client == null || client.getService() == null || !client.getService().asBinder().isBinderAlive() || !client.getService().asBinder().pingBinder()) {
            WppCore.getCurrentActivity().runOnUiThread(() -> {
                var dialog = new AlertDialogWpp(WppCore.getCurrentActivity());
                dialog.setTitle("Bridge Error");
                dialog.setMessage("The Connection with WaEnhancer was lost, it is necessary to reconnect with WaEnhancer in order to reestablish the connection.");
                dialog.setPositiveButton("reconnect", (dialog1, which) -> {
                    client.tryReconnect();
                    dialog.dismiss();
                });
                dialog.setNegativeButton("cancel", null);
                dialog.show();
            });
            throw new Exception("Failed connect to Bridge");
        }
        return client.getService();
    }


    public static List<Pair<String, String>> getAllContacts() {
        loadWADatabase();
        if (mWaDatabase == null) return new ArrayList<>();
        var cursor = mWaDatabase.query("wa_contacts", new String[]{"jid", "display_name"}, null, null, null, null, "display_name ASC");
        List<Pair<String, String>> contacts = new ArrayList<>();
        while (cursor.moveToNext()) {
            String jid = cursor.getString(0);
            String name = cursor.getString(1);
            if (name != null && !name.isEmpty() && jid != null) {
                contacts.add(new Pair<>(name, jid));
            }
        }
        cursor.close();
        logX("WaEnhancer: WA contacts loaded: " + contacts.size());
        return contacts;
    }

    public interface ActivityChangeState {

        void onChange(Activity activity, ChangeType type);

        enum ChangeType {
            CREATED, STARTED, ENDED, RESUMED, PAUSED
        }
    }


    public static void notifyUpdatePhotoProfile(Object jidObj) {
        try {
            if (jidObj == null) return;
            XposedBridge.log("WaEnhancer: notifyUpdatePhotoProfile triggered for: " + jidObj);
            
            // Broadcast to features
            Intent intent = new Intent("com.wmods.wppenhacer.PROFILE_PHOTO_UPDATED");
            // Try to extract raw string or pass the object
            String rawJid = jidObj.toString();
            intent.putExtra("jid", rawJid);
            Utils.getApplication().sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log("WaEnhancer: Error in notifyUpdatePhotoProfile: " + t);
        }
    }

    public static String generateRandomString(int length) {
        String characters = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }
}
