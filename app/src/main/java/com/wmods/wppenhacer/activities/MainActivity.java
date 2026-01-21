package com.wmods.wppenhacer.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
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

    private ActivityMainBinding binding;
    private BatteryPermissionHelper batteryPermissionHelper = BatteryPermissionHelper.Companion.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

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
            }
        });
        binding.viewPager.setCurrentItem(2, false);
        createMainDir();
        FilePicker.registerFilePicker(this);

        handleSearchIntent(getIntent());
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

        // Prevent re-handling on configuration changes.
        intent.removeExtra(EXTRA_SEARCH_TAB_INDEX);
        intent.removeExtra(EXTRA_SEARCH_SCREEN_CLASS);
        intent.removeExtra(EXTRA_SEARCH_PREF_KEY);

        navigateToPreference(tabIndex, screenClass, prefKey);
    }

    private void navigateToPreference(int tabIndex, @Nullable String screenClassName, @Nullable String prefKey) {
        binding.viewPager.setCurrentItem(tabIndex, false);
        binding.viewPager.postDelayed(() -> tryNavigateToPreference(tabIndex, screenClassName, prefKey, 0), 50);
    }

    private void tryNavigateToPreference(int tabIndex, @Nullable String screenClassName, @Nullable String prefKey, int attempt) {
        var tabFragment = findTabFragment(tabIndex);
        if (tabFragment == null) {
            if (attempt < 20) {
                binding.viewPager.postDelayed(() -> tryNavigateToPreference(tabIndex, screenClassName, prefKey, attempt + 1), 50);
            }
            return;
        }

        if (tabIndex == 0 && tabFragment instanceof com.wmods.wppenhacer.ui.fragments.GeneralFragment generalFragment) {
            openGeneralScreenAndScroll(generalFragment, screenClassName, prefKey);
            return;
        }

        if (tabFragment instanceof androidx.preference.PreferenceFragmentCompat prefFragment) {
            if (prefKey != null) {
                prefFragment.scrollToPreference(prefKey);
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
            // Retry once the state is restored.
            binding.viewPager.postDelayed(() -> openGeneralScreenAndScroll(generalFragment, screenClassName, prefKey), 50);
            return;
        }

        // Reset to a clean stack, then open the target screen.
        fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);

        try {
            Class<?> clazz = Class.forName(target);
            var fragment = (androidx.fragment.app.Fragment) clazz.getDeclaredConstructor().newInstance();

            boolean isRoot = target.endsWith("$GeneralPreferenceFragment");
            var tx = fm.beginTransaction().replace(R.id.frag_container, fragment);
            if (!isRoot) {
                tx.addToBackStack(null);
            }
            tx.commitNow();

            if (prefKey != null && fragment instanceof androidx.preference.PreferenceFragmentCompat pf) {
                // Ensure preferences are laid out before scrolling.
                binding.viewPager.post(() -> pf.scrollToPreference(prefKey));
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private Fragment findTabFragment(int tabIndex) {
        // ViewPager2 fragments are typically tagged as "f0", "f1", ...
        Fragment byTag = getSupportFragmentManager().findFragmentByTag("f" + tabIndex);
        if (byTag != null) return byTag;

        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (tabIndex == 1 && f instanceof com.wmods.wppenhacer.ui.fragments.PrivacyFragment) return f;
            if (tabIndex == 3 && f instanceof com.wmods.wppenhacer.ui.fragments.MediaFragment) return f;
            if (tabIndex == 4 && f instanceof com.wmods.wppenhacer.ui.fragments.CustomizationFragment) return f;
            if (tabIndex == 0 && f instanceof com.wmods.wppenhacer.ui.fragments.GeneralFragment) return f;
        }
        return null;
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
        onBackPressed();
        return super.onSupportNavigateUp();
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
