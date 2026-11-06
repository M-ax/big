package com.example.big.playback;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class PlaybackInput {
    private static final Set<String> NETWORK_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "rtsp", "rtsps", "smb", "ftp", "ftps", "udp", "rtp"));

    private PlaybackInput() {
    }

    public static URI parseNetworkUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            if (uri.getScheme() == null
                    || !NETWORK_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                return null;
            }
            return uri;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    public static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d",
                    seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}