package com.example.big.smb;

import org.junit.Test;

import java.net.URI;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SmbLocationTest {
    @Test
    public void acceptsManualAddressFormatsAndCanonicalizesHost() {
        SmbLocation expected = SmbLocation.parse("smb://nas.example/Media/Films/");
        for (String address : new String[]{"SMB://NAS.EXAMPLE/Media/Films",
                "nas.example/Media/Films", "\\\\nas.example\\Media\\Films"}) {
            assertEquals(address, expected, SmbLocation.parse(address));
            assertEquals(expected.hashCode(), SmbLocation.parse(address).hashCode());
        }
        assertEquals("smb://nas.example/Media/Films/", expected.getUrl());
        assertEquals("/Media/Films/", expected.getDecodedPath());
        assertEquals("nas.example", expected.getHost());
        assertEquals(445, expected.getPort());
        assertEquals(SmbLocation.parse("nas.example"), SmbLocation.parse("nas.example:445/"));
    }

    @Test
    public void acceptsIpAddressesAndExplicitPorts() {
        assertEquals("smb://192.168.1.2:1445/share/",
                SmbLocation.parse("192.168.1.2:1445/share").getUrl());
        SmbLocation ipv6 = SmbLocation.parse("smb://[2001:DB8::1]:1445/share");
        assertEquals("smb://[2001:db8::1]:1445/share/", ipv6.getUrl());
        assertEquals(1445, ipv6.getPort());
        assertEquals("smb://[::1]/", SmbLocation.parse("[::1]").getUrl());
    }

    @Test
    public void preservesSpecialNamesAndEncodesPlaybackUrlExactlyOnce() throws Exception {
        SmbLocation folder = SmbLocation.parse("nas/Media");
        String name = "A space % # + café 日本語 🎬.mkv";
        SmbLocation file = folder.child(name, false);
        assertEquals("smb://nas/Media/A%20space%20%25%20%23%20%2B%20caf%C3%A9%20"
                + "%E6%97%A5%E6%9C%AC%E8%AA%9E%20%F0%9F%8E%AC.mkv", file.getUrl());
        assertEquals("/Media/" + name, new URI(file.getUrl()).getPath());
        assertEquals("smb://nas/Media/" + name, file.getDisplayPath());
        assertEquals(folder, file.parent());
        assertEquals("/Media/" + name + "/", SmbLocation.parse(file.getUrl()).getDecodedPath());
        assertEquals("/Media/%2e%2e/", folder.child("%2e%2e", true).getDecodedPath());
        assertEquals(folder.child("%2e%2e", true),
                SmbLocation.parse("smb://nas/Media/%252e%252e/"));
        assertEquals("/Media/a+b/", SmbLocation.parse("smb://nas/Media/a+b").getDecodedPath());
    }

    @Test
    public void uncNamesAreLiteralRatherThanUrlEncoded() {
        SmbLocation location = SmbLocation.parse("\\\\nas\\Media\\100% # + %20");
        assertEquals("/Media/100% # + %20/", location.getDecodedPath());
        assertEquals("smb://nas/Media/100%25%20%23%20%2B%20%2520/", location.getUrl());
    }

    @Test
    public void hierarchyIsImmutableAndStopsAtServer() {
        SmbLocation root = SmbLocation.parse("nas");
        SmbLocation share = root.child("Media", true);
        SmbLocation folder = share.child("Movies", true);
        SmbLocation file = folder.child("film.mp4", false);
        assertTrue(root.isServerRoot());
        assertNull(root.parent());
        assertEquals(root, share.parent());
        assertEquals(share, folder.parent());
        assertEquals(folder, file.parent());
        assertFalse(folder.isServerRoot());
        assertEquals("smb://nas/", root.getUrl());
        assertNotEquals(folder.child("film.mp4", true), file);
        assertNotEquals(share, root.child("media", true));
        assertThrows(IllegalArgumentException.class, () -> root.child("file", false));
        assertThrows(IllegalArgumentException.class, () -> file.child("nested", true));
    }

    @Test
    public void rejectsMissingHostsCredentialsQueriesFragmentsAndInvalidAuthorities() {
        for (String value : new String[]{null, "", " ", "smb://", "smb:///share", "/share",
                "\\\\\\share", "http://nas/share", "smb:nas/share", "user@nas/share",
                "smb://user:secret@nas/share", "nas?server=other", "nas/share#fragment",
                "nas/share?query", "nas:0", "nas:65536", "nas:-1", "nas:", "nas:abc",
                "nas:+445", "nas:9999999999999", "[::1", "::1", "[not-ip]", "[fe80::1%eth0]",
                "bad host/share", "bad_host/share", "-host/share", "host-.com/share",
                "host..com/share", "999.1.1.1/share", "1.2.3/share", "nas%40evil/share"}) {
            IllegalArgumentException error = assertThrows(String.valueOf(value),
                    IllegalArgumentException.class, () -> SmbLocation.parse(value));
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    public void rejectsTraversalSeparatorsMalformedEscapesAndControlCharacters() {
        for (String path : new String[]{".", "..", "%2e", "%2E%2e", ".%2e", "%2e.",
                "a/../b", "a/./b", "%2fother", "%5cother", "a%2fb", "a%5Cb", "a//b",
                "%", "%2", "%xx", "%C0%AE", "%ED%A0%80", "%FF", "%00", "%0A", "a\nb"}) {
            assertThrows(path, IllegalArgumentException.class,
                    () -> SmbLocation.parse("smb://nas/share/" + path));
        }
        SmbLocation folder = SmbLocation.parse("nas/share");
        for (String name : new String[]{null, "", ".", "..", "../other", "a/b", "a\\b",
                "/absolute", "\\absolute", "x\u0000y", "x\ny", "\uD800"}) {
            assertThrows(String.valueOf(name), IllegalArgumentException.class,
                    () -> folder.child(name, true));
        }
        assertThrows(IllegalArgumentException.class, () -> SmbLocation.parse("nas/share/\uD800"));
    }

    @Test
    public void mediaExtensionsUseLocaleRootAndDoNotMistakeSubstrings() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            for (String name : new String[]{"film.AVI", "film.MKV", "film.mp4", "film.ts",
                    "film.webm", "audio.MP3", "audio.FLAC", "audio.m4a", "audio.opus"}) {
                assertTrue(name, SmbEntry.isPlayable(name));
            }
            for (String name : new String[]{null, "", "movie", "mp4", "file.pdf", "film.mp4.bak",
                    "film.mp4/", "film.mp4?token=x"}) {
                assertFalse(String.valueOf(name), SmbEntry.isPlayable(name));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}