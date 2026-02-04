// WaeIIFace.aidl
package com.wmods.wppenhacer.xposed.bridge;

import android.os.ParcelFileDescriptor;
import java.util.List;

interface WaeIIFace {
    ParcelFileDescriptor openFile(String path, boolean create);
    boolean createDir(String path);
    List listFiles(String path);
    List<String> getContacts();
    ParcelFileDescriptor getContactPhoto(String jid);
}