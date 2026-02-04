package com.wmods.wppenhacer.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.util.Log;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.navigation.NavigationBarView;
import com.waseemsabir.betterypermissionhelper.BatteryPermissionHelper;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.base.BaseActivity;
import com.wmods.wppenhacer.adapter.MainPagerAdapter;
import com.wmods.wppenhacer.databinding.ActivityMainBinding;
import com.wmods.wppenhacer.utils.FilePicker;

import java.io.File;

public class MainActivity extends BaseActivity {

    public static final String EXTRA_SEARCH_TAB_INDEX = "extra_search_tab_index";
    public static final String EXTRA_SEARCH_SCREEN_CLASS = "extra_search_screen_class";
    public static final String EXTRA_SEARCH_PREF_KEY = "extra_search_pref_key";

    private PendingNavigation pendingNavigation;

    private static class PendingNavigation {
        final int tabIndex;
        final String screenClassName;
        final String prefKey;

        PendingNavigation(int tabIndex, String screenClassName, String prefKey) {
            this.tabIndex = tabIndex;
            this.screenClassName = screenClassName;
            this.prefKey = prefKey;
        }
    }

    private ActivityMainBinding binding;
    private BatteryPermissionHelper batteryPermissionHelper = BatteryPermissionHelper.Companion.getInstance();
    private androidx.activity.OnBackPressedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        backCallback = new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int currentItem = binding.viewPager.getCurrentItem();
                
                Fragment currentFragment = findTabFragment(currentItem);
                
                if (currentFragment != null && currentFragment.isAdded()) {
                    androidx.fragment.app.FragmentManager childFm = currentFragment.getChildFragmentManager();
                    if (childFm.getBackStackEntryCount() > 0) {
                        childFm.popBackStack();
                        return;
                    }
                }

                if (currentItem != 2) {
                    binding.viewPager.setCurrentItem(2, true);
                    return;
                }

