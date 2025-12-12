package com.wmods.wppenhacer.xposed.features.privacy;

import static com.wmods.wppenhacer.xposed.features.privacy.HideSeen.getKeyMessage;

import android.util.Pair;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.customization.HideSeenView;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class HideReceipt extends Feature {
    // Cache reflection results to avoid repeated lookups
    private static Class<?> cachedJidClass;
    private static List<Pair<Integer, Class<? extends String>>> cachedStringClasses;
    private static Method cachedMethod;
    private static volatile long lastLogTime = 0;
    private static final long LOG_THROTTLE_MS = 5000; // Only log once every 5 seconds

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

        // Cache the method and reflection results once
        cachedMethod = method;
        cachedJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.Jid");
        cachedStringClasses = ReflectionUtils.findClassesOfType(method.getParameterTypes(), String.class);

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    // Early return: use cached class instead of repeated lookup
                    var userJidObject = ReflectionUtils.getArg(param.args, cachedJidClass, 0);
                    if (userJidObject == null) return;

                    // Early return: check if we even need to do anything
                    if (!hideReceipt && !ghostmode && !hideread) return;

                    // Use cached string classes instead of recalculating
                    FMessageWpp.Key keyMessage = getKeyMessage(param, userJidObject, cachedStringClasses);
                    if (keyMessage == null) return;

                    var currentUserJid = new FMessageWpp.UserJid(userJidObject);

                    // Get msgTypeIdx early for quick checks
                    var msgTypeIdx = cachedStringClasses.get(cachedStringClasses.size() - 1).first;

                    // Early return: if already "sender", nothing to do
                    if ("sender".equals(param.args[msgTypeIdx])) return;

                    // Lazy load fmessage only when needed
                    var fmessage = keyMessage.getFMessage();

                    if (fmessage == null) {
                        // Throttled logging to reduce I/O spam
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > LOG_THROTTLE_MS) {
                            lastLogTime = now;
                            logDebug("HideReceipt: fMessageWpp == null, skipping (throttled log)");
                        }
                        return;
                    }

                    var fmessageKey = fmessage.getKey();
                    if (fmessageKey == null) {
                        // Throttled logging
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > LOG_THROTTLE_MS) {
                            lastLogTime = now;
                            logDebug("HideReceipt: fMessageWpp.getKey() == null (throttled log)");
                        }
                        return;
                    }

                    // Check message history cache
                    if (MessageHistory.getInstance().getHideSeenMessage(fmessageKey.remoteJid.getPhoneRawString(), fmessageKey.messageID, fmessage.isViewOnce() ? MessageHistory.MessageType.VIEW_ONCE_TYPE : MessageHistory.MessageType.MESSAGE_TYPE) != null) {
                        return;
                    }

                    var privacy = CustomPrivacy.getJSON(currentUserJid.getPhoneNumber());
                    var customHideReceipt = privacy.optBoolean("HideReceipt", hideReceipt);
                    var customHideRead = privacy.optBoolean("HideSeen", hideread);

                    if (customHideReceipt || ghostmode) {
                        if (WppCore.getCurrentConversation() == null || customHideRead)
                            param.args[msgTypeIdx] = "inactive";
                    }

                    if ("inactive".equals(param.args[msgTypeIdx])) {
                        MessageHistory.getInstance().insertHideSeenMessage(currentUserJid.getPhoneRawString(), fmessageKey.messageID, fmessage.isViewOnce() ? MessageHistory.MessageType.VIEW_ONCE_TYPE : MessageHistory.MessageType.MESSAGE_TYPE, false);
                        HideSeenView.updateAllBubbleViews();
                    }
                } catch (Throwable t) {
                    // Only log errors, not normal flow
                    log(t);
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
