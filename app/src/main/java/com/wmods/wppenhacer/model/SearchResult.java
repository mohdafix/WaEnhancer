package com.wmods.wppenhacer.model;

import androidx.annotation.NonNull;

public class SearchResult {
    private final String key;
    private final String title;
    private final String summary;
    private final String category;
    private final Class<?> fragmentClass;

    public SearchResult(String key, String title, String summary, String category, Class<?> fragmentClass) {
        this.key = key;
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.fragmentClass = fragmentClass;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getCategory() {
        return category;
    }

    public Class<?> getFragmentClass() {
        return fragmentClass;
    }

    @NonNull
    @Override
    public String toString() {
        return title + " (" + category + ")";
    }
}
