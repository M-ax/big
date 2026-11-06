package com.example.big.smb;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SmbFavoritesStoreTest {
    private SharedPreferences preferences;
    private SmbFavoritesStore store;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("smb_favorites_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        store = new SmbFavoritesStore(preferences);
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    @Test
    public void persistsCanonicalDirectoriesDeduplicatesAndRemovesAcrossInstances() {
        SmbLocation folder = SmbLocation.parse("SMB://SERVER:445/Media/My%20Films/100%25/");
        assertTrue(store.add(folder));
        assertFalse(store.add(SmbLocation.parse("server/Media/My Films/100%25")));
        SmbFavoritesStore reopened = new SmbFavoritesStore(preferences);
        assertEquals(Collections.singletonList(folder), reopened.getAll());
        assertTrue(reopened.contains(folder));
        assertEquals(Collections.singleton("smb://server/Media/My%20Films/100%25/"),
                preferences.getStringSet("directories", Collections.emptySet()));
        assertEquals(1, preferences.getAll().size());
        assertTrue(reopened.remove(folder));
        assertFalse(reopened.remove(folder));
        assertTrue(new SmbFavoritesStore(preferences).getAll().isEmpty());
    }

    @Test
    public void repairsMalformedEntriesWithoutRetainingCredentials() {
        preferences.edit().putStringSet("directories", new HashSet<>(Arrays.asList(
                "smb://SERVER:445/Media/", "smb://server/Media/", "smb://server/",
                "smb://alice:private-password@server/Media/", "smb://DOMAIN;alice@server/Media/",
                "smb://server/Media/?password=secret", "smb://server/Media/#secret",
                "smb://server/Media/%2E%2E/", "smb://server/Media/%ZZ/",
                "smb://server/Media/movie.mp4", "https://server/Media/", "not a URL")))
                .commit();
        assertEquals(Collections.singletonList(SmbLocation.parse("server/Media")), store.getAll());
        assertEquals(Collections.singleton("smb://server/Media/"),
                preferences.getStringSet("directories", Collections.emptySet()));
    }

    @Test
    public void wrongPreferenceTypeIsRepairedAndDoesNotBlockNewFavorites() {
        preferences.edit().putString("directories", "malformed private-password").commit();
        assertTrue(store.getAll().isEmpty());
        assertTrue(preferences.getStringSet("directories", Collections.emptySet()).isEmpty());
        assertTrue(store.add(SmbLocation.parse("server/Media")));
        assertEquals(1, store.getAll().size());
        preferences.edit().putInt("directories", 7).commit();
        assertTrue(store.getAll().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsServerRoot() {
        store.add(SmbLocation.parse("server"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFiles() {
        store.add(SmbLocation.parse("server/Media").child("movie.mp4", false));
    }

    @Test
    public void keepsDistinctPortsAndExactEscapedPaths() {
        SmbLocation first = SmbLocation.parse("server/Media").child("# ? + % 雪", true);
        SmbLocation second = SmbLocation.parse("server:1445/Media").child("# ? + % 雪", true);
        store.add(first);
        store.add(second);
        assertEquals(2, store.getAll().size());
        assertTrue(store.getAll().contains(first));
        assertTrue(store.getAll().contains(second));
    }
}