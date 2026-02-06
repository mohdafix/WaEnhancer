package com.wmods.wppenhacer.xposed.core.devkit;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.devrel.gmscore.tools.apk.arsc.ArscUtils;
import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class UnobfuscatorCache {

    private final Application mApplication;
    private static UnobfuscatorCache mInstance;
    public final SharedPreferences sPrefsCacheHooks;

    private final Map<String, String> reverseResourceMap = new HashMap<>();
    private final SharedPreferences sPrefsCacheStrings;

    @SuppressLint("ApplySharedPref")
    public UnobfuscatorCache(Application application) {
        mApplication = application;
        try {
            sPrefsCacheHooks = mApplication.getSharedPreferences("UnobfuscatorCache", Context.MODE_PRIVATE);
            sPrefsCacheStrings = mApplication.getSharedPreferences("UnobfuscatorCacheStrings", Context.MODE_PRIVATE);
            long version = sPrefsCacheHooks.getLong("version", 0);
            long currentVersion = mApplication.getPackageManager().getPackageInfo(mApplication.getPackageName(), 0).getLongVersionCode();
            long savedUpdateTime = sPrefsCacheHooks.getLong("updateTime", 0);
            String savedVersionName = sPrefsCacheHooks.getString("wae_version_name", "");
            String versionName = BuildConfig.VERSION_NAME;
            long lastUpdateTime = savedUpdateTime;
            try {
                lastUpdateTime = mApplication.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime;
            } catch (Exception ignored) {
            }
            if (version != currentVersion || savedUpdateTime != lastUpdateTime || !versionName.equals(savedVersionName)) {
                Utils.showToast(application.getString(ResId.string.starting_cache), Toast.LENGTH_LONG);
                sPrefsCacheHooks.edit().clear().apply();
                sPrefsCacheHooks.edit().putLong("version", currentVersion).apply();
                sPrefsCacheHooks.edit().putLong("updateTime", lastUpdateTime).apply();
                sPrefsCacheHooks.edit().putString("wae_version_name", versionName).apply();
                if (version != currentVersion) {
                    sPrefsCacheStrings.edit().clear().apply();
                }
            }
            initCacheStrings();
        } catch (Exception e) {
            throw new RuntimeException("Can't initialize UnobfuscatorCache: " + e.getMessage(), e);
        }

    }

    public static void init(Application mApp) {
        if (mInstance == null)
            mInstance = new UnobfuscatorCache(mApp);
    }

    public static UnobfuscatorCache getInstance() {
        return mInstance;
    }

    private void initCacheStrings() {
        getOfuscateIDString("mystatus");
        getOfuscateIDString("online");
        getOfuscateIDString("groups");
        getOfuscateIDString("messagedeleted");
        getOfuscateIDString("selectcalltype");
        getOfuscateIDString("lastseensun%s");
        getOfuscateIDString("updates");
    }

    private void initializeReverseResourceMap() {
        try {
            var app = Utils.getApplication();
            var source = app.getApplicationInfo().sourceDir;
            var table = ArscUtils.getResourceTable(new File(source));
            var pool = table.getStringPool();
            var pkg = table.getPackage(app.getPackageName());
            var typeChunks = pkg.getTypeChunks("string");
            var chunk = typeChunks.stream().filter(typeChunk -> typeChunk.getConfiguration().isDefault()).findFirst().orElse(null);
            var entries = chunk.getEntries();
            int baseValue = 0x7f12;
            for (var entry : entries.entrySet()) {
                try {
                    int keyHexValue = entry.getKey();
                    int result = baseValue << 16 | keyHexValue;
                    String resourceString = pool.getString(entry.getValue().value().data()).toLowerCase().replaceAll("\\s", "");
                    reverseResourceMap.put(resourceString, String.valueOf(result));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
            reverseResourceMap.clear();
        }
        if (reverseResourceMap.isEmpty()) {
            initializeReverseResourceMapBruteForce();
        }
    }

    private void initializeReverseResourceMapBruteForce() {
        var currentTime = System.currentTimeMillis();
        try {
            var configuration = new Configuration(mApplication.getResources().getConfiguration());
            configuration.setLocale(Locale.ENGLISH);
            var context = Utils.getApplication().createConfigurationContext(configuration);
            Resources resources = context.getResources();

            // Try reflection on R$string class first for efficiency
            try {
                String packageName = mApplication.getPackageName();
                Class<?> rStringClass = Class.forName(packageName + ".R$string", false, mApplication.getClassLoader());
                Field[] fields = rStringClass.getDeclaredFields();
                for (Field field : fields) {
                    try {
                        int id = field.getInt(null);
                        String resourceString = resources.getString(id).toLowerCase().replaceAll("\\s", "");
                        reverseResourceMap.put(resourceString, String.valueOf(id));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                // Fallback to brute force if reflection fails
                int startId = 0x7f120000;
                int endId = 0x7f12ffff;
                for (int i = startId; i <= endId; i++) {
                    try {
                        String resourceString = resources.getString(i).toLowerCase().replaceAll("\\s", "");
                        reverseResourceMap.put(resourceString, String.valueOf(i));
                    } catch (Resources.NotFoundException ignored) {
                    }
                }
            }
            XposedBridge.log("String cache saved in " + (System.currentTimeMillis() - currentTime) + "ms");
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private String getMapIdString(String search) {
        if (reverseResourceMap.isEmpty()) {
            initializeReverseResourceMap();
        }
        search = search.toLowerCase().replaceAll("\\s", "");
        XposedBridge.log("need search obsfucate: " + search);
        return reverseResourceMap.get(search);
    }

    @SuppressLint("ApplySharedPref")
    public int getOfuscateIDString(String search) {
        search = search.toLowerCase().replaceAll("\\s", "");
        var id = sPrefsCacheStrings.getString(search, null);
        if (id == null) {
            id = getMapIdString(search);
            if (id != null) {
                sPrefsCacheStrings.edit().putString(search, id).apply();
            }
        }
        return id == null ? -1 : Integer.parseInt(id);
    }

    public String getString(String search) {
        var id = getOfuscateIDString(search);
        return id < 1 ? "" : mApplication.getResources().getString(id);
    }

    public Field getField(ClassLoader loader, FunctionCall<Field> functionCall) throws Exception {
        return getField(loader, getKeyName(), functionCall);
    }

    public Field getField(ClassLoader loader, String key, FunctionCall<Field> functionCall) throws Exception {
        String value = sPrefsCacheHooks.getString(key, null);
        if (value == null) {
            try {
                Field result = functionCall.call();
                if (result == null) throw new NoSuchFieldException("Field is null");
                saveField(key, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting field " + key + ": " + e.getMessage(), e);
            }
        }
        String[] ClassAndName = value.split(":");
        Class<?> cls = ReflectionUtils.findClass(ClassAndName[0], loader);
        return XposedHelpers.findField(cls, ClassAndName[1]);
    }

    public Field[] getFields(ClassLoader loader, FunctionCall<Field[]> functionCall) throws Exception {
        var methodName = getKeyName();
        String value = sPrefsCacheHooks.getString(methodName, null);
        if (value == null) {
            try {
                Field[] result = functionCall.call();
                if (result == null) throw new NoSuchFieldException("Fields is null");
                saveFields(methodName, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting fields " + methodName + ": " + e.getMessage(), e);
            }
        }
        ArrayList<Field> fields = new ArrayList<>();
        String[] fieldsString = value.split("&");
        for (String field : fieldsString) {
            String[] ClassAndName = field.split(":");
            Class<?> cls = ReflectionUtils.findClass(ClassAndName[0], loader);
            fields.add(XposedHelpers.findField(cls, ClassAndName[1]));
        }
        return fields.toArray(new Field[0]);
    }

    // -----------------------------------------------------------------------------------------
    // UPDATED METHOD: CORRUPTION FIX
    // -----------------------------------------------------------------------------------------
    public Method getMethod(ClassLoader loader, FunctionCall<Method> functionCall) throws Exception {
        var methodName = getKeyName();
        String value = sPrefsCacheHooks.getString(methodName, null);

        // 1. Check if value exists and is valid
        if (value != null) {
            boolean isCorrupted = false;

            // Detect the specific "Intent" bug or malformed strings
            if (value.contains("Intent") && !value.contains(":")) isCorrupted = true;
            if (value.startsWith("L")) isCorrupted = true; // DexKit format sometimes leaks
            if (value.contains(";")) isCorrupted = true; // Signature format

            if (isCorrupted) {
                XposedBridge.log("UnobfuscatorCache: Detected corruption in " + methodName + ". Rescanning...");
                Method result = functionCall.call();
                if (result == null) throw new NoSuchMethodException("Method is null");
                saveMethod(methodName, result);
                return result;
            }
        }

        // 2. Standard Logic (Null check)
        if (value == null) {
            try {
                Method result = functionCall.call();
                if (result == null) throw new NoSuchMethodException("Method is null");
                saveMethod(methodName, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting method " + methodName + ": " + e.getMessage(), e);
            }
        }

        // 3. Try to load from cache (with safety net)
        try {
            return getMethodFromString(loader, value);
        } catch (Exception e) {
            XposedBridge.log("UnobfuscatorCache: Failed to parse cached method " + methodName + ". Rescanning...");
            Method result = functionCall.call();
            if (result == null) throw new NoSuchMethodException("Method is null");
            saveMethod(methodName, result);
            return result;
        }
    }

    public Method[] getMethods(ClassLoader loader, FunctionCall<Method[]> functionCall) throws Exception {
        var methodName = getKeyName();
        String value = sPrefsCacheHooks.getString(methodName, null);
        if (value == null) {
            try {
                Method[] result = functionCall.call();
                if (result == null) throw new NoSuchMethodException("Methods is null");
                saveMethods(methodName, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting methods " + methodName + ": " + e.getMessage(), e);
            }
        }
        var methodStrings = value.split("&");
        ArrayList<Method> methods = new ArrayList<>();
        for (String methodString : methodStrings) {
            var method = getMethodFromString(loader, methodString);
            methods.add(method);
        }
        return methods.toArray(new Method[0]);
    }

    @NonNull
    private Method getMethodFromString(ClassLoader loader, String value) throws Exception {
        if (value == null) throw new Exception("Cache value is null");

        String[] classAndName = value.split(":");
        if (classAndName.length < 2) throw new Exception("Invalid format");

        Class<?> cls = XposedHelpers.findClass(classAndName[0], loader);
        if (classAndName.length == 3) {
            String[] params = classAndName[2].split(",");
            Class<?>[] paramTypes = Arrays.stream(params).map(param -> ReflectionUtils.findClass(param, loader)).toArray(Class<?>[]::new);
            return XposedHelpers.findMethodExact(cls, classAndName[1], paramTypes);
        }
        return XposedHelpers.findMethodExact(cls, classAndName[1]);
    }

    public Class<?> getClass(ClassLoader loader, FunctionCall<Class<?>> functionCall) throws Exception {
        return getClass(loader, getKeyName(), functionCall);
    }

    public Class<?> getClass(ClassLoader loader, String key, FunctionCall<Class<?>> functionCall) throws Exception {
        String value = sPrefsCacheHooks.getString(key, null);
        if (value == null) {
            try {
                Class<?> result = functionCall.call();
                if (result == null) throw new ClassNotFoundException("Class is null");
                saveClass(key, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting class " + key + ": " + e.getMessage(), e);
            }
        }
        return XposedHelpers.findClass(value, loader);
    }

    public Class<?>[] getClasses(ClassLoader loader, FunctionCall<Class<?>[]> functionCall) throws Exception {
        var methodName = getKeyName();
        String value = sPrefsCacheHooks.getString(methodName, null);
        if (value == null) {
            try {
                Class<?>[] result = functionCall.call();
                if (result == null) throw new ClassNotFoundException("Classes is null");
                saveClasses(methodName, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting classes " + methodName + ": " + e.getMessage(), e);
            }
        }
        String[] classStrings = value.split("&");
        ArrayList<Class<?>> classes = new ArrayList<>();
        for (String classString : classStrings) {
            classes.add(XposedHelpers.findClass(classString, loader));
        }
        return classes.toArray(new Class<?>[0]);
    }

    public HashMap<String, Field> getMapField(ClassLoader loader, FunctionCall<HashMap<String, Field>> functionCall) throws Exception {
        var key = getKeyName();
        String value = sPrefsCacheHooks.getString(key, null);
        if (value == null) {
            try {
                var result = functionCall.call();
                if (result == null) throw new Exception("HashMap is null");
                saveHashMap(key, result);
                return result;
            } catch (Exception e) {
                throw new Exception("Error getting HashMap " + key + ": " + e.getMessage(), e);
            }
        }
        return loadHashMap(loader, key);
    }

    private void saveHashMap(String key, HashMap<String, Field> map) {
        JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, Field> entry : map.entrySet()) {
            Field field = entry.getValue();
            String value = field.getDeclaringClass().getName() + ":" + field.getName();
            try {
                jsonObject.put(entry.getKey(), value);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        sPrefsCacheHooks.edit().putString(key, jsonObject.toString()).apply();
    }

    private HashMap<String, Field> loadHashMap(ClassLoader loader, String key) {
        HashMap<String, Field> map = new HashMap<>();
        String jsonString = sPrefsCacheHooks.getString(key, null);
        if (jsonString == null) return map;

        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            Iterator<String> keys = jsonObject.keys();

            while (keys.hasNext()) {
                String mapKey = keys.next();
                String value = jsonObject.getString(mapKey);
                String[] parts = value.split(":");
                if (parts.length == 2) {
                    String className = parts[0];
                    String fieldName = parts[1];
                    try {
                        Class<?> clazz = loader.loadClass(className);
                        Field field = clazz.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        map.put(mapKey, field);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return map;
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveField(String key, Field field) {
        String value = field.getDeclaringClass().getName() + ":" + field.getName();
        sPrefsCacheHooks.edit().putString(key, value).apply();
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveFields(String key, Field[] fields) {
        ArrayList<String> values = new ArrayList<>();
        for (Field field : fields) {
            values.add(field.getDeclaringClass().getName() + ":" + field.getName());
        }
        sPrefsCacheHooks.edit().putString(key, String.join("&", values)).apply();
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveMethod(String key, Method method) {
        String value = method.getDeclaringClass().getName() + ":" + method.getName();
        if (method.getParameterTypes().length > 0) {
            value += ":" + Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
        }
        sPrefsCacheHooks.edit().putString(key, value).apply();
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveMethods(String key, Method[] methods) {
        ArrayList<String> values = new ArrayList<>();
        for (Method method : methods) {
            String value = method.getDeclaringClass().getName() + ":" + method.getName();
            if (method.getParameterTypes().length > 0) {
                value += ":" + Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
            }
            values.add(value);
        }
        sPrefsCacheHooks.edit().putString(key, String.join("&", values)).apply();
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveClass(String message, Class<?> messageClass) {
        sPrefsCacheHooks.edit().putString(message, messageClass.getName()).apply();
    }

    @SuppressWarnings("ApplySharedPref")
    public void saveClasses(String message, Class<?>[] messageClass) {
        ArrayList<String> values = new ArrayList<>();
        for (Class<?> aClass : messageClass) {
            values.add(aClass.getName());
        }
        sPrefsCacheHooks.edit().putString(message, String.join("&", values)).apply();
    }

    private String getKeyName() {
        AtomicReference<String> keyName = new AtomicReference<>("");
        Arrays.stream(Thread.currentThread().getStackTrace()).filter(stackTraceElement -> stackTraceElement.getClassName().equals(Unobfuscator.class.getName())).findFirst().ifPresent(stackTraceElement -> keyName.set(stackTraceElement.getMethodName()));
        return keyName.get();
    }

    public Constructor getConstructor(ClassLoader loader, FunctionCall functionCall) throws Exception {
        var methodName = getKeyName();
        String value = sPrefsCacheHooks.getString(methodName, null);
        if (value == null) {
            var result = (Constructor) functionCall.call();
            if (result == null) throw new Exception("Class is null");
            saveConstructor(methodName, result);
            return result;
        }
        String[] classAndName = value.split(":");
        Class<?> cls = XposedHelpers.findClass(classAndName[0], loader);
        if (classAndName.length == 2) {
            String[] params = classAndName[1].split(",");
            Class<?>[] paramTypes = Arrays.stream(params).map(param -> ReflectionUtils.findClass(param, loader)).toArray(Class<?>[]::new);
            return XposedHelpers.findConstructorExact(cls, paramTypes);
        }
        return XposedHelpers.findConstructorExact(cls);
    }

    @SuppressWarnings("ApplySharedPref")
    private void saveConstructor(String key, Constructor constructor) {
        String value = constructor.getDeclaringClass().getName();
        if (constructor.getParameterTypes().length > 0) {
            value += ":" + Arrays.stream(constructor.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
        }
        sPrefsCacheHooks.edit().putString(key, value).apply();
    }

    public interface FunctionCall<T> {
        T call() throws Exception;
    }
}
