package com.wmods.wppenhacer.xposed.features.general;

import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.DelMessageStore;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache;
import com.wmods.wppenhacer.xposed.utils.DebugUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke extends Feature {

    private static final HashMap<String, HashSet<String>> messageRevokedMap = new HashMap<>();

    public AntiRevoke(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {

        var antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(antiRevokeMessageMethod));

        var bubbleMethod = Unobfuscator.loadAntiRevokeBubbleMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(bubbleMethod));

        var unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(unknownStatusPlaybackMethod));

        var statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader);
        logDebug(statusPlaybackClass);

        XposedBridge.hookMethod(antiRevokeMessageMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                var fMessage = new FMessageWpp(param.args[0]);
                var messageKey = fMessage.getKey();
                if (messageKey == null) return;
                var deviceJid = fMessage.getDeviceJid();
                var messageID = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");
                // Caso o proprio usuario tenha deletado o status
                if (WppCore.getPrivBoolean(messageID + "_delpass", false)) {
                    WppCore.removePrivKey(messageID + "_delpass");
                    var activity = WppCore.getCurrentActivity();
                    Class<?> StatusPlaybackActivityClass = classLoader.loadClass("com.whatsapp.status.playback.StatusPlaybackActivity");
                    if (activity != null && StatusPlaybackActivityClass.isInstance(activity)) {
                        activity.finish();
                    }
                    return;
                }
                if (messageKey.remoteJid != null && messageKey.remoteJid.isGroup()) {
                    if (deviceJid != null && antiRevoke(fMessage) != 0) {
                        param.setResult(true);
                    }
                } else if (!messageKey.isFromMe && antiRevoke(fMessage) != 0) {
                    param.setResult(true);
                }
            }
        });

        XposedBridge.hookMethod(bubbleMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var objMessage = param.args[2];
                var dateTextView = (TextView) param.args[1];
                isMRevoked(objMessage, dateTextView, "antirevoke");
            }
        });

        XposedBridge.hookMethod(unknownStatusPlaybackMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object obj = ReflectionUtils.getArg(param.args, param.method.getDeclaringClass(), 0);
                var objFMessage = findObjectFMessage(param);
                var field = ReflectionUtils.getFieldByType(param.method.getDeclaringClass(), statusPlaybackClass);

                Object objView = field.get(obj);
                var textViews = ReflectionUtils.getFieldsByType(statusPlaybackClass, TextView.class);
                if (textViews.isEmpty()) {
                    log("Could not find TextView");
                    return;
                }
                int dateId = Utils.getID("date", "id");
                for (Field textView : textViews) {
                    TextView textView1 = (TextView) textView.get(objView);
                    if (textView1 != null && textView1.getId() == dateId) {
                        isMRevoked(objFMessage, textView1, "antirevokestatus");
                        break;
                    }
                }
            }
        });

    }

    @Nullable
    private static Object findObjectFMessage(XC_MethodHook.MethodHookParam param) throws IllegalAccessException {
        if (FMessageWpp.TYPE.isInstance(param.args[0]))
            return param.args[0];

        if (param.args.length > 1) {
            if (FMessageWpp.TYPE.isInstance(param.args[1]))
                return param.args[1];
            var FMessageField = ReflectionUtils.findFieldUsingFilterIfExists(param.args[1].getClass(), f -> FMessageWpp.TYPE.isAssignableFrom(f.getType()));
            if (FMessageField != null) {
                return FMessageField.get(param.args[1]);
            }
        }

        var field = ReflectionUtils.findFieldUsingFilterIfExists(param.args[0].getClass(), f -> f.getType() == FMessageWpp.TYPE);
        if (field != null)
            return field.get(param.args[0]);

        var field1 = ReflectionUtils.findFieldUsingFilter(param.args[0].getClass(), f -> f.getType() == FMessageWpp.Key.TYPE);
        var key = field1.get(param.args[0]);
        return WppCore.getFMessageFromKey(key);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Anti Revoke";
    }

    private static void saveRevokedMessage(FMessageWpp fMessage) {
        var messageKey = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");
        var key = fMessage.getKey();
        if (key == null || key.remoteJid == null) return;
        var stripJID = key.remoteJid.getPhoneNumber();
        HashSet<String> messages = getRevokedMessages(fMessage);
        messages.add(messageKey);
        DelMessageStore.getInstance(Utils.getApplication()).insertMessage(stripJID, messageKey, System.currentTimeMillis());
    }

    private static HashSet<String> getRevokedMessages(FMessageWpp fMessage) {
        var key = fMessage.getKey();
        if (key == null || key.remoteJid == null) return new HashSet<>();
        String stripJID = key.remoteJid.getPhoneNumber();
        if (messageRevokedMap.containsKey(stripJID)) {
            return messageRevokedMap.get(stripJID);
        }
        var messages = DelMessageStore.getInstance(Utils.getApplication()).getMessagesByJid(stripJID);
        if (messages == null) messages = new HashSet<>();
        messageRevokedMap.put(stripJID, messages);
        return messages;
    }

    private void isMRevoked(Object objMessage, TextView dateTextView, String antirevokeType) {
        if (dateTextView == null) return;

        var fMessage = new FMessageWpp(objMessage);
        var key = fMessage.getKey();
        if (key == null) return;
        var messageRevokedList = getRevokedMessages(fMessage);
        var id = fMessage.getRowId();
        String keyOrig = null;

        if ((key.messageID != null && messageRevokedList.contains(key.messageID)) ||
                ((keyOrig = MessageStore.getInstance().getOriginalMessageKey(id)) != null && messageRevokedList.contains(keyOrig))) {
            var timestamp = DelMessageStore.getInstance(Utils.getApplication()).getTimestampByMessageId(keyOrig == null ? key.messageID : keyOrig);
            if (timestamp > 0) {
                Locale locale = Utils.getApplication().getResources().getConfiguration().getLocales().get(0);
                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
                var date = dateFormat.format(new Date(timestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v -> Utils.showToast(String.format(Utils.getApplication().getString(ResId.string.message_removed_on), date), Toast.LENGTH_LONG));
            }
            var antirevokeValue = Integer.parseInt(prefs.getString(antirevokeType, "0"));
            if (antirevokeValue == 1) {
                // Text
                var newTextData = UnobfuscatorCache.getInstance().getString("messagedeleted") + " | " + dateTextView.getText();
                dateTextView.setText(newTextData);
            } else if (antirevokeValue == 2) {
                // Icon
                var drawable = Utils.getApplication().getDrawable(ResId.drawable.deleted);
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
                dateTextView.setCompoundDrawablePadding(5);
            }
        } else {
            dateTextView.setCompoundDrawables(null, null, null, null);
            var revokeNotice = UnobfuscatorCache.getInstance().getString("messagedeleted") + " | ";
            var dateText = dateTextView.getText().toString();
            if (dateText.contains(revokeNotice)) {
                dateTextView.setText(dateText.replace(revokeNotice, ""));
            }
            dateTextView.getPaint().setUnderlineText(false);
            dateTextView.setOnClickListener(null);
        }
    }

    private int antiRevoke(FMessageWpp fMessage) {
        try {
            showToast(fMessage);
        } catch (Exception e) {
            log(e);
        }

        String messageKey = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");
        var key = fMessage.getKey();
        if (key == null || key.remoteJid == null) return 0;
        String stripJID = key.remoteJid.getPhoneNumber();

        if (messageKey == null || stripJID == null) return 0;

        int revokeboolean = stripJID.equals("status") ? Integer.parseInt(prefs.getString("antirevokestatus", "0")) : Integer.parseInt(prefs.getString("antirevoke", "0"));
        if (revokeboolean == 0) return revokeboolean;

        var messageRevokedList = getRevokedMessages(fMessage);

        boolean contains = false;
        for (String msg : messageRevokedList) {
            if (Objects.equals(msg, messageKey)) {
                contains = true;
                break;
            }
        }

        if (!contains) {
            try {
                CompletableFuture.runAsync(() -> {
                    saveRevokedMessage(fMessage);
                    try {
                        var mConversation = WppCore.getCurrentConversation();
                        if (mConversation != null && Objects.equals(stripJID, WppCore.getCurrentUserJid().getPhoneNumber())) {
                            mConversation.runOnUiThread(() -> {
                                if (mConversation.hasWindowFocus()) {
                                    mConversation.startActivity(mConversation.getIntent());
                                    mConversation.overridePendingTransition(0, 0);
                                    mConversation.getWindow().getDecorView().findViewById(android.R.id.content).postInvalidate();
                                } else {
                                    mConversation.recreate();
                                }
                            });
                        }
                    } catch (Exception e) {
                        logDebug(e);
                    }
                });
            } catch (Exception e) {
                logDebug(e);
            }
        }
        return revokeboolean;
    }

    private void showToast(FMessageWpp fMessage) {
        var key = fMessage.getKey();
        if (key == null || key.remoteJid == null) return;
        var jidAuthor = key.remoteJid;
        var messageSuffix = Utils.getApplication().getString(ResId.string.deleted_message);
        if (jidAuthor.isStatus()) {
            messageSuffix = Utils.getApplication().getString(ResId.string.deleted_status);
            var userJid = fMessage.getUserJid();
            if (userJid != null) {
                jidAuthor = userJid;
            }
        }
        if (jidAuthor.isNull()) return;
        String name = WppCore.getContactName(jidAuthor);
        if (TextUtils.isEmpty(name)) {
            name = jidAuthor.getPhoneNumber();
        }
        String message;
        var fUserJid = fMessage.getUserJid();
        if (jidAuthor.isGroup() && fUserJid != null && !fUserJid.isNull()) {
            String participantName = WppCore.getContactName(fUserJid);
            if (TextUtils.isEmpty(participantName)) {
                participantName = fUserJid.getPhoneNumber();
            }
            message = Utils.getApplication().getString(ResId.string.deleted_a_message_in_group, participantName, name);
        } else {
            message = name + " " + messageSuffix;
        }
        if (prefs.getBoolean("toastdeleted", false)) {
            Utils.showToast(message, Toast.LENGTH_LONG);
        }
        Tasker.sendTaskerEvent(name, jidAuthor.getPhoneNumber(), jidAuthor.isStatus() ? "deleted_status" : "deleted_message");
    }

}
