package com.example.big.smb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Stores directory URLs only; encrypted credentials live in a separate, non-backed-up store. */
public final class SmbFavoritesStore {
    private static final String DIRECTORIES = "directories";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final SmbCredentialsStore credentials;

    public SmbFavoritesStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences("smb_favorites", Context.MODE_PRIVATE),
                new SmbCredentialsStore(context));
    }

    SmbFavoritesStore(SharedPreferences preferences) {
        this(preferences, null);
    }

    SmbFavoritesStore(SharedPreferences preferences, SmbCredentialsStore credentials) {
        this.preferences = preferences;
        this.credentials = credentials;
    }

    public List<SmbLocation> getAll() {
        synchronized (LOCK) {
            List<SmbLocation> result = new ArrayList<>();
            for (String url : read()) {
                result.add(SmbLocation.parse(url));
            }
            return Collections.unmodifiableList(result);
        }
    }

    public boolean contains(SmbLocation location) {
        synchronized (LOCK) {
            return isFolder(location) && read().contains(location.getUrl());
        }
    }

    public boolean add(SmbLocation location) {
        if (!isFolder(location)) {
            throw new IllegalArgumentException("A share or directory is required.");
        }
        synchronized (LOCK) {
            Set<String> urls = read();
            boolean changed = urls.add(location.getUrl());
            if (changed) {
                write(urls);
            }
            return changed;
        }
    }

    public boolean remove(SmbLocation location) {
        boolean changed;
        synchronized (LOCK) {
            Set<String> urls = read();
            changed = location != null && urls.remove(location.getUrl());
            if (credentials != null) {
                credentials.remove(location);
            }
            if (changed) {
                write(urls);
            }
        }
        SmbFavoritesSessions.forget(location);
        return changed;
    }

    boolean saveCredentials(SmbLocation location, SmbSession session) {
        synchronized (LOCK) {
            return !contains(location) || (credentials != null && credentials.save(location, session));
        }
    }

    private Set<String> read() {
        Object stored = preferences.getAll().get(DIRECTORIES);
        Set<String> urls = new TreeSet<>();
        if (stored instanceof Set<?>) {
            for (Object value : (Set<?>) stored) {
                if (!(value instanceof String)) {
                    continue;
                }
                String url = (String) value;
                if (!url.regionMatches(true, 0, "smb://", 0, 6) || !url.endsWith("/")) {
                    continue;
                }
                try {
                    SmbLocation location = SmbLocation.parse(url);
                    if (isFolder(location)) {
                        urls.add(location.getUrl());
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore and scrub malformed or credential-bearing persisted addresses.
                }
            }
        }
        if (stored != null && !urls.equals(stored)) {
            write(urls);
        }
        return urls;
    }

    private void write(Set<String> urls) {
        preferences.edit().putStringSet(DIRECTORIES, new TreeSet<>(urls)).apply();
    }

    private static boolean isFolder(SmbLocation location) {
        return location != null && location.isDirectory() && !location.isServerRoot();
    }
}