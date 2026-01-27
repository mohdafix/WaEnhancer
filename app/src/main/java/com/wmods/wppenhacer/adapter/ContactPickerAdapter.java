package com.wmods.wppenhacer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.preference.ContactData;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ContactPickerAdapter extends RecyclerView.Adapter<ContactPickerAdapter.ViewHolder> {

    public interface OnContactSelectedListener {
        void onContactSelected(ContactData contact, boolean selected);
    }

    private final List<ContactData> allContacts;
    private final List<ContactData> filteredContacts;
    private final List<ContactData> selectedContacts;
    private final OnContactSelectedListener listener;

    public ContactPickerAdapter(List<ContactData> contacts, List<ContactData> selectedContacts, OnContactSelectedListener listener) {
        this.allContacts = contacts;
        this.filteredContacts = new ArrayList<>(contacts);
        this.selectedContacts = selectedContacts;
        this.listener = listener;
    }

    public void filter(String query) {
        filteredContacts.clear();
        if (query == null || query.isEmpty()) {
            filteredContacts.addAll(allContacts);
        } else {
            String lowerQuery = query.toLowerCase().trim();
            List<ContactData> prefixMatches = new ArrayList<>();
            List<ContactData> wordMatches = new ArrayList<>();
            List<ContactData> otherMatches = new ArrayList<>();

            for (ContactData contact : allContacts) {
                String name = contact.getDisplayName().toLowerCase();
                String jid = contact.getJid().toLowerCase();

                if (name.startsWith(lowerQuery)) {
                    prefixMatches.add(contact);
                } else {
                    boolean isWordMatch = false;
                    for (String word : name.split("[\\s-]+")) {
                        if (word.startsWith(lowerQuery)) {
                            isWordMatch = true;
                            break;
                        }
                    }
                    if (isWordMatch) {
                        wordMatches.add(contact);
                    } else if (name.contains(lowerQuery) || jid.contains(lowerQuery)) {
                        otherMatches.add(contact);
                    }
                }
            }

            filteredContacts.addAll(prefixMatches);
            filteredContacts.addAll(wordMatches);
            filteredContacts.addAll(otherMatches);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactData contact = filteredContacts.get(position);
        holder.bind(contact, selectedContacts.contains(contact), listener);
    }

    @Override
    public int getItemCount() {
        return filteredContacts.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textContactName, textContactJid;
        ImageView imageContactPhoto;
        CheckBox checkBox;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textContactName = itemView.findViewById(R.id.text_contact_name);
            textContactJid = itemView.findViewById(R.id.text_contact_jid);
            imageContactPhoto = itemView.findViewById(R.id.image_contact_photo);
            checkBox = itemView.findViewById(R.id.checkbox_select);
        }

        public void bind(ContactData contact, boolean isSelected, OnContactSelectedListener listener) {
            textContactName.setText(contact.getDisplayName());
            textContactJid.setText(contact.getJid());
            checkBox.setChecked(isSelected);

            // Set photo if available
            if (contact.getPhotoUri() != null) {
                imageContactPhoto.setImageURI(android.net.Uri.parse(contact.getPhotoUri()));
                imageContactPhoto.setColorFilter(null);
                imageContactPhoto.setPadding(0, 0, 0, 0);
            } else {
                imageContactPhoto.setImageResource(R.drawable.ic_general);
                imageContactPhoto.setColorFilter(itemView.getContext().getColor(R.color.text_secondary));
                int padding = (int) (8 * itemView.getContext().getResources().getDisplayMetrics().density);
                imageContactPhoto.setPadding(padding, padding, padding, padding);
            }

            itemView.setOnClickListener(v -> {
                boolean nextState = !checkBox.isChecked();
                checkBox.setChecked(nextState);
                if (listener != null) {
                    listener.onContactSelected(contact, nextState);
                }
            });
        }
    }
}