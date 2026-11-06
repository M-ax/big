package com.example.big.smb;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SmbFavoritesCredentialsTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final SmbLocation folder = SmbLocation.parse("credentials-recovery-test:1445/Media/Exact%20Folder");
    private final List<String> tokens = new ArrayList<>();
    private final List<SmbLocation> folders = new ArrayList<>();

    @Before
    public void setUp() {
        favorite(folder);
    }

    @After
    public void tearDown() {
        for (String token : tokens) {
            SmbSession.remove(token);
        }
        for (SmbLocation location : folders) {
            new SmbFavoritesStore(context).remove(location);
        }
    }

    @Test
    public void restoresExactFavoriteAfterAllLiveSessionsAreRemovedAndUpdatesLatestLogin() {
        SmbSession first = new SmbSession(folder, "alice", "first-password", "FIRST", false);
        String token = register(first);
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        SmbSession.remove(token);
        String restored = recover(folder);
        assertNotNull(restored);
        assertNotEquals(token, restored);
        assertEquals(first.mediaOptions(), SmbSession.find(restored, folder.getUrl()).mediaOptions());
        SmbSession consumed = SmbFavoritesSessions.takeToken(restored, folder);
        assertNotNull(consumed);
        assertNull(SmbSession.find(restored, folder.getUrl()));

        SmbSession latest = new SmbSession(folder, "bob", "latest-password", "LATEST", false);
        String latestToken = register(latest);
        assertTrue(SmbFavoritesSessions.remember(context, latestToken, folder));
        SmbSession.remove(latestToken);
        String latestRestored = recover(folder);
        assertEquals(latest.mediaOptions(), SmbSession.find(latestRestored, folder.getUrl()).mediaOptions());
    }

    @Test
    public void automaticRecoveryNeverBorrowsSiblingParentHostOrPortCredentials() {
        String token = register(new SmbSession(folder, "alice", "private-password", "", false));
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        for (String address : new String[]{"credentials-recovery-test:1445/Media/Other",
                "credentials-recovery-test:1445/Media", "credentials-recovery-test/Media/Exact%20Folder",
                "different-recovery-test:1445/Media/Exact%20Folder"}) {
            SmbLocation other = SmbLocation.parse(address);
            favorite(other);
            assertNull(recover(other));
        }
        SmbSession.remove(token);
        String restored = recover(folder);
        assertNotNull(restored);
        assertNull(SmbSession.find(restored, "smb://different-recovery-test:1445/Media/movie.mp4"));
        assertNull(SmbSession.find(restored, "smb://credentials-recovery-test/Media/movie.mp4"));
        assertNotNull(SmbSession.find(restored, folder.child("movie.mp4", false).getUrl()));
    }

    @Test
    public void removalDeletesLoginAndRevokesPendingRecoveredTokensWithoutRemovingOtherFavorites() {
        SmbLocation other = folder.parent().child("Other", true);
        favorite(other);
        String token = register(new SmbSession(folder, "alice", "private-password", "", false));
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        assertTrue(SmbFavoritesSessions.remember(context, token, other));
        SmbSession.remove(token);
        String restored = recover(folder);
        new SmbFavoritesStore(context).remove(folder);
        assertNull(SmbSession.find(restored, folder.getUrl()));
        assertNull(new SmbCredentialsStore(context).load(folder));
        favorite(folder);
        assertNull(recover(folder));
        assertNotNull(recover(other));
    }

    @Test
    public void failedSessionInvalidatesItsCachedLoginsButNotUnrelatedAccounts() {
        SmbLocation other = folder.parent().child("Other", true);
        favorite(other);
        String token = register(new SmbSession(folder, "alice", "stale-password", "", false));
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        String unrelated = register(new SmbSession(other, "bob", "valid-password", "", false));
        assertTrue(SmbFavoritesSessions.remember(context, unrelated, other));
        SmbSession.remove(token);
        SmbSession cached = SmbFavoritesSessions.takeToken(recover(folder), folder);
        String active = register(cached);
        SmbFavoritesSessions.invalidate(context, cached, folder.child("Child", true));
        assertNull(SmbSession.find(active, folder.getUrl()));
        assertNull(recover(folder));
        assertTrue(new SmbFavoritesStore(context).contains(folder));
        assertNotNull(recover(other));
    }

    @Test
    public void existingFavoritePicksUpCurrentSessionOnlyAfterSuccessfulRemember() {
        assertNull(recover(folder));
        String token = register(new SmbSession(folder, "alice", "password", "", false));
        assertNull(recover(folder));
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        SmbSession.remove(token);
        assertNotNull(recover(folder));
    }

    @Test
    public void recoveredIntentTokenCannotBeConsumedForAnotherFolder() {
        String token = register(new SmbSession(folder, "alice", "password", "", false));
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        SmbSession.remove(token);
        String restored = recover(folder);
        assertNull(SmbFavoritesSessions.takeToken(restored, folder.parent()));
        assertNull(SmbSession.find(restored, folder.getUrl()));
    }

    private void favorite(SmbLocation location) {
        new SmbFavoritesStore(context).add(location);
        folders.add(location);
    }

    private String register(SmbSession session) {
        String token = session.register();
        tokens.add(token);
        return token;
    }

    private String recover(SmbLocation location) {
        String token = SmbFavoritesSessions.findToken(context, location);
        tokens.add(token);
        return token;
    }
}