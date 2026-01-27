package com.wmods.wppenhacer.xposed.features.others;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
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

            // Container
            android.widget.LinearLayout contentLayout = new android.widget.LinearLayout(activity);
            contentLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding = Utils.dipToPixels(20);
            contentLayout.setPadding(padding, Utils.dipToPixels(8), padding, padding);

            // 1. Message Input Section
            TextView msgLabel = new TextView(activity);
            msgLabel.setText("Message to schedule");
            msgLabel.setTextSize(13);
            msgLabel.setAlpha(0.7f);
            msgLabel.setTextColor(DesignUtils.getPrimaryTextColor());
            contentLayout.addView(msgLabel);

            EditText messageInput = new EditText(activity);
            messageInput.setText(initialText);
            messageInput.setHint("Type message...");
            messageInput.setBackground(null);
            messageInput.setPadding(0, Utils.dipToPixels(8), 0, Utils.dipToPixels(16));
            contentLayout.addView(messageInput);

            // Divider
            View div1 = new View(activity);
            div1.setBackgroundColor(0x1A000000);
            android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(-1, Utils.dipToPixels(1));
            divLp.bottomMargin = Utils.dipToPixels(20);
            contentLayout.addView(div1, divLp);

            // 2. Delay Chips Section
            TextView delayLabel = new TextView(activity);
            delayLabel.setText("Quick Delay");
            delayLabel.setTextSize(13);
            delayLabel.setAlpha(0.7f);
            delayLabel.setTextColor(DesignUtils.getPrimaryTextColor());
            delayLabel.setPadding(0, 0, 0, Utils.dipToPixels(12));
            contentLayout.addView(delayLabel);

            android.widget.HorizontalScrollView hScroll = new android.widget.HorizontalScrollView(activity);
            hScroll.setHorizontalScrollBarEnabled(false);
            android.widget.LinearLayout chipContainer = new android.widget.LinearLayout(activity);
            chipContainer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            
            java.util.concurrent.atomic.AtomicInteger selectedMins = new java.util.concurrent.atomic.AtomicInteger(5);
            
            // Custom Picker Layout (init here to reference later)
            android.widget.LinearLayout customPickerGroup = new android.widget.LinearLayout(activity);
            customPickerGroup.setOrientation(android.widget.LinearLayout.VERTICAL);
            customPickerGroup.setVisibility(View.GONE);
            DatePicker datePicker = new DatePicker(activity);
            datePicker.setCalendarViewShown(false);
            TimePicker timePicker = new TimePicker(activity);
            timePicker.setIs24HourView(true);
            customPickerGroup.addView(datePicker);
            customPickerGroup.addView(timePicker);

            Object[][] opts = {{"5m", 5}, {"10m", 10}, {"30m", 30}, {"1h", 60}, {"Custom", -1}};
            for (Object[] opt : opts) {
                String label = (String) opt[0];
                int mins = (int) opt[1];
                View chip = createChip(activity, label, mins, chipContainer, selectedMins, customPickerGroup);
                chipContainer.addView(chip);
                if (mins == 5) updateChipStyle((TextView)chip, true); // default
            }
            hScroll.addView(chipContainer);
            contentLayout.addView(hScroll);
            
            // Add custom picker group after chips
            android.widget.LinearLayout.LayoutParams cpLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            cpLp.topMargin = Utils.dipToPixels(16);
            contentLayout.addView(customPickerGroup, cpLp);

            ScrollView scrollView = new ScrollScrollView(activity);
            scrollView.addView(contentLayout);

            com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp dialog = new com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp(activity);
            dialog.setTitle("Schedule Message");
            dialog.setView(scrollView);
            
            if (prefs.getBoolean("floatingmenu", false)) {
                dialog.setBlur(true);
            }

            dialog.setPositiveButton("Schedule", (d, w) -> {
                String message = messageInput.getText().toString().trim();
                if (TextUtils.isEmpty(message)) {
                    Utils.showToast("Message is empty", android.widget.Toast.LENGTH_SHORT);
                    return;
                }

                long scheduledTime;
                int mins = selectedMins.get();
                if (mins == -1) {
                    Calendar cal = Calendar.getInstance();
                    cal.set(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                            timePicker.getHour(), timePicker.getMinute(), 0);
                    scheduledTime = cal.getTimeInMillis();
                } else {
                    scheduledTime = System.currentTimeMillis() + (mins * 60 * 1000L);
                }

                if (scheduledTime <= System.currentTimeMillis()) {
                    Utils.showToast("Please select a future time", android.widget.Toast.LENGTH_SHORT);
                    return;
                }

                saveScheduledMessage(currentUserJid.getPhoneRawString(), contactName, message, scheduledTime, isBusiness);
            });
            dialog.setNegativeButton("Cancel", null);
            dialog.show();

        } catch (Throwable e) {
            XposedBridge.log(e);
            Utils.showToast("Error creating dialog", android.widget.Toast.LENGTH_SHORT);
        }
    }

    private View createChip(Context context, String label, int mins, android.widget.LinearLayout container, java.util.concurrent.atomic.AtomicInteger selectedMins, View customGroup) {
        TextView chip = new TextView(context);
        chip.setText(label);
        int ph = Utils.dipToPixels(16);
        int pv = Utils.dipToPixels(8);
        chip.setPadding(ph, pv, ph, pv);
        chip.setGravity(android.view.Gravity.CENTER);
        
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Utils.dipToPixels(8);
        chip.setLayoutParams(lp);

        updateChipStyle(chip, false);

        chip.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                updateChipStyle((TextView) container.getChildAt(i), false);
            }
            updateChipStyle(chip, true);
            selectedMins.set(mins);
            customGroup.setVisibility(mins == -1 ? View.VISIBLE : View.GONE);
        });
        return chip;
    }

    private void updateChipStyle(TextView chip, boolean selected) {
        int primary = DesignUtils.getUnSeenColor();
        int bg = selected ? primary : 0x0D000000;
        int txt = selected ? Color.WHITE : DesignUtils.getPrimaryTextColor();
        
        float r = Utils.dipToPixels(20);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(r);
        if (!selected) {
            gd.setStroke(Utils.dipToPixels(1), 0x1A000000);
        }
        chip.setBackground(gd);
        chip.setTextColor(txt);
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
    
    // Simple scrollview subclass to avoid conflicts if needed, or just use ScrollView
    private class ScrollScrollView extends ScrollView {
        public ScrollScrollView(Context context) { super(context); }
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