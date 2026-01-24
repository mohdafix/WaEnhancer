package com.wmods.wppenhacer.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;

public class ScheduledMessageReceiver extends BroadcastReceiver {
    public static final String ACTION_MESSAGE_SENT = "com.wmods.wppenhacer.MESSAGE_SENT";
    public static final String ACTION_SEND_MESSAGE = "com.wmods.wppenhacer.SEND_MESSAGE";
    private static final String TAG = "ScheduledMessageReceiver";

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);
        if ("android.intent.action.BOOT_COMPLETED".equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (ScheduledMessageService.shouldServiceRun(context)) {
                ScheduledMessageService.startService(context);
                Log.d(TAG, "Started service from boot");
                return;
            }
            return;
        }
        if ("com.wmods.wppenhacer.MESSAGE_SENT".equals(action)) {
            handleMessageSentCallback(context, intent);
        }
    }

    private void handleMessageSentCallback(Context context, Intent intent) {
        long messageId = intent.getLongExtra(ScheduledMessageService.EXTRA_MESSAGE_ID, -1L);
        boolean success = intent.getBooleanExtra("success", false);
        Log.d(TAG, "Message sent callback - ID: " + messageId + ", Success: " + success);
        if (success && messageId != -1) {
            ScheduledMessageStore store = ScheduledMessageStore.getInstance(context);
            store.markAsSent(messageId);
            Log.d(TAG, "Marked message " + messageId + " as sent");
        }
    }
}