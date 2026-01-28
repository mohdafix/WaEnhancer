package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScheduledMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    private final Context context;
    private final OnMessageActionListener listener;
    private List<ListItem> items = new ArrayList<>();

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
        this.items.clear();
        if (messages != null && !messages.isEmpty()) {
            // Sort by contact name
            Collections.sort(messages, new Comparator<ScheduledMessage>() {
                @Override
                public int compare(ScheduledMessage o1, ScheduledMessage o2) {
                    return o1.getContactsDisplayString().compareToIgnoreCase(o2.getContactsDisplayString());
                }
            });

            String currentHeader = "";
            for (ScheduledMessage message : messages) {
                String header = message.getContactsDisplayString();
                if (!header.equals(currentHeader)) {
                    this.items.add(new HeaderItem(header));
                    currentHeader = header;
                }
                this.items.add(new MessageItem(message));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_message, parent, false);
            return new MessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((HeaderItem) items.get(position));
        } else if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).bind(((MessageItem) items.get(position)).message);
        }
    }

    @Override
    public int getItemCount() {
        return this.items.size();
    }

    // --- List Items ---

    private interface ListItem {
        int getType();
    }

    private static class HeaderItem implements ListItem {
        String title;
        HeaderItem(String title) { this.title = title; }
        @Override public int getType() { return VIEW_TYPE_HEADER; }
    }

    private static class MessageItem implements ListItem {
        ScheduledMessage message;
        MessageItem(ScheduledMessage message) { this.message = message; }
        @Override public int getType() { return VIEW_TYPE_ITEM; }
    }

    // --- ViewHolders ---

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView textTitle;

        HeaderViewHolder(View itemView) {
            super(itemView);
            textTitle = itemView.findViewById(R.id.text_header_title);
        }

        void bind(HeaderItem item) {
            textTitle.setText(item.title);
        }
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView textContactName;
        TextView textMessagePreview;
        TextView textScheduleTime;
        Chip chipStatus;
        SwitchMaterial switchActive;
        ImageButton buttonEdit;
        ImageButton buttonDelete;

        public MessageViewHolder(View itemView) {
            super(itemView);
            this.textContactName = itemView.findViewById(R.id.text_contact_name);
            this.textMessagePreview = itemView.findViewById(R.id.text_message_preview);
            this.textScheduleTime = itemView.findViewById(R.id.text_schedule_time);
            this.chipStatus = itemView.findViewById(R.id.chip_status);
            this.switchActive = itemView.findViewById(R.id.switch_active);
            this.buttonEdit = itemView.findViewById(R.id.button_edit);
            this.buttonDelete = itemView.findViewById(R.id.button_delete);
        }

        public void bind(final ScheduledMessage message) {
            // Use GONE instead of Invisible to remove the space if desired, or just keep it distinct
            // For now, we might want to hide the contact name in the card since it is in the header,
            // OR keep it. The user asked for grouping, so header is best.
            // Let's hide the contact name inside the card since it's redundant with the header.
            this.textContactName.setVisibility(View.GONE);
            
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
            this.switchActive.setEnabled(!(message.isSent() && message.getRepeatType() == 0));
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