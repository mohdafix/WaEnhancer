package com.wmods.wppenhacer.adapter;


import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScheduledMessagesAdapter extends RecyclerView.Adapter<ScheduledMessagesAdapter.ViewHolder> {
    private final Context context;
    private final OnMessageActionListener listener;
    private List<ScheduledMessage> messages = new ArrayList();

    public interface OnMessageActionListener {
        void onEdit(ScheduledMessage scheduledMessage);
        void onDelete(ScheduledMessage scheduledMessage);
        void onToggleActive(ScheduledMessage scheduledMessage, boolean z);
    }

    public ScheduledMessagesAdapter(Context context, OnMessageActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setMessages(List<ScheduledMessage> messages) {
        this.messages = messages;
        notifyDataSetChanged();
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_message, parent, false);
        return new ViewHolder(view);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public void onBindViewHolder(ViewHolder holder, int position) {
        ScheduledMessage message = this.messages.get(position);
        holder.bind(message);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemCount() {
        return this.messages.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        TextView textContactName;
        TextView textMessagePreview;
        TextView textScheduleTime;
        Chip chipStatus;
        SwitchMaterial switchActive;
        ImageButton buttonEdit;
        ImageButton buttonDelete;

        public ViewHolder(View itemView) {
            super(itemView);
            this.textContactName = (TextView) itemView.findViewById(R.id.text_contact_name);
            this.textMessagePreview = (TextView) itemView.findViewById(R.id.text_message_preview);
            this.textScheduleTime = (TextView) itemView.findViewById(R.id.text_schedule_time);
            this.chipStatus = (Chip) itemView.findViewById(R.id.chip_status);
            this.switchActive = (SwitchMaterial) itemView.findViewById(R.id.switch_active);
            this.buttonEdit = (ImageButton) itemView.findViewById(R.id.button_edit);
            this.buttonDelete = (ImageButton) itemView.findViewById(R.id.button_delete);
        }

        public void bind(final ScheduledMessage message) {
            this.textContactName.setText(message.getContactsDisplayString());
            this.textMessagePreview.setText(message.getMessage());
            StringBuilder timeInfo = new StringBuilder("Scheduled for: ");
            if (message.getRepeatType() != 0) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeInfo.append(timeFormat.format(new Date(message.getScheduledTime())));
                timeInfo.append(", ");
                timeInfo.append(message.getRepeatTypeString());
            } else {
                SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, ", Locale.getDefault());
                timeInfo.append(dateFormat.format(new Date(message.getScheduledTime())));
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeInfo.append(timeFormat.format(new Date(message.getScheduledTime())));
                timeInfo.append(", Once");
            }
            this.textScheduleTime.setText(timeInfo.toString());
            this.switchActive.setOnCheckedChangeListener(null);
            this.switchActive.setChecked(message.isActive());
            this.switchActive.setEnabled((message.isSent() && message.getRepeatType() == 0) ? false : true);
            this.switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> ScheduledMessagesAdapter.this.listener.onToggleActive(message, isChecked));
            this.buttonEdit.setOnClickListener(v -> ScheduledMessagesAdapter.this.listener.onEdit(message));
            this.buttonDelete.setOnClickListener(v -> ScheduledMessagesAdapter.this.listener.onDelete(message));
            if (message.isSent() && message.getRepeatType() == 0) {
                this.chipStatus.setText(R.string.schedule_sent);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            } else if (message.isActive()) {
                this.chipStatus.setText(R.string.schedule_pending);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_warning)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_warning)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            } else {
                this.chipStatus.setText(R.string.schedule_inactive);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            }
        }


    }
}