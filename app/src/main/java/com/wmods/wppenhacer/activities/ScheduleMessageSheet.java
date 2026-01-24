package com.wmods.wppenhacer.activities;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.SelectedContactsAdapter;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;
import com.wmods.wppenhacer.xposed.bridge.client.ProviderClient;
import com.wmods.wppenhacer.xposed.bridge.client.BridgeClient;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ScheduleMessageSheet extends BottomSheetDialogFragment {

    private static final String ARG_MESSAGE_ID = "message_id";

    private RecyclerView recyclerSelectedContacts;
    private SelectedContactsAdapter selectedContactsAdapter;
    private EditText editMessage;
    private Button buttonSelectContacts, buttonSave;
    private RadioGroup radioGroupRepeat, radioGroupWhatsapp;
    private ChipGroup chipGroupDays;
    private TextView textDate, textTime, textSheetTitle;
    private View layoutDate, layoutTime;

    private ScheduledMessageStore messageStore;
    private ScheduledMessage editingMessage;
    private Calendar selectedTime;
    private final List<String> selectedContactNames = new ArrayList<>();
    private final List<String> selectedContactJids = new ArrayList<>();

    // Status UI
    private com.google.android.material.card.MaterialCardView cardBridgeStatus;
    private android.widget.ImageView imageStatusIcon;
    private TextView textStatusMessage;
    private com.google.android.material.progressindicator.CircularProgressIndicator statusProgress;
    private com.google.android.material.button.MaterialButton buttonRetryBridge;

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
        setupListeners(view);
        initializeBridge();

        if (editingMessage != null) {
            populateFields(view);
            textSheetTitle.setText("Edit Schedule");
        }
    }

    private void initializeViews(View view) {
        recyclerSelectedContacts = view.findViewById(R.id.recycler_selected_contacts);
        selectedContactsAdapter = new SelectedContactsAdapter(selectedContactNames, selectedContactJids, position -> {
            selectedContactNames.remove(position);
            selectedContactJids.remove(position);
            selectedContactsAdapter.notifyItemRemoved(position);
        });
        recyclerSelectedContacts.setAdapter(selectedContactsAdapter);
        editMessage = view.findViewById(R.id.edit_message);
        buttonSelectContacts = view.findViewById(R.id.button_select_contacts);
        buttonSave = view.findViewById(R.id.button_save);
        radioGroupRepeat = view.findViewById(R.id.radio_group_repeat);
        radioGroupWhatsapp = view.findViewById(R.id.radio_group_whatsapp);
        chipGroupDays = view.findViewById(R.id.chip_group_days);
        textDate = view.findViewById(R.id.text_date);
        textTime = view.findViewById(R.id.text_time);
        textSheetTitle = view.findViewById(R.id.text_sheet_title);
        layoutDate = view.findViewById(R.id.layout_date);
        layoutTime = view.findViewById(R.id.layout_time);
        
        updateDateTimeDisplay();

        cardBridgeStatus = view.findViewById(R.id.card_bridge_status);
        imageStatusIcon = view.findViewById(R.id.image_status_icon);
        textStatusMessage = view.findViewById(R.id.text_status_message);
        statusProgress = view.findViewById(R.id.status_progress);
        buttonRetryBridge = view.findViewById(R.id.button_retry_bridge);
        
        view.findViewById(R.id.button_close).setOnClickListener(v -> dismiss());
    }

    private void setupListeners(View view) {
        radioGroupRepeat.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isCustom = checkedId == R.id.radio_custom_days;
            chipGroupDays.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        });

        buttonSelectContacts.setOnClickListener(v -> pickContacts());
        buttonSave.setOnClickListener(v -> saveMessage());
        layoutDate.setOnClickListener(v -> showDatePicker());
        layoutTime.setOnClickListener(v -> showTimePicker());
        buttonRetryBridge.setOnClickListener(v -> initializeBridge());
    }

    private void updateDateTimeDisplay() {
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault());
        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
        textDate.setText(dateFormat.format(selectedTime.getTime()));
        textTime.setText(timeFormat.format(selectedTime.getTime()));
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

    private void initializeBridge() {
        setBridgeStatus(BridgeState.LOADING);
        new Thread(() -> {
            boolean connected = false;
            String error = "Connection failed";
            if (WppCore.client != null && WppCore.client.getService() != null && WppCore.client.getService().asBinder().pingBinder()) {
                connected = true;
            } else {
                try {
                    ProviderClient pc = new ProviderClient(requireContext());
                    if (Boolean.TRUE.equals(pc.connect().get(10, TimeUnit.SECONDS))) {
                        WppCore.client = pc;
                        connected = true;
                    }
                } catch (Exception e) {
                    error = "Provider failed: " + e.getMessage();
                }
                if (!connected) {
                    try {
                        BridgeClient bc = new BridgeClient(requireContext());
                        if (Boolean.TRUE.equals(bc.connect().get(10, TimeUnit.SECONDS))) {
                            WppCore.client = bc;
                            connected = true;
                        }
                    } catch (Exception e) {
                        error = "Fallback failed: " + e.getMessage();
                    }
                }
            }
            final boolean finalConnected = connected;
            final String finalError = error;
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    if (finalConnected) setBridgeStatus(BridgeState.CONNECTED);
                    else setBridgeStatus(BridgeState.DISCONNECTED, finalError);
                });
            }
        }).start();
    }

    private enum BridgeState { LOADING, CONNECTED, DISCONNECTED }

    private void setBridgeStatus(BridgeState state) { setBridgeStatus(state, null); }

    private void setBridgeStatus(BridgeState state, String message) {
        if (!isAdded()) return;
        if (state == BridgeState.LOADING) {
            statusProgress.setVisibility(View.VISIBLE);
            imageStatusIcon.setVisibility(View.GONE);
            textStatusMessage.setText("Connecting...");
            buttonRetryBridge.setVisibility(View.GONE);
        } else if (state == BridgeState.CONNECTED) {
            statusProgress.setVisibility(View.GONE);
            imageStatusIcon.setVisibility(View.VISIBLE);
            imageStatusIcon.setImageDrawable(requireContext().getDrawable(android.R.drawable.presence_online));
            imageStatusIcon.setColorFilter(requireContext().getColor(R.color.status_active));
            textStatusMessage.setText("Connected");
            buttonRetryBridge.setVisibility(View.GONE);
        } else {
            statusProgress.setVisibility(View.GONE);
            imageStatusIcon.setVisibility(View.VISIBLE);
            imageStatusIcon.setImageDrawable(requireContext().getDrawable(android.R.drawable.stat_notify_error));
            imageStatusIcon.setColorFilter(requireContext().getColor(R.color.status_inactive));
            textStatusMessage.setText(message != null ? message : "Disconnected");
            buttonRetryBridge.setVisibility(View.VISIBLE);
        }
    }

    private void pickContacts() {
        ContactPickerSheet sheet = new ContactPickerSheet();
        sheet.setOnContactsSelectedListener(contacts -> {
            for (com.wmods.wppenhacer.preference.ContactData contact : contacts) {
                if (!selectedContactJids.contains(contact.getJid())) {
                    selectedContactNames.add(contact.getDisplayName());
                    selectedContactJids.add(contact.getJid());
                }
            }
            selectedContactsAdapter.notifyDataSetChanged();
        });
        sheet.show(getChildFragmentManager(), "ContactPickerSheet");
    }

    private void populateFields(View view) {
        selectedContactNames.addAll(editingMessage.getContactNames());
        selectedContactJids.addAll(editingMessage.getContactJids());
        selectedContactsAdapter.notifyDataSetChanged();
        editMessage.setText(editingMessage.getMessage());
        int repeat = editingMessage.getRepeatType();
        if (repeat == ScheduledMessage.REPEAT_ONCE) radioGroupRepeat.check(R.id.radio_once);
        else if (repeat == ScheduledMessage.REPEAT_DAILY) radioGroupRepeat.check(R.id.radio_daily);
        else if (repeat == ScheduledMessage.REPEAT_WEEKLY) radioGroupRepeat.check(R.id.radio_weekly);
        else if (repeat == ScheduledMessage.REPEAT_MONTHLY) radioGroupRepeat.check(R.id.radio_monthly);
        else if (repeat == ScheduledMessage.REPEAT_CUSTOM_DAYS) {
            radioGroupRepeat.check(R.id.radio_custom_days);
            chipGroupDays.setVisibility(View.VISIBLE);
            for (int day : editingMessage.getSelectedDays()) {
                Chip chip = view.findViewById(getChipIdForDay(day));
                if (chip != null) chip.setChecked(true);
            }
        }
        if (editingMessage.getWhatsappType() == ScheduledMessage.WHATSAPP_BUSINESS) radioGroupWhatsapp.check(R.id.radio_whatsapp_business);
        else radioGroupWhatsapp.check(R.id.radio_whatsapp_normal);
    }

    private int getChipIdForDay(int day) {
        switch (day) {
            case 1: return R.id.chip_sunday;
            case 2: return R.id.chip_monday;
            case 3: return R.id.chip_tuesday;
            case 4: return R.id.chip_wednesday;
            case 5: return R.id.chip_thursday;
            case 6: return R.id.chip_friday;
            case 7: return R.id.chip_saturday;
            default: return 0;
        }
    }

    private void saveMessage() {
        if (selectedContactJids.isEmpty()) {
            Toast.makeText(requireContext(), "Select contacts", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = editMessage.getText().toString().trim();
        if (msg.isEmpty()) {
            Toast.makeText(requireContext(), "Enter message", Toast.LENGTH_SHORT).show();
            return;
        }
        int repeat = getRepeatTypeFromRadio();
        long time = selectedTime.getTimeInMillis();
        if (repeat == ScheduledMessage.REPEAT_ONCE && time < System.currentTimeMillis()) {
            Toast.makeText(requireContext(), "Time is in past", Toast.LENGTH_SHORT).show();
            return;
        }
        int wType = radioGroupWhatsapp.getCheckedRadioButtonId() == R.id.radio_whatsapp_business ? ScheduledMessage.WHATSAPP_BUSINESS : ScheduledMessage.WHATSAPP_NORMAL;
        if (editingMessage == null) editingMessage = new ScheduledMessage();
        editingMessage.setContactJids(new ArrayList<>(selectedContactJids));
        editingMessage.setContactNames(new ArrayList<>(selectedContactNames));
        editingMessage.setMessage(msg);
        editingMessage.setScheduledTime(time);
        editingMessage.setRepeatType(repeat);
        editingMessage.setWhatsappType(wType);
        if (repeat == ScheduledMessage.REPEAT_CUSTOM_DAYS) editingMessage.setRepeatDays(getRepeatDaysFromChips());
        
        if (editingMessage.getId() != 0) messageStore.updateMessage(editingMessage);
        else messageStore.insertMessage(editingMessage);
        
        ScheduledMessageService.startService(requireContext());
        if (listener != null) listener.onMessageSaved();
        dismiss();
    }

    private int getRepeatTypeFromRadio() {
        int id = radioGroupRepeat.getCheckedRadioButtonId();
        if (id == R.id.radio_daily) return ScheduledMessage.REPEAT_DAILY;
        if (id == R.id.radio_weekly) return ScheduledMessage.REPEAT_WEEKLY;
        if (id == R.id.radio_monthly) return ScheduledMessage.REPEAT_MONTHLY;
        if (id == R.id.radio_custom_days) return ScheduledMessage.REPEAT_CUSTOM_DAYS;
        return ScheduledMessage.REPEAT_ONCE;
    }

    private int getRepeatDaysFromChips() {
        int days = 0;
        if (((Chip) requireView().findViewById(R.id.chip_sunday)).isChecked()) days |= ScheduledMessage.DAY_SUNDAY;
        if (((Chip) requireView().findViewById(R.id.chip_monday)).isChecked()) days |= ScheduledMessage.DAY_MONDAY;
        if (((Chip) requireView().findViewById(R.id.chip_tuesday)).isChecked()) days |= ScheduledMessage.DAY_TUESDAY;
        if (((Chip) requireView().findViewById(R.id.chip_wednesday)).isChecked()) days |= ScheduledMessage.DAY_WEDNESDAY;
        if (((Chip) requireView().findViewById(R.id.chip_thursday)).isChecked()) days |= ScheduledMessage.DAY_THURSDAY;
        if (((Chip) requireView().findViewById(R.id.chip_friday)).isChecked()) days |= ScheduledMessage.DAY_FRIDAY;
        if (((Chip) requireView().findViewById(R.id.chip_saturday)).isChecked()) days |= ScheduledMessage.DAY_SATURDAY;
        return days;
    }
}
