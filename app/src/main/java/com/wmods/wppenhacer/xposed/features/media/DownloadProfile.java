package com.wmods.wppenhacer.xposed.features.media;

import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class DownloadProfile extends Feature {

    public DownloadProfile(@NonNull ClassLoader classLoader,
                           @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        var loadProfileInfoField = Unobfuscator.loadProfileInfoField(classLoader);
        var profileClass = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "ViewProfilePhoto"
        );

        if (profileClass == null) return;

        XposedHelpers.findAndHookMethod(
                profileClass,
                "onCreateOptionsMenu",
                Menu.class,
                new XC_MethodHook() {

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Menu menu = (Menu) param.args[0];

                        MenuItem item = menu.add(
                                0, 0, 0, ResId.string.download
                        );

                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                        item.setIcon(ResId.drawable.download);

                        item.setOnMenuItemClickListener(menuItem -> {
                            new Thread(() -> performDownload(param)).start();
                            return true;
                        });
                    }
                }
        );
    }

    private void performDownload(XC_MethodHook.MethodHookParam param) {

        try {
            Class<?> cls = param.thisObject.getClass().getSuperclass();
            if (cls == null) cls = param.thisObject.getClass();

            var field = ReflectionUtils.getFieldByType(
                    cls,
                    Unobfuscator.loadProfileInfoField(classLoader).getDeclaringClass()
            );

            Object fieldObj = ReflectionUtils.getObjectField(field, param.thisObject);
            if (fieldObj == null) return;

            WaContactWpp waContact = new WaContactWpp(fieldObj);
            var jidWrapper = waContact.getUserJid();
            if (jidWrapper == null) return;

            String userJid = (String) XposedHelpers.callMethod(
                    jidWrapper.userJid, "getRawString"
            );

            boolean isGroup = userJid.contains("@g.us");

            if (isGroup) {
                performGroupDownload(userJid);
            } else {
                performContactDownload(userJid);
            }

        } catch (Throwable t) {
            log("Download failed: " + t.getMessage());
        }
    }

    private void performContactDownload(String jid) {
        File src = findProfilePhotoFile(jid);
        if (src == null) {
            Utils.showToast("Profile picture not found", Toast.LENGTH_SHORT);
            return;
        }

        saveAndNotify(src, "Profile Pictures",
                jid.replace("@s.whatsapp.net", "").replace("@lid", "") + ".jpg");
    }

    private void performGroupDownload(String jid) {
        File src = findProfilePhotoFile(jid);
        if (src == null) {
            Utils.showToast("Group picture not found", Toast.LENGTH_SHORT);
            return;
        }

        saveAndNotify(src, "Group Pictures",
                jid.replace("@g.us", "") + ".jpg");
    }

    /**
     * 🔥 FINAL FIX
     */
    private void saveAndNotify(File sourceFile, String folderName, String fileName) {

        String destPath;
        try {
            destPath = Utils.getDestination(folderName);
        } catch (Exception e) {
            Utils.showToast("Failed to create folder", Toast.LENGTH_SHORT);
            return;
        }

        try {
            if (sourceFile.getAbsolutePath().startsWith("/data/data/")) {

                // 1️⃣ Copy to temp file we own
                File temp = new File(Utils.getApplication().getCacheDir(), fileName);
                copyFileDirect(sourceFile, temp);

                // 2️⃣ Use MediaStore-aware copy
                String err = Utils.copyFile(temp, destPath, fileName);
                temp.delete();

                if (!TextUtils.isEmpty(err)) {
                    Utils.showToast("Save failed: " + err, Toast.LENGTH_SHORT);
                    return;
                }

            } else {
                // normal external file
                String err = Utils.copyFile(sourceFile, destPath, fileName);
                if (!TextUtils.isEmpty(err)) {
                    Utils.showToast("Save failed: " + err, Toast.LENGTH_SHORT);
                    return;
                }
            }

            Utils.showToast("Saved to: " + destPath, Toast.LENGTH_LONG);

        } catch (Throwable t) {
            log("Copy failed: " + t.getMessage());
            Utils.showToast("Unable to save this picture", Toast.LENGTH_SHORT);
        }
    }


    /**
     * Direct stream copy – NO ContentResolver, NO PFD
     */
    private void copyFileDirect(File src, File dst) throws Exception {

        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private File findProfilePhotoFile(String jid) {

        String base = jid
                .replace("@lid", "")
                .replace("@s.whatsapp.net", "")
                .replace("@g.us", "");

        String[] names = { jid + ".jpg", base + ".jpg" };

        File[] dirs = {
                new File("/data/data/com.whatsapp/cache/Profile Pictures"),
                new File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures")
        };

        for (File dir : dirs) {
            if (dir != null && dir.isDirectory()) {
                for (String n : names) {
                    File f = new File(dir, n);
                    if (f.exists()) {
                        log("Found profile photo: " + f.getAbsolutePath());
                        return f;
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
