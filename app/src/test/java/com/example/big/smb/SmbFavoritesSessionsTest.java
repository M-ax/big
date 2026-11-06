package com.example.big.smb;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SmbFavoritesSessionsTest {
    @Test
    public void reusesOnlyLiveSessionsWithMatchingHostAndPort() {
        SmbLocation folder = SmbLocation.parse("favorites-server:1445/Media/Films");
        String token = new SmbSession(folder, "alice", "private-password", "DOMAIN", false).register();
        try {
            SmbFavoritesSessions.remember(token, folder);
            assertEquals(token, SmbFavoritesSessions.findToken(
                    SmbLocation.parse("FAVORITES-SERVER:1445/Other/Exact%20Folder")));
            assertNull(SmbFavoritesSessions.findToken(SmbLocation.parse("other-server:1445/Media")));
            assertNull(SmbFavoritesSessions.findToken(SmbLocation.parse("favorites-server/Media")));
        } finally {
            SmbSession.remove(token);
        }
        assertNull(SmbFavoritesSessions.findToken(folder));
    }

    @Test
    public void expiredAndMismatchedTokensCannotReplaceACompatibleSession() {
        SmbLocation folder = SmbLocation.parse("favorites-server/Media");
        String token = new SmbSession(folder, "", "", "", true).register();
        try {
            SmbFavoritesSessions.remember(token, folder);
            SmbFavoritesSessions.remember("expired", folder);
            SmbFavoritesSessions.remember(token, SmbLocation.parse("another-server/Media"));
            assertEquals(token, SmbFavoritesSessions.findToken(folder));
            assertNull(SmbFavoritesSessions.findToken(SmbLocation.parse("another-server/Media")));
        } finally {
            SmbSession.remove(token);
        }
    }

    @Test
    public void disconnectingLatestSessionCanFallBackToAnotherStillLiveSession() {
        SmbLocation folder = SmbLocation.parse("favorites-multiple/Media");
        String first = new SmbSession(folder, "alice", "first-password", "", false).register();
        String second = new SmbSession(folder, "bob", "second-password", "", false).register();
        try {
            SmbFavoritesSessions.remember(first, folder);
            SmbFavoritesSessions.remember(second, folder);
            assertEquals(second, SmbFavoritesSessions.findToken(folder));
            SmbSession.remove(second);
            assertEquals(first, SmbFavoritesSessions.findToken(folder));
        } finally {
            SmbSession.remove(first);
            SmbSession.remove(second);
        }
        assertNull(SmbFavoritesSessions.findToken(folder));
    }
}