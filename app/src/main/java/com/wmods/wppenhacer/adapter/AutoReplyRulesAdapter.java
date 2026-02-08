package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.db.AutoReplyDatabase;

import java.util.ArrayList;
import java.util.List;

public class AutoReplyRulesAdapter extends RecyclerView.Adapter<AutoReplyRulesAdapter.RuleViewHolder> {

    private final Context context;
    private final OnRuleActionListener listener;
    private List<AutoReplyDatabase.AutoReplyRule> rules = new ArrayList<>();

    public interface OnRuleActionListener {
        void onEdit(AutoReplyDatabase.AutoReplyRule rule);
        void onDelete(AutoReplyDatabase.AutoReplyRule rule);
        void onToggleActive(AutoReplyDatabase.AutoReplyRule rule, boolean isEnabled);
    }

    public AutoReplyRulesAdapter(Context context, OnRuleActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setRules(List<AutoReplyDatabase.AutoReplyRule> rules) {
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_auto_reply_rule, parent, false);
        return new RuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RuleViewHolder holder, int position) {
        holder.bind(rules.get(position));
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    class RuleViewHolder extends RecyclerView.ViewHolder {
        View viewStatusStrip;
        TextView textPattern;
        TextView textReply;
        TextView textMatchType;
        TextView textTarget;
        TextView textTimeWindow;
        SwitchMaterial switchEnabled;

        RuleViewHolder(View itemView) {
            super(itemView);
            viewStatusStrip = itemView.findViewById(R.id.view_status_strip);
            textPattern = itemView.findViewById(R.id.text_pattern);
            textReply = itemView.findViewById(R.id.text_reply);
            textMatchType = itemView.findViewById(R.id.text_match_type);
            textTarget = itemView.findViewById(R.id.text_target);
            textTimeWindow = itemView.findViewById(R.id.text_time_window);
            switchEnabled = itemView.findViewById(R.id.switch_enabled);
        }

        void bind(final AutoReplyDatabase.AutoReplyRule rule) {
            // Pattern
            String patternText = getMatchTypeLabel(rule.matchType);
            if (rule.matchType != AutoReplyDatabase.MatchType.ALL) {
                patternText += ": \"" + rule.pattern + "\"";
            }
            textPattern.setText(patternText);

            // Reply preview
            String replyPreview = rule.replyMessage;
            if (replyPreview.length() > 50) {
                replyPreview = replyPreview.substring(0, 47) + "...";
            }
            textReply.setText("→ " + replyPreview);

            // Match type badge
            textMatchType.setText(getMatchTypeLabel(rule.matchType));

            // Target
            textTarget.setText(getTargetLabel(rule.targetType));

            // Time window
            if (rule.startTime != null && rule.endTime != null && 
                !rule.startTime.isEmpty() && !rule.endTime.isEmpty()) {
                textTimeWindow.setVisibility(View.VISIBLE);
                textTimeWindow.setText("⏰ " + rule.startTime + " - " + rule.endTime);
            } else {
                textTimeWindow.setVisibility(View.GONE);
            }

            // Delay indicator
            if (rule.delaySeconds > 0) {
                String delayText = " (delay: " + rule.delaySeconds + "s)";
                textReply.append(delayText);
            }

            // Status strip color
            int statusColor = rule.enabled
                    ? ContextCompat.getColor(context, R.color.status_active)
                    : ContextCompat.getColor(context, R.color.status_inactive);
            viewStatusStrip.setBackgroundColor(statusColor);

            // Switch
            switchEnabled.setOnCheckedChangeListener(null);
            switchEnabled.setChecked(rule.enabled);
            switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> 
                    listener.onToggleActive(rule, isChecked));

            // Click listeners
            itemView.setOnClickListener(v -> listener.onEdit(rule));
            itemView.setOnLongClickListener(v -> {
                listener.onDelete(rule);
                return true;
            });
        }

        private String getMatchTypeLabel(AutoReplyDatabase.MatchType matchType) {
            switch (matchType) {
                case ALL:
                    return context.getString(R.string.auto_reply_match_all);
                case CONTAINS:
                    return context.getString(R.string.auto_reply_match_contains);
                case EXACT:
                    return context.getString(R.string.auto_reply_match_exact);
                case REGEX:
                    return context.getString(R.string.auto_reply_match_regex);
                default:
                    return "Unknown";
            }
        }

        private String getTargetLabel(AutoReplyDatabase.TargetType targetType) {
            switch (targetType) {
                case ALL:
                    return context.getString(R.string.auto_reply_target_all);
                case CONTACTS:
                    return context.getString(R.string.auto_reply_target_contacts);
                case GROUPS:
                    return context.getString(R.string.auto_reply_target_groups);
                case SPECIFIC:
                    return context.getString(R.string.auto_reply_target_specific);
                default:
                    return "Unknown";
            }
        }
    }
}
