package com.wmods.wppenhacer.activities;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class ScheduleMessageSheet extends BottomSheetDialogFragment {

    private static final String ARG_MESSAGE_ID = "message_id";
    private static final String ARG_PREFILL_JID = "prefill_jid";
    private static final String ARG_PREFILL_NAME = "prefill_name";
    private static final String ARG_PREFILL_MESSAGE = "prefill_message";
    
    // UI Components
    private TextInputEditText inputMessage, inputDate, inputTime;
    private AutoCompleteTextView dropdownRepeat, dropdownAppType;
    private ChipGroup chipGroupDays, chipGroupRecipients;
    private com.google.android.material.button.MaterialButton btnAttachMedia, btnSave, btnAddContact;
    private android.widget.TextView textMediaStatus;

    private ScheduledMessageStore messageStore;
    private ScheduledMessage editingMessage;
    private Calendar selectedTime;
    
    // Pre-fill data
    private String prefillJid;
    private String prefillName;
    private String prefillMessage;
    
    private final List<String> selectedContactNames = new ArrayList<>();
    private final List<String> selectedContactJids = new ArrayList<>();
    private String currentMediaPath;

    public interface OnMessageSavedListener {
        void onMessageSaved();
    }

    private OnMessageSavedListener listener;

    public static ScheduleMessageSheet newInstance(long messageId) {
        ScheduleMessageSheet fragment = new ScheduleMessageSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_MESSAGE_ID, messageId);
        fragment.setArguments(args);
        return fragment;
    }

    public static ScheduleMessageSheet newInstance(String jid, String name, String message) {
        ScheduleMessageSheet fragment = new ScheduleMessageSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_MESSAGE_ID, -1);
        args.putString(ARG_PREFILL_JID, jid);
        args.putString(ARG_PREFILL_NAME, name);
        args.putString(ARG_PREFILL_MESSAGE, message);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnMessageSavedListener(OnMessageSavedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        messageStore = ScheduledMessageStore.getInstance(requireContext());
        selectedTime = Calendar.getInstance();
        
        if (getArguments() != null) {
            long messageId = getArguments().getLong(ARG_MESSAGE_ID, -1);
            if (messageId != -1) {
                editingMessage = messageStore.getMessage(messageId);
                if (editingMessage != null) {
                    selectedTime.setTimeInMillis(editingMessage.getScheduledTime());
                }
            } else {
                // Check if prefill
                prefillJid = getArguments().getString(ARG_PREFILL_JID);
                prefillName = getArguments().getString(ARG_PREFILL_NAME);
                prefillMessage = getArguments().getString(ARG_PREFILL_MESSAGE);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_create_edit_scheduled_message, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
        setupDropdowns();
        setupListeners();
        updateDateTimeDisplay();

        if (editingMessage != null) {
            populateFields();
            btnSave.setText("Update Schedule");
        } else if (prefillJid != null) {
            // New message with prefill
            if (prefillMessage != null) inputMessage.setText(prefillMessage);
            addRecipientChip(prefillName != null ? prefillName : prefillJid, prefillJid);
            selectedContactJids.add(prefillJid);
            selectedContactNames.add(prefillName != null ? prefillName : prefillJid);
        }
    }

    private void initializeViews(View view) {
        inputMessage = view.findViewById(R.id.input_message);
        inputDate = view.findViewById(R.id.input_date);
        inputTime = view.findViewById(R.id.input_time);
        dropdownRepeat = view.findViewById(R.id.dropdown_repeat);
        dropdownAppType = view.findViewById(R.id.dropdown_app_type);
        
        chipGroupDays = view.findViewById(R.id.chip_group_days);
        chipGroupRecipients = view.findViewById(R.id.chip_group_recipients);
        
        btnAttachMedia = view.findViewById(R.id.btn_attach_media);
        btnSave = view.findViewById(R.id.button_save);
        btnAddContact = view.findViewById(R.id.btn_add_contact);
        textMediaStatus = view.findViewById(R.id.text_media_status);
    }

    private void setupDropdowns() {
        String[] repeats = new String[]{"Once", "Daily", "Weekly", "Monthly", "Custom"};
        ArrayAdapter<String> adapterRepeat = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, repeats);
        dropdownRepeat.setAdapter(adapterRepeat);
        
        String[] apps = new String[]{"WhatsApp", "Business"};
        ArrayAdapter<String> adapterApp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, apps);
        dropdownAppType.setAdapter(adapterApp);
        
        // Defaults
        if (editingMessage == null) {
            dropdownRepeat.setText("Once", false);
            dropdownAppType.setText("WhatsApp", false);
        }
    }

    private void setupListeners() {
        dropdownRepeat.setOnItemClickListener((parent, view, position, id) -> {
            String selection = parent.getItemAtPosition(position).toString();
            chipGroupDays.setVisibility(selection.equals("Custom") ? View.VISIBLE : View.GONE);
        });

        btnAddContact.setOnClickListener(v -> pickContacts());
        
        inputDate.setOnClickListener(v -> showDatePicker());
        inputTime.setOnClickListener(v -> showTimePicker());
        
        btnAttachMedia.setOnClickListener(v -> mediaPickerLauncher.launch("image/*"));
        
        btnSave.setOnClickListener(v -> saveMessage());
    }
    
    private void pickContacts() {
        ContactPickerSheet sheet = new ContactPickerSheet();
        sheet.setOnContactsSelectedListener(contacts -> {
            for (com.wmods.wppenhacer.preference.ContactData contact : contacts) {
                if (!selectedContactJids.contains(contact.getJid())) {
                    addRecipientChip(contact.getDisplayName(), contact.getJid());
                    selectedContactJids.add(contact.getJid());
                    selectedContactNames.add(contact.getDisplayName());
                }
            }
        });
        sheet.show(getChildFragmentManager(), "ContactPickerSheet");
    }
    
    private void addRecipientChip(String name, String jid) {
        Chip chip = new Chip(requireContext());
        chip.setText(name);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            chipGroupRecipients.removeView(chip);
            int index = selectedContactJids.indexOf(jid);
            if (index != -1) {
                selectedContactJids.remove(index);
                // Name checks might be duplicate, so remove by index if synced
                if (index < selectedContactNames.size()) selectedContactNames.remove(index);
            }
        });
        chipGroupRecipients.addView(chip);
    }
    
    private final androidx.activity.result.ActivityResultLauncher<String> mediaPickerLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) processSelectedMedia(uri);
            });

    private void processSelectedMedia(android.net.Uri uri) {
        try {
            File destDir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "WaEnhancer");
            if (!destDir.exists()) destDir.mkdirs();
            String fileName = "sch_" + System.currentTimeMillis() + ".jpg";
            File destFile = new File(destDir, fileName);
            
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            currentMediaPath = destFile.getAbsolutePath();
            textMediaStatus.setText("Media attached");
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDateTimeDisplay() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        inputDate.setText(dateFormat.format(selectedTime.getTime()));
        inputTime.setText(timeFormat.format(selectedTime.getTime()));
    }

    private void showDatePicker() {
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            selectedTime.set(Calendar.YEAR, year);
            selectedTime.set(Calendar.MONTH, month);
            selectedTime.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            updateDateTimeDisplay();
        }, selectedTime.get(Calendar.YEAR), selectedTime.get(Calendar.MONTH), selectedTime.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(selectedTime.get(Calendar.HOUR_OF_DAY))
                .setMinute(selectedTime.get(Calendar.MINUTE))
                .setTitleText("Select Time")
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            selectedTime.set(Calendar.HOUR_OF_DAY, picker.getHour());
            selectedTime.set(Calendar.MINUTE, picker.getMinute());
            updateDateTimeDisplay();
        });
        picker.show(getChildFragmentManager(), "MaterialTimePicker");
    }

    private void populateFields() {
        inputMessage.setText(editingMessage.getMessage());
        currentMediaPath = editingMessage.getImagePath();
        if (currentMediaPath != null) textMediaStatus.setText("Media attached");

        // Repeat
        String repeatStr = "Once";
        int rType = editingMessage.getRepeatType();
        if (rType == ScheduledMessage.REPEAT_DAILY) repeatStr = "Daily";
        else if (rType == ScheduledMessage.REPEAT_WEEKLY) repeatStr = "Weekly";
        else if (rType == ScheduledMessage.REPEAT_MONTHLY) repeatStr = "Monthly";
        else if (rType == ScheduledMessage.REPEAT_CUSTOM_DAYS) {
            repeatStr = "Custom";
            chipGroupDays.setVisibility(View.VISIBLE);
            // Check days
            checkDayChip(R.id.chip_sun, 1);
            checkDayChip(R.id.chip_mon, 2);
            checkDayChip(R.id.chip_tue, 3);
            checkDayChip(R.id.chip_wed, 4);
            checkDayChip(R.id.chip_thu, 5);
            checkDayChip(R.id.chip_fri, 6);
            checkDayChip(R.id.chip_sat, 7);
        }
        dropdownRepeat.setText(repeatStr, false);

        // App
        dropdownAppType.setText(editingMessage.getWhatsappType() == ScheduledMessage.WHATSAPP_BUSINESS ? "Business" : "WhatsApp", false);

        // Recipients
        selectedContactJids.clear();
        selectedContactNames.clear();
        selectedContactJids.addAll(editingMessage.getContactJids());
        selectedContactNames.addAll(editingMessage.getContactNames());
        chipGroupRecipients.removeAllViews();
        for (int i = 0; i < selectedContactNames.size(); i++) {
            // Need jid for removal logic, assuming sync
            if (i < selectedContactJids.size()) {
                addRecipientChip(selectedContactNames.get(i), selectedContactJids.get(i));
            }
        }
    }
    
    private void checkDayChip(int chipId, int dayFlag) {
       Chip c = requireView().findViewById(chipId);
       if (c != null && editingMessage.getSelectedDays().contains(dayFlag)) {
           c.setChecked(true);
       }
    }

    private void saveMessage() {
        if (selectedContactJids.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a recipient", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = inputMessage.getText().toString().trim();
        if (msg.isEmpty() && currentMediaPath == null) {
            Toast.makeText(requireContext(), "Enter a message", Toast.LENGTH_SHORT).show();
            return;
        }
        
        int repeatType = ScheduledMessage.REPEAT_ONCE;
        String rStr = dropdownRepeat.getText().toString();
        if (rStr.equals("Daily")) repeatType = ScheduledMessage.REPEAT_DAILY;
        else if (rStr.equals("Weekly")) repeatType = ScheduledMessage.REPEAT_WEEKLY;
        else if (rStr.equals("Monthly")) repeatType = ScheduledMessage.REPEAT_MONTHLY;
        else if (rStr.equals("Custom")) repeatType = ScheduledMessage.REPEAT_CUSTOM_DAYS;
        
        int wType = dropdownAppType.getText().toString().equals("Business") ? ScheduledMessage.WHATSAPP_BUSINESS : ScheduledMessage.WHATSAPP_NORMAL;
        
        if (editingMessage == null) editingMessage = new ScheduledMessage();
        editingMessage.setContactJids(new ArrayList<>(selectedContactJids));
        editingMessage.setContactNames(new ArrayList<>(selectedContactNames));
        editingMessage.setMessage(msg);
        editingMessage.setScheduledTime(selectedTime.getTimeInMillis());
        editingMessage.setRepeatType(repeatType);
        editingMessage.setWhatsappType(wType);
        editingMessage.setImagePath(currentMediaPath);
        
        if (repeatType == ScheduledMessage.REPEAT_CUSTOM_DAYS) {
            int days = 0;
            if (((Chip)requireView().findViewById(R.id.chip_sun)).isChecked()) days |= ScheduledMessage.DAY_SUNDAY;
            if (((Chip)requireView().findViewById(R.id.chip_mon)).isChecked()) days |= ScheduledMessage.DAY_MONDAY;
            if (((Chip)requireView().findViewById(R.id.chip_tue)).isChecked()) days |= ScheduledMessage.DAY_TUESDAY;
            if (((Chip)requireView().findViewById(R.id.chip_wed)).isChecked()) days |= ScheduledMessage.DAY_WEDNESDAY;
            if (((Chip)requireView().findViewById(R.id.chip_thu)).isChecked()) days |= ScheduledMessage.DAY_THURSDAY;
            if (((Chip)requireView().findViewById(R.id.chip_fri)).isChecked()) days |= ScheduledMessage.DAY_FRIDAY;
            if (((Chip)requireView().findViewById(R.id.chip_sat)).isChecked()) days |= ScheduledMessage.DAY_SATURDAY;
            editingMessage.setRepeatDays(days);
        } else {
             editingMessage.setRepeatDays(0);
        }
        
        editingMessage.setActive(true);
        editingMessage.setSent(false);
        
        if (editingMessage.getId() != 0) messageStore.updateMessage(editingMessage);
        else messageStore.insertMessage(editingMessage);
        
        ScheduledMessageService.startService(requireContext());
        if (listener != null) listener.onMessageSaved();
        dismiss();
    }
}
