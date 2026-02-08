package com.wmods.wppenhacer.activities;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.db.AutoReplyDatabase;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class AutoReplyRuleSheet extends BottomSheetDialogFragment {

    private static final String ARG_RULE_ID = "rule_id";

    // UI Components
    private TextInputEditText inputPattern;
    private TextInputEditText inputReply;
    private TextInputEditText inputDelay;
    // private TextInputEditText inputSpecificJids; // Removed
    private TextInputEditText inputStartTime;
    private TextInputEditText inputEndTime;
    private AutoCompleteTextView dropdownMatchType;
    private AutoCompleteTextView dropdownTargetType;
    private com.google.android.material.switchmaterial.SwitchMaterial switchEnabled;
    private com.google.android.material.button.MaterialButton btnSave;
    private View layoutSpecificJids;
    private ChipGroup chipGroupSpecificJids;
    private com.google.android.material.button.MaterialButton btnPickContacts;

    private AutoReplyDatabase database;
    private AutoReplyDatabase.AutoReplyRule editingRule;
    private List<String> selectedSpecificJids = new ArrayList<>();

    public interface OnRuleSavedListener {
        void onRuleSaved();
    }

    private OnRuleSavedListener listener;

    public static AutoReplyRuleSheet newInstance(long ruleId) {
        AutoReplyRuleSheet fragment = new AutoReplyRuleSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_RULE_ID, ruleId);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnRuleSavedListener(OnRuleSavedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = AutoReplyDatabase.getInstance();

        if (getArguments() != null) {
            long ruleId = getArguments().getLong(ARG_RULE_ID, -1);
            if (ruleId != -1) {
                editingRule = database.getRule(ruleId);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_auto_reply_rule, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
        setupDropdowns();
        setupListeners();

        if (editingRule != null) {
            populateFields();
            btnSave.setText(R.string.auto_reply_edit_rule);
        } else {
            btnSave.setText(R.string.auto_reply_add_rule);
        }
    }

    private void initializeViews(View view) {
        inputPattern = view.findViewById(R.id.input_pattern);
        inputReply = view.findViewById(R.id.input_reply);
        inputDelay = view.findViewById(R.id.input_delay);
        // inputSpecificJids = view.findViewById(R.id.input_specific_jids);
        inputStartTime = view.findViewById(R.id.input_start_time);
        inputEndTime = view.findViewById(R.id.input_end_time);
        dropdownMatchType = view.findViewById(R.id.dropdown_match_type);
        dropdownTargetType = view.findViewById(R.id.dropdown_target_type);
        switchEnabled = view.findViewById(R.id.switch_enabled);
        btnSave = view.findViewById(R.id.button_save);
        layoutSpecificJids = view.findViewById(R.id.layout_specific_jids);
        chipGroupSpecificJids = view.findViewById(R.id.chip_group_specific_jids);
        btnPickContacts = view.findViewById(R.id.button_pick_contacts);
    }

    private void setupDropdowns() {
        String[] matchTypes = new String[] {
                getString(R.string.auto_reply_match_all),
                getString(R.string.auto_reply_match_contains),
                getString(R.string.auto_reply_match_exact),
                getString(R.string.auto_reply_match_regex)
        };
        ArrayAdapter<String> adapterMatch = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, matchTypes);
        dropdownMatchType.setAdapter(adapterMatch);

        String[] targetTypes = new String[] {
                getString(R.string.auto_reply_target_all),
                getString(R.string.auto_reply_target_contacts),
                getString(R.string.auto_reply_target_groups),
                getString(R.string.auto_reply_target_specific)
        };
        ArrayAdapter<String> adapterTarget = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, targetTypes);
        dropdownTargetType.setAdapter(adapterTarget);

        // Defaults
        if (editingRule == null) {
            dropdownMatchType.setText(matchTypes[1], false); // Contains
            dropdownTargetType.setText(targetTypes[0], false); // All
            switchEnabled.setChecked(true);
        }
    }

    private void setupListeners() {
        dropdownMatchType.setOnItemClickListener((parent, view, position, id) -> {
            // Show/hide pattern input based on match type
            boolean isAllMessages = position == 0;
            inputPattern.setEnabled(!isAllMessages);
            if (isAllMessages) {
                inputPattern.setText("");
                inputPattern.setHint("(matches all messages)");
            } else {
                inputPattern.setHint(R.string.auto_reply_pattern_hint);
            }
        });

        dropdownTargetType.setOnItemClickListener((parent, view, position, id) -> {
            // Show/hide specific JIDs input
            boolean isSpecific = position == 3;
            layoutSpecificJids.setVisibility(isSpecific ? View.VISIBLE : View.GONE);
        });

        inputStartTime.setOnClickListener(v -> showTimePicker(true));
        inputEndTime.setOnClickListener(v -> showTimePicker(false));

        btnSave.setOnClickListener(v -> saveRule());

        btnPickContacts.setOnClickListener(v -> pickContacts());
    }

    private void pickContacts() {
        ContactPickerSheet sheet = new ContactPickerSheet();
        sheet.setOnContactsSelectedListener(contacts -> {
            for (com.wmods.wppenhacer.preference.ContactData contact : contacts) {
                if (!selectedSpecificJids.contains(contact.getJid())) {
                    addRecipientChip(contact.getDisplayName(), contact.getJid());
                    selectedSpecificJids.add(contact.getJid());
                }
            }
        });
        sheet.show(getChildFragmentManager(), "ContactPickerSheet");
    }

    private void addRecipientChip(String name, String jid) {
        Chip chip = new Chip(requireContext());
        chip.setText(name.isEmpty() ? jid : name);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            chipGroupSpecificJids.removeView(chip);
            selectedSpecificJids.remove(jid);
        });
        chipGroupSpecificJids.addView(chip);
    }

    private void showTimePicker(boolean isStartTime) {
        Calendar now = Calendar.getInstance();
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(now.get(Calendar.HOUR_OF_DAY))
                .setMinute(now.get(Calendar.MINUTE))
                .setTitleText(isStartTime ? R.string.auto_reply_start_time : R.string.auto_reply_end_time)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            String time = String.format(Locale.getDefault(), "%02d:%02d", picker.getHour(), picker.getMinute());
            if (isStartTime) {
                inputStartTime.setText(time);
            } else {
                inputEndTime.setText(time);
            }
        });
        picker.show(getChildFragmentManager(), "TimePicker");
    }

    private void populateFields() {
        if (editingRule == null)
            return;

        // Pattern
        if (editingRule.pattern != null) {
            inputPattern.setText(editingRule.pattern);
        }

        // Reply
        if (editingRule.replyMessage != null) {
            inputReply.setText(editingRule.replyMessage);
        }

        // Match type
        String matchTypeStr = getMatchTypeString(editingRule.matchType);
        dropdownMatchType.setText(matchTypeStr, false);
        inputPattern.setEnabled(editingRule.matchType != AutoReplyDatabase.MatchType.ALL);

        // Target type
        String targetTypeStr = getTargetTypeString(editingRule.targetType);
        dropdownTargetType.setText(targetTypeStr, false);
        layoutSpecificJids.setVisibility(
                editingRule.targetType == AutoReplyDatabase.TargetType.SPECIFIC ? View.VISIBLE : View.GONE);

        // Specific JIDs
        if (editingRule.specificJids != null && !editingRule.specificJids.isEmpty()) {
            String[] jids = editingRule.specificJids.split(",");
            for (String jid : jids) {
                jid = jid.trim();
                if (!jid.isEmpty()) {
                    selectedSpecificJids.add(jid);
                    // We don't have names here easily, so use JID as name or a placeholder
                    addRecipientChip(jid, jid);
                }
            }
        }

        // Delay
        if (editingRule.delaySeconds > 0) {
            inputDelay.setText(String.valueOf(editingRule.delaySeconds));
        }

        // Time window
        if (editingRule.startTime != null) {
            inputStartTime.setText(editingRule.startTime);
        }
        if (editingRule.endTime != null) {
            inputEndTime.setText(editingRule.endTime);
        }

        // Enabled
        switchEnabled.setChecked(editingRule.enabled);
    }

    private String getMatchTypeString(AutoReplyDatabase.MatchType matchType) {
        switch (matchType) {
            case ALL:
                return getString(R.string.auto_reply_match_all);
            case CONTAINS:
                return getString(R.string.auto_reply_match_contains);
            case EXACT:
                return getString(R.string.auto_reply_match_exact);
            case REGEX:
                return getString(R.string.auto_reply_match_regex);
            default:
                return getString(R.string.auto_reply_match_contains);
        }
    }

    private String getTargetTypeString(AutoReplyDatabase.TargetType targetType) {
        switch (targetType) {
            case ALL:
                return getString(R.string.auto_reply_target_all);
            case CONTACTS:
                return getString(R.string.auto_reply_target_contacts);
            case GROUPS:
                return getString(R.string.auto_reply_target_groups);
            case SPECIFIC:
                return getString(R.string.auto_reply_target_specific);
            default:
                return getString(R.string.auto_reply_target_all);
        }
    }

    private AutoReplyDatabase.MatchType parseMatchType(String text) {
        if (text.equals(getString(R.string.auto_reply_match_all))) {
            return AutoReplyDatabase.MatchType.ALL;
        } else if (text.equals(getString(R.string.auto_reply_match_exact))) {
            return AutoReplyDatabase.MatchType.EXACT;
        } else if (text.equals(getString(R.string.auto_reply_match_regex))) {
            return AutoReplyDatabase.MatchType.REGEX;
        } else {
            return AutoReplyDatabase.MatchType.CONTAINS;
        }
    }

    private AutoReplyDatabase.TargetType parseTargetType(String text) {
        if (text.equals(getString(R.string.auto_reply_target_contacts))) {
            return AutoReplyDatabase.TargetType.CONTACTS;
        } else if (text.equals(getString(R.string.auto_reply_target_groups))) {
            return AutoReplyDatabase.TargetType.GROUPS;
        } else if (text.equals(getString(R.string.auto_reply_target_specific))) {
            return AutoReplyDatabase.TargetType.SPECIFIC;
        } else {
            return AutoReplyDatabase.TargetType.ALL;
        }
    }

    private void saveRule() {
        AutoReplyDatabase.MatchType matchType = parseMatchType(dropdownMatchType.getText().toString());

        String pattern = inputPattern.getText() != null ? inputPattern.getText().toString().trim() : "";
        String reply = inputReply.getText() != null ? inputReply.getText().toString().trim() : "";

        // Validation
        if (matchType != AutoReplyDatabase.MatchType.ALL && TextUtils.isEmpty(pattern)) {
            inputPattern.setError("Pattern is required");
            return;
        }

        if (TextUtils.isEmpty(reply)) {
            inputReply.setError("Reply message is required");
            return;
        }

        AutoReplyDatabase.TargetType targetType = parseTargetType(dropdownTargetType.getText().toString());

        String specificJids = null;
        if (targetType == AutoReplyDatabase.TargetType.SPECIFIC) {
            specificJids = android.text.TextUtils.join(",", selectedSpecificJids);
            if (TextUtils.isEmpty(specificJids)) {
                Toast.makeText(requireContext(), "Please select at least one contact", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        int delaySeconds = 0;
        String delayStr = inputDelay.getText() != null ? inputDelay.getText().toString().trim() : "";
        if (!TextUtils.isEmpty(delayStr)) {
            try {
                delaySeconds = Integer.parseInt(delayStr);
            } catch (NumberFormatException e) {
                inputDelay.setError("Invalid delay value");
                return;
            }
        }

        String startTime = inputStartTime.getText() != null ? inputStartTime.getText().toString().trim() : null;
        String endTime = inputEndTime.getText() != null ? inputEndTime.getText().toString().trim() : null;

        // Create or update rule
        if (editingRule == null) {
            editingRule = new AutoReplyDatabase.AutoReplyRule();
        }

        editingRule.pattern = pattern;
        editingRule.replyMessage = reply;
        editingRule.matchType = matchType;
        editingRule.targetType = targetType;
        editingRule.specificJids = specificJids;
        editingRule.delaySeconds = delaySeconds;
        editingRule.startTime = startTime;
        editingRule.endTime = endTime;
        editingRule.enabled = switchEnabled.isChecked();

        if (editingRule.id > 0) {
            database.updateRule(editingRule);
        } else {
            database.insertRule(editingRule);
        }

        Toast.makeText(requireContext(), R.string.auto_reply_rule_saved, Toast.LENGTH_SHORT).show();

        if (listener != null) {
            listener.onRuleSaved();
        }
        dismiss();
    }
}
