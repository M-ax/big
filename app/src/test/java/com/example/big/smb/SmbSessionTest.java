package com.example.big.smb;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SmbSessionTest {
    @Test
    public void tokenIsOpaqueAndBoundToServerAndProtocol() {
        SmbSession session = new SmbSession(SmbLocation.parse("server:1445/Media"),
                "alice", "private-password", "WORKGROUP", false);
        String token = session.register();
        try {
            assertFalse(token.contains("alice"));
            assertFalse(token.contains("private-password"));
            assertSame(session, SmbSession.find(token, "smb://server:1445/Media/a.mp4"));
            assertNull(SmbSession.find(token, "smb://another:1445/Media/a.mp4"));
            assertNull(SmbSession.find(token, "smb://server/Media/a.mp4"));
            assertNull(SmbSession.find(token, "https://server:1445/Media/a.mp4"));
            assertNull(SmbSession.find(token, "smb://user:password@server:1445/Media/a.mp4"));
            assertNull(SmbSession.find(token, "smb://server:1445/Media/a.mp4?token=1"));
            assertNull(SmbSession.find(token, "smb://server:1445/Media/a.mp4#part"));
        } finally {
            SmbSession.remove(token);
        }
        assertNull(SmbSession.find(token, "smb://server:1445/Media/a.mp4"));
    }

    @Test
    public void credentialOptionsPreservePasswordWhitespaceAndPunctuation() {
        SmbSession session = new SmbSession(SmbLocation.parse("server/Media"),
                " alice ", " p:@ss #+% ", " DOMAIN ", false);
        assertEquals(":smb-user=alice", session.mediaOptions().get(0));
        assertEquals(":smb-pwd= p:@ss #+% ", session.mediaOptions().get(1));
        assertEquals(":smb-domain=DOMAIN", session.mediaOptions().get(2));
    }

    @Test
    public void guestDoesNotReuseTypedCredentials() {
        SmbSession session = new SmbSession(SmbLocation.parse("server"),
                "alice", "private-password", "DOMAIN", true);
        assertEquals(":smb-user=Guest", session.mediaOptions().get(0));
        assertEquals(":smb-pwd=", session.mediaOptions().get(1));
        assertEquals(":smb-domain=", session.mediaOptions().get(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void accountRequiresUsername() {
        new SmbSession(SmbLocation.parse("server"), " ", "password", "", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNativeOptionNullTerminator() {
        new SmbSession(SmbLocation.parse("server"), "alice", "pass\u0000word", "", false);
    }

    @Test
    public void missingOrExpiredTokensCannotAuthenticate() {
        assertNull(SmbSession.find(null, "smb://server/Media/a.mp4"));
        assertNull(SmbSession.find("expired", "smb://server/Media/a.mp4"));
        SmbSession.remove(null);
    }
}