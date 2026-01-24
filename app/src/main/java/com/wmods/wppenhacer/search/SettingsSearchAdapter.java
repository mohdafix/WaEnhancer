package com.wmods.wppenhacer.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SettingsSearchAdapter extends RecyclerView.Adapter<SettingsSearchAdapter.VH> {

    private final List<SettingsSearchIndex.Entry> data = new ArrayList<>();
    private final Consumer<SettingsSearchIndex.Entry> onClick;

    public SettingsSearchAdapter(Consumer<SettingsSearchIndex.Entry> onClick) {
        this.onClick = onClick;
    }

    public void submit(List<SettingsSearchIndex.Entry> newData) {
        data.clear();
        if (newData != null) {
            data.addAll(newData);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_settings_search_result, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        var entry = data.get(position);
        holder.title.setText(entry.title);

        String subtitle = entry.summary;
        if (TextUtils.isEmpty(subtitle)) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(subtitle);
        }

        if (!TextUtils.isEmpty(entry.breadcrumb)) {
            holder.breadcrumb.setVisibility(View.VISIBLE);
            holder.breadcrumb.setText(entry.breadcrumb);
        } else {
            holder.breadcrumb.setVisibility(View.GONE);
        }

        // Set Icon
        int iconRes = switch (entry.tabIndex) {
            case 0 -> R.drawable.ic_general;
            case 1 -> R.drawable.ic_privacy;
            case 3 -> R.drawable.ic_media;
            default -> R.drawable.ic_general;
        };
        
        if (entry.breadcrumb != null) {
            String lowerBc = entry.breadcrumb.toLowerCase();
            if (lowerBc.contains("media")) iconRes = R.drawable.ic_media;
            else if (lowerBc.contains("privacy")) iconRes = R.drawable.ic_privacy;
            else if (lowerBc.contains("conversation") || lowerBc.contains("chat")) iconRes = R.drawable.ic_general;
        }

        holder.icon.setImageResource(iconRes);
        holder.icon.setColorFilter(holder.itemView.getContext().getColor(R.color.text_primary));

        holder.itemView.setOnClickListener(v -> onClick.accept(entry));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView breadcrumb;
        final android.widget.ImageView icon;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            breadcrumb = itemView.findViewById(R.id.breadcrumb);
            icon = itemView.findViewById(R.id.image_icon);
        }
    }
}
