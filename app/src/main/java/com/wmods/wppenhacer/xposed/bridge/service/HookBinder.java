package com.wmods.wppenhacer.xposed.bridge.service;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Pair;

import com.wmods.wppenhacer.xposed.bridge.WaeIIFace;
import com.wmods.wppenhacer.xposed.core.WppCore;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HookBinder extends WaeIIFace.Stub {

    private static HookBinder mInstance;

    public static HookBinder getInstance() {
        if (mInstance == null) {
            mInstance = new HookBinder();
        }
        return mInstance;
    }

    @Override
    public ParcelFileDescriptor openFile(String path, boolean create) throws RemoteException {
        File file = new File(path);
        if (!file.exists() && create) {
            try {
                file.createNewFile();
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public boolean createDir(String path) throws RemoteException {
        File file = new File(path);
        return file.mkdirs();
    }

    @Override
    public List listFiles(String path) throws RemoteException {
        var files = new File(path).listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(files);
    }

    @Override
    public List<String> getContacts() throws RemoteException {
        android.util.Log.d("HookBinder", "getContacts() called");
        
        try {
            // Ensure database is loaded
            WppCore.loadWADatabase();
            
            List<Pair<String, String>> contacts = WppCore.getAllContacts();
            android.util.Log.d("HookBinder", "WppCore.getAllContacts() returned " + contacts.size() + " contacts");
            
            List<String> result = new ArrayList<>();
            for (Pair<String, String> pair : contacts) {
                result.add(pair.first + "|" + pair.second);
            }
            
            android.util.Log.d("HookBinder", "Returning " + result.size() + " formatted contacts");
            return result;
        } catch (Exception e) {
            android.util.Log.e("HookBinder", "Error getting contacts", e);
            return new ArrayList<>();
        }
    }

}
