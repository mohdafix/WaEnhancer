package com.wmods.wppenhacer.xposed.features.general;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.Utils;
import com.wmods.wppenhacer.xposed.features.listeners.ContactItemListener;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ProfilePictureChangeNotifier extends Feature {

    private static final String PREF_KEY = "profile_picture_change_toast";
    private static final String HASH_PREFS = "wppenhancer_profile_hashes";
    private android.content.SharedPreferences hashPrefs;

    public ProfilePictureChangeNotifier(@NonNull ClassLoader classLoader,
                                        @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        int notificationOption = getNotificationOption();
        if (notificationOption == 0) {
            return; // Disabled
        }

        // Hook into contact item binding to detect profile picture changes
        ContactItemListener.contactListeners.add(new ProfilePictureChangeListener());
    }

    private android.content.SharedPreferences getHashPrefs() {
        if (hashPrefs == null) {
            hashPrefs = Utils.getApplication().getSharedPreferences(HASH_PREFS, android.content.Context.MODE_PRIVATE);
        }
        return hashPrefs;
    }

    private int getNotificationOption() {
        try {
            return Integer.parseInt(this.prefs.getString(PREF_KEY, "0"));
        } catch (Throwable ignored) {
            // Backward compatibility: old builds used a boolean preference
            return this.prefs.getBoolean(PREF_KEY, false) ? 1 : 0;
        }
    }

    private void handleProfilePictureUpdate(WaContactWpp waContact) {
        try {
            var userJid = waContact.getUserJid();
            if (userJid.isNull()) return;

            String jid = userJid.getUserRawString();
            String contactName = WppCore.getContactName(userJid);

            checkAndNotifyProfilePictureChange(jid, contactName);

        } catch (Exception e) {
            log("Error handling profile picture update: " + e.getMessage());
        }
    }

    private void checkAndNotifyProfilePictureChange(String jid, String contactName) {
        try {
            String currentHash = getProfilePictureHash(jid);
            String previousHash = getHashPrefs().getString(jid, null);

            // If we have a previous hash and it differs from current, notify
            if (previousHash != null && !previousHash.equals(currentHash) && !currentHash.equals("no_profile")) {
                // Profile picture has changed
                String displayName = TextUtils.isEmpty(contactName) ?
                        WppCore.stripJID(jid) : contactName;

                int notificationOption = getNotificationOption();
                String title = "Profile Picture Updated";
                String message = displayName + " updated their profile picture";

                if (notificationOption == 1) {
                    // Show Toast (Main Thread)
                    Utils.showToast(message, Toast.LENGTH_LONG);
                } else if (notificationOption == 2) {
                    // Show Notification
                    showNotification(title, message, jid);
                }

                log("Profile picture changed for: " + displayName);
            }

            // Update the stored hash if changed or new
            if (!currentHash.equals(previousHash)) {
                getHashPrefs().edit().putString(jid, currentHash).apply();
            }

        } catch (Exception e) {
            log("Error checking profile picture change: " + e.getMessage());
        }
    }

    private void showNotification(String title, String message, String jid) {
        try {
            PendingIntent pendingIntent = null;
            try {
                // Create intent to open chat with the contact
                Intent intent = new Intent();
                intent.setClassName(Utils.getApplication().getPackageName(), "com.whatsapp.Conversation");

                String rawJid = jid.contains("@s.whatsapp.net") ? jid : jid + "@s.whatsapp.net";
                var jidObj = WppCore.createUserJid(rawJid);
                if (jidObj != null) {
                    // WhatsApp usually expects a Parcelable Jid in "jid".
                    // If runtime type is not Parcelable, fallback to string.
                    try {
                        intent.putExtra("jid", (Parcelable) jidObj);
                    } catch (Throwable ignored) {
                        intent.putExtra("jid", rawJid);
                    }
                } else {
                    intent.putExtra("jid", rawJid);
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                pendingIntent = PendingIntent.getActivity(Utils.getApplication(), rawJid.hashCode(), intent, flags);
            } catch (Throwable ignored) {
            }

            Utils.showNotification(title, message, pendingIntent);
        } catch (Exception e) {
            log("Error showing notification: " + e.getMessage());
        }
    }

    private String getProfilePictureHash(String jid) {
        try {
            File profileFile = WppCore.getContactPhotoFile(jid);
            if (profileFile != null && profileFile.exists()) {
                return profileFile.getAbsolutePath() + "_" + profileFile.lastModified();
            }

            // Try alternative paths (duplicated from WppCore for safety or legacy)
            String strippedJid = WppCore.stripJID(jid);
            File[] possibleFiles = {
                    new File("/data/data/com.whatsapp/cache/Profile Pictures/" + strippedJid + ".jpg"),
                    new File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures/" + strippedJid + ".jpg"),
                    new File("/data/data/com.whatsapp/files/Avatars/" + jid + ".j")
            };

            for (File file : possibleFiles) {
                if (file.exists()) {
                    return file.getAbsolutePath() + "_" + file.lastModified();
                }
            }

        } catch (Exception e) {
            log("Error getting profile picture hash: " + e.getMessage());
        }

        return "no_profile";
    }

    private class ProfilePictureChangeListener extends ContactItemListener.OnContactItemListener {
        @Override
        public void onBind(WaContactWpp waContact, android.view.View view) {
            try {
                // Pass waContact to handleProfilePictureUpdate to keep logic centralized
                // We use execute to run on background thread
                Utils.getExecutor().execute(() ->
                        handleProfilePictureUpdate(waContact));

            } catch (Exception e) {
                log("Error in profile picture change listener: " + e.getMessage());
            }
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Profile Picture Change Notifier";
    }
}