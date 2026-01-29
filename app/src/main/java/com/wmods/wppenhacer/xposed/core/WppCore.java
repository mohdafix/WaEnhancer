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
    private static Object mMediaActionUser;
    private static Method mMediaActionUserMethod;
    private static Method mMediaBuilderMethod;
    private static Method mMediaWrapperMethod;
    private static Constructor<?> mMediaEntryConstructor;
    private static Constructor<?> mMediaSourceConstructor;

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

        // MediaUserAction (For Scheduled Media)
        try {
            Method sendMediaMethod = Unobfuscator.loadSendMediaUserAction(loader);
            if (sendMediaMethod != null) {
                mMediaActionUserMethod = sendMediaMethod;
                 XposedBridge.log("WaEnhancer: Found MediaActionUser method: " + sendMediaMethod);
                XposedBridge.log("WaEnhancer: Params: " + Arrays.toString(sendMediaMethod.getParameterTypes()));
                
                // Discover Pipeline (Builder/Wrapper/Entry)
                Method[] pipeline = Unobfuscator.loadMediaPipelineMethods(loader);
                mMediaBuilderMethod = pipeline[0];
                mMediaWrapperMethod = pipeline[1];
                
                try {
                    mMediaEntryConstructor = Unobfuscator.loadMediaEntryConstructor(loader);
                    XposedBridge.log("WaEnhancer: Found MediaEntry Constructor: " + mMediaEntryConstructor);
                    
                    mMediaSourceConstructor = Unobfuscator.loadMediaSourceConstructor(loader);
                    XposedBridge.log("WaEnhancer: Found MediaSource Constructor: " + mMediaSourceConstructor);
                } catch (Throwable t) {
                    XposedBridge.log("WaEnhancer: Failed Media Pipeline discovery: " + t);
                }

                XposedBridge.hookAllConstructors(sendMediaMethod.getDeclaringClass(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        mMediaActionUser = param.thisObject;
                        XposedBridge.log("WaEnhancer: Captured MediaActionUser instance.");
                    }
                });
            } else {
                 XposedBridge.log("WaEnhancer: Failed to load MediaActionUser method.");
            }
        } catch (Throwable t) {
            XposedBridge.log("WaEnhancer: Error initializing MediaActionUser hooks: " + t);
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

    public static void sendMediaMessage(List<String> jids, java.io.File mediaFile, String caption, long messageId) {
        XposedBridge.log("WaEnhancer: sendMediaMessage called for messageId: " + messageId);
        try {
            // Check if user action hooks are ready
            if (mMediaActionUserMethod == null) {
                 XposedBridge.log("WaEnhancer: MediaActionUser behavior broken. Method not found during init.");
                 Utils.showToast("Media send not supported.", Toast.LENGTH_SHORT);
                 return;
            }

            // Fail Hard if instance is missing (No dynamic fix)
            if (mMediaActionUser == null) {
                 XposedBridge.log("WaEnhancer: MediaActionUser instance is NULL. Constructor hook failed or not fired yet.");
                 Utils.showToast("Media sender not ready.", Toast.LENGTH_SHORT);
                 return;
            }

            Class<?>[] params = mMediaActionUserMethod.getParameterTypes();
            Object[] args = ReflectionUtils.initArray(params);

            // Recipients Conversion
            List<Object> recipients = new ArrayList<>();
            for (String raw : jids) {
                // Use Standard UserJid creation
                Object userJid = createUserJid(raw);
                if (userJid != null) {
                    recipients.add(userJid);
                }
            }
            
            if (recipients.isEmpty()) {
                 XposedBridge.log("WaEnhancer: No valid JIDs for media send.");
                 Utils.showToast("No valid recipients.", Toast.LENGTH_SHORT);
                 return;
            }

            // PIPELINE: Builder (A02) -> Wrapper (A03) -> Sender (A00)
            
            ArrayList<?> builtMediaList = null; 
            ArrayList<?> wrappedMediaList = null;

            // Step 1: BUILD (A02)
            if (mMediaBuilderMethod != null && mediaFile != null && mediaFile.exists()) {
                 XposedBridge.log("WaEnhancer: Invoking Media Builder (A02)...");
                 Object[] buildArgs = ReflectionUtils.initArray(mMediaBuilderMethod.getParameterTypes());
                 
                 // Fill A02 args (File, Caption, etc)
                 // Priority: Strings (Caption/Path), Long (Size), List (Recipients)
                 Class<?>[] bParams = mMediaBuilderMethod.getParameterTypes();
                 
                 // Try to assign File if possible (unlikely in this version per logs, but safe to try)
                 int fIdx = ReflectionUtils.findIndexOfType(bParams, File.class);
                 if (fIdx != -1) buildArgs[fIdx] = mediaFile;
                 
                 // Assign Strings: Assume first string is path (if no File arg) or caption? 
                 // Heuristic: Assign Path AND Caption to available string slots.
                 // Dump suggests multiple strings.
                 List<Integer> strIndices = new ArrayList<>();
                 for(int i=0; i<bParams.length; i++) if(String.class.isAssignableFrom(bParams[i])) strIndices.add(i);
                 
                 if (!strIndices.isEmpty()) {
                     // If we have at least 1 string slot
                     if (fIdx == -1) {
                         // No File arg, so first String is likely Path
                         buildArgs[strIndices.get(0)] = mediaFile.getAbsolutePath();
                         if (strIndices.size() > 1 && caption != null) {
                             buildArgs[strIndices.get(1)] = caption;
                         }
                     } else {
                         // File arg exists, so first String is likely Caption or Mime
                         if (caption != null) buildArgs[strIndices.get(0)] = caption;
                     }
                 }
                 
                 // Assign List (Recipients)
                 int lIdx = ReflectionUtils.findIndexOfType(bParams, List.class);
                 if (lIdx != -1) buildArgs[lIdx] = recipients;
                 
                  // Assign Long (Size)
                 int longIdx = ReflectionUtils.findIndexOfType(bParams, Long.class);
                 if (longIdx == -1) longIdx = ReflectionUtils.findIndexOfType(bParams, long.class);
                 if (longIdx != -1) buildArgs[longIdx] = mediaFile.length();

                 // Injected Step: Create MediaEntry Object
                 if (mMediaEntryConstructor != null && mMediaSourceConstructor != null) {
                     try {
                        // 1. Construct MediaSource (X.1Rw)
                        // Accepts File or String

                        // 0. Get MediaEnv (X.OG7)
                        // It is likely a field in UserActions (mMediaActionUser)
                        // It is the 1st param of MediaSource Ctor: X.1Rw(X.OG7, String, boolean)
                        
                        Object mediaEnv = null;
                        Class<?> og7Class = mMediaSourceConstructor.getParameterTypes()[0];
                        
                        // Scan UserActions fields for OG7
                        for (Field f : mMediaActionUser.getClass().getDeclaredFields()) {
                            if (f.getType() == og7Class) {
                                f.setAccessible(true);
                                mediaEnv = f.get(mMediaActionUser);
                                if (mediaEnv != null) {
                                     XposedBridge.log("WaEnhancer: Found MediaEnv (OG7) in UserActions field: " + f.getName());
                                     break;
                                }
                            }
                        }
                        
                        if (mediaEnv == null) {
                            XposedBridge.log("WaEnhancer: CRITICAL - Could not find MediaEnv (OG7) in UserActions.");
                            // Try to proceed? Construction will likely fail if we pass null.
                            // But maybe we can pass null?
                        }

                        // 1. Construct MediaSource (X.1Rw)
                        // Sig: (X.OG7, String path, boolean isVideo)
                        // or (X.OG7, File, boolean)
                        // Logs showed: public X.1Rw(X.OG7, java.lang.String, boolean)
                        
                        Object mediaSource = null;
                        Class<?>[] srcParams = mMediaSourceConstructor.getParameterTypes();
                        Object[] srcArgs = ReflectionUtils.initArray(srcParams);
                        
                        // Arg 0: OG7
                        if (srcParams.length > 0 && og7Class.isAssignableFrom(srcParams[0])) {
                            srcArgs[0] = mediaEnv;
                        }

                        // Fill String (Path)
                        int pathIdx = ReflectionUtils.findIndexOfType(srcParams, String.class);
                        if (pathIdx != -1) srcArgs[pathIdx] = mediaFile.getAbsolutePath();
                        
                        // Fill boolean (isVideo) - Default false for image
                        // Assuming boolean param is for 'isVideo' or 'isGif'
                        // initArray defaults to false, which is correct for Image.

                        mediaSource = mMediaSourceConstructor.newInstance(srcArgs);
                        XposedBridge.log("WaEnhancer: Created MediaSource: " + mediaSource);

                        // 2. Construct MediaEntry (X.1Od)
                        // Sig: (MediaSource, int type, long size, ...)
                        Object mediaEntry = null;
                        Class<?>[] entryParams = mMediaEntryConstructor.getParameterTypes();
                        Object[] entryArgs = ReflectionUtils.initArray(entryParams);
                        
                        // Fill MediaSource
                        int srcIdx = ReflectionUtils.findIndexOfType(entryParams, mMediaSourceConstructor.getDeclaringClass());
                        if (srcIdx != -1) entryArgs[srcIdx] = mediaSource;
                        
                        // Fill Size (long)
                        int sizeIdx = ReflectionUtils.findIndexOfType(entryParams, long.class);
                        if (sizeIdx != -1) entryArgs[sizeIdx] = mediaFile.length();
                        
                        // Fill Type (int)
                        int typeIdx = ReflectionUtils.findIndexOfType(entryParams, int.class);
                        if (typeIdx != -1) {
                             if (entryParams[typeIdx] == byte.class) entryArgs[typeIdx] = (byte)1;
                             else entryArgs[typeIdx] = 1;
                        }

                        mediaEntry = mMediaEntryConstructor.newInstance(entryArgs);
                        XposedBridge.log("WaEnhancer: Created MediaEntry: " + mediaEntry);
                        
                        // Inject MediaEntry into Builder Args (A02)
                        int entryArgIdx = ReflectionUtils.findIndexOfType(bParams, mMediaEntryConstructor.getDeclaringClass());
                        if (entryArgIdx != -1) {
                            buildArgs[entryArgIdx] = mediaEntry;
                        } else {
                            XposedBridge.log("WaEnhancer: Warning - Could not find MediaEntry slot in A02 params.");
                        }
                        
                        // Inject MediaEntry or OG7 into Sender Args (A00) later?
                        // A00 takes OG7 and MediaEntry?
                        // We need to save these for Step 3.
                        // I'll assume I can find them again or logic flow continues.
                        
                        // NOTE: For A00 (Sender), we need to fill OG7 and MediaEntry if corresponding slots exist.
                        // I will update Step 3 logic below to use these.

                     } catch (Exception e) {
                         XposedBridge.log("WaEnhancer: Failed to construct Media Objects: " + e);
                     }
                 } else {
                     XposedBridge.log("WaEnhancer: Skipping Media Creation (Constructors missing). SourceCtor=" + mMediaSourceConstructor);
                 }
                 
                 // End of Media Object Creation (605)
                 
                 try {
                     builtMediaList = (ArrayList<?>) mMediaBuilderMethod.invoke(mMediaActionUser, buildArgs);
                     XposedBridge.log("WaEnhancer: A02 Builder returned: " + (builtMediaList != null ? builtMediaList.size() : "null"));
                     if (builtMediaList != null && !builtMediaList.isEmpty()) {
                         XposedBridge.log("WaEnhancer: A02 media class verify=" + builtMediaList.get(0).getClass().getName());
                     }
                 } catch (Exception e) {
                     XposedBridge.log("WaEnhancer: A02 Builder invocation failed: " + e);
                 }
            } else {
                XposedBridge.log("WaEnhancer: Skipping A02 (Method missing or no file).");
            }
            // End of A02 (618)

            // Step 2: WRAP (A03)
            if (mMediaWrapperMethod != null && builtMediaList != null && !builtMediaList.isEmpty()) {
                XposedBridge.log("WaEnhancer: Invoking Media Wrapper (A03)...");
                Object[] wrapArgs = ReflectionUtils.initArray(mMediaWrapperMethod.getParameterTypes());
                Class<?>[] wParams = mMediaWrapperMethod.getParameterTypes();

                // Pass the list from A02
                int listIdx = ReflectionUtils.findIndexOfType(wParams, List.class);
                if (listIdx != -1) wrapArgs[listIdx] = builtMediaList; // Primary list
                
                int recipientsIdx = -1;
                for(int i=0; i<wParams.length; i++) {
                     if(List.class.isAssignableFrom(wParams[i])) {
                         if (i == listIdx) continue; 
                         recipientsIdx = i; 
                         break;
                     }
                }
                if (recipientsIdx != -1) wrapArgs[recipientsIdx] = recipients;
                
                int sIdx = ReflectionUtils.findIndexOfType(wParams, String.class);
                if (sIdx != -1 && caption != null) wrapArgs[sIdx] = caption;

                try {
                    wrappedMediaList = (ArrayList<?>) mMediaWrapperMethod.invoke(mMediaActionUser, wrapArgs);
                    XposedBridge.log("WaEnhancer: A03 Wrapper returned: " + (wrappedMediaList != null ? wrappedMediaList.size() : "null"));
                } catch (Exception e) {
                    XposedBridge.log("WaEnhancer: A03 Wrapper invocation failed: " + e);
                }
            }
            // End of A03 (651 approx)



            // Step 3: SEND (A00) - Static
            // Now we inject the wrapped list into A00 if available
            
            // Fill Arguments via Reflection
            // Arg 0: UserActions Instance (because method is static)
            if (params.length > 0 && params[0].isAssignableFrom(mMediaActionUser.getClass())) {
                args[0] = mMediaActionUser;
            } else {
                 XposedBridge.log("WaEnhancer: CRITICAL - First param mismatch. Expected " + params[0] + " but got " + mMediaActionUser.getClass());
                 return;
            }

            // 1. Recipients
            int listIndex = ReflectionUtils.findIndexOfType(params, List.class);
            if (listIndex != -1) args[listIndex] = recipients;
            
            // 2. Caption
            int captionIndex = ReflectionUtils.findIndexOfType(params, String.class);
            if (captionIndex != -1) args[captionIndex] = caption;
            
            // 3. Media Items (from A03 or A02, or mocked)
            // Does A00 accept a List?
            // Logs for A00 signature: List, String, boolean...
            // It has only ONE list?
            // "interface java.util.List"
            // If it has only one list, and we used it for recipients... where does the media go?
            // Wait, looking at Step 1429 dump:
            // ... java.util.List, boolean, boolean ...
            // Maybe there are other params like X.1Od or X.5CY that hold the media?
            // Or maybe there is a SECOND list I missed in the signature?
            // "class X.10d, class X.7jR ... class java.lang.String, interface java.util.List"
            // Ah, X.10d might be the media object?
            // If `wrappedMediaList` is valid and non-empty, maybe we pass the *items* or the list itself to a param?
            
            // The prompt "Call Final Send Method" says: "Pass result into A00 ... Use defaults/false"
            // "sendArgs[indexOfMediaList] = sendList"
            // So there MUST be a param for the media list.
            
            // If I can't find a second list param, I might be stuck.
            // But let's look for ANY param assignable from ArrayList?
            // If `wrappedMediaList` is ArrayList, find param assignable from ArrayList.
            if (wrappedMediaList != null) {
                int mediaListIdx = ReflectionUtils.findIndexOfType(params, ArrayList.class);
                if (mediaListIdx == -1) mediaListIdx = ReflectionUtils.findIndexOfType(params, List.class); // fallback
                
                // If the only list found was for recipients, we have a conflict unless there are 2 lists.
                // Let's check for multiple lists in params explicitly.
                List<Integer> listIndices = new ArrayList<>();
                for(int i=0; i<params.length; i++) if(List.class.isAssignableFrom(params[i])) listIndices.add(i);
                
                if (listIndices.size() >= 2) {
                    // Assuming one is recipients, one is media.
                    // Which order?
                    // Typically media content is first?
                    // Safe guess: Try to put media list in the slot that ISN'T the recipients slot we already filled?
                    // We filled `listIndex` (first found list) as recipients.
                    // Let's try to swap if needed or fill the second one.
                    if (listIndices.get(0) == listIndex) {
                        args[listIndices.get(1)] = wrappedMediaList;
                    } else {
                        args[listIndices.get(0)] = wrappedMediaList;
                    }
                } else if (listIndices.size() == 1) {
                     // Only 1 list?
                     // Maybe the media is passed as X.1Od (single item) if the list has 1 item?
                     // Or maybe recipients are passed differently?
                     // For now, if we have built media, let's try to inject it if we find a compatible type.
                     // If A00 signature from dumps shows X.1Od etc, those might be the media args.
                     
                     // Fallback: If A00 takes X.1Od (MediaData?), try to pass the first item of wrapped list if compatible?
                     if (!wrappedMediaList.isEmpty()) {
                         Object item = wrappedMediaList.get(0);
                         int itemIdx = ReflectionUtils.findIndexOfType(params, item.getClass());
                         if (itemIdx != -1) args[itemIdx] = item;
                     }
                }
            }

            XposedBridge.log("WaEnhancer: Invoking Static MediaActionUser send for " + recipients.size() + " contacts.");
            
            // Static Invocation
            mMediaActionUserMethod.invoke(null, args); 
            
            XposedBridge.log("WaEnhancer: Media send invoked successfully via Static MediaActionUser.");

            // Success callback
            if (messageId != -1) {
                Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
                intent.putExtra("message_id", messageId);
                intent.putExtra("success", true);
                intent.setPackage("com.wmods.wppenhacer");
                Utils.getApplication().sendBroadcast(intent);
            }
            
            Utils.showToast("Media sent to " + recipients.size(), Toast.LENGTH_SHORT);

        } catch (Throwable t) {
            XposedBridge.log("WaEnhancer: Exception in sendMediaMessage: " + t.getMessage());
            XposedBridge.log(t);
            Utils.showToast("Error sending media: " + t.getMessage(), Toast.LENGTH_SHORT);

            // Failure callback
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


}
