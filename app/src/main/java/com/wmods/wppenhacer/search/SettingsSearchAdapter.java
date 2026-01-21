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

        String subtitle = TextUtils.isEmpty(entry.summary) ? entry.breadcrumb : entry.summary;
        holder.subtitle.setText(subtitle == null ? "" : subtitle);

        if (!TextUtils.isEmpty(entry.breadcrumb) && !TextUtils.isEmpty(entry.summary)) {
            holder.breadcrumb.setVisibility(View.VISIBLE);
            holder.breadcrumb.setText(entry.breadcrumb);
        } else {
            holder.breadcrumb.setVisibility(View.GONE);
        }

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

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            breadcrumb = itemView.findViewById(R.id.breadcrumb);
        }
    }
}
