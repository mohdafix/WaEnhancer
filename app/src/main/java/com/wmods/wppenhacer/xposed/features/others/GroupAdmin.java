package com.wmods.wppenhacer.xposed.features.others;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GroupAdmin extends Feature {

    private static final String TAG = "GroupAdmin";

    public GroupAdmin(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("admin_grp", false)) return;
        var jidFactory = Unobfuscator.loadJidFactory(classLoader);
        var grpAdmin1 = Unobfuscator.loadGroupAdminMethod(classLoader);
        var grpcheckAdmin = Unobfuscator.loadGroupCheckAdminMethod(classLoader);
        var hooked = new XC_MethodHook() {
            @SuppressLint("ResourceType")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // GroupAdmin.afterHookedMethod - defensive null checks
                try {
                    var targetObj = param.thisObject != null ? param.thisObject : param.args[1];
                    if (targetObj == null) {
                        Log.w(TAG, "targetObj is null, skipping");
                        return;
                    }
                    Object fMessageObj = XposedHelpers.callMethod(targetObj, "getFMessage");

                    FMessageWpp fMessage;
                    try {
                        fMessage = new FMessageWpp(fMessageObj);
                    } catch (Exception e) {
                        // constructor failed — log and skip
                        Log.w(TAG, "FMessageWpp construction failed, skipping GroupAdmin hook: " + e.getMessage());
                        return;
                    }

                    if (fMessage == null) {
                        Log.w(TAG, "FMessageWpp is null, skipping");
                        return;
                    }

                    Object userJid = null;
                    try {
                        userJid = fMessage.getUserJid();
                    } catch (Throwable t) {
                        Log.w(TAG, "getUserJid() threw: " + t.getMessage());
                    }

                    if (userJid == null) {
                        Log.w(TAG, "userJid is null, skipping GroupAdmin logic");
                        return;
                    }

                    var chatCurrentJid = WppCore.getCurrentUserJid();
                     if (chatCurrentJid == null) {
                        Log.w(TAG, "chatCurrentJid is null, skipping");
                        return;
                    }

                    // Now safe to call isGroup() on chatCurrentJid
                    boolean isGroup = false;
                    try {
                        // if UserJid has isGroup method
                        isGroup = (Boolean) chatCurrentJid.getClass().getMethod("isGroup").invoke(chatCurrentJid);
                    } catch (NoSuchMethodException nsme) {
                        Log.w(TAG, "isGroup() not found on chatCurrentJid: " + nsme.getMessage());
                    } catch (Throwable t) {
                        Log.w(TAG, "isGroup() invocation error: " + t.getMessage());
                    }

                    if (isGroup) {
                        var field = ReflectionUtils.getFieldByType(targetObj.getClass(), grpcheckAdmin.getDeclaringClass());
                        if (field == null) {
                            Log.w(TAG, "Could not find field for grpcheckAdmin, skipping.");
                            return;
                        }
                        field.setAccessible(true);
                        var grpParticipants = field.get(targetObj);
                        var jidGrp = jidFactory.invoke(null, chatCurrentJid.getUserRawString());
                        
                        Object userJidField = null;
                        try {
                            userJidField = userJid.getClass().getField("userJid").get(userJid);
                        } catch (Exception e) {
                            Log.w(TAG, "Could not get userJid field from userJid object, falling back to userJid itself: " + e.getMessage());
                            userJidField = userJid;
                        }

                        var result = grpcheckAdmin.invoke(grpParticipants, jidGrp, userJidField);
                        var view = (View) targetObj;
                        var context = view.getContext();
                        ImageView iconAdmin;
                        if ((iconAdmin = view.findViewById(0x7fff0010)) == null) {
                            var nameGroup = (LinearLayout) view.findViewById(Utils.getID("name_in_group", "id"));
                             if (nameGroup == null) {
                                Log.w(TAG, "name_in_group layout not found, skipping icon creation.");
                                return;
                            }
                            var view1 = new LinearLayout(context);
                            view1.setOrientation(LinearLayout.HORIZONTAL);
                            view1.setGravity(Gravity.CENTER_VERTICAL);
                            var nametv = nameGroup.getChildAt(0);
                            iconAdmin = new ImageView(context);
                            var size = Utils.dipToPixels(16);
                            iconAdmin.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                            iconAdmin.setImageResource(ResId.drawable.admin);
                            iconAdmin.setId(0x7fff0010);
                            if (nametv != null) {
                                nameGroup.removeView(nametv);
                                view1.addView(nametv);
                            }
                            view1.addView(iconAdmin);
                            nameGroup.addView(view1, 0);
                        }
                        iconAdmin.setVisibility(result != null && (boolean) result ? View.VISIBLE : View.GONE);
                    }
                } catch (Throwable outer) {
                    // protect UI thread: never let our hook crash the app
                    Log.e(TAG, "Unhandled in GroupAdmin.afterHookedMethod: " + outer.getMessage());
                }
            }
        };
        XposedBridge.hookMethod(grpAdmin1, hooked);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "GroupAdmin";
    }
}
