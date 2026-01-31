package com.wmods.wppenhacer.ui.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.RecordingsAdapter;
import com.wmods.wppenhacer.databinding.FragmentRecordingsBinding;
import com.wmods.wppenhacer.model.Recording;
import com.wmods.wppenhacer.ui.dialogs.AudioPlayerDialog;
import com.wmods.wppenhacer.ui.dialogs.VideoPlayerDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class RecordingsFragment extends Fragment implements RecordingsAdapter.OnRecordingActionListener {

    private FragmentRecordingsBinding binding;
    private RecordingsAdapter adapter;
    private List<Recording> allRecordings = new ArrayList<>();
    private List<File> baseDirs = new ArrayList<>();
    private boolean isGroupByContact = true;
    private String currentContactFilter = null;
    private int currentSortType = 1; // 1=date, 2=name, 3=duration, 4=contact

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private Future<?> loadFuture;
    
    // Cache to avoid re-scanning directories
    private static List<Recording> cachedRecordings = null;
    private static long cacheTimestamp = 0;
    private static final long CACHE_VALIDITY_MS = 5000; // Cache valid for 5 seconds
    private static List<File> cachedBaseDirs = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentRecordingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("NewApi")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new RecordingsAdapter(this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        // Set up selection change listener
        adapter.setSelectionChangeListener(count -> {
            if (count > 0) {
                binding.selectionBar.setVisibility(View.VISIBLE);
                binding.tvSelectionCount.setText(getString(R.string.selected_count, count));
            } else {
                binding.selectionBar.setVisibility(View.GONE);
            }
        });

        // Initialize base directories
        initializeBaseDirs();

        // View mode toggle
        binding.chipList.setOnClickListener(v -> {
            isGroupByContact = false;
            currentContactFilter = null;
            updateDisplayList();
        });
        
        binding.chipGroupByContact.setOnClickListener(v -> {
            isGroupByContact = true;
            currentContactFilter = null;
            updateDisplayList();
        });

        // Selection bar buttons
        binding.btnCloseSelection.setOnClickListener(v -> adapter.clearSelection());
        binding.btnSelectAll.setOnClickListener(v -> adapter.selectAll());
        binding.btnShareSelected.setOnClickListener(v -> shareSelectedRecordings());
        binding.btnDeleteSelected.setOnClickListener(v -> deleteSelectedRecordings());

        // Sort FAB
        binding.fabSort.setOnClickListener(v -> showSortMenu());

        loadRecordings();
    }

    private void initializeBaseDirs() {
        var prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String path = prefs.getString("call_recording_path", null);
        
        baseDirs.clear();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            baseDirs.add(new File(Environment.getExternalStorageDirectory(), "WA Call Recordings"));
        }
        
        if (path != null && !path.isEmpty()) {
            baseDirs.add(new File(path, "WA Call Recordings"));
        }
        
        baseDirs.add(new File("/sdcard/Android/data/com.whatsapp/files/Recordings"));
        baseDirs.add(new File("/sdcard/Android/data/com.whatsapp.w4b/files/Recordings"));
        
        baseDirs.add(new File(Environment.getExternalStorageDirectory(), "Music/WaEnhancer/Recordings"));
    }

    private void loadRecordings() {
        if (loadFuture != null) {
            loadFuture.cancel(true);
            loadFuture = null;
        }

        final var context = getContext();
        if (context == null) return;
        final var activity = getActivity();
        if (activity == null) return;
        final var appContext = context.getApplicationContext();
        final var dirsSnapshot = new ArrayList<>(baseDirs);

        // Check if cache is valid
        long now = System.currentTimeMillis();
        if (cachedRecordings != null && 
            cachedBaseDirs != null &&
            cachedBaseDirs.equals(dirsSnapshot) &&
            (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
            // Use cached data
            allRecordings = new ArrayList<>(cachedRecordings);
            showLoading(false);
            binding.emptyView.setVisibility(allRecordings.isEmpty() ? View.VISIBLE : View.GONE);
            binding.recyclerView.setVisibility(allRecordings.isEmpty() ? View.GONE : View.VISIBLE);
            applySort();
            updateDisplayList();
            return;
        }

        showLoading(true);

        loadFuture = loadExecutor.submit(() -> {
            // Use parallel stream for faster directory scanning
            // Each directory is scanned in parallel, significantly improving performance
            List<Recording> recordings = dirsSnapshot.parallelStream()
                .filter(dir -> dir.exists() && dir.isDirectory())
                .flatMap(dir -> scanDirectoryParallel(dir, appContext).stream())
                .collect(Collectors.toList());

            if (Thread.currentThread().isInterrupted()) return;

            // Update cache
            cachedRecordings = new ArrayList<>(recordings);
            cachedBaseDirs = new ArrayList<>(dirsSnapshot);
            cacheTimestamp = System.currentTimeMillis();

            activity.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;

                allRecordings = recordings;
                showLoading(false);

                if (allRecordings.isEmpty()) {
                    binding.emptyView.setVisibility(View.VISIBLE);
                    binding.recyclerView.setVisibility(View.GONE);
                } else {
                    binding.emptyView.setVisibility(View.GONE);
                    binding.recyclerView.setVisibility(View.VISIBLE);
                    applySort();
                    updateDisplayList();
                }
            });
        });
    }
    
    /**
     * Parallel directory scanner - finds all recording files in a directory
     * Uses efficient file filtering without loading metadata
     */
    private List<Recording> scanDirectoryParallel(File dir, android.content.Context context) {
        List<Recording> recordings = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return recordings;
        
        // Quick filter for recording files only
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) return recordings;
            
            if (file.isDirectory()) {
                // Recursively scan subdirectories
                recordings.addAll(scanDirectoryParallel(file, context));
            } else {
                String name = file.getName().toLowerCase();
                // Fast extension check without regex
                if (name.endsWith(".wav") || name.endsWith(".mp3") || 
                    name.endsWith(".aac") || name.endsWith(".m4a") || name.endsWith(".mp4")) {
                    // Create Recording without loading duration (lazy loading)
                    recordings.add(new Recording(file, context));
                }
            }
        }
        return recordings;
    }

    private void showLoading(boolean loading) {
        if (binding == null) return;
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            binding.emptyView.setVisibility(View.GONE);
            binding.recyclerView.setVisibility(View.GONE);
        }
        binding.fabSort.setEnabled(!loading);
    }

    private void updateDisplayList() {
        if (isGroupByContact && currentContactFilter == null) {
            // Show grouped by contact list (pseudo-folders)
            Map<String, List<Recording>> groups = allRecordings.stream()
                    .collect(Collectors.groupingBy(Recording::getGroupKey));

            List<Recording> contactItems = new ArrayList<>();
            groups.forEach((name, recs) -> {
                if (recs == null || recs.isEmpty()) return;

                long totalDuration = 0L;
                long latestDate = 0L;
                for (Recording r : recs) {
                    totalDuration += r.getDuration();
                    if (r.getDate() > latestDate) latestDate = r.getDate();
                }

                final long totalDurationFinal = totalDuration;
                final long latestDateFinal = latestDate;
                final int countFinal = recs.size();

                // We create a dummy recording to represent the group
                Recording groupItem = new Recording(recs.get(0).getFile(), requireContext()) {
                    @Override
                    public String getFormattedSize() {
                        return countFinal + " recordings";
                    }

                    @Override
                    public String getFormattedDuration() {
                        return formatDuration(totalDurationFinal);
                    }

                    @Override
                    public long getDuration() {
                        return totalDurationFinal;
                    }

                    @Override
                    public long getDate() {
                        return latestDateFinal;
                    }

                    @Override
                    public String getPhoneNumber() {
                        // In grouped mode we don't want a secondary line (looks like a folder list)
                        return null;
                    }
                };
                contactItems.add(groupItem);
            });

            contactItems.sort(getGroupedSortComparator());
            adapter.setRecordings(contactItems);
        } else if (currentContactFilter != null) {
            // Show recordings for specific contact
            List<Recording> filtered = allRecordings.stream()
                    .filter(r -> r.getGroupKey().equals(currentContactFilter))
                    .collect(Collectors.toList());
            adapter.setRecordings(filtered);
        } else {
            // Normal list view
            adapter.setRecordings(allRecordings);
        }
    }

    private Comparator<Recording> getGroupedSortComparator() {
        return switch (currentSortType) {
            case 1 -> Comparator.comparingLong(Recording::getDate).reversed()
                    .thenComparing(Recording::getContactName, String.CASE_INSENSITIVE_ORDER);
            case 2 -> Comparator.comparing(Recording::getContactName, String.CASE_INSENSITIVE_ORDER);
            case 3 -> Comparator.comparingLong(Recording::getDuration).reversed()
                    .thenComparing(Recording::getContactName, String.CASE_INSENSITIVE_ORDER);
            case 4 -> Comparator.comparing(Recording::getContactName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingLong(Recording::getDate).reversed();
            default -> Comparator.comparing(Recording::getContactName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    private static String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    // Note: traverseDirectory replaced by scanDirectoryParallel for better performance

    private void applySort() {
        switch (currentSortType) {
            case 1 -> allRecordings.sort((r1, r2) -> Long.compare(r2.getDate(), r1.getDate())); // Date desc
            case 2 -> allRecordings.sort(Comparator.comparing(Recording::getContactName)); // Name
            case 3 -> allRecordings.sort((r1, r2) -> Long.compare(r2.getDuration(), r1.getDuration())); // Duration desc
            case 4 -> allRecordings.sort(Comparator.comparing(Recording::getContactName)
                    .thenComparing((r1, r2) -> Long.compare(r2.getDate(), r1.getDate()))); // Contact then date
        }
    }

    private void showSortMenu() {
        PopupMenu popup = new PopupMenu(requireContext(), binding.fabSort);
        popup.getMenu().add(0, 1, 0, R.string.sort_date);
        popup.getMenu().add(0, 2, 0, R.string.sort_name);
        popup.getMenu().add(0, 3, 0, R.string.sort_duration);
        popup.getMenu().add(0, 4, 0, R.string.sort_contact);
        
        popup.setOnMenuItemClickListener(item -> {
            currentSortType = item.getItemId();
            applySort();
            updateDisplayList();
            return true;
        });
        popup.show();
    }

    @Override
    public void onPlay(Recording recording) {
        if (isGroupByContact && currentContactFilter == null) {
            // Entering a contact's recording list
            currentContactFilter = recording.getGroupKey();
            updateDisplayList();
        } else {
            String fileName = recording.getFile().getName().toLowerCase();
            if (fileName.endsWith(".mp4") || fileName.endsWith(".avi") || fileName.endsWith(".mkv")) {
                // Video recording
                VideoPlayerDialog dialog = new VideoPlayerDialog(requireContext(), recording.getFile());
                dialog.show();
            } else {
                // Audio recording
                AudioPlayerDialog dialog = new AudioPlayerDialog(requireContext(), recording.getFile());
                dialog.show();
            }
        }
    }

    @Override
    public void onShare(Recording recording) {
        if (isGroupByContact && currentContactFilter == null) return;
        shareRecording(recording.getFile());
    }

    @Override
    public void onDelete(Recording recording) {
        if (isGroupByContact && currentContactFilter == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_confirmation)
                .setMessage(recording.getFile().getName())
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    if (recording.getFile().delete()) {
                        loadRecordings();
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    @Override
    public void onLongPress(Recording recording, int position) {
        if (isGroupByContact && currentContactFilter == null) return;
        adapter.setSelectionMode(true);
        adapter.toggleSelection(position);
    }

    private void shareRecording(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(), 
                    requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_recording)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error sharing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareSelectedRecordings() {
        List<Recording> selected = adapter.getSelectedRecordings();
        if (selected.isEmpty()) return;

        if (selected.size() == 1) {
            shareRecording(selected.get(0).getFile());
            adapter.clearSelection();
            return;
        }

        ArrayList<Uri> uris = new ArrayList<>();
        for (Recording rec : selected) {
            try {
                Uri uri = FileProvider.getUriForFile(requireContext(),
                        requireContext().getPackageName() + ".fileprovider", rec.getFile());
                uris.add(uri);
            } catch (Exception ignored) {}
        }

        if (!uris.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("audio/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_recordings)));
        }
        adapter.clearSelection();
    }

    private void deleteSelectedRecordings() {
        List<Recording> selected = adapter.getSelectedRecordings();
        if (selected.isEmpty()) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_confirmation)
                .setMessage(getString(R.string.delete_multiple_confirmation, selected.size()))
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    int deleted = 0;
                    for (Recording rec : selected) {
                        if (rec.getFile().delete()) {
                            deleted++;
                        }
                    }
                    Toast.makeText(requireContext(), "Deleted " + deleted + " recordings", Toast.LENGTH_SHORT).show();
                    adapter.clearSelection();
                    loadRecordings();
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (loadFuture != null) {
            loadFuture.cancel(true);
            loadFuture = null;
        }
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        loadExecutor.shutdownNow();
    }
}
