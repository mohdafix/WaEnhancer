package com.wmods.wppenhacer.activities;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.ContactPickerAdapter;
import com.wmods.wppenhacer.preference.ContactData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContactPickerSheet extends BottomSheetDialogFragment {

    public interface OnContactsSelectedListener {
        void onContactsSelected(List<ContactData> selectedContacts);
    }

    private RecyclerView recyclerContacts;
    private ContactPickerAdapter adapter;
    private EditText editSearch;
    private final List<ContactData> allContacts = new ArrayList<>();
    private final List<ContactData> selectedContacts = new ArrayList<>();
    private OnContactsSelectedListener listener;

    public void setOnContactsSelectedListener(OnContactsSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_contact_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        recyclerContacts = view.findViewById(R.id.recycler_contacts);
        editSearch = view.findViewById(R.id.edit_search);
        
        view.findViewById(R.id.button_done).setOnClickListener(v -> {
            if (listener != null) {
                listener.onContactsSelected(selectedContacts);
            }
            dismiss();
        });

        setupSearch();
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 100);
        } else {
            loadContacts();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else if (isAdded()) {
            Toast.makeText(requireContext(), "Permission required to load contacts", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupSearch() {
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) {
                    adapter.filter(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void loadContacts() {
        new Thread(() -> {
            List<ContactData> contacts = fetchContacts();
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    allContacts.clear();
                    allContacts.addAll(contacts);
                    setupAdapter();
                });
            }
        }).start();
    }

    private void setupAdapter() {
        adapter = new ContactPickerAdapter(allContacts, selectedContacts, (contact, selected) -> {
            if (selected) {
                if (!selectedContacts.contains(contact)) {
                    selectedContacts.add(contact);
                }
            } else {
                selectedContacts.remove(contact);
            }
        });
        recyclerContacts.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerContacts.setAdapter(adapter);
    }

    private List<ContactData> fetchContacts() {
        List<ContactData> contacts = new ArrayList<>();
        Set<String> seenJids = new HashSet<>();
        
        try {
            Cursor cursor = requireContext().getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.Data.PHOTO_THUMBNAIL_URI
                },
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{"vnd.android.cursor.item/vnd.com.whatsapp.profile"},
                ContactsContract.Data.DISPLAY_NAME + " ASC"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    String phone = cursor.getString(1);
                    String photoUri = cursor.getString(2);
                    
                    if (name != null && phone != null && !phone.isEmpty()) {
                        String rawPhone = phone.replaceAll("[^0-9]", "");
                        String jid = rawPhone + "@s.whatsapp.net";
                        
                        if (!seenJids.contains(jid)) {
                            if (photoUri == null) {
                                photoUri = findWhatsAppPhoto(rawPhone);
                            }
                            contacts.add(new ContactData(name, jid, photoUri));
                            seenJids.add(jid);
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return contacts;
    }

    private String findWhatsAppPhoto(String phone) {
        String[] paths = {
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/0/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/Profile Pictures/",
            "/storage/emulated/0/WhatsApp Business/Media/Profile Pictures/"
        };
        
        for (String path : paths) {
            java.io.File file = new java.io.File(path + phone + ".jpg");
            if (file.exists()) return android.net.Uri.fromFile(file).toString();
        }
        return null;
    }
}
