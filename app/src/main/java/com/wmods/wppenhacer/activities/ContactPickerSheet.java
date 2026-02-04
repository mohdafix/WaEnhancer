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

import com.wmods.wppenhacer.xposed.bridge.client.BridgeClient;
import com.wmods.wppenhacer.xposed.bridge.WaeIIFace;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    private BridgeClient bridgeClient;

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

        bridgeClient = new BridgeClient(requireContext());
        bridgeClient.connect().thenAccept(connected -> {
            if (Boolean.TRUE.equals(connected)) {
                syncPhotosWithBridge();
            }
        });
    }

    private void syncPhotosWithBridge() {
        if (bridgeClient == null) return;
        WaeIIFace service = bridgeClient.getService();
        if (service == null) return;

        new Thread(() -> {
            try {
                // Get contacts from bridge to see if we missed any or have new JIDs
                List<String> bridgeContacts = service.getContacts();
                if (bridgeContacts != null && !bridgeContacts.isEmpty()) {
                    // Just log for now, mainly want to sync photos
                }

                // For each contact already loaded, try to refresh its photo via bridge if not found locally
                List<ContactData> contactsToRefresh;
                synchronized (allContacts) {
                    contactsToRefresh = new ArrayList<>(allContacts);
                }

                File cacheDir = new File(requireContext().getCacheDir(), "profile_sync");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                for (ContactData contact : contactsToRefresh) {
                    if (contact.getPhotoUri() == null || contact.getPhotoUri().startsWith("android.resource")) {
                        try {
                            android.os.ParcelFileDescriptor pfd = service.getContactPhoto(contact.getJid());
                            if (pfd != null) {
                                File localFile = new File(cacheDir, contact.getJid() + ".jpg");
                                try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                                     OutputStream out = new FileOutputStream(localFile)) {
                                    byte[] buffer = new byte[8192];
                                    int read;
                                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                                }
                                pfd.close();
                                
                                String newUri = android.net.Uri.fromFile(localFile).toString();
                                contact.setPhotoUri(newUri);
                                
                                // Notify adapter
                                if (isAdded()) {
                                    requireActivity().runOnUiThread(() -> {
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                    });
                                }
                            }
                        } catch (Exception e) {
                            // Quietly fail for individual photos
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
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
                    synchronized (allContacts) {
                        allContacts.clear();
                        allContacts.addAll(contacts);
                    }
                    setupAdapter();
                    // Re-trigger sync if bridge is already connected
                    if (bridgeClient != null && bridgeClient.getService() != null) {
                        syncPhotosWithBridge();
                    }
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




    private java.util.Map<Long, String> getContactPhotoMap() {
        java.util.Map<Long, String> photoMap = new java.util.HashMap<>();
        try {
            Cursor cursor = requireContext().getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI},
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI + " IS NOT NULL",
                    null,
                    null
            );
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String uri = cursor.getString(1);
                    photoMap.put(id, uri);
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return photoMap;
    }

    private List<ContactData> fetchContacts() {
        List<ContactData> contacts = new ArrayList<>();
        Set<String> seenJids = new HashSet<>();
        // Pre-fetch all contact photos to avoid N+1 queries
        java.util.Map<Long, String> photoMap = getContactPhotoMap();
        
        try {
            Cursor cursor = requireContext().getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.Data.PHOTO_THUMBNAIL_URI,
                    ContactsContract.Data.CONTACT_ID
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
                    long contactId = cursor.getLong(3);
                    
                    if (name != null && phone != null && !phone.isEmpty()) {
                        String rawPhone = phone.replaceAll("[^0-9]", "");
                        String jid = rawPhone + "@s.whatsapp.net";
                        
                        if (!seenJids.contains(jid)) {
                            // 1. Try URI from Data row
                            // 2. Try URI from pre-fetched Contact map
                            // 3. Try file-based fallback
                            if (photoUri == null && contactId > 0) {
                                photoUri = photoMap.get(contactId);
                            }

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
        if (phone == null || phone.isEmpty()) return null;

        String[] basePaths = {
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/0/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/Profile Pictures/",
            "/storage/emulated/0/WhatsApp Business/Media/Profile Pictures/",
            // Dual App / Parallel Space paths
            "/storage/emulated/999/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/999/WhatsApp/Media/Profile Pictures/",
            "/storage/emulated/10/Android/media/com.whatsapp/WhatsApp/Media/Profile Pictures/"
        };

        String[] fileFormats = {
            phone + ".jpg",
            phone + "@s.whatsapp.net.jpg",
            phone + "@g.us.jpg"
        };

        for (String path : basePaths) {
            for (String format : fileFormats) {
                java.io.File file = new java.io.File(path + format);
                if (file.exists()) {
                    return android.net.Uri.fromFile(file).toString();
                }
            }
        }
        return null;
    }
}
