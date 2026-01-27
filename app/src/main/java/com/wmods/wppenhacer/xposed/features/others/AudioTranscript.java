package com.wmods.wppenhacer.xposed.features.others;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class AudioTranscript extends Feature {

    public AudioTranscript(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        if (!prefs.getBoolean("audio_transcription", false))
            return;

        var transcribeMethod = Unobfuscator.loadTranscribeMethod(classLoader);
        Class<?> TranscriptionSegmentClass = Unobfuscator.loadTranscriptSegment(classLoader);

        XposedBridge.hookMethod(transcribeMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                prefs.reload();
                String provider = prefs.getString("transcription_provider", "assemblyai");
                
                var pttTranscriptionRequest = param.args[0];
                var fieldFMessage = ReflectionUtils.getFieldByExtendType(pttTranscriptionRequest.getClass(), FMessageWpp.TYPE);
                var fmessageObj = fieldFMessage.get(pttTranscriptionRequest);
                var fmessage = new FMessageWpp(fmessageObj);
                File file = fmessage.getMediaFile();
                
                if (file == null) {
                    Utils.showToast(Utils.getApplication().getString(ResId.string.download_not_available), 1);
                    return;
                }
                
                // Defensive check: verify file existence before proceeding
                if (!file.exists()) {
                     XposedBridge.log("AudioTranscript: file not found: " + file.getAbsolutePath());
                     Utils.showToast("Audio file not found locally", 0);
                     return;
                }
                
                var callback = param.args[1];
                var onComplete = ReflectionUtils.findMethodUsingFilter(callback.getClass(), method -> method.getParameterCount() == 4);

                // Create a temporary copy in the app's internal cache to avoid permission issues (Err 13/EACCES)
                File tempFile = new File(Utils.getApplication().getCacheDir(), "temp_transcript_" + System.currentTimeMillis() + ".opus");
                try {
                    copyFile(file, tempFile);
                } catch (IOException e) {
                    XposedBridge.log("AudioTranscript: Failed to create temp copy: " + e.getMessage());
                    // Fallback to original file if copy fails
                    tempFile = file;
                }

                // Choose transcription provider based on user preference
                String transcript = "";
                try {
                    if ("groq".equals(provider)) {
                        transcript = transcriptionGroqAI(tempFile);
                    } else if ("gemini".equals(provider)) {
                        transcript = transcriptionGeminiAI(tempFile);
                    } else {
                        transcript = transcriptionAssemblyAI(tempFile);
                    }
                } catch (Throwable e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    XposedBridge.log("AudioTranscript error: " + errorMsg);
                    Utils.showToast("Transcription failed: " + errorMsg, 1);
                    return;
                } finally {
                    // Cleanup temp file
                    if (tempFile != file && tempFile.exists()) {
                        tempFile.delete();
                    }
                }

                if (TextUtils.isEmpty(transcript)) {
                    ReflectionUtils.callMethod(onComplete, callback, fmessageObj, "", new ArrayList<>(), 1);
                    param.setResult(null);
                    return;
                }

                var segments = new ArrayList<>();
                var words = transcript.split("\\s");
                var totalLength = 0;
                for (var word : words) {
                    segments.add(XposedHelpers.newInstance(TranscriptionSegmentClass, totalLength, word.length(), 100, -1, -1));
                    totalLength += word.length() + 1;
                }
                ReflectionUtils.callMethod(onComplete, callback, fmessageObj, transcript, segments, 1);
                param.setResult(null);
            }
        });

    }

    private String transcriptionAssemblyAI(File fileOpus) throws Exception {
        String apiKey = prefs.getString("assemblyai_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            return "API key not provided";
        }
        
        // Final check before network call
        if (!fileOpus.exists()) {
             throw new IOException("File not found for upload: " + fileOpus.getAbsolutePath());
        }

        XposedBridge.log("AudioTranscript: trying to open " + fileOpus.getAbsolutePath());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        RequestBody requestBody = RequestBody.create(fileOpus, MediaType.parse("application/octet-stream"));

        Request uploadRequest = new Request.Builder()
                .url("https://api.assemblyai.com/v2/upload")
                .addHeader("Authorization", apiKey)
                .post(requestBody)
                .build();

        try (okhttp3.Response response = client.newCall(uploadRequest).execute()) {
            if (!response.isSuccessful()) {
                return "Failed to upload audio: " + response.code();
            }

            JSONObject uploadResult = new JSONObject(response.body().string());
            String audioUrl = uploadResult.getString("upload_url");

            JSONObject transcriptionJson = new JSONObject();
            transcriptionJson.put("audio_url", audioUrl);
//            transcriptionJson.put("language_code", Locale.getDefault().getDisplayLanguage());
            transcriptionJson.put("language_detection", true);

            Request transcribeRequest = new Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript")
                    .addHeader("Authorization", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(transcriptionJson.toString(), MediaType.parse("application/json")))
                    .build();

            try (okhttp3.Response transcribeResponse = client.newCall(transcribeRequest).execute()) {
                if (!transcribeResponse.isSuccessful()) {
                    return "Failed to start transcription: " + transcribeResponse.code();
                }

                JSONObject transcribeResult = new JSONObject(transcribeResponse.body().string());
                String transcriptId = transcribeResult.getString("id");

                String status = "processing";

                while ("processing".equals(status) || "queued".equals(status)) {
                    Thread.sleep(1000);

                    Request checkRequest = new Request.Builder()
                            .url("https://api.assemblyai.com/v2/transcript/" + transcriptId)
                            .addHeader("Authorization", apiKey)
                            .build();

                    try (okhttp3.Response checkResponse = client.newCall(checkRequest).execute()) {
                        if (!checkResponse.isSuccessful()) {
                            return "Failed to check transcription status: " + checkResponse.code();
                        }

                        JSONObject checkResult = new JSONObject(checkResponse.body().string());
                        status = checkResult.getString("status");

                        if ("completed".equals(status)) {
                            return checkResult.getString("text");
                        } else if ("error".equals(status)) {
                            return "Transcription error: " + checkResult.optString("error", "Unknown error");
                        }
                    }
                }
                return "Transcription failed";
            }
        }
    }

    private String transcriptionGroqAI(File fileAudio) throws Exception {
        String apiKey = prefs.getString("groq_api_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            return "Groq API key not provided";
        }
        
         if (!fileAudio.exists()) {
             throw new IOException("File not found for upload: " + fileAudio.getAbsolutePath());
        }

        XposedBridge.log("AudioTranscript: trying to open " + fileAudio.getAbsolutePath());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        String targetLang = prefs.getString("transcription_translation", "").trim();

        // Groq API accepts direct file upload with multipart/form-data
        // Force filename to .ogg to ensure server-side compatibility
        okhttp3.MultipartBody.Builder builder = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", "audio.ogg",
                        RequestBody.create(fileAudio, MediaType.parse("audio/ogg")))
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("temperature", "0");

        if (!TextUtils.isEmpty(targetLang)) {
            // Whisper prompt can help with language and translation
            builder.addFormDataPart("prompt", "Transcribe this audio. If translation requested, translate to: " + targetLang);
        }

        RequestBody requestBody = builder.build();

        Request transcribeRequest = new Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (okhttp3.Response response = client.newCall(transcribeRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "No body";
            if (!response.isSuccessful()) {
                XposedBridge.log("Groq Error Body: " + responseBody);
                return "Groq Error: " + response.code() + " - " + response.message();
            }

            JSONObject result = new JSONObject(responseBody);
            String transcribedText = result.getString("text");

            // If a target language is set, use Groq's Chat API to translate the transcribed text
            if (!TextUtils.isEmpty(targetLang)) {
                return translateTextGroq(transcribedText, targetLang, apiKey, client);
            }

            return transcribedText;
        }
    }

    private String translateTextGroq(String text, String targetLang, String apiKey, OkHttpClient client) throws Exception {
        JSONObject root = new JSONObject();
        root.put("model", "llama-3.1-8b-instant");
        
        org.json.JSONArray messages = new org.json.JSONArray();
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

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return text + " (Translation failed: " + response.code() + ")";
            }
            JSONObject result = new JSONObject(response.body().string());
            return result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content").trim();
        }
    }

    private String transcriptionGeminiAI(File fileAudio) throws Exception {
        String apiKey = prefs.getString("gemini_api_key", "").trim();
        if (TextUtils.isEmpty(apiKey)) {
            return "Gemini API key not provided";
        }

        String targetLang = prefs.getString("transcription_translation", "");
        String prompt = "Transcribe this audio.";
        if (!TextUtils.isEmpty(targetLang)) {
            prompt = "Transcribe this audio and translate it to " + targetLang + ".";
        }
        prompt += " Provide only the transcription/translation text, nothing else. If there is no talking, return an empty string or 'No speech detected'.";

        if (!fileAudio.exists()) {
            throw new IOException("File not found: " + fileAudio.getAbsolutePath());
        }

        // 60-second timeout for large audio files and AI processing
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // Safe way to read file bytes
        byte[] bytes;
        try (FileInputStream fis = new FileInputStream(fileAudio)) {
            bytes = new byte[(int) fileAudio.length()];
            int offset = 0;
            int numRead = 0;
            while (offset < bytes.length && (numRead = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                offset += numRead;
            }
        }
        String base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP);

        String modelName = prefs.getString("gemini_model", "gemini-1.5-flash").trim();

        // Build Request Body
        JSONObject root = new JSONObject();
        org.json.JSONArray contentsArray = new org.json.JSONArray();
        JSONObject contentObj = new JSONObject();
        org.json.JSONArray partsArray = new org.json.JSONArray();

        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
        partsArray.put(textPart);

        JSONObject audioPart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        // WhatsApp opus files are usually packaged as ogg
        inlineData.put("mime_type", "audio/ogg");
        inlineData.put("data", base64Audio);
        audioPart.put("inline_data", inlineData);
        partsArray.put(audioPart);

        contentObj.put("parts", partsArray);
        contentsArray.put(contentObj);
        root.put("contents", contentsArray);

        RequestBody body = RequestBody.create(root.toString(), MediaType.parse("application/json"));

        // Use v1beta and dynamic model name to avoid 404 issues
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey)
                .post(body)
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "No body";
            if (!response.isSuccessful()) {
                XposedBridge.log("Gemini Error Body: " + responseBody);
                return "Gemini API error: " + response.code() + " - " + response.message();
            }

            JSONObject jsonResult = new JSONObject(responseBody);
            try {
                return jsonResult.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text").trim();
            } catch (Exception e) {
                XposedBridge.log("Gemini JSON Parsing error: " + e + "\nResponse: " + jsonResult.toString());
                return "Failed to parse Gemini response";
            }
        }
    }


    @NonNull
    @Override
    public String getPluginName() {
        return "Audio Transcript";
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
