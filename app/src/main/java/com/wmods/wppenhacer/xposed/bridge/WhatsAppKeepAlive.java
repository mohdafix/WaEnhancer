package com.wmods.wppenhacer.xposed.bridge;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.features.general.ScheduledMessageServiceHook;
import de.robv.android.xposed.XposedBridge;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class WhatsAppKeepAlive {
    public static final String ACTION_SCHEDULED_STATUS = "com.wmods.wppenhacer.SCHEDULED_MESSAGES_STATUS";
    private static final long CHECK_INTERVAL_MS = 30000;
    public static final String EXTRA_FROM_KEEPALIVE = "from_wae_keepalive";
    public static final String EXTRA_HAS_PENDING = "has_pending";
    public static final String EXTRA_NEEDS_BUSINESS = "needs_business";
    public static final String EXTRA_NEEDS_WHATSAPP = "needs_whatsapp";
    public static final String EXTRA_PENDING_COUNT = "pending_count";
    private static final String TAG = "WhatsAppKeepAlive";
    private static WhatsAppKeepAlive instance;
    private Context context;
    private Handler handler;
    private BroadcastReceiver statusReceiver;
    private final AtomicBoolean hasPendingMessages = new AtomicBoolean(false);
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final AtomicBoolean needsWhatsapp = new AtomicBoolean(false);
    private final AtomicBoolean needsBusiness = new AtomicBoolean(false);
    private final Runnable monitorRunnable = new Runnable() { // from class: com.wmods.wppenhacer.xposed.bridge.WhatsAppKeepAlive.1
        @Override // java.lang.Runnable
        public void run() {
            if (!WhatsAppKeepAlive.this.hasPendingMessages.get()) {
                WhatsAppKeepAlive.this.stopMonitoring();
                return;
            }
            WhatsAppKeepAlive.this.checkAndRestartWhatsApp();
            if (WhatsAppKeepAlive.this.isMonitoring.get()) {
                WhatsAppKeepAlive.this.handler.postDelayed(this, WhatsAppKeepAlive.CHECK_INTERVAL_MS);
            }
        }
    };

    public static synchronized WhatsAppKeepAlive getInstance() {
        try {
            if (instance == null) {
                instance = new WhatsAppKeepAlive();
            }
        } catch (Throwable th) {
            throw th;
        }
        return instance;
    }

    private WhatsAppKeepAlive() {
    }

    public void init(Context systemContext) {
        this.context = systemContext;
        this.handler = new Handler(Looper.getMainLooper());
        registerStatusReceiver();
        XposedBridge.log("WhatsAppKeepAlive: Initialized in system_server");
    }

    private void registerStatusReceiver() {
        this.statusReceiver = new BroadcastReceiver() { // from class: com.wmods.wppenhacer.xposed.bridge.WhatsAppKeepAlive.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context ctx, Intent intent) {
                if ("com.wmods.wppenhacer.SCHEDULED_MESSAGES_STATUS".equals(intent.getAction())) {
                    boolean hasPending = intent.getBooleanExtra("has_pending", false);
                    int pendingCount = intent.getIntExtra("pending_count", 0);
                    boolean wantsWhatsapp = intent.getBooleanExtra("needs_whatsapp", false);
                    boolean wantsBusiness = intent.getBooleanExtra("needs_business", false);
                    XposedBridge.log("WhatsAppKeepAlive: Received status update - hasPending: " + hasPending + ", count: " + pendingCount + ", needsWhatsapp: " + wantsWhatsapp + ", needsBusiness: " + wantsBusiness);
                    WhatsAppKeepAlive.this.hasPendingMessages.set(hasPending);
                    WhatsAppKeepAlive.this.needsWhatsapp.set(wantsWhatsapp);
                    WhatsAppKeepAlive.this.needsBusiness.set(wantsBusiness);
                    if (hasPending && !WhatsAppKeepAlive.this.isMonitoring.get()) {
                        WhatsAppKeepAlive.this.startMonitoring();
                    } else if (!hasPending && WhatsAppKeepAlive.this.isMonitoring.get()) {
                        WhatsAppKeepAlive.this.stopMonitoring();
                        WhatsAppKeepAlive.this.stopScheduledServices();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.wmods.wppenhacer.SCHEDULED_MESSAGES_STATUS");
        ContextCompat.registerReceiver(this.context, this.statusReceiver, filter, 2);
        XposedBridge.log("WhatsAppKeepAlive: Status receiver registered");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startMonitoring() {
        if (this.isMonitoring.compareAndSet(false, true)) {
            XposedBridge.log("WhatsAppKeepAlive: Starting WhatsApp monitoring");
            this.handler.post(this.monitorRunnable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopMonitoring() {
        if (this.isMonitoring.compareAndSet(true, false)) {
            XposedBridge.log("WhatsAppKeepAlive: Stopping WhatsApp monitoring");
            this.handler.removeCallbacks(this.monitorRunnable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopScheduledServices() {
        String[] packages = {FeatureLoader.PACKAGE_WPP, FeatureLoader.PACKAGE_BUSINESS};
        for (String pkg : packages) {
            try {
                Intent stopIntent = new Intent(ScheduledMessageServiceHook.ACTION_STOP_SCHEDULED_SERVICE);
                stopIntent.setPackage(pkg);
                this.context.sendBroadcast(stopIntent);
                XposedBridge.log("WhatsAppKeepAlive: Sent stop service broadcast to " + pkg);
            } catch (Exception e) {
                XposedBridge.log("WhatsAppKeepAlive: Failed to send stop broadcast to " + pkg + ": " + e.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkAndRestartWhatsApp() {
        boolean whatsappRunning = isWhatsAppRunning(FeatureLoader.PACKAGE_WPP);
        boolean businessRunning = isWhatsAppRunning(FeatureLoader.PACKAGE_BUSINESS);
        XposedBridge.log("WhatsAppKeepAlive: WhatsApp running: " + whatsappRunning + ", Business running: " + businessRunning + ", needsWhatsapp: " + this.needsWhatsapp.get() + ", needsBusiness: " + this.needsBusiness.get());
        if (this.needsWhatsapp.get() && !whatsappRunning) {
            XposedBridge.log("WhatsAppKeepAlive: WhatsApp normal needed but not running, attempting to start...");
            startWhatsAppPackage(FeatureLoader.PACKAGE_WPP);
        }
        if (this.needsBusiness.get() && !businessRunning) {
            XposedBridge.log("WhatsAppKeepAlive: WhatsApp Business needed but not running, attempting to start...");
            startWhatsAppPackage(FeatureLoader.PACKAGE_BUSINESS);
        }
    }

    private boolean isWhatsAppRunning(String packageName) {
        ActivityManager am = null;
        List<ActivityManager.RunningAppProcessInfo> runningProcesses;
        try {
            am = (ActivityManager) this.context.getSystemService("activity");
        } catch (Exception e) {
            XposedBridge.log("WhatsAppKeepAlive: Error checking if WhatsApp is running: " + e.getMessage());
        }
        if (am == null || (runningProcesses = am.getRunningAppProcesses()) == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo process : runningProcesses) {
            if (process.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void startWhatsAppPackage(String packageName) {
        try {
            String serviceName = findInstrumentationService(packageName);
            if (serviceName != null) {
                Intent serviceIntent = new Intent();
                serviceIntent.setComponent(new ComponentName(packageName, serviceName));
                serviceIntent.putExtra(EXTRA_FROM_KEEPALIVE, true);
                this.context.startForegroundService(serviceIntent);
                XposedBridge.log("WhatsAppKeepAlive: Started " + serviceName + " for " + packageName);
            } else {
                XposedBridge.log("WhatsAppKeepAlive: InstrumentationService not found for " + packageName);
            }
        } catch (Exception e) {
            XposedBridge.log("WhatsAppKeepAlive: Failed to start service for " + packageName + ": " + e.getMessage());
        }
    }

    private String findInstrumentationService(String packageName) throws PackageManager.NameNotFoundException {
        try {
            PackageInfo packageInfo = this.context.getPackageManager().getPackageInfo(packageName, 4);
            if (packageInfo.services != null) {
                for (ServiceInfo serviceInfo : packageInfo.services) {
                    if (serviceInfo.name.endsWith("InstrumentationService")) {
                        return serviceInfo.name;
                    }
                }
                return null;
            }
            return null;
        } catch (Exception e) {
            XposedBridge.log("WhatsAppKeepAlive: Error finding InstrumentationService: " + e.getMessage());
            return null;
        }
    }

    public void updateStatus(boolean hasPending, int pendingCount, boolean wantsWhatsapp, boolean wantsBusiness) {
        XposedBridge.log("WhatsAppKeepAlive: Direct status update - hasPending: " + hasPending + ", count: " + pendingCount + ", needsWhatsapp: " + wantsWhatsapp + ", needsBusiness: " + wantsBusiness);
        this.hasPendingMessages.set(hasPending);
        this.needsWhatsapp.set(wantsWhatsapp);
        this.needsBusiness.set(wantsBusiness);
        if (hasPending && !this.isMonitoring.get()) {
            startMonitoring();
        } else if (!hasPending && this.isMonitoring.get()) {
            stopMonitoring();
        }
    }
}