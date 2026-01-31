package com.wmods.wppenhacer.model;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model class representing a call recording with metadata.
 */
public class Recording {

    private final File file;
    private String phoneNumber;
    private String contactName;
    private volatile long duration = -1; // -1 means not loaded yet, in milliseconds
    private final long date;
    private final long size;
    private final transient Context context; // transient to avoid serialization issues

    // UPDATED PATTERN: Now includes .m4a
    // Matches: Call_NameOrNumber_YYYYMMDD_HHMMSS.(wav|m4a)
    private static final Pattern FILENAME_PATTERN = Pattern.compile("Call_(.+)_\\d{8}_\\d{6}\\.(wav|m4a)");

    public Recording(File file, Context context) {
        this.file = file;
        this.context = context;
        this.date = file.lastModified();
        this.size = file.length();
        extractInfoFromFilename();
        // Duration is now loaded lazily - not parsed in constructor
    }

    private void extractInfoFromFilename() {
        String filename = file.getName();
        Matcher matcher = FILENAME_PATTERN.matcher(filename);
        if (matcher.matches()) {
            String extracted = matcher.group(1);
            // If it's a phone number, keep it as phone, otherwise it's the contact name
            if (extracted.matches("[+\\d]+")) {
                phoneNumber = extracted;
                contactName = extracted;
            } else {
                contactName = extracted.replace("_", " ");
                phoneNumber = "";
            }
        } else {
            // Fallback for old files or unknown formats
            contactName = filename;
            phoneNumber = "";
        }
    }

    /**
     * UPDATED: Now handles both WAV and M4A formats with lazy loading.
     * For M4A, it uses MediaMetadataRetriever (slower, loaded on demand).
     * For WAV, it reads the header (fast, also loaded on demand).
     * This method is called lazily when getDuration() is first accessed.
     */
    private void parseDuration() {
        if (!file.exists() || file.length() < 100) { // M4A headers are larger
            duration = 0;
            return;
        }

        String filename = file.getName();
        if (filename.endsWith(".m4a") || filename.endsWith(".mp4")) {
            // Use MediaMetadataRetriever for M4A/MP4 files
            if (context != null) {
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    // Use URI for better compatibility, especially with FileProvider
                    retriever.setDataSource(context, Uri.fromFile(file));
                    String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationStr != null) {
                        duration = Long.parseLong(durationStr);
                    } else {
                        duration = 0;
                    }
                } catch (Exception e) {
                    duration = 0;
                }
            } else {
                duration = 0;
            }
            return;
        }

        // Original logic for WAV files
        if (filename.endsWith(".wav")) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                byte[] header = new byte[44];
                raf.read(header);

                if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                    duration = estimateDurationForWav();
                    return;
                }

                int byteRate = (header[28] & 0xFF) | ((header[29] & 0xFF) << 8) | ((header[30] & 0xFF) << 16) | ((header[31] & 0xFF) << 24);
                long dataSize = (header[40] & 0xFF) | ((header[41] & 0xFF) << 8) | ((header[42] & 0xFF) << 16) | ((long)(header[43] & 0xFF) << 24);

                if (byteRate > 0) {
                    duration = (dataSize * 1000L) / byteRate;
                } else {
                    duration = estimateDurationForWav();
                }
            } catch (Exception e) {
                duration = estimateDurationForWav();
            }
        } else {
            // Unknown format
            duration = 0;
        }
    }

    // Fallback estimation for WAV
    private long estimateDurationForWav() {
        // (file.length() - 44) for WAV header, 176400 is a common byte rate for CD quality (44100Hz, 16bit, Stereo)
        // For mono, it's half. Let's assume a safe average.
        long dataSize = file.length() - 44;
        return (dataSize * 1000L) / 88200; // Approximation for 44.1kHz, 16-bit, stereo
    }

    // Fallback estimation for M4A
    private long estimateDurationForM4A() {
        // A very rough estimate, not reliable
        return 0;
    }


    public File getFile() { return file; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getContactName() { return contactName; }
    
    /**
     * Lazy loading of duration - only parses when first accessed
     * This significantly improves loading performance for large recording lists
     */
    public long getDuration() { 
        if (duration < 0) {
            synchronized (this) {
                if (duration < 0) {
                    parseDuration();
                }
            }
        }
        return duration; 
    }
    
    public long getDate() { return date; }
    public long getSize() { return size; }

    public String getFormattedDuration() {
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    public String getFormattedSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    public String getGroupKey() {
        return (contactName != null && !contactName.isEmpty()) ? contactName : "unknown";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recording recording = (Recording) o;
        return file.equals(recording.file);
    }

    @Override
    public int hashCode() { return file.hashCode(); }
}
