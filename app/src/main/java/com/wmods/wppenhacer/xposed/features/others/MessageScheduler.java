package com.wmods.wppenhacer.xposed.features.others;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Scheduler UI only:
 * - Adds a menu entry in Conversation
 * - Reads current conversation jid + existing input text
 * - Shows date/time picker dialog
 * - Validates and persists (jid, text, time)
 *
 * Hard constraints: no sending, no services, no alarms, no notifications.
 */
public class MessageScheduler extends Feature {

    private static final String PREFS_NAME = "scheduled_messages_prefs";
    private static final String SCHEDULED_MESSAGES_KEY = "scheduled_messages";
    private static final int MENU_ITEM_ID = 0x57A11;

    private SharedPreferences scheduledMessagesPrefs;

    public MessageScheduler(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        Context context = Utils.getApplication();
        scheduledMessagesPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hookConversationMenu();
    }

    private void hookConversationMenu() throws Exception {
        var onCreateMenuConversationMethod = Unobfuscator.loadBlueOnReplayCreateMenuConversationMethod(classLoader);
        XposedBridge.hookMethod(onCreateMenuConversationMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!prefs.getBoolean("schedule_message", true)) return;

                var menu = (Menu) param.args[0];
                var activity = WppCore.getCurrentConversation();
                if (activity == null) return;
                addScheduleMessageMenuItem(menu, activity);
            }
        });
    }

    private void addScheduleMessageMenuItem(Menu menu, Activity activity) {
        if (menu.findItem(MENU_ITEM_ID) != null) return;

        MenuItem item = menu.add(0, MENU_ITEM_ID, 0, "Schedule Message");

        // Decide based on conversation type, not just activity.
        // If chatting with a business account (from regular WA app), use overflow to avoid duplicate toolbar icons.
        boolean isBusinessChat = isCurrentConversationBusiness(activity);
        item.setShowAsAction(isBusinessChat ? MenuItem.SHOW_AS_ACTION_NEVER : MenuItem.SHOW_AS_ACTION_ALWAYS);

        // Best-effort icon.
        try {
            int waIconId = Utils.getID("ic_schedule", "drawable");
            if (waIconId != -1) {
                item.setIcon(waIconId);
            } else {
                var iconDraw = activity.getDrawable(android.R.drawable.ic_menu_recent_history);
                if (iconDraw != null) {
                    iconDraw.setTint(DesignUtils.getPrimaryTextColor());
                    item.setIcon(iconDraw);
                }
            }
        } catch (Throwable ignored) {
        }

        item.setOnMenuItemClickListener(mi -> {
            showScheduleMessageDialog(activity);
            return true;
        });
    }

    private boolean isCurrentConversationBusiness(Activity activity) {
        try {
            var userJid = WppCore.getCurrentUserJid();
            if (userJid == null || userJid.isNull()) return false;

            // Preferred: if WA provides isBusiness(), use it.
            try {
                //noinspection JavaReflectionMemberAccess
                return (Boolean) XposedHelpers.callMethod(userJid, "isBusiness");
            } catch (Throwable ignored) {
            }

            // Fallback: raw string detection for business markers.
            String raw = userJid.getPhoneRawString();
            if (raw != null && raw.toLowerCase().contains("business")) return true;

            // Secondary check: try userJid as string
            String userRaw = userJid.getUserRawString();
            return userRaw != null && userRaw.toLowerCase().contains("business");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showScheduleMessageDialog(Activity activity) {
        try {
            var currentUserJid = WppCore.getCurrentUserJid();
            if (currentUserJid == null || currentUserJid.isNull()) {
                Utils.showToast("Cannot get current conversation", Toast.LENGTH_SHORT);
                return;
            }

            String initialText = getMessageInputText(activity);

            ScrollView scrollView = new ScrollView(activity);
            scrollView.setFillViewport(true);

            android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padH = Utils.dipToPixels(20);
            int padV = Utils.dipToPixels(16);
            layout.setPadding(padH, padV, padH, Utils.dipToPixels(8));

            TextView messageLabel = new TextView(activity);
            messageLabel.setText("Message");
            messageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            messageLabel.setTextColor(0xFF8696A0);

            EditText messageInput = new EditText(activity);
            messageInput.setText(initialText);
            messageInput.setMinLines(2);
            messageInput.setMaxLines(6);
            messageInput.setHint("Type a message in the chat first");

            TextView dateLabel = new TextView(activity);
            dateLabel.setText("Date");
            dateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            dateLabel.setTextColor(0xFF8696A0);

            DatePicker datePicker = new DatePicker(activity);
            datePicker.setMinDate(System.currentTimeMillis() - 1000);

            TextView timeLabel = new TextView(activity);
            timeLabel.setText("Time");
            timeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            timeLabel.setTextColor(0xFF8696A0);

            TimePicker timePicker = new TimePicker(activity);
            timePicker.setIs24HourView(true);

            layout.addView(messageLabel);
            layout.addView(messageInput);
            layout.addView(dateLabel);
            layout.addView(datePicker);
            layout.addView(timeLabel);
            layout.addView(timePicker);

            scrollView.addView(layout);

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Schedule Message")
                    .setView(scrollView)
                    // We override click so validation does not auto-close.
                    .setPositiveButton("Schedule", null)
                    .setNegativeButton("Cancel", null)
                    .create();

            dialog.setOnShowListener(d -> {
                var btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (btn == null) return;
                btn.setOnClickListener(v -> {
                    String message = messageInput.getText() == null ? "" : messageInput.getText().toString();
                    if (TextUtils.isEmpty(message) || TextUtils.isEmpty(message.trim())) {
                        Utils.showToast("Message cannot be empty", Toast.LENGTH_SHORT);
                        return;
                    }

                    Calendar cal = Calendar.getInstance();
                    cal.set(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                            timePicker.getHour(), timePicker.getMinute(), 0);
                    long scheduledTime = cal.getTimeInMillis();
                    if (scheduledTime <= System.currentTimeMillis()) {
                        Utils.showToast("Please select a future time", Toast.LENGTH_SHORT);
                        return;
                    }

                    String jid = currentUserJid.getPhoneRawString();
                    if (TextUtils.isEmpty(jid)) {
                        Utils.showToast("Cannot get current conversation", Toast.LENGTH_SHORT);
                        return;
                    }

                    saveScheduledMessage(jid, message, scheduledTime);

                    String formattedTime = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            .format(new Date(scheduledTime));
                    Utils.showToast("Message scheduled for " + formattedTime, Toast.LENGTH_LONG);
                    dialog.dismiss();
                });
            });

            dialog.show();
        } catch (Throwable e) {
            Utils.showToast("Error: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    private String getMessageInputText(Activity activity) {
        try {
            int entryId = Utils.getID("entry", "id");
            if (entryId != -1) {
                View v = activity.findViewById(entryId);
                if (v instanceof EditText) {
                    CharSequence cs = ((EditText) v).getText();
                    return cs == null ? "" : cs.toString();
                }
            }

            View rootView = activity.getWindow().getDecorView();
            if (rootView instanceof android.view.ViewGroup) {
                java.util.ArrayList<EditText> edits = new java.util.ArrayList<>();
                findEditTexts(rootView, edits);
                for (EditText e : edits) {
                    CharSequence cs = e.getText();
                    if (cs != null && cs.length() > 0) return cs.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void findEditTexts(View view, java.util.ArrayList<EditText> list) {
        if (view instanceof EditText) {
            list.add((EditText) view);
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findEditTexts(group.getChildAt(i), list);
            }
        }
    }

    private void saveScheduledMessage(String jid, String message, long scheduledTime) {
        try {
            String existingJson = scheduledMessagesPrefs.getString(SCHEDULED_MESSAGES_KEY, "[]");
            JSONArray scheduledMessages = new JSONArray(existingJson);

            JSONObject newMessage = new JSONObject();
            newMessage.put("jid", jid);
            newMessage.put("text", message);
            newMessage.put("time", scheduledTime);
            newMessage.put("id", System.currentTimeMillis());
            scheduledMessages.put(newMessage);

            scheduledMessagesPrefs.edit()
                    .putString(SCHEDULED_MESSAGES_KEY, scheduledMessages.toString())
                    .apply();
        } catch (JSONException e) {
            Utils.showToast("Error saving scheduled message", Toast.LENGTH_SHORT);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Message Scheduler";
    }
}