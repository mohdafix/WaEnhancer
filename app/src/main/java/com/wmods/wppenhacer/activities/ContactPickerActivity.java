package com.wmods.wppenhacer.activities;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.ContactPickerAdapter;
import com.wmods.wppenhacer.databinding.ActivityContactPickerBinding;
import com.wmods.wppenhacer.preference.ContactData;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.activities.base.BaseActivity;
import java.util.ArrayList;
import java.util.List;

public class ContactPickerActivity extends BaseActivity {

    private ActivityContactPickerBinding binding;
    private ContactPickerAdapter adapter;
    private List<ContactData> allContacts = new ArrayList<>();
    private List<ContactData> selectedContacts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityContactPickerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        loadContacts();
        setupRecyclerView();
        setupListeners();
    }

    private void loadContacts() {
        // Try WhatsApp contacts first
        List<android.util.Pair<String, String>> waContacts = WppCore.getAllContacts();
        android.util.Log.d("WaEnhancer", "WA contacts loaded: " + waContacts.size());
        for (android.util.Pair<String, String> pair : waContacts) {
            allContacts.add(new ContactData(pair.first, pair.second));
        }

        // If no WhatsApp contacts, load Android contacts and construct JIDs
        if (allContacts.isEmpty()) {
            loadAndroidContacts();
            android.util.Log.d("WaEnhancer", "Android fallback executed, total contacts: " + allContacts.size());
        } else {
            android.util.Log.d("WaEnhancer", "WA contacts available: " + allContacts.size());
        }

        if (allContacts.isEmpty()) {
            Toast.makeText(this, "No contacts found", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadAndroidContacts() {
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        Cursor cursor = getContentResolver().query(uri, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                // Clean number: remove spaces, dashes, etc.
                String cleanNumber = number.replaceAll("[^0-9+]", "");
                if (cleanNumber.startsWith("+")) {
                    // Keep as is
                } else if (cleanNumber.length() == 10) {
                    // Assume country code +1 for US, but generic
                    cleanNumber = "+" + cleanNumber;
                }
                String jid = cleanNumber + "@s.whatsapp.net";
                allContacts.add(new ContactData(name, jid));
            }
            cursor.close();
            android.util.Log.d("WaEnhancer", "Android WA fallback loaded: " + allContacts.size());
        }
    }

    private void setupRecyclerView() {
        adapter = new ContactPickerAdapter(allContacts, selectedContacts, (contact, selected) -> {
            if (selected) {
                selectedContacts.add(contact);
            } else {
                selectedContacts.remove(contact);
            }
        });
        binding.contactListView.setLayoutManager(new LinearLayoutManager(this));
        binding.contactListView.setAdapter(adapter);
    }

    private void setupListeners() {
        binding.selectAllButton.setOnClickListener(v -> {
            selectedContacts.clear();
            selectedContacts.addAll(allContacts);
            adapter.notifyDataSetChanged();
        });

        binding.saveButton.setOnClickListener(v -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selected_contacts", new ArrayList<>(selectedContacts));
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }
}
