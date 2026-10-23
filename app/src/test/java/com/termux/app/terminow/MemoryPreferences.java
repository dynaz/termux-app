package com.termux.app.terminow;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** SharedPreferences in memory, for the store in unit tests without Android. */
class MemoryPreferences implements SharedPreferences {

    private final Map<String, Object> mValues = new HashMap<>();

    @Override
    public synchronized Map<String, ?> getAll() {
        return new HashMap<>(mValues);
    }

    @Nullable
    @Override
    public synchronized String getString(String key, @Nullable String defValue) {
        Object value = mValues.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public synchronized Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object value = mValues.get(key);
        return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object value = mValues.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object value = mValues.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object value = mValues.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object value = mValues.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return mValues.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new Editor() {
            private final Map<String, Object> mChanges = new HashMap<>();
            private final Set<String> mRemoved = new HashSet<>();
            private boolean mClear;

            @Override
            public Editor putString(String key, @Nullable String value) {
                mChanges.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, @Nullable Set<String> values) {
                mChanges.put(key, values == null ? null : new HashSet<>(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                mChanges.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                mChanges.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                mChanges.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                mChanges.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                mRemoved.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                mClear = true;
                return this;
            }

            @Override
            public boolean commit() {
                synchronized (MemoryPreferences.this) {
                    if (mClear) mValues.clear();
                    for (String key : mRemoved) mValues.remove(key);
                    for (Map.Entry<String, Object> change : mChanges.entrySet()) {
                        if (change.getValue() == null) mValues.remove(change.getKey());
                        else mValues.put(change.getKey(), change.getValue());
                    }
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

}
