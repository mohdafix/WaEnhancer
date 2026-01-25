package com.wmods.wppenhacer.xposed.features.others;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
            String contactName = WppCore.getContactName(currentUserJid);
            boolean isBusiness = isCurrentConversationBusiness(activity);

            // Container for everything
            android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(activity);
            rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = Utils.dipToPixels(20);
            rootLayout.setPadding(pad, pad, pad, pad);

            // Message Input Label
            TextView messageLabel = new TextView(activity);
            messageLabel.setText("Message to schedule");
            messageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            messageLabel.setTextColor(DesignUtils.getPrimaryTextColor());
            rootLayout.addView(messageLabel);

            // Message Input
            EditText messageInput = new EditText(activity);
            messageInput.setText(initialText);
            messageInput.setHint("Write something...");
            rootLayout.addView(messageInput);

            // Delay Label
            TextView delayLabel = new TextView(activity);
            delayLabel.setText("Send after:");
            delayLabel.setPadding(0, Utils.dipToPixels(16), 0, Utils.dipToPixels(8));
            delayLabel.setTextColor(DesignUtils.getPrimaryTextColor());
            rootLayout.addView(delayLabel);

            // Delay Options (Radio Group)
            android.widget.RadioGroup delayGroup = new android.widget.RadioGroup(activity);
            
            int[][] options = {{5, 5}, {10, 10}, {25, 25}, {60, 60}}; // {label, mins}
            for (int[] opt : options) {
                android.widget.RadioButton rb = new android.widget.RadioButton(activity);
                rb.setText(opt[0] + " Minutes");
                rb.setTag(opt[1]);
                delayGroup.addView(rb);
            }

            android.widget.RadioButton rbCustom = new android.widget.RadioButton(activity);
            rbCustom.setText("Custom Date/Time");
            rbCustom.setTag(-1);
            delayGroup.addView(rbCustom);
            
            // Set default
            ((android.widget.RadioButton)delayGroup.getChildAt(0)).setChecked(true);
            rootLayout.addView(delayGroup);

            // Custom Picker Layout (Hidden by default)
            android.widget.LinearLayout customPickerLayout = new android.widget.LinearLayout(activity);
            customPickerLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            customPickerLayout.setVisibility(View.GONE);
            
            DatePicker datePicker = new DatePicker(activity);
            datePicker.setCalendarViewShown(false);
            datePicker.setMinDate(System.currentTimeMillis() - 1000);
            
            TimePicker timePicker = new TimePicker(activity);
            timePicker.setIs24HourView(true);
            
            customPickerLayout.addView(datePicker);
            customPickerLayout.addView(timePicker);
            rootLayout.addView(customPickerLayout);

            // Toggle visibility based on radio selection
            delayGroup.setOnCheckedChangeListener((group, checkedId) -> {
                View selected = group.findViewById(checkedId);
                if (selected != null && (int)selected.getTag() == -1) {
                    customPickerLayout.setVisibility(View.VISIBLE);
                } else {
                    customPickerLayout.setVisibility(View.GONE);
                }
            });

            ScrollView scrollView = new ScrollView(activity);
            scrollView.addView(rootLayout);

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Schedule Message")
                    .setView(scrollView)
                    .setPositiveButton("Schedule", null)
                    .setNegativeButton("Cancel", null)
                    .create();

            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String message = messageInput.getText().toString().trim();
                    if (TextUtils.isEmpty(message)) {
                        Utils.showToast("Message cannot be empty", Toast.LENGTH_SHORT);
                        return;
                    }

                    long scheduledTime;
                    int checkedId = delayGroup.getCheckedRadioButtonId();
                    View selected = delayGroup.findViewById(checkedId);
                    int delayMins = (int) selected.getTag();

                    if (delayMins == -1) {
                        // Custom logic
                        Calendar cal = Calendar.getInstance();
                        cal.set(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                                timePicker.getHour(), timePicker.getMinute(), 0);
                        scheduledTime = cal.getTimeInMillis();
                    } else {
                        scheduledTime = System.currentTimeMillis() + (delayMins * 60 * 1000L);
                    }

                    if (scheduledTime <= System.currentTimeMillis()) {
                        Utils.showToast("Please select a future time", Toast.LENGTH_SHORT);
                        return;
                    }

                    saveScheduledMessage(currentUserJid.getPhoneRawString(), contactName, message, scheduledTime, isBusiness);
                    dialog.dismiss();
                });
            });

            dialog.show();
        } catch (Throwable e) {
            Utils.showToast("Error: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    private void saveScheduledMessage(String jid, String name, String message, long scheduledTime, boolean isBusiness) {
        try {
            Intent intent = new Intent("com.wmods.wppenhacer.SCHEDULE_MESSAGE");
            intent.putExtra("jid", jid);
            intent.putExtra("name", name);
            intent.putExtra("message", message);
            intent.putExtra("scheduled_time", scheduledTime);
            intent.putExtra("whatsapp_type", isBusiness ? 1 : 0);
            
            // Explicitly target our app's receiver for reliability across processes
            intent.setComponent(new android.content.ComponentName("com.wmods.wppenhacer", 
                "com.wmods.wppenhacer.receivers.ScheduledMessageReceiver"));
            
            Utils.getApplication().sendBroadcast(intent);
            
            String formattedTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(scheduledTime));
            Utils.showToast("Scheduled for " + formattedTime, Toast.LENGTH_LONG);
        } catch (Exception e) {
            Utils.showToast("Error scheduling: " + e.getMessage(), Toast.LENGTH_SHORT);
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

    @NonNull
    @Override
    public String getPluginName() {
        return "Message Scheduler";
    }
}