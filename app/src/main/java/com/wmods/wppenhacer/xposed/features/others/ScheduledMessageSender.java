package com.wmods.wppenhacer.xposed.features.others;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Listens for SEND_MESSAGE broadcast from the app and triggers the actual sending in WhatsApp.
 */
public class ScheduledMessageSender extends Feature {
    public static final String ACTION_SEND_MESSAGE = "com.wmods.wppenhacer.SEND_MESSAGE";

    public ScheduledMessageSender(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        registerReceiver();
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_SEND_MESSAGE);
        ContextCompat.registerReceiver(Utils.getApplication(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_SEND_MESSAGE.equals(intent.getAction())) {
                    XposedBridge.log("ScheduledMessageSender: Received SEND_MESSAGE broadcast");
                    
                    List<String> jids = intent.getStringArrayListExtra("contact_jids");
                    String message = intent.getStringExtra("message");
                    long messageId = intent.getLongExtra("message_id", -1);
                    String imagePath = intent.getStringExtra("image_path");
                    
                    if (jids == null || jids.isEmpty()) {
                        XposedBridge.log("ScheduledMessageSender: No contacts specified");
                        return;
                    }
                    
                    // Check if this is a media message
                    if (imagePath != null && !imagePath.isEmpty()) {
                        XposedBridge.log("ScheduledMessageSender: Sending media message to " + jids.size() + " contacts");
                        java.io.File mediaFile = new java.io.File(imagePath);
                        
                        if (!mediaFile.exists()) {
                            XposedBridge.log("ScheduledMessageSender: Media file not found: " + imagePath);
                            Utils.showToast("Media file not found", android.widget.Toast.LENGTH_SHORT);
                            return;
                        }
                        
                        String caption = message != null ? message : "";
                        WppCore.sendImageMessage(jids, caption, mediaFile, messageId);
                    } else if (message != null) {
                        // Text message
                        XposedBridge.log("ScheduledMessageSender: Sending text message to " + jids.size() + " contacts");
                        WppCore.sendMessage(jids, message, messageId);
                    } else {
                        XposedBridge.log("ScheduledMessageSender: Missing both message and media");
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED);
        XposedBridge.log("ScheduledMessageSender: Receiver registered");
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Scheduled Message Sender";
    }
}