                setEnabled(false);
                MainActivity.this.getOnBackPressedDispatcher().onBackPressed();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);

        var prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("call_recording_enable", false)) {
            binding.navView.getMenu().findItem(R.id.navigation_recordings).setVisible(false);
        }

        binding.navView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @SuppressLint("NonConstantResourceId")
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                return switch (item.getItemId()) {
                    case R.id.navigation_chat -> {
                        binding.viewPager.setCurrentItem(0, true);
                        yield true;
                    }
                    case R.id.navigation_privacy -> {
                        binding.viewPager.setCurrentItem(1, true);
                        yield true;
                    }
                    case R.id.navigation_home -> {
                        binding.viewPager.setCurrentItem(2, true);
                        yield true;
                    }
                    case R.id.navigation_media -> {
                        binding.viewPager.setCurrentItem(3, true);
                        yield true;
                    }
                    case R.id.navigation_colors -> {
                        binding.viewPager.setCurrentItem(4, true);
                        yield true;
                    }
                    case R.id.navigation_recordings -> {
                        binding.viewPager.setCurrentItem(5);
                        yield true;
                    }
                    default -> false;
                };
            }
        });

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                binding.navView.getMenu().getItem(position).setChecked(true);
                updateBackCallbackState();
            }
        });
        binding.viewPager.setCurrentItem(2, false); // Default to Home
        createMainDir();
        FilePicker.registerFilePicker(this);

        // Register lifecycle callbacks to handle deferred navigation
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentResumed(@NonNull androidx.fragment.app.FragmentManager fm, @NonNull Fragment f) {
                if (pendingNavigation != null) {
                    // Check if this fragment corresponds to the pending tab
                    // ViewPager2 tags fragments as "f" + position (e.g., "f1")
                    String tag = f.getTag();
                    if (tag != null && tag.equals("f" + pendingNavigation.tabIndex)) {
                        attemptPendingNavigation(f);
                    }
                }
            }
        }, false);

        checkPermissions();
        handleSearchIntent(getIntent());
        updateBackCallbackState();
    }

    private void checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            String[] permissions = {
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.POST_NOTIFICATIONS
            };
            requestPermissions(permissions, 100);
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 100);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSearchIntent(intent);
    }

    private void handleSearchIntent(@NonNull Intent intent) {
        if (!intent.hasExtra(EXTRA_SEARCH_TAB_INDEX)) return;

        int tabIndex = intent.getIntExtra(EXTRA_SEARCH_TAB_INDEX, 2);
        String screenClass = intent.getStringExtra(EXTRA_SEARCH_SCREEN_CLASS);
        String prefKey = intent.getStringExtra(EXTRA_SEARCH_PREF_KEY);

        // Store pending operation
        pendingNavigation = new PendingNavigation(tabIndex, screenClass, prefKey);

        // Switch tab
        binding.viewPager.setCurrentItem(tabIndex, false);

        // If fragment is already alive and resumed, execute immediately
        Fragment currentFragment = findTabFragment(tabIndex);
        if (currentFragment != null && currentFragment.isResumed()) {
            attemptPendingNavigation(currentFragment);
        }
        
        // Clean up intent
        intent.removeExtra(EXTRA_SEARCH_TAB_INDEX);
        intent.removeExtra(EXTRA_SEARCH_SCREEN_CLASS);
        intent.removeExtra(EXTRA_SEARCH_PREF_KEY);
    }

    private void attemptPendingNavigation(@NonNull Fragment fragment) {
        if (pendingNavigation == null) return;
        
        // Double check this fragment matches the request
        // We use a looser check here because tag might vary, but mostly we care about index logic
        // But for safety, verify if possible. 
        // Actually, since we register lifecycle callback on FM, we get notified for ALL fragments.
        // We really should check if this resumed fragment IS the one for the tab index.
        
        Fragment tabFragment = findTabFragment(pendingNavigation.tabIndex);
        if (tabFragment != fragment && fragment.getParentFragment() != tabFragment) {
            // It might be a child fragment of the tab fragment?
             if (tabFragment != null && fragment.getParentFragmentManager() == tabFragment.getChildFragmentManager()) {
                 // It's a child, maybe we don't need to do anything if it's already there?
                 // But wait, our logic to open screen REPLACES the child.
             } else if (tabFragment != fragment) {
                 return; // Not the fragment we are waiting for
             }
        }
        
        // If the fragment passed here is indeed the tab fragment or we found it
        if (tabFragment != null) {
            try {
                if (pendingNavigation.tabIndex == 0 && tabFragment instanceof com.wmods.wppenhacer.ui.fragments.GeneralFragment generalFragment) {
                    openGeneralScreenAndScroll(generalFragment, pendingNavigation.screenClassName, pendingNavigation.prefKey);
                } else if (tabFragment instanceof androidx.preference.PreferenceFragmentCompat) {
                    androidx.preference.PreferenceFragmentCompat prefFragment = (androidx.preference.PreferenceFragmentCompat) tabFragment;
                    if (pendingNavigation.prefKey != null) {
                        binding.viewPager.post(() -> {
                            try {
                                if (prefFragment instanceof com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment) {
                                    ((com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment) prefFragment).scrollToPreferenceAndHighlight(pendingNavigation.prefKey);
                                } else {
                                    prefFragment.scrollToPreference(pendingNavigation.prefKey);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
                pendingNavigation = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void openGeneralScreenAndScroll(
            @NonNull com.wmods.wppenhacer.ui.fragments.GeneralFragment generalFragment,
            @Nullable String screenClassName,
            @Nullable String prefKey
    ) {
        String target = screenClassName;
        if (target == null || target.isBlank()) {
            target = "com.wmods.wppenhacer.ui.fragments.GeneralFragment$GeneralPreferenceFragment";
        }

        var fm = generalFragment.getChildFragmentManager();
        if (fm.isStateSaved()) {
            return; // Can't commit now, rely on next resume?
        }

        // Reset to a clean stack
        fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);

        try {
            // Find class
            Class<?> clazz = Class.forName(target);
            var fragment = (androidx.fragment.app.Fragment) clazz.getDeclaredConstructor().newInstance();

            boolean isRoot = target.endsWith("$GeneralPreferenceFragment");
            
            var tx = fm.beginTransaction()
                    .replace(R.id.frag_container, fragment);
            
            if (!isRoot) {
                tx.addToBackStack(null);
            }
            tx.commit(); // Use commit allowed stateless instead of commitNow to be safer during lifecycle changes

            // Post action to scroll after the child fragment attaches and creates view
            if (prefKey != null) {
                binding.viewPager.postDelayed(() -> {
                    if (fragment instanceof com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment && fragment.isVisible()) {
                        ((com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment) fragment).scrollToPreferenceAndHighlight(prefKey);
                    } else if (fragment instanceof androidx.preference.PreferenceFragmentCompat && fragment.isVisible()) {
                         ((androidx.preference.PreferenceFragmentCompat) fragment).scrollToPreference(prefKey);
                    }
                }, 100); 
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Nullable
    private Fragment findTabFragment(int tabIndex) {
        // ViewPager2 fragments are tagged as "f" + position by FragmentStateAdapter
        Fragment byTag = getSupportFragmentManager().findFragmentByTag("f" + tabIndex);
        if (byTag != null) return byTag;

        // Fallback search in case tags are different or missing
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (tabIndex == 1 && f instanceof com.wmods.wppenhacer.ui.fragments.PrivacyFragment) return f;
            if (tabIndex == 2 && f instanceof com.wmods.wppenhacer.ui.fragments.HomeFragment) return f;
            if (tabIndex == 3 && f instanceof com.wmods.wppenhacer.ui.fragments.MediaFragment) return f;
            if (tabIndex == 4 && f instanceof com.wmods.wppenhacer.ui.fragments.CustomizationFragment) return f;
            if (tabIndex == 0 && f instanceof com.wmods.wppenhacer.ui.fragments.GeneralFragment) return f;
            if (tabIndex == 5 && f instanceof com.wmods.wppenhacer.ui.fragments.RecordingsFragment) return f;
        }
        return null;
    }

    private void updateBackCallbackState() {
        if (backCallback == null) return;
        int currentItem = binding.viewPager.getCurrentItem();
        boolean isHome = (currentItem == 2);
        boolean homeHasStack = false;
        
        // Check if current fragment has back stack
        Fragment f = findTabFragment(currentItem);
        if (f != null && f.isAdded() && f.getChildFragmentManager().getBackStackEntryCount() > 0) {
             homeHasStack = true;
        }
        
        // Enable if NOT home, OR if currently managing a backstack
        backCallback.setEnabled(!isHome || homeHasStack);
    }

    private void createMainDir() {
        var nomedia = new File(App.getWaEnhancerFolder(), ".nomedia");
        if (nomedia.exists()) {
            nomedia.delete();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            fragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.header_menu, menu);
        Log.d("WaEnhancer", "Options menu inflated");
        var powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            menu.findItem(R.id.batteryoptimization).setVisible(false);
        }
        return true;
    }

    @SuppressLint("BatteryLife")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_search) {
            var options = ActivityOptionsCompat.makeCustomAnimation(
                    this, R.anim.slide_in_right, R.anim.slide_out_left);
            startActivity(new Intent(this, SettingsSearchActivity.class), options.toBundle());
            return true;
        } else if (item.getItemId() == R.id.menu_about) {
            var options = ActivityOptionsCompat.makeCustomAnimation(
                    this, R.anim.slide_in_right, R.anim.slide_out_left);
            startActivity(new Intent(this, AboutActivity.class), options.toBundle());
            return true;
        } else if (item.getItemId() == R.id.menu_scheduled_messages) {
            Log.d("WaEnhancer", "Scheduled Messages menu tapped");
            var options = ActivityOptionsCompat.makeCustomAnimation(
                    this, R.anim.slide_in_right, R.anim.slide_out_left);
            startActivity(new Intent(this, ScheduledMessagesListActivity.class), options.toBundle());
            return true;
        } else if (item.getItemId() == R.id.batteryoptimization) {
            if (batteryPermissionHelper.isBatterySaverPermissionAvailable(this, true)) {
                batteryPermissionHelper.getPermission(this, true, true);
            } else {
                var intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
            }
        }
        return super.onOptionsItemSelected(item);
    }

    public static boolean isXposedEnabled() {
        return false;
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    // This inner class was not part of the conflict, but is required for the file to be complete.
    private static class DepthPageTransformer implements ViewPager2.PageTransformer {
        private static final float MIN_SCALE = 0.85f;

        @Override
        public void transformPage(@NonNull android.view.View page, float position) {
            int pageWidth = page.getWidth();

            if (position < -1) {
                page.setAlpha(0f);
            } else if (position <= 0) {
                page.setAlpha(1f);
                page.setTranslationX(0f);
                page.setTranslationZ(0f);
                page.setScaleX(1f);
                page.setScaleY(1f);
            } else if (position <= 1) {
                page.setAlpha(1 - position);
                page.setTranslationX(pageWidth * -position);
                page.setTranslationZ(-1f);
                float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
                page.setScaleX(scaleFactor);
                page.setScaleY(scaleFactor);
            } else {
                page.setAlpha(0f);
            }
        }
    }
}
