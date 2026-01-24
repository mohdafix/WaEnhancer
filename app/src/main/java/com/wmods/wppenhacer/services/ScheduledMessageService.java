package com.wmods.wppenhacer.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.MainActivity;
import com.wmods.wppenhacer.utils.ScheduledMessageHelper;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.wmods.wppenhacer.App;

public class ScheduledMessageService extends Service {
    public static final String ACTION_CHECK_MESSAGES = "com.wmods.wppenhacer.CHECK_SCHEDULED_MESSAGES";
    public static final String ACTION_MESSAGE_SENT = "com.wmods.wppenhacer.MESSAGE_SENT";
    public static final String ACTION_SCHEDULED_STATUS = "com.wmods.wppenhacer.SCHEDULED_MESSAGES_STATUS";
    public static final String ACTION_SEND_MESSAGE = "com.wmods.wppenhacer.SEND_SCHEDULED_MESSAGE";
    private static final String CHANNEL_ID = "scheduled_messages_channel";
    private static final int CHECK_INTERVAL = 30000;
    public static final String EXTRA_HAS_PENDING = "has_pending";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_NEEDS_BUSINESS = "needs_business";
    public static final String EXTRA_NEEDS_WHATSAPP = "needs_whatsapp";
    public static final String EXTRA_PENDING_COUNT = "pending_count";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "ScheduledMessageService";
    private boolean isRunning = false;
    private ScheduledMessageStore messageStore;
    private PowerManager.WakeLock wakeLock;

    public static void startService(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") != 0) {
            Log.w(TAG, "Notification permission not granted, service may not show notifications");
        }
        Intent intent = new Intent(context, (Class<?>) ScheduledMessageService.class);
        intent.setAction(ACTION_CHECK_MESSAGES);
        try {
            context.startForegroundService(intent);
            Log.d(TAG, "Service start requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
        }
    }

    public static void stopService(Context context) {
        Intent intent = new Intent(context, (Class<?>) ScheduledMessageService.class);
        context.stopService(intent);
        Log.d(TAG, "Service stop requested");
    }

    public static boolean shouldServiceRun(Context context) {
        ScheduledMessageStore store = ScheduledMessageStore.getInstance(context);
        List<ScheduledMessage> activeMessages = store.getActiveMessages();
        return !activeMessages.isEmpty();
    }

    public static boolean areNotificationsEnabled(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService("notification");
        if (manager == null) {
            return false;
        }
        return manager.areNotificationsEnabled();
    }

