package com.example.big.playback;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PlaybackInputTest {
    @Test
    public void acceptsSupportedNetworkUrls() {
        for (String scheme : new String[]{"http", "https", "rtsp", "rtsps", "smb", "ftp", "ftps", "udp", "rtp"}) {
            assertNotNull(PlaybackInput.parseNetworkUrl(scheme + "://server/media.mp4"));
        }
        assertEquals("HTTPS://server/media%20file.mp4?token=abc",
                PlaybackInput.parseNetworkUrl("  HTTPS://server/media%20file.mp4?token=abc  ").toString());
        assertNotNull(PlaybackInput.parseNetworkUrl("http://[::1]:8080/video"));
    }

    @Test
    public void rejectsMalformedUrlsAndNonNetworkInput() {
        for (String value : new String[]{null, "", " ", "server/video", "file:///video.mp4",
                "content://provider/file", "javascript:alert(1)", "https://", "https:video",
                "http://server:0/file", "http://server:70000/file", "http://server/a b",
                "http://server:bad/file", "http://server/video#fragment"}) {
            assertNull(value, PlaybackInput.parseNetworkUrl(value));
        }
    }

    @Test
    public void rejectsEmbeddedCredentials() {
        assertNull(PlaybackInput.parseNetworkUrl("smb://user:secret@server/share/movie.mkv"));
        assertNull(PlaybackInput.parseNetworkUrl("https://user@server/video"));
        assertNull(PlaybackInput.parseNetworkUrl("http://user%40name:secret@server/video"));
    }

    @Test
    public void formatsPlaybackTimes() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("0:00", PlaybackInput.formatTime(-1));
            assertEquals("0:00", PlaybackInput.formatTime(0));
            assertEquals("1:05", PlaybackInput.formatTime(65999));
            assertEquals("1:01:01", PlaybackInput.formatTime(3661000));
            assertEquals("100:00:00", PlaybackInput.formatTime(360000000));
        } finally {
            Locale.setDefault(previous);
        }
    }
}