package com.wmods.wppenhacer.search;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.wmods.wppenhacer.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SettingsSearchIndex {

    public static final class Entry {
        @NonNull
        public final String title;
        @Nullable
        public final String summary;
        @Nullable
        public final String breadcrumb;
        public final int tabIndex;
        @NonNull
        public final String screenClassName;
        @Nullable
        public final String preferenceKey;
        @NonNull
        final String matchText;

        Entry(
                @NonNull String title,
                @Nullable String summary,
                @Nullable String breadcrumb,
                int tabIndex,
                @NonNull String screenClassName,
                @Nullable String preferenceKey
        ) {
            this.title = title;
            this.summary = summary;
            this.breadcrumb = breadcrumb;
            this.tabIndex = tabIndex;
            this.screenClassName = screenClassName;
            this.preferenceKey = preferenceKey;

            StringBuilder sb = new StringBuilder();
            sb.append(title);
            if (!TextUtils.isEmpty(summary)) sb.append(' ').append(summary);
            if (!TextUtils.isEmpty(breadcrumb)) sb.append(' ').append(breadcrumb);
            this.matchText = sb.toString().toLowerCase(Locale.ROOT);
        }
    }

    private static final class ScreenSpec {
        final int tabIndex;
        final int xmlRes;
        final String screenClassName;
        final String screenTitle;

        ScreenSpec(int tabIndex, int xmlRes, String screenClassName, String screenTitle) {
            this.tabIndex = tabIndex;
            this.xmlRes = xmlRes;
            this.screenClassName = screenClassName;
            this.screenTitle = screenTitle;
        }
    }

    private static volatile List<Entry> CACHE;

    private SettingsSearchIndex() {
    }

    @NonNull
    public static List<Entry> get(@NonNull Context context) {
        var cached = CACHE;
        if (cached != null) return cached;

        synchronized (SettingsSearchIndex.class) {
            cached = CACHE;
            if (cached != null) return cached;
            cached = buildIndex(context.getApplicationContext());
            CACHE = cached;
            return cached;
        }
    }

    @NonNull
    public static List<Entry> filter(@NonNull List<Entry> all, @NonNull String needleLowerCase, int limit) {
        if (needleLowerCase.trim().isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.matchText.contains(needleLowerCase)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    @NonNull
    private static List<Entry> buildIndex(@NonNull Context context) {
        ArrayList<Entry> out = new ArrayList<>();

        ArrayList<ScreenSpec> screens = new ArrayList<>();
        screens.add(new ScreenSpec(
                0,
                R.xml.fragment_general,
                "com.wmods.wppenhacer.ui.fragments.GeneralFragment$GeneralPreferenceFragment",
                context.getString(R.string.general)
        ));
        screens.add(new ScreenSpec(
                0,
                R.xml.preference_general_home,
                "com.wmods.wppenhacer.ui.fragments.GeneralFragment$HomeGeneralPreference",
                context.getString(R.string.general)
        ));
        screens.add(new ScreenSpec(
                0,
                R.xml.preference_general_homescreen,
                "com.wmods.wppenhacer.ui.fragments.GeneralFragment$HomeScreenGeneralPreference",
                context.getString(R.string.home_screen)
        ));
        screens.add(new ScreenSpec(
                0,
                R.xml.preference_general_conversation,
                "com.wmods.wppenhacer.ui.fragments.GeneralFragment$ConversationGeneralPreference",
                context.getString(R.string.conversation)
        ));
        screens.add(new ScreenSpec(
                1,
                R.xml.fragment_privacy,
                "com.wmods.wppenhacer.ui.fragments.PrivacyFragment",
                context.getString(R.string.privacy)
        ));
        screens.add(new ScreenSpec(
                3,
                R.xml.fragment_media,
                "com.wmods.wppenhacer.ui.fragments.MediaFragment",
                context.getString(R.string.media)
        ));
        screens.add(new ScreenSpec(
                4,
                R.xml.fragment_customization,
                "com.wmods.wppenhacer.ui.fragments.CustomizationFragment",
                context.getString(R.string.perso)
        ));

        for (ScreenSpec screen : screens) {
            try {
                PreferenceManager pm = new PreferenceManager(context);
                var preferenceScreen = pm.inflateFromResource(context, screen.xmlRes, null);
                ArrayList<String> path = new ArrayList<>();
                if (!TextUtils.isEmpty(screen.screenTitle)) {
                    path.add(screen.screenTitle);
                }
                collect(out, preferenceScreen, path, screen);
            } catch (Throwable ignored) {
                // If one screen fails to inflate (custom prefs, missing classes), keep the rest.
            }
        }
        return out;
    }

    private static void collect(
            @NonNull List<Entry> out,
            @NonNull Preference pref,
            @NonNull ArrayList<String> path,
            @NonNull ScreenSpec screen
    ) {
        if (pref instanceof PreferenceGroup group) {
            CharSequence titleCs = group.getTitle();
            boolean pushed = false;
            if (!TextUtils.isEmpty(titleCs) && !(group instanceof androidx.preference.PreferenceScreen)) {
                path.add(titleCs.toString());
                pushed = true;
            }

            for (int i = 0; i < group.getPreferenceCount(); i++) {
                collect(out, group.getPreference(i), path, screen);
            }

            if (pushed) {
                path.remove(path.size() - 1);
            }
            return;
        }

        CharSequence titleCs = pref.getTitle();
        String title = titleCs == null ? null : titleCs.toString();
        if (TextUtils.isEmpty(title)) {
            return;
        }

        String summary = null;
        CharSequence summaryCs = pref.getSummary();
        if (!TextUtils.isEmpty(summaryCs)) summary = summaryCs.toString();

        String breadcrumb = String.join(" > ", path);

        String targetScreen = screen.screenClassName;
        String prefKey = pref.getKey();

        if (!TextUtils.isEmpty(pref.getFragment())) {
            targetScreen = pref.getFragment();
            prefKey = null;
        }

        out.add(new Entry(title, summary, breadcrumb, screen.tabIndex, targetScreen, prefKey));
    }
}