    public static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == 0;
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        this.messageStore = ScheduledMessageStore.getInstance(this);
        createNotificationChannel();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(0), 1);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, createNotification(0), 1);
            } else {
                startForeground(NOTIFICATION_ID, createNotification(0));
            }
            this.isRunning = true;
            Log.d(TAG, "Service created and foreground started");
            sendScheduledMessagesStatus();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
        }
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquireWakeLock();
        try {
            Log.d(TAG, "onStartCommand called with action: " + (intent != null ? intent.getAction() : "null"));
            if (intent != null) {
                String action = intent.getAction();
                if (ACTION_SEND_MESSAGE.equals(action)) {
                    long messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L);
                    if (messageId != -1) {
                        try {
                            sendScheduledMessage(messageId);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG, "Error sending scheduled message", e);
                        }
                    }
                } else if (ACTION_CHECK_MESSAGES.equals(action)) {
                    try {
                        checkAndScheduleMessages();
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Error checking/scheduling messages", e);
                    }
                }
            } else {
                try {
                    checkAndScheduleMessages();
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Error checking/scheduling messages", e);
                }
            }
            long nextMsgTime = getNextMessageTime();
            scheduleNextCheck(nextMsgTime);
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand", e);
        } finally {
            // Keep the wake lock for a bit longer to ensure broadcasts are delivered
            // We'll release it after a short delay or let it timeout
            if (wakeLock != null && wakeLock.isHeld()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (wakeLock.isHeld()) {
                            wakeLock.release();
                            Log.d(TAG, "WakeLock released after delay");
                        }
                    } catch (Exception ignored) {}
                }, 10000); // 10 seconds delay
            }
        }
        
        if (!shouldServiceRun(this)) {
            Log.d(TAG, "No more active messages, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WaEnhancer:ScheduledMessageWakeLock");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L); // 10 minutes timeout for safety
            Log.d(TAG, "WakeLock acquired");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            } catch (Exception ignored) {}
        }
    }

    private void sendScheduledMessage(long messageId) throws PackageManager.NameNotFoundException {
        Log.d(TAG, "Attempting to send message ID: " + messageId);
        boolean sent = ScheduledMessageHelper.sendScheduledMessage(this, messageId);
        Log.d(TAG, "Message " + messageId + " send result: " + sent);
        if (sent) {
            notifyMessageSent(messageId);
        }
        updateNotificationWithActiveCount();
    }

    private void notifyMessageSent(long messageId) {
        Intent intent = new Intent("com.wmods.wppenhacer.MESSAGE_SENT");
        intent.putExtra("message_id", messageId);
        intent.putExtra("success", true); // Default to true if intent was sent to WhatsApp
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Log.d(TAG, "Broadcast sent for message ID: " + messageId);
    }

    private void checkAndScheduleMessages() throws PackageManager.NameNotFoundException {
        List<ScheduledMessage> activeMessages = this.messageStore.getActiveMessages();
        Log.d(TAG, "Checking messages. Active count: " + activeMessages.size());
        if (activeMessages.isEmpty()) {
            Log.d(TAG, "No active messages, updating notification");
            updateNotification(0, null);
            sendScheduledMessagesStatus();
            return;
        }
        long now = System.currentTimeMillis();
        ScheduledMessage nextMessage = null;
        long nextTime = Long.MAX_VALUE;
        for (ScheduledMessage msg : activeMessages) {
            long msgNextTime = msg.getNextScheduledTime();
            Log.d(TAG, "Message " + msg.getId() + " (" + msg.getContactName() + "): nextTime=" + formatTime(msgNextTime) + ", now=" + formatTime(now) + ", diff=" + ((msgNextTime - now) / 1000) + "s, shouldSend=" + msg.shouldSendNow());
            if (msgNextTime < nextTime && !msg.shouldSendNow()) {
                nextTime = msgNextTime;
                nextMessage = msg;
            }
        }
        List<ScheduledMessage> pendingMessages = this.messageStore.getPendingMessages();
        if (pendingMessages.isEmpty()) {
            Log.d(TAG, "No pending messages ready to send now");
            updateNotification(activeMessages.size(), nextMessage);
            return;
        }
        Log.d(TAG, "Found " + pendingMessages.size() + " pending messages ready to send");
        List<Long> messageIds = new ArrayList<>();
        for (ScheduledMessage message : pendingMessages) {
            messageIds.add(Long.valueOf(message.getId()));
        }
        for (Long id : messageIds) {
            sendScheduledMessage(id.longValue());
        }
        updateNotificationWithActiveCount();
        sendScheduledMessagesStatus();
    }

    private long getNextMessageTime() {
        List<ScheduledMessage> activeMessages = this.messageStore.getActiveMessages();
        long nextTime = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (ScheduledMessage msg : activeMessages) {
            long msgNextTime = msg.getNextScheduledTime();
            if (msgNextTime > now && msgNextTime < nextTime) {
                nextTime = msgNextTime;
            }
        }
        return nextTime;
    }

    private void updateNotificationWithActiveCount() {
        List<ScheduledMessage> activeMessages = this.messageStore.getActiveMessages();
        ScheduledMessage nextMessage = null;
        long nextTime = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (ScheduledMessage msg : activeMessages) {
            long msgNextTime = msg.getNextScheduledTime();
            if (msgNextTime > now && msgNextTime < nextTime) {
                nextTime = msgNextTime;
                nextMessage = msg;
            }
        }
        updateNotification(activeMessages.size(), nextMessage);
    }

    private String formatTime(long timeMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault());
        return sdf.format(new Date(timeMillis));
    }

    private void scheduleNextCheck(long nextMessageTime) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(this, ScheduledMessageService.class);
        intent.setAction(ACTION_CHECK_MESSAGES);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long now = System.currentTimeMillis();
        long nextCheckTime = now + 30000; // Default 30s check
        
        // If a message is scheduled sooner than 30s, schedule for that time exactly
        if (nextMessageTime > now && nextMessageTime < nextCheckTime + 5000) {
            nextCheckTime = nextMessageTime;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Using setAlarmClock is most reliable for high-priority tasks
                AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(nextCheckTime, pendingIntent);
                alarmManager.setAlarmClock(info, pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextCheckTime, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextCheckTime, pendingIntent);
            }
            Log.d(TAG, "Next check scheduled at " + formatTime(nextCheckTime) + " using " + 
                (Build.VERSION.SDK_INT >= 21 ? "AlarmClock" : "ExactAlarm"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule alarm", e);
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextCheckTime, pendingIntent);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.scheduled_messages), 2);
        channel.setDescription(getString(R.string.scheduled_messages_desc));
        channel.setShowBadge(true);
        NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            Log.d(TAG, "Notification channel created");
        }
    }

    private Notification createNotification(int activeCount) {
        String text;
        Intent mainIntent = new Intent(this, (Class<?>) MainActivity.class);
        mainIntent.setFlags(335544320);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, 67108864);
        String title = getString(R.string.scheduled_messages);
        if (activeCount > 0) {
            text = getString(R.string.scheduled_messages_active, Integer.valueOf(activeCount));
        } else {
            text = getString(R.string.scheduled_messages_monitoring);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_dashboard_black_24dp).setPriority(-1).setOngoing(true).setContentIntent(pendingIntent).setCategory("service").setVisibility(1).build();
    }

    private void updateNotification(int activeCount, ScheduledMessage nextMessage) {
        StringBuilder contentText = new StringBuilder();
        StringBuilder bigText = new StringBuilder();

        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);
        
        String title = getString(R.string.scheduled_messages);
        
        if (activeCount == 0) {
            contentText.append(getString(R.string.scheduled_messages_monitoring));
            bigText.append(getString(R.string.scheduled_messages_monitoring));
        } else {
            // Summary Line
            String summary = getString(R.string.scheduled_messages_active, activeCount);
            contentText.append(summary);
            bigText.append(summary).append("\n\n");
            
            // Next Message Info
            if (nextMessage != null) {
                String nextTimeStr = formatTime(nextMessage.getNextScheduledTime());
                String nextInfo = "Next: " + nextMessage.getContactsDisplayString() + " at " + nextTimeStr;
                contentText.setLength(0);
                contentText.append(nextInfo);
                bigText.append("Upcoming:\n• ").append(nextInfo).append("\n\n");
            }

            // Stats Breakdown
            List<ScheduledMessage> allMessages = messageStore.getAllMessages();
            int pendingCount = 0;
            for (ScheduledMessage msg : allMessages) {
                // Count as pending if it's user-active and either not sent or will repeat
                if (msg.isActive() && !(msg.isSent() && msg.getRepeatType() == 0)) {
                    pendingCount++;
                }
            }
            
            if (pendingCount > 0) {
                bigText.append("Status Summary:\n");
                bigText.append("• Pending Schedules: ").append(pendingCount);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText.toString())
                .setSmallIcon(R.drawable.ic_dashboard_black_24dp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()))
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void sendScheduledMessagesStatus() {
        List<ScheduledMessage> activeMessages = this.messageStore.getActiveMessages();
        boolean hasPending = !activeMessages.isEmpty();
        int pendingCount = activeMessages.size();
        boolean needsWhatsapp = false;
        boolean needsBusiness = false;
        for (ScheduledMessage msg : activeMessages) {
            if (msg.getWhatsappType() == 1) {
                needsBusiness = true;
            } else {
                needsWhatsapp = true;
            }
            if (needsWhatsapp && needsBusiness) {
                break;
            }
        }
        sendScheduledMessagesStatus(hasPending, pendingCount, needsWhatsapp, needsBusiness);
    }

    private void sendScheduledMessagesStatus(boolean hasPending, int pendingCount, boolean needsWhatsapp, boolean needsBusiness) {
        Intent statusIntent = new Intent("com.wmods.wppenhacer.SCHEDULED_MESSAGES_STATUS");
        statusIntent.putExtra("has_pending", hasPending);
        statusIntent.putExtra("pending_count", pendingCount);
        statusIntent.putExtra("needs_whatsapp", needsWhatsapp);
        statusIntent.putExtra("needs_business", needsBusiness);
        sendBroadcast(statusIntent);
        Log.d(TAG, "Sent scheduled messages status: hasPending=" + hasPending + ", count=" + pendingCount + ", needsWhatsapp=" + needsWhatsapp + ", needsBusiness=" + needsBusiness);
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override // android.app.Service
    public void onDestroy() {
        super.onDestroy();
        this.isRunning = false;
        Log.d(TAG, "Service destroyed");
        sendScheduledMessagesStatus(false, 0, false, false);
        if (shouldServiceRun(this)) {
            Log.d(TAG, "Active messages remain, requesting restart");
            startService(this);
        }
    }

    @Override // android.app.Service
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed, checking if should restart");
        if (shouldServiceRun(this)) {
            Log.d(TAG, "Active messages remain after task removed, restarting");
            startService(this);
        }
    }
}