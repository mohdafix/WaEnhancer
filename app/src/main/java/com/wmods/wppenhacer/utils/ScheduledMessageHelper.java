package com.wmods.wppenhacer.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.receivers.ScheduledMessageReceiver;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;
import java.util.ArrayList;

public class ScheduledMessageHelper {
    private static final String PACKAGE_WHATSAPP = "com.whatsapp";
    private static final String PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    private static final String TAG = "ScheduledMessageHelper";

    public static boolean sendScheduledMessage(Context context, long messageId) throws PackageManager.NameNotFoundException {
        ScheduledMessageStore store = ScheduledMessageStore.getInstance(context);
        ScheduledMessage message = store.getMessage(messageId);
        if (message == null || !message.isActive()) {
            Log.d(TAG, "Message not found or inactive: " + messageId);
            return false;
        }
        if (!message.shouldSendNow()) {
            Log.d(TAG, "Message not ready to send: " + messageId);
            return false;
        }
        Intent intent = new Intent(ScheduledMessageReceiver.ACTION_SEND_MESSAGE);
        intent.putStringArrayListExtra("contact_jids", new ArrayList<>(message.getContactJids()));
        intent.putExtra("message", message.getMessage());
        intent.putExtra(ScheduledMessageService.EXTRA_MESSAGE_ID, messageId);
        intent.putExtra("whatsapp_type", message.getWhatsappType());
        if (message.hasImage()) {
            intent.putExtra("image_path", message.getImagePath());
        }
        String targetPackage = getTargetPackage(context, message);
        boolean sent = false;
        try {
            context.getPackageManager().getPackageInfo(targetPackage, 0);
            intent.setPackage(targetPackage);
            context.sendBroadcast(intent);
            sent = true;
            Log.d(TAG, "Sent message to " + targetPackage + " for ID: " + messageId + " with " + message.getContactCount() + " contacts");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send to " + targetPackage + ", trying fallback", e);
            String fallbackPackage = targetPackage.equals("com.whatsapp") ? "com.whatsapp.w4b" : "com.whatsapp";
            try {
                context.getPackageManager().getPackageInfo(fallbackPackage, 0);
                intent.setPackage(fallbackPackage);
                context.sendBroadcast(intent);
                sent = true;
                Log.d(TAG, "Sent message to fallback " + fallbackPackage + " for ID: " + messageId);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to send to fallback " + fallbackPackage, e2);
            }
        }
        if (sent) {
            Log.d(TAG, "Intent sent to " + targetPackage + " for message ID: " + messageId);
        } else {
            Log.e(TAG, "Failed to send message - WhatsApp not found");
        }
        return sent;
    }

    private static String getTargetPackage(Context context, ScheduledMessage message) {
        return (BuildConfig.APPLICATION_ID.endsWith(".w4b") || message.getWhatsappType() == 1) ? "com.whatsapp.w4b" : "com.whatsapp";
    }
}