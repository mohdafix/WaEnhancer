package com.wmods.wppenhacer.xposed.features.general;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.DelMessageStore;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache;
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke extends Feature {

    private static final ConcurrentHashMap<String, Set<String>> messageRevokedMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.List<NotificationCompat.MessagingStyle.Message>> notificationMessagesMap = new ConcurrentHashMap<>();
    private static final ThreadLocal<DateFormat> DATE_FORMAT_THREAD_LOCAL = ThreadLocal.withInitial(() ->
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Utils.getApplication().getResources().getConfiguration().getLocales().get(0)));

    public AntiRevoke(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Nullable
    private static Object findObjectFMessage(XC_MethodHook.MethodHookParam param) throws IllegalAccessException {
        if (param.args == null || param.args.length == 0) return null;

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
        if (field1 != null) {
            var key = field1.get(param.args[0]);
            return WppCore.getFMessageFromKey(key);
        }
        return null;

    }

    private static void persistRevokedMessage(FMessageWpp fMessage) {
        var messageKey = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");
        var stripJID = fMessage.getKey().remoteJid.getPhoneNumber();
        Set<String> messages = getRevokedMessagesForJid(fMessage);
        messages.add(messageKey);
        DelMessageStore.getInstance(Utils.getApplication()).insertMessage(stripJID, messageKey, System.currentTimeMillis());
    }

    private static Set<String> getRevokedMessagesForJid(FMessageWpp fMessage) {
        String stripJID = fMessage.getKey().remoteJid.getPhoneNumber();
        if (stripJID == null) return Collections.synchronizedSet(new java.util.HashSet<>());
        return messageRevokedMap.computeIfAbsent(stripJID, k -> {
            var messages = DelMessageStore.getInstance(Utils.getApplication()).getMessagesByJid(k);
            if (messages == null) return Collections.synchronizedSet(new java.util.HashSet<>());
            return Collections.synchronizedSet(messages);
        });
    }

    @Override
    public void doHook() throws Exception {

        var antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(antiRevokeMessageMethod));

        var unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(unknownStatusPlaybackMethod));

        var statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader);
        logDebug(statusPlaybackClass);

        XposedBridge.hookMethod(antiRevokeMessageMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;

                var fMessage = new FMessageWpp(param.args[0]);
                var messageKey = fMessage.getKey();
                var deviceJid = fMessage.getDeviceJid();
                var messageID = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");

                if (WppCore.getPrivBoolean(messageID + "_delpass", false)) {
                    WppCore.removePrivKey(messageID + "_delpass");
                    var activity = WppCore.getCurrentActivity();
                    Class<?> StatusPlaybackActivityClass = classLoader.loadClass("com.whatsapp.status.playback.StatusPlaybackActivity");
                    if (activity != null && StatusPlaybackActivityClass.isInstance(activity)) {
                        activity.finish();
                    }
                    return;
                }
                if (messageKey.remoteJid.isGroup()) {
                    if (deviceJid != null && handleRevocationAttempt(fMessage) != 0) {
                        param.setResult(true);
                    }
                } else if (!messageKey.isFromMe && handleRevocationAttempt(fMessage) != 0) {
                    param.setResult(true);
                }
            }
        });


        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override
            public void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup) {
                if (fMessage.getKey().isFromMe) return;
                var dateTextView = findDateView(viewGroup);
                if (dateTextView != null) {
                    bindRevokedMessageUI(fMessage, dateTextView, "antirevoke");
                }
            }
        });

        XposedBridge.hookMethod(unknownStatusPlaybackMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object obj = ReflectionUtils.getArg(param.args, param.method.getDeclaringClass(), 0);
                var objFMessage = findObjectFMessage(param);
                var field = ReflectionUtils.getFieldByType(param.method.getDeclaringClass(), statusPlaybackClass);

                if (obj == null || field == null || objFMessage == null) return;

                Object objView = field.get(obj);
                if (objView == null) return;

                var textViews = ReflectionUtils.getFieldsByType(statusPlaybackClass, TextView.class);
                if (textViews.isEmpty()) {
                    log("Could not find TextView");
                    return;
                }
                int dateId = Utils.getID("date", "id");
                for (Field textView : textViews) {
                    TextView textView1 = (TextView) textView.get(objView);
                    if (textView1 != null && textView1.getId() == dateId) {
                        bindRevokedMessageUI(new FMessageWpp(objFMessage), textView1, "antirevokestatus");
                        break;
                    }
                }
            }
        });

        // Auto-clear notifications on view
        XposedHelpers.findAndHookMethod("com.whatsapp.Conversation", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                Intent intent = activity.getIntent();
                if (intent != null) {
                    String jid = intent.getStringExtra("jid");
                    if (jid != null) {
                        Utils.cancelNotification("antirevoke_" + jid, 1001);
                        notificationMessagesMap.remove(jid);
                        if (notificationMessagesMap.isEmpty()) {
                            Utils.clearGroupCount("antirevoke_group");
                            Utils.cancelNotification("antirevoke_group".hashCode());
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("com.whatsapp.status.playback.StatusPlaybackActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                Intent intent = activity.getIntent();
                if (intent != null) {
                    String jid = intent.getStringExtra("jid");
                    if (jid != null) {
                        Utils.cancelNotification("antirevoke_" + jid, 1001);
                        notificationMessagesMap.remove(jid);
                        if (notificationMessagesMap.isEmpty()) {
                            Utils.clearGroupCount("antirevoke_group");
                            Utils.cancelNotification("antirevoke_group".hashCode());
                        }
                    }
                }
            }
        });
    }

    private TextView findDateView(ViewGroup viewGroup) {
        int[] ids = {
            Utils.getID("date", "id"),
            Utils.getID("date_tv", "id"), 
            Utils.getID("timestamp", "id"),
            Utils.getID("time", "id")
        };
        
        for (int id : ids) {
            if (id != -1) {
                var view = viewGroup.findViewById(id);
                if (view instanceof TextView) {
                    return (TextView) view;
                }
            }
        }
        return null;
    }

    private void bindRevokedMessageUI(FMessageWpp fMessage, TextView dateTextView, String antirevokeType) {
        if (dateTextView == null) return;

        var key = fMessage.getKey();
        var messageRevokedList = getRevokedMessagesForJid(fMessage);
        var id = fMessage.getRowId();
        String keyOrig = null;
        if (messageRevokedList.contains(key.messageID) || ((keyOrig = MessageStore.getInstance().getOriginalMessageKey(id)) != null && messageRevokedList.contains(keyOrig))) {
            var timestamp = DelMessageStore.getInstance(Utils.getApplication()).getTimestampByMessageId(keyOrig == null ? key.messageID : keyOrig);
            if (timestamp > 0) {
                var date = Objects.requireNonNull(DATE_FORMAT_THREAD_LOCAL.get()).format(new Date(timestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v -> Utils.showToast(String.format(Utils.getApplication().getString(ResId.string.message_removed_on), date), Toast.LENGTH_LONG));
            }
            var antirevokeValue = Integer.parseInt(prefs.getString(antirevokeType, "0"));
            if (antirevokeValue == 1) {
                var newTextData = UnobfuscatorCache.getInstance().getString("messagedeleted") + " | " + dateTextView.getText();
                dateTextView.setText(newTextData);
            } else if (antirevokeValue == 2) {
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


    private int handleRevocationAttempt(FMessageWpp fMessage) {
        try {
            showRevocationToast(fMessage);
        } catch (Exception e) {
            log(e);
        }
        String messageKey = (String) XposedHelpers.getObjectField(fMessage.getObject(), "A01");
        String stripJID = fMessage.getKey().remoteJid.getPhoneNumber();
        int revokeboolean = stripJID.equals("status") ? Integer.parseInt(prefs.getString("antirevokestatus", "0")) : Integer.parseInt(prefs.getString("antirevoke", "0"));
        if (revokeboolean == 0) return revokeboolean;
        var messageRevokedList = getRevokedMessagesForJid(fMessage);
        if (!messageRevokedList.contains(messageKey)) {
            try {
                CompletableFuture.runAsync(() -> {
                    persistRevokedMessage(fMessage);
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

    private void showRevocationToast(FMessageWpp fMessage) {
        var jidAuthor = fMessage.getKey().remoteJid;
        var isStatus = jidAuthor.isStatus();
        var messageSuffix = Utils.getApplication().getString(isStatus ? ResId.string.deleted_status : ResId.string.deleted_message);
        if (isStatus) {
            jidAuthor = fMessage.getUserJid();
        }
        if (jidAuthor.userJid == null) return;
        String name = WppCore.getContactName(jidAuthor);
        if (TextUtils.isEmpty(name)) {
            name = jidAuthor.getPhoneNumber();
        }
        boolean isGroupDeletion = jidAuthor.isGroup() && !fMessage.getUserJid().isNull();

        String participantName = null;
        if (isGroupDeletion) {
            var participantJid = fMessage.getUserJid();
            participantName = WppCore.getContactName(participantJid);
            if (TextUtils.isEmpty(participantName)) {
                participantName = participantJid.getPhoneNumber();
            }
        }

        String message;
        if (isGroupDeletion) {
            message = Utils.getApplication().getString(ResId.string.deleted_a_message_in_group, participantName, name);
        } else {
            message = name + " " + messageSuffix;
        }

        // Cleaner notification layout:
        // - Group:   title = group name, content = "<person> deleted a message"
        // - Chat:    title = person name, content = "deleted a message/status"
        String notificationTitle;
        String notificationContent;
        if (isGroupDeletion) {
            notificationTitle = name;
            notificationContent = participantName + " " + messageSuffix;
        } else {
            notificationTitle = name;
            notificationContent = messageSuffix;
        }
        int toastDeletedOption;
        try {
            toastDeletedOption = Integer.parseInt(prefs.getString("toastdeleted", "0"));
        } catch (Throwable ignored) {
            // Backward compatibility: old builds used a boolean preference
            toastDeletedOption = prefs.getBoolean("toastdeleted", false) ? 1 : 0;
        }
        if (toastDeletedOption != 0) {
            Set<String> filters = prefs.getStringSet("notification_filter", null);
            if (filters == null) {
                // Default to all if not set
                filters = new java.util.HashSet<>();
                filters.add("private");
                filters.add("group");
                filters.add("status");
            }

            boolean isAllowed = false;
            if (isStatus) {
                if (filters.contains("status")) isAllowed = true;
            } else if (isGroupDeletion) {
                if (filters.contains("group")) isAllowed = true;
            } else {
                if (filters.contains("private")) isAllowed = true;
            }

            if (isAllowed) {
                if (toastDeletedOption == 1) {
                    Utils.showToast(message, Toast.LENGTH_LONG);
                } else if (toastDeletedOption == 2) {
                    PendingIntent pendingIntent = null;
                    try {
                        String rawJid = jidAuthor.getPhoneRawString();
                        if (rawJid == null) rawJid = jidAuthor.getUserRawString();

                        if (rawJid != null) {
                            Intent intent;
                            if (isStatus) {
                                var statusPlaybackClass = Unobfuscator.getClassByName("StatusPlaybackActivity", classLoader);
                                intent = new Intent(Utils.getApplication(), statusPlaybackClass);
                                intent.putExtra("jid", rawJid);
                            } else {
                                intent = new Intent();
                                intent.setClassName(Utils.getApplication().getPackageName(), "com.whatsapp.Conversation");
                                intent.putExtra("jid", rawJid);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            }
                            
                            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                flags |= PendingIntent.FLAG_IMMUTABLE;
                            }
                            pendingIntent = PendingIntent.getActivity(Utils.getApplication(), rawJid.hashCode(), intent, flags);
                            
                            // Build MessagingStyle history
                            var messages = notificationMessagesMap.computeIfAbsent(rawJid, k -> Collections.synchronizedList(new java.util.ArrayList<>()));
                            var senderPerson = new androidx.core.app.Person.Builder()
                                    .setName(isGroupDeletion ? participantName : name)
                                    .build();
                            
                            messages.add(new NotificationCompat.MessagingStyle.Message(messageSuffix, System.currentTimeMillis(), senderPerson));
                            if (messages.size() > 10) messages.remove(0);

                            var userPerson = new androidx.core.app.Person.Builder().setName("Me").build();
                            var messagingStyle = new NotificationCompat.MessagingStyle(userPerson);
                            if (isGroupDeletion) {
                                messagingStyle.setConversationTitle(name);
                                messagingStyle.setGroupConversation(true);
                            }
                            
                            for (var msg : messages) {
                                messagingStyle.addMessage(msg);
                            }

                            String tag = "antirevoke_" + rawJid;
                            Utils.showNotification(notificationTitle, notificationContent, pendingIntent, tag, 1001, "antirevoke_group", messagingStyle);
                        }
                    } catch (Throwable e) {
                        log(e);
                    }
                }
            }
        }
        Tasker.sendTaskerEvent(name, jidAuthor.getPhoneNumber(), jidAuthor.isStatus() ? "deleted_status" : "deleted_message");
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Anti Revoke";
    }

}
