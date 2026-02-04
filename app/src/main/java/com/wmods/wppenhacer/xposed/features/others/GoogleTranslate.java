package com.wmods.wppenhacer.xposed.features.others;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GoogleTranslate extends Feature {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    public GoogleTranslate(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        XposedBridge.log("GoogleTranslate: Initializing hooks...");

        if (!prefs.getBoolean("google_translate", false)) {
            XposedBridge.log("GoogleTranslate: Feature is disabled in preferences.");
            return;
        }

        Method checkSupportLanguage;
        try {
            checkSupportLanguage = Unobfuscator.loadCheckSupportLanguage(classLoader);
        } catch (Exception e) {
            XposedBridge.log("GoogleTranslate: Failed to load checkSupportLanguage: " + e.getMessage());
            return;
        }

        if (checkSupportLanguage == null) {
            XposedBridge.log("GoogleTranslate: checkSupportLanguage method not found via Unobfuscator.");
            return;
        }

        XposedBridge.log("GoogleTranslate: Found checkSupportLanguage in class: " + checkSupportLanguage.getDeclaringClass().getName());

        XposedBridge.hookMethod(checkSupportLanguage, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Bypass language support check by spoofing a known supported pair
                param.args[0] = "pt";
                param.args[1] = "en";
            }
        });

        Class<?> translatorClazz = checkSupportLanguage.getDeclaringClass();
        
        // Dynamically search for translation methods in this class
        Method stringTranslate = null;
        Method listTranslate = null;

        for (Method m : translatorClazz.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            Class<?> returnType = m.getReturnType();
            
            // The result class for translation usually has a constructor (String, float, int) 
            // or for list translation (String[], float, int).
            boolean isResultClass = false;
            try {
                returnType.getConstructor(String.class, float.class, int.class);
                isResultClass = true;
            } catch (NoSuchMethodException ignored) {
                try {
                    returnType.getConstructor(String[].class, float.class, int.class);
                    isResultClass = true;
                } catch (NoSuchMethodException ignored2) {}
            }

            if (isResultClass) {
                if (params.length == 1 && params[0] == String.class) {
                    stringTranslate = m;
                } else if (params.length == 1 && params[0] == List.class) {
                    listTranslate = m;
                }
            }
        }

        if (stringTranslate != null) {
            XposedBridge.log("GoogleTranslate: Hooking string translation method: " + stringTranslate.getName());
            XposedHelpers.findAndHookMethod(translatorClazz, stringTranslate.getName(), String.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    String texto = (String) param.args[0];
                    XposedBridge.log("GoogleTranslate: Translating string: " + (texto.length() > 20 ? texto.substring(0, 20) + "..." : texto));
                    
                    String translation = getTranslation(texto);
                    XposedBridge.log("GoogleTranslate: Translation result: " + (translation.length() > 20 ? translation.substring(0, 20) + "..." : translation));
                    
                    Class<?> returnType = ((Method) param.method).getReturnType();
                    return returnType.getConstructor(String.class, float.class, int.class).newInstance(translation, 1.0f, 0);
                }
            });
        }

        if (listTranslate != null) {
            XposedBridge.log("GoogleTranslate: Hooking list translation method: " + listTranslate.getName());
            XposedHelpers.findAndHookMethod(translatorClazz, listTranslate.getName(), List.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    List<?> list = (List<?>) param.args[0];
                    XposedBridge.log("GoogleTranslate: Translating list of size: " + list.size());
                    
                    ArrayList<String> translated = new ArrayList<>();
                    for (Object obj : list) {
                        if (obj instanceof String) {
                            translated.add(getTranslation((String) obj));
                        }
                    }

                    Class<?> returnType = ((Method) param.method).getReturnType();
                    return returnType.getConstructor(String[].class, float.class, int.class).newInstance(translated.toArray(new String[0]), 1.0f, 0);
                }
            });
        }

        if (stringTranslate == null && listTranslate == null) {
            XposedBridge.log("GoogleTranslate: ERROR - Could not find translation methods in " + translatorClazz.getName());
        }
    }

    private String getTranslation(String text) {
        if (TextUtils.isEmpty(text)) return text;
        
        prefs.reload();
        String provider = prefs.getString("translation_provider", "google");
        String targetLang = prefs.getString("target_translation_language", "");
        if (TextUtils.isEmpty(targetLang)) {
            targetLang = Locale.getDefault().getLanguage();
        }

        XposedBridge.log("GoogleTranslate: Using provider=" + provider + ", targetLang=" + targetLang);

        try {
            CompletableFuture<String> future;
            if ("groq".equals(provider)) {
                future = translateGroq(text, targetLang);
            } else if ("gemini".equals(provider)) {
                future = translateGemini(text, targetLang);
            } else {
                future = translateGoogle(text, targetLang);
            }
            
            // Set a timeout to prevent blocking the thread indefinitely
            return future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            XposedBridge.log("GoogleTranslate: Translation Error (" + provider + "): " + e.toString());
            // Return original text on failure to keep the conversation readable
            return text;
        }
    }

    public CompletableFuture<String> translateGoogle(String text, String languageDest) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String url;
        try {
            url = String.format(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl=%s&q=%s",
                    languageDest,
                    URLEncoder.encode(text, "UTF-8")
            );
        } catch (Exception e) {
            future.completeExceptionally(new RuntimeException("Error encoding URL: " + e.getMessage()));
            return future;
        }

        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(new RuntimeException("Error fetching translation: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseData = response.body().string();
                    try {
                        JSONArray jsonArray = new JSONArray(responseData);
                        JSONArray translations = jsonArray.getJSONArray(0);
                        StringBuilder translation = new StringBuilder();

                        for (int i = 0; i < translations.length(); i++) {
                            JSONArray item = translations.getJSONArray(i);
                            translation.append(item.getString(0));
                        }

                        future.complete(translation.toString());
                    } catch (Exception e) {
                        future.completeExceptionally(new RuntimeException("Error processing response: " + e.getMessage()));
                    }
                } else {
                    future.completeExceptionally(new RuntimeException("Response not successful: " + response.code()));
                }
            }
        });

        return future;
    }

    private CompletableFuture<String> translateGroq(String text, String targetLang) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String apiKey = prefs.getString("groq_api_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            future.complete(text + " [Groq API key not provided]");
            return future;
        }

        try {
            JSONObject root = new JSONObject();
            root.put("model", "llama-3.1-8b-instant");
            
            JSONArray messages = new JSONArray();
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "You are a professional translator. Translate the following text to " + targetLang + ". Provide ONLY the translated text without any explanations or extra characters.");
            messages.put(systemMessage);
            
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", text);
            messages.put(userMessage);
            
            root.put("messages", messages);
            root.put("temperature", 0);

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(root.toString(), MediaType.parse("application/json")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body().string();
                        if (!response.isSuccessful()) {
                            future.complete(text + " [Groq Error: " + response.code() + "]");
                            return;
                        }
                        JSONObject result = new JSONObject(body);
                        future.complete(result.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content").trim());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private CompletableFuture<String> translateGemini(String text, String targetLang) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String apiKey = prefs.getString("gemini_api_key", "").trim();
        String modelName = prefs.getString("gemini_model", "gemini-1.5-flash").trim();
        
        if (TextUtils.isEmpty(apiKey)) {
            future.complete(text + " [Gemini API key not provided]");
            return future;
        }

        try {
            JSONObject root = new JSONObject();
            JSONArray contentsArray = new JSONArray();
            JSONObject contentObj = new JSONObject();
            JSONArray partsArray = new JSONArray();

            JSONObject textPart = new JSONObject();
            textPart.put("text", "Translate the following text to " + targetLang + ". Provide ONLY the translated text.\n\nText: " + text);
            partsArray.put(textPart);

            contentObj.put("parts", partsArray);
            contentsArray.put(contentObj);
            root.put("contents", contentsArray);

            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(root.toString(), MediaType.parse("application/json")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body().string();
                        if (!response.isSuccessful()) {
                            future.complete(text + " [Gemini Error: " + response.code() + "]");
                            return;
                        }
                        JSONObject jsonResult = new JSONObject(body);
                        future.complete(jsonResult.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text").trim());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Message Translation";
    }
}
