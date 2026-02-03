package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ScheduledMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    private final Context context;
    private final OnMessageActionListener listener;
    private List<ListItem> items = new ArrayList<>();
    
    // Data structures for grouping
    private Map<String, List<ScheduledMessage>> groupedData = new LinkedHashMap<>();
    private Map<String, Boolean> expandedState = new HashMap<>();

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
        groupedData.clear();
        if (messages != null && !messages.isEmpty()) {
            // Sort raw list first
            Collections.sort(messages, (o1, o2) -> o1.getContactsDisplayString().compareToIgnoreCase(o2.getContactsDisplayString()));

            // Group data
            for (ScheduledMessage message : messages) {
                String key = message.getContactsDisplayString();
                if (!groupedData.containsKey(key)) {
                    groupedData.put(key, new ArrayList<>());
                    
                    // Initialize state as collapsed by default
                    if (!expandedState.containsKey(key)) {
                        expandedState.put(key, false);
                    }
                }
                groupedData.get(key).add(message);
            }
        }
        rebuildList();
    }
    
    private void rebuildList() {
        items.clear();
        for (Map.Entry<String, List<ScheduledMessage>> entry : groupedData.entrySet()) {
            String headerTitle = entry.getKey();
            List<ScheduledMessage> msgs = entry.getValue();
            boolean isExpanded = expandedState.get(headerTitle) != null && expandedState.get(headerTitle);
            
            // Calculate Stats
            int active = 0;
            int sent = 0;
            for (ScheduledMessage m : msgs) {
                if (m.isActive() && !(m.isSent() && m.getRepeatType() == 0)) {
                    active++;
                } else {
                    sent++;
                }
            }
            
            items.add(new HeaderItem(headerTitle, headerTitle, isExpanded, active, sent));
            
            if (isExpanded) {
                for (ScheduledMessage msg : msgs) {
                    items.add(new MessageItem(msg));
                }
            }
        }
        notifyDataSetChanged();
    }
    
    private void toggleGroup(String headerTitle) {
        boolean current = expandedState.get(headerTitle) != null && expandedState.get(headerTitle);
        expandedState.put(headerTitle, !current);
        rebuildList();
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
            return new HeaderViewHolder(view, this::toggleGroup);
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
        String displayTitle;
        String key; 
        boolean isExpanded;
        int activeCount;
        int sentCount;
        
        HeaderItem(String displayTitle, String key, boolean isExpanded, int activeCount, int sentCount) { 
            this.displayTitle = displayTitle; 
            this.key = key;
            this.isExpanded = isExpanded;
            this.activeCount = activeCount;
            this.sentCount = sentCount;
        }
        @Override public int getType() { return VIEW_TYPE_HEADER; }
    }

    private static class MessageItem implements ListItem {
        ScheduledMessage message;
        MessageItem(ScheduledMessage message) { this.message = message; }
        @Override public int getType() { return VIEW_TYPE_ITEM; }
    }

    // --- ViewHolders ---
    
    interface OnHeaderClickListener {
        void onHeaderClick(String title);
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView textTitle;
        TextView textCountActive;
        TextView textCountSent;
        ImageView imageExpand;
        OnHeaderClickListener listener;
        String currentKey;

        HeaderViewHolder(View itemView, OnHeaderClickListener listener) {
            super(itemView);
            this.listener = listener;
            textTitle = itemView.findViewById(R.id.text_header_title);
            textCountActive = itemView.findViewById(R.id.text_count_active);
            textCountSent = itemView.findViewById(R.id.text_count_sent);
            imageExpand = itemView.findViewById(R.id.image_expand);
            
            itemView.setOnClickListener(v -> {
                if (currentKey != null) listener.onHeaderClick(currentKey);
            });
        }

        void bind(HeaderItem item) {
            currentKey = item.key;
            textTitle.setText(item.displayTitle);
            
            if (item.activeCount > 0) {
                textCountActive.setVisibility(View.VISIBLE);
                textCountActive.setText(item.activeCount + " Active");
            } else {
                textCountActive.setVisibility(View.GONE);
            }
            
            if (item.sentCount > 0) {
                textCountSent.setVisibility(View.VISIBLE);
                textCountSent.setText(item.sentCount + " Done");
            } else {
                textCountSent.setVisibility(View.GONE);
            }
            
            // Rotation animation for expand/collapse
            imageExpand.animate().rotation(item.isExpanded ? 180 : 0).setDuration(200).start();
        }
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        View viewStatusStrip;
        TextView textContactName;
        TextView textMessagePreview;
        TextView textScheduleTime;
        TextView textRepeatInfo;
        ImageView imageMediaIndicator;
        com.google.android.material.button.MaterialButton buttonToggleStatus;

        public MessageViewHolder(View itemView) {
            super(itemView);
            this.viewStatusStrip = itemView.findViewById(R.id.view_status_strip);
            this.textContactName = itemView.findViewById(R.id.text_contact_name);
            this.textMessagePreview = itemView.findViewById(R.id.text_message_preview);
            this.textScheduleTime = itemView.findViewById(R.id.text_schedule_time);
            this.textRepeatInfo = itemView.findViewById(R.id.text_repeat_info);
            this.imageMediaIndicator = itemView.findViewById(R.id.image_media_indicator);
            this.buttonToggleStatus = itemView.findViewById(R.id.button_toggle_status);
        }

        public void bind(final ScheduledMessage message) {
            // Title: Contact Names
            this.textContactName.setText(message.getContactsDisplayString());
            
            // Preview: Message
            this.textMessagePreview.setText(message.getMessage());
            
            // Date Time
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault());
            this.textScheduleTime.setText(dateFormat.format(new Date(message.getScheduledTime())));
            
            // Repeat Tag
            if (message.getRepeatType() != ScheduledMessage.REPEAT_ONCE) {
                this.textRepeatInfo.setVisibility(View.VISIBLE);
                this.textRepeatInfo.setText(message.getRepeatTypeString());
            } else {
                this.textRepeatInfo.setVisibility(View.GONE);
            }
            
            // Media Icon
            if (message.getImagePath() != null) {
                this.imageMediaIndicator.setVisibility(View.VISIBLE);
            } else {
                this.imageMediaIndicator.setVisibility(View.GONE);
            }
            
            // Toggle Logic (Icon Button)
            boolean isDone = message.isSent() && message.getRepeatType() == ScheduledMessage.REPEAT_ONCE;
            
            if (isDone) {
                 this.buttonToggleStatus.setIconResource(R.drawable.ic_round_check_circle_24);
                 this.buttonToggleStatus.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                 this.buttonToggleStatus.setEnabled(false);
            } else if (message.isActive()) {
                 this.buttonToggleStatus.setIconResource(R.drawable.ic_pause);
                 this.buttonToggleStatus.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                 this.buttonToggleStatus.setEnabled(true);
            } else {
                 this.buttonToggleStatus.setIconResource(R.drawable.ic_play);
                 this.buttonToggleStatus.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive)));
                 this.buttonToggleStatus.setEnabled(true);
            }
            // Clear text if any
            this.buttonToggleStatus.setText("");

            this.buttonToggleStatus.setOnClickListener(v -> {
                if (!isDone) {
                    ScheduledMessagesAdapter.this.listener.onToggleActive(message, !message.isActive());
                    // Optimistic update
                    boolean newActive = !message.isActive();
                    this.buttonToggleStatus.setIconResource(newActive ? R.drawable.ic_pause : R.drawable.ic_play);
                    this.buttonToggleStatus.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, 
                        newActive ? R.color.status_active : R.color.status_inactive)));
                }
            });
            
            // Status Strip Color
            int statusColor;
            if (message.isSent() && message.getRepeatType() == ScheduledMessage.REPEAT_ONCE) {
                statusColor = ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active); // Green for Sent/Done
            } else if (message.isActive()) {
                statusColor = ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_warning); // Orange/Yellow for Pending
            } else {
                statusColor = ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive); // Grey/Red for Inactive
            }
            this.viewStatusStrip.setBackgroundColor(statusColor);
            
            // Click Listeners
            this.itemView.setOnClickListener(v -> ScheduledMessagesAdapter.this.listener.onEdit(message));
            this.itemView.setOnLongClickListener(v -> {
                ScheduledMessagesAdapter.this.listener.onDelete(message);
                return true;
            });
        }
    }
}