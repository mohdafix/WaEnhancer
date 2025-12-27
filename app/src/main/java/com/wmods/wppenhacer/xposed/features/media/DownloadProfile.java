package com.wmods.wppenhacer.xposed.features.media;

import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;

public class DownloadProfile extends Feature {

    public DownloadProfile(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        // We hook the ViewProfilePhoto class. This is the only reliable class we know.
        var loadProfileInfoField = Unobfuscator.loadProfileInfoField(classLoader);
        var profileClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "ViewProfilePhoto");

        if (profileClass != null) {
            XposedHelpers.findAndHookMethod(profileClass, "onCreateOptionsMenu", Menu.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        var menu = (Menu) param.args[0];
                        var item = menu.add(0, 0, 0, "Download");
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

                        // --- ICON IMPLEMENTATION ---
                        try {
                            var appContext = Utils.getApplication();
                            if (appContext != null) {
                                // IMPORTANT: Replace with your actual module's package name!
                                var moduleContext = appContext.createPackageContext("com.wmods.wppenhacer", 0);
                                int drawableId = moduleContext.getResources().getIdentifier("ic_download", "drawable", moduleContext.getPackageName());
                                if (drawableId != 0) {
                                    var iconDrawable = moduleContext.getDrawable(drawableId);
                                    item.setIcon(iconDrawable);
                                }
                            }
                        } catch (Exception e) {
                            log("Failed to set icon: " + e.getMessage());
                        }
                        // --- END ICON ---

                        item.setOnMenuItemClickListener(menuItem -> {
                            new Thread(() -> performDownload(param)).start();
                            return true;
                        });
                    } catch (Exception e) {
                        log("Failed to add download menu item: " + e.getMessage());
                    }
                }
            });
            log("Successfully hooked ViewProfilePhoto for download button.");
        } else {
            log("Could not find ViewProfilePhoto class. The plugin will not work.");
        }
    }

    private void performDownload(XC_MethodHook.MethodHookParam param) {
        try {
            // --- Get the JID object ---
            var subCls = param.thisObject.getClass().getSuperclass();
            if (subCls == null) subCls = param.thisObject.getClass();

            var loadProfileInfoField = Unobfuscator.loadProfileInfoField(classLoader);
            var field = ReflectionUtils.getFieldByType(subCls, loadProfileInfoField.getDeclaringClass());
            if (field == null) {
                field = ReflectionUtils.getFieldByType(param.thisObject.getClass(), loadProfileInfoField.getDeclaringClass());
            }
            if (field == null) {
                Utils.showToast("Error: Could not find profile data.", Toast.LENGTH_SHORT);
                return;
            }

            var fieldObj = ReflectionUtils.getObjectField(field, param.thisObject);
            if (fieldObj == null) {
                Utils.showToast("Error: Profile data is empty.", Toast.LENGTH_SHORT);
                return;
            }

            var waContact = new WaContactWpp(fieldObj);
            var userJidWrapper = waContact.getUserJid();
            if (userJidWrapper == null) {
                Utils.showToast("Error: Could not get user JID.", Toast.LENGTH_SHORT);
                return;
            }

            // --- Get raw JID strings from the wrapper, bypassing the buggy getUserRawString() ---
            Object userJidObject = userJidWrapper.userJid;
            Object phoneJidObject = userJidWrapper.phoneJid;

            String userJidString = (String) XposedHelpers.callMethod(userJidObject, "getRawString");
            String phoneJidString = (phoneJidObject != null) ? (String) XposedHelpers.callMethod(phoneJidObject, "getRawString") : null;

            // --- DECIDE WHETHER IT'S A CONTACT OR GROUP BASED ON THE JID STRING ---
            boolean isGroup = userJidString != null && userJidString.contains("@g.us");

            if (isGroup) {
                performGroupDownload(userJidString);
            } else {
                performContactDownload(userJidString, phoneJidString);
            }

        } catch (Exception e) {
            log("Download failed: " + e.getMessage());
            e.printStackTrace();
            Utils.showToast("An unexpected error occurred.", Toast.LENGTH_SHORT);
        }
    }

    private void performContactDownload(String userJidString, String phoneJidString) {
        // Search for the file using both LID and Phone JID patterns
        var sourceFile = findProfilePhotoFile(userJidString); // Try with LID first
        if (sourceFile == null && phoneJidString != null) {
            sourceFile = findProfilePhotoFile(phoneJidString); // Try with Phone JID
        }

        if (sourceFile == null) {
            Utils.showToast("Profile picture not found.", Toast.LENGTH_SHORT);
            return;
        }

        // Generate filename based on the JID that was found
        String name = userJidString.replace("@lid", "").replace("@s.whatsapp.net", "") + ".jpg";

        saveAndNotify(sourceFile, "Profile Pictures", name);
    }

    private void performGroupDownload(String groupJidString) {
        // For groups, the file is named based on the group JID
        var sourceFile = findProfilePhotoFile(groupJidString);

        if (sourceFile == null) {
            Utils.showToast("Group picture not found.", Toast.LENGTH_SHORT);
            return;
        }

        String name = groupJidString.replace("@g.us", "") + ".jpg";
        saveAndNotify(sourceFile, "Group Pictures", name);
    }

    private void saveAndNotify(File sourceFile, String folderName, String fileName) {
        String destPath;
        try {
            destPath = Utils.getDestination(folderName);
        } catch (Exception e) {
            log("Failed to get destination path: " + e.getMessage());
            Utils.showToast("Error: Could not create destination folder.", Toast.LENGTH_SHORT);
            return;
        }

        var error = Utils.copyFile(sourceFile, destPath, fileName);
        if (TextUtils.isEmpty(error)) {
            Utils.showToast("Saved to: " + destPath, Toast.LENGTH_LONG);
        } else {
            Utils.showToast("Error when saving: " + error, Toast.LENGTH_LONG);
        }
    }

    /**
     * Searches for the profile photo file in common WhatsApp storage locations,
     * including the new internal app data cache directory.
     */
    private File findProfilePhotoFile(String jidString) {
        if (jidString == null) return null;

        // Create variations of the filename
        String baseName = jidString.replace("@lid", "").replace("@s.whatsapp.net", "").replace("@g.us", "");
        String[] fileNames = {
                jidString + ".jpg",         // e.g., "1234567890@lid.jpg" or "123-456@g.us.jpg"
                baseName + ".jpg"            // e.g., "1234567890.jpg" or "123-456.jpg"
        };

        // Search in common directories AND the new internal cache directory
        // NOTE: Reading from /data/data/... requires the storage permissions to be granted
        // by the WPP Enhancer module's permission hook.
        File[] possibleDirs = {
                // NEW INTERNAL CACHE DIR (This is the most likely one now)
                new File("/data/data/com.whatsapp/cache/Profile Pictures"),

                // Standard external directories (keep them just in case)
                new File("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures"),
                new File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures"),
                new File("/sdcard/WhatsApp/Media/Profile Pictures"),
                new File("/storage/emulated/0/WhatsApp/Media/Profile Pictures"),
                new File("/sdcard/Android/media/com.whatsapp/WhatsApp/Profile Pictures"),
                new File("/sdcard/WhatsApp/Profile Pictures"),
                new File("/storage/emulated/0/WhatsApp/Profile Pictures")
        };

        for (File dir : possibleDirs) {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                for (String name : fileNames) {
                    var potentialFile = new File(dir, name);
                    if (potentialFile.exists()) {
                        log("Found profile photo at: " + potentialFile.getAbsolutePath());
                        return potentialFile;
                    }
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Download Profile Picture";
    }
}
