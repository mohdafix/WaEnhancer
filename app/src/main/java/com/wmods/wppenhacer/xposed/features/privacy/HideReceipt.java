package com.wmods.wppenhacer.xposed.features.privacy;

import static com.wmods.wppenhacer.xposed.features.privacy.HideSeen.getKeyMessage;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.components.FMessageSafe;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.customization.HideSeenView;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectUtils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class HideReceipt extends Feature {
    public HideReceipt(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        var hideReceipt = prefs.getBoolean("hidereceipt", false);
        var ghostmode = WppCore.getPrivBoolean("ghostmode", false);
        var hideread = prefs.getBoolean("hideread", false);

        logDebug("hideReceipt: " + hideReceipt + ", ghostmode: " + ghostmode + ", hideread: " + hideread);

        var method = Unobfuscator.loadReceiptMethod(classLoader);
        logDebug("hook method:" + Unobfuscator.getMethodDescriptor(method));

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    var userJidObject = ReflectionUtils.getArg(param.args, Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.Jid"), 0);
                    if (userJidObject == null) return;
                    var strings = ReflectionUtils.findClassesOfType(((Method) param.method).getParameterTypes(), String.class);
                    FMessageWpp.Key keyMessage = getKeyMessage(param, userJidObject, strings);
                    if (keyMessage == null)
                        return;
                    var currentUserJid = new FMessageWpp.UserJid(userJidObject);
                    var fmessage = keyMessage.getFMessage();

                    if (fmessage == null) {
                        XposedBridge.log("HideReceipt: fMessageWpp == null, skipping hook action");
                        return;
                    }

                    // Use FMessageSafe for safe validation
                    FMessageSafe f = FMessageSafe.from(fmessage.getObject());
                    if (!f.isValid()) {
                        XposedBridge.log("HideReceipt: fMessageWpp invalid, skipping. raw=" + f.getRawClassName());
                        if (f.getRaw() != null) XposedBridge.log("HideReceipt probe: methods=" + ReflectUtils.methodListSnippet(f.getRaw(),20));
                        return;
                    }

                    if (fmessage.getKey() == null) {
                        XposedBridge.log("HideReceipt: fMessageWpp.getKey() == null");
                        return;
                    }

                    Object userJid = f.getUserJid();
                    if (userJid == null) {
                        XposedBridge.log("HideReceipt: userJid null, skipping. raw=" + f.getRawClassName());
                        return;
                    }

                    if (MessageHistory.getInstance().getHideSeenMessage(fmessage.getKey().remoteJid.getPhoneRawString(), fmessage.getKey().messageID, fmessage.isViewOnce() ? MessageHistory.MessageType.VIEW_ONCE_TYPE : MessageHistory.MessageType.MESSAGE_TYPE) != null) {
                        return;
                    }

                    var privacy = CustomPrivacy.getJSON(currentUserJid.getPhoneNumber());
                    var customHideReceipt = privacy.optBoolean("HideReceipt", hideReceipt);
                    var msgTypeIdx = strings.get(strings.size() - 1).first;
                    var customHideRead = privacy.optBoolean("HideSeen", hideread);
                    if (param.args[msgTypeIdx] != "sender" && (customHideReceipt || ghostmode)) {
                        if (WppCore.getCurrentConversation() == null || customHideRead)
                            param.args[msgTypeIdx] = "inactive";
                    }
                    if (param.args[msgTypeIdx] == "inactive") {
                        MessageHistory.getInstance().insertHideSeenMessage(currentUserJid.getPhoneRawString(), fmessage.getKey().messageID, fmessage.isViewOnce() ? MessageHistory.MessageType.VIEW_ONCE_TYPE : MessageHistory.MessageType.MESSAGE_TYPE, false);
                        HideSeenView.updateAllBubbleViews();
                    }
                } catch (Throwable t) {
                    XposedBridge.log("HideReceipt: " + android.util.Log.getStackTraceString(t));
                }
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Hide Receipt";
    }
}
