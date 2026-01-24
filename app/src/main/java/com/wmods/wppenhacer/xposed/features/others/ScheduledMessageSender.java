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
                    
                    if (jids != null && !jids.isEmpty() && message != null) {
                        XposedBridge.log("ScheduledMessageSender: Sending message to " + jids.size() + " contacts");
                        WppCore.sendMessage(jids, message, messageId);
                    } else {
                        XposedBridge.log("ScheduledMessageSender: Missing data in broadcast");
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
