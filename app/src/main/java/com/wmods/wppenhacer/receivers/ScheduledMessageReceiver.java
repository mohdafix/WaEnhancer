package com.wmods.wppenhacer.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
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
        } else if ("com.wmods.wppenhacer.SCHEDULE_MESSAGE".equals(action)) {
            handleScheduleMessage(context, intent);
        }
    }

    public static final String ACTION_SCHEDULE_MESSAGE = "com.wmods.wppenhacer.SCHEDULE_MESSAGE";

    private void handleScheduleMessage(Context context, Intent intent) {
        try {
            String jid = intent.getStringExtra("jid");
            String message = intent.getStringExtra("message");
            long scheduledTime = intent.getLongExtra("scheduled_time", -1);
            int whatsappType = intent.getIntExtra("whatsapp_type", 0);
            String name = intent.getStringExtra("name");

            if (jid == null || message == null || scheduledTime == -1) {
                Log.e(TAG, "Incomplete message data in SCHEDULE_MESSAGE");
                return;
            }

            ScheduledMessageStore store = ScheduledMessageStore.getInstance(context);
            
            java.util.List<String> jids = new java.util.ArrayList<>();
            jids.add(jid);
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add(name != null ? name : jid);

            ScheduledMessage msg = new ScheduledMessage();
            msg.setContactJids(jids);
            msg.setContactNames(names);
            msg.setMessage(message);
            msg.setScheduledTime(scheduledTime);
            msg.setWhatsappType(whatsappType);
            msg.setActive(true);
            msg.setCreatedTime(System.currentTimeMillis());

            long id = store.insertMessage(msg);
            Log.d(TAG, "Saved scheduled message from broadcast, ID: " + id);

            // Start service to process it
            ScheduledMessageService.startService(context);
        } catch (Exception e) {
            Log.e(TAG, "Error handling SCHEDULE_MESSAGE", e);
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