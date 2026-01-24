package com.wmods.wppenhacer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.wmods.wppenhacer.R;
import java.util.List;

public class SelectedContactsAdapter extends RecyclerView.Adapter<SelectedContactsAdapter.ViewHolder> {

    public interface OnContactRemoveListener {
        void onRemove(int position);
    }

    public final List<String> contactNames;
    public final List<String> contactJids;
    private final OnContactRemoveListener listener;

    public SelectedContactsAdapter(List<String> contactNames, List<String> contactJids, OnContactRemoveListener listener) {
        this.contactNames = contactNames;
        this.contactJids = contactJids;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_selected_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.textContactName.setText(contactNames.get(position));
        holder.buttonRemove.setOnClickListener(v -> listener.onRemove(position));
    }

    @Override
    public int getItemCount() {
        return contactNames.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textContactName;
        ImageButton buttonRemove;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textContactName = itemView.findViewById(R.id.text_contact_name);
            buttonRemove = itemView.findViewById(R.id.button_remove);
        }
    }
}