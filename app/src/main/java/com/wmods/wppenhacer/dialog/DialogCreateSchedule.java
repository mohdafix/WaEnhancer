package com.wmods.wppenhacer.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.databinding.DialogCreateScheduleBinding;
import com.wmods.wppenhacer.activities.ContactPickerActivity;
import com.wmods.wppenhacer.preference.ContactData;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class DialogCreateSchedule extends BottomSheetDialogFragment {

    public interface OnScheduleSavedListener {
        void onScheduleSaved();
    }

    private DialogCreateScheduleBinding binding;
    private ScheduledMessageStore messageStore;
    private ScheduledMessage editingMessage;
    private OnScheduleSavedListener listener;
    private Calendar selectedTime;
    private List<ContactData> selectedContacts = new ArrayList<>();

    public static DialogCreateSchedule newInstance() {
        return new DialogCreateSchedule();
    }

    public static DialogCreateSchedule newInstance(long messageId) {
        DialogCreateSchedule dialog = new DialogCreateSchedule();
        Bundle args = new Bundle();
        args.putLong("message_id", messageId);
        dialog.setArguments(args);
        return dialog;
    }

    public void setOnScheduleSavedListener(OnScheduleSavedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogCreateScheduleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        messageStore = ScheduledMessageStore.getInstance(requireContext());
        selectedTime = Calendar.getInstance();

        if (getArguments() != null) {
            long messageId = getArguments().getLong("message_id", -1);
            if (messageId != -1) {
                editingMessage = messageStore.getMessage(messageId);
                if (editingMessage != null) {
                    selectedTime.setTimeInMillis(editingMessage.getScheduledTime());
                }
            }
        }

        setupListeners();
        if (editingMessage != null) {
            populateFields();
        }
    }

    private void setupListeners() {
        binding.radioGroupRepeat.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isCustom = checkedId == R.id.radio_custom_days;
            binding.daysOfWeekContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        });

        binding.editDate.setOnClickListener(v -> showDatePicker());
        binding.editTime.setOnClickListener(v -> showTimePicker());

        binding.editContact.setOnClickListener(v -> showContactPicker());

        binding.getRoot().findViewById(R.id.saveButton).setOnClickListener(v -> saveMessage());
    }

    private void showDatePicker() {
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Date")
                .setSelection(selectedTime.getTimeInMillis())
                .build();
        datePicker.addOnPositiveButtonClickListener(selection -> {
            selectedTime.setTimeInMillis(selection);
            binding.editDate.setText(datePicker.getHeaderText());
        });
        datePicker.show(getParentFragmentManager(), "date_picker");
    }

    private void showTimePicker() {
        // Detect if we're in dark mode
        boolean isDarkMode = false;
        
        // Check system UI mode
        int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            isDarkMode = true;
        }
        
        // Also check if AMOLED/dark theme is enabled in preferences
        if (!isDarkMode) {
            try {
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
                isDarkMode = prefs.getBoolean("monet_theme", false);
            } catch (Exception ignored) {}
        }
        
        // Create MaterialTimePicker with proper dark theme
        MaterialTimePicker timePicker;
        
        if (isDarkMode) {
            // Wrap context with Material 3 dark theme
            android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(
                requireContext(),
                com.google.android.material.R.style.Theme_Material3_Dark
            );
            
            // Create a custom fragment with the themed context
            timePicker = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(selectedTime.get(Calendar.HOUR_OF_DAY))
                    .setMinute(selectedTime.get(Calendar.MINUTE))
                    .setTitleText("Select Time")
                    .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    .build();
            
            // Override the dialog theme
            try {
                timePicker.requireDialog().getWindow().setBackgroundDrawableResource(android.R.color.background_dark);
            } catch (Exception ignored) {}
        } else {
            timePicker = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(selectedTime.get(Calendar.HOUR_OF_DAY))
                    .setMinute(selectedTime.get(Calendar.MINUTE))
                    .setTitleText("Select Time")
                    .build();
        }
        
        timePicker.addOnPositiveButtonClickListener(dialog -> {
            selectedTime.set(Calendar.HOUR_OF_DAY, timePicker.getHour());
            selectedTime.set(Calendar.MINUTE, timePicker.getMinute());
            binding.editTime.setText(String.format("%02d:%02d", timePicker.getHour(), timePicker.getMinute()));
        });
        
        timePicker.show(getParentFragmentManager(), "time_picker");
    }

    private void showContactPicker() {
        Intent intent = new Intent(requireContext(), ContactPickerActivity.class);
        intent.putExtra("selected_contacts", new ArrayList<>(selectedContacts));
        startActivityForResult(intent, 100);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<ContactData> contacts = (ArrayList<ContactData>) data.getSerializableExtra("selected_contacts");
            if (contacts != null) {
                selectedContacts.clear();
                selectedContacts.addAll(contacts);
                updateContactDisplay();
            }
        }
    }

    private void updateContactDisplay() {
        StringBuilder sb = new StringBuilder();
        for (ContactData contact : selectedContacts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(contact.getDisplayName());
        }
        binding.editContact.setText(sb.toString());
    }

    private void populateFields() {
        selectedContacts.clear();
        List<String> jids = editingMessage.getContactJids();
        List<String> names = editingMessage.getContactNames();
        for (int i = 0; i < jids.size(); i++) {
            String name = i < names.size() ? names.get(i) : "";
            selectedContacts.add(new ContactData(name, jids.get(i)));
        }
        updateContactDisplay();

        binding.editMessage.setText(editingMessage.getMessage());
        // Set date and time from selectedTime

        int repeatType = editingMessage.getRepeatType();
        switch (repeatType) {
            case ScheduledMessage.REPEAT_ONCE:
                binding.radioOnce.setChecked(true);
                break;
            case ScheduledMessage.REPEAT_DAILY:
                binding.radioDaily.setChecked(true);
                break;
            case ScheduledMessage.REPEAT_WEEKLY:
                binding.radioWeekly.setChecked(true);
                break;
            case ScheduledMessage.REPEAT_MONTHLY:
                binding.radioMonthly.setChecked(true);
                break;
            case ScheduledMessage.REPEAT_CUSTOM_DAYS:
                binding.radioCustomDays.setChecked(true);
                binding.daysOfWeekContainer.setVisibility(View.VISIBLE);
                setChipsFromRepeatDays(editingMessage.getRepeatDays());
                break;
        }

        if (editingMessage.getWhatsappType() == ScheduledMessage.WHATSAPP_BUSINESS) {
            binding.radioWhatsappBusiness.setChecked(true);
        } else {
            binding.radioWhatsappNormal.setChecked(true);
        }
    }

    private void setChipsFromRepeatDays(int repeatDays) {
        binding.chipSunday.setChecked((repeatDays & ScheduledMessage.DAY_SUNDAY) != 0);
        binding.chipMonday.setChecked((repeatDays & ScheduledMessage.DAY_MONDAY) != 0);
        binding.chipTuesday.setChecked((repeatDays & ScheduledMessage.DAY_TUESDAY) != 0);
        binding.chipWednesday.setChecked((repeatDays & ScheduledMessage.DAY_WEDNESDAY) != 0);
        binding.chipThursday.setChecked((repeatDays & ScheduledMessage.DAY_THURSDAY) != 0);
        binding.chipFriday.setChecked((repeatDays & ScheduledMessage.DAY_FRIDAY) != 0);
        binding.chipSaturday.setChecked((repeatDays & ScheduledMessage.DAY_SATURDAY) != 0);
    }

    private void saveMessage() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(requireContext(), "Please select contacts", Toast.LENGTH_SHORT).show();
            return;
        }

        String message = binding.editMessage.getText().toString().trim();
        if (message.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedTime.getTimeInMillis() <= System.currentTimeMillis()) {
            Toast.makeText(requireContext(), "Please select a future time", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> contactJids = new ArrayList<>();
        List<String> contactNames = new ArrayList<>();
        for (ContactData contact : selectedContacts) {
            contactJids.add(contact.getJid());
            contactNames.add(contact.getName());
        }

        int repeatType = getRepeatType();
        int repeatDays = getRepeatDays();
        int whatsappType = binding.radioWhatsappBusiness.isChecked() ? ScheduledMessage.WHATSAPP_BUSINESS : ScheduledMessage.WHATSAPP_NORMAL;

        if (editingMessage != null) {
            editingMessage.setContactJids(contactJids);
            editingMessage.setContactNames(contactNames);
            editingMessage.setMessage(message);
            editingMessage.setScheduledTime(selectedTime.getTimeInMillis());
            editingMessage.setRepeatType(repeatType);
            editingMessage.setRepeatDays(repeatDays);
            editingMessage.setWhatsappType(whatsappType);
            messageStore.updateMessage(editingMessage);
        } else {
            ScheduledMessage newMessage = new ScheduledMessage();
            newMessage.setContactJids(contactJids);
            newMessage.setContactNames(contactNames);
            newMessage.setMessage(message);
            newMessage.setScheduledTime(selectedTime.getTimeInMillis());
            newMessage.setRepeatType(repeatType);
            newMessage.setRepeatDays(repeatDays);
            newMessage.setWhatsappType(whatsappType);
            messageStore.insertMessage(newMessage);
        }

        ScheduledMessageService.startService(requireContext());

        if (listener != null) {
            listener.onScheduleSaved();
        }
        dismiss();
    }

    private int getRepeatType() {
        if (binding.radioDaily.isChecked()) return ScheduledMessage.REPEAT_DAILY;
        if (binding.radioWeekly.isChecked()) return ScheduledMessage.REPEAT_WEEKLY;
        if (binding.radioMonthly.isChecked()) return ScheduledMessage.REPEAT_MONTHLY;
        if (binding.radioCustomDays.isChecked()) return ScheduledMessage.REPEAT_CUSTOM_DAYS;
        return ScheduledMessage.REPEAT_ONCE;
    }

    private int getRepeatDays() {
        int days = 0;
        if (binding.chipSunday.isChecked()) days |= ScheduledMessage.DAY_SUNDAY;
        if (binding.chipMonday.isChecked()) days |= ScheduledMessage.DAY_MONDAY;
        if (binding.chipTuesday.isChecked()) days |= ScheduledMessage.DAY_TUESDAY;
        if (binding.chipWednesday.isChecked()) days |= ScheduledMessage.DAY_WEDNESDAY;
        if (binding.chipThursday.isChecked()) days |= ScheduledMessage.DAY_THURSDAY;
        if (binding.chipFriday.isChecked()) days |= ScheduledMessage.DAY_FRIDAY;
        if (binding.chipSaturday.isChecked()) days |= ScheduledMessage.DAY_SATURDAY;
        return days;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
