package com.example.big.smb;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SmbLocation {
    private final String host;
    private final int port;
    private final List<String> segments;
    private final boolean directory;

    private SmbLocation(String host, int port, List<String> segments, boolean directory) {
        this.host = host;
        this.port = port;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.directory = directory;
    }

    /** URL/bare paths decode escapes once; UNC paths and child names are literal SMB names. */
    public static SmbLocation parse(String address) throws IllegalArgumentException {
        if (address == null) {
            throw invalidAddress();
        }
        validateText(address);
        String value = address.trim();
        boolean unc = value.startsWith("\\\\");
        if (unc) {
            value = value.substring(2).replace('\\', '/');
        } else {
            if (value.regionMatches(true, 0, "smb://", 0, 6)) {
                value = value.substring(6);
            }
            if (value.indexOf('\\') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
                throw invalidAddress();
            }
        }
        int slash = value.indexOf('/');
        String authority = slash < 0 ? value : value.substring(0, slash);
        if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0) {
            throw invalidAddress();
        }
        String host;
        int port;
        try {
            URI uri = new URI("smb://" + authority + "/").parseServerAuthority();
            host = uri.getHost();
            port = uri.getPort();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !"/".equals(uri.getRawPath())) {
                throw invalidAddress();
            }
            String suffix = authority.substring(host.length());
            if (!suffix.isEmpty() && !suffix.matches(":[0-9]+")) {
                throw invalidAddress();
            }
            if (port == 0 || port > 65535 || (port < 0 && !suffix.isEmpty())) {
                throw invalidAddress();
            }
        } catch (URISyntaxException exception) {
            throw invalidAddress();
        }
        List<String> segments = new ArrayList<>();
        if (slash >= 0 && slash < value.length() - 1) {
            String path = value.substring(slash + 1);
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            for (String segment : path.split("/", -1)) {
                String name = unc ? segment : decode(segment);
                validateName(name);
                segments.add(name);
            }
        }
        return new SmbLocation(host.toLowerCase(Locale.ROOT), port < 0 ? 445 : port, segments, true);
    }

    public String getUrl() {
        StringBuilder result = new StringBuilder(authority()).append('/');
        for (int index = 0; index < segments.size(); index++) {
            result.append(encode(segments.get(index)));
            if (directory || index < segments.size() - 1) {
                result.append('/');
            }
        }
        return result.toString();
    }

    public String getDisplayPath() {
        return authority() + getDecodedPath();
    }

    /** The host includes brackets for IPv6, as required by Java URL authorities. */
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDecodedPath() {
        StringBuilder result = new StringBuilder("/");
        for (int index = 0; index < segments.size(); index++) {
            result.append(segments.get(index));
            if (directory || index < segments.size() - 1) {
                result.append('/');
            }
        }
        return result.toString();
    }

    public boolean isServerRoot() {
        return segments.isEmpty();
    }

    public boolean isDirectory() {
        return directory;
    }

    public SmbLocation parent() {
        return isServerRoot() ? null
                : new SmbLocation(host, port, segments.subList(0, segments.size() - 1), true);
    }

    public SmbLocation child(String name, boolean directory) {
        if (!this.directory || (isServerRoot() && !directory)) {
            throw invalidAddress();
        }
        validateName(name);
        List<String> childSegments = new ArrayList<>(segments);
        childSegments.add(name);
        return new SmbLocation(host, port, childSegments, directory);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SmbLocation)) {
            return false;
        }
        SmbLocation location = (SmbLocation) other;
        return port == location.port && directory == location.directory
                && host.equals(location.host) && segments.equals(location.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, segments, directory);
    }

    private String authority() {
        return "smb://" + host + (port == 445 ? "" : ":" + port);
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw invalidAddress();
        }
        validateText(name);
    }

    private static void validateText(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)) {
                throw invalidAddress();
            }
            if (Character.isHighSurrogate(character)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw invalidAddress();
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalidAddress();
            }
        }
    }

    private static String decode(String segment) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segment.length();) {
            if (segment.charAt(index) != '%') {
                result.append(segment.charAt(index++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < segment.length() && segment.charAt(index) == '%') {
                if (index + 2 >= segment.length()) {
                    throw invalidAddress();
                }
                int high = Character.digit(segment.charAt(index + 1), 16);
                int low = Character.digit(segment.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalidAddress();
                }
                bytes.write(high * 16 + low);
                index += 3;
            }
            try {
                result.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException exception) {
                throw invalidAddress();
            }
        }
        return result.toString();
    }

    private static String encode(String name) {
        StringBuilder result = new StringBuilder();
        for (byte value : name.getBytes(StandardCharsets.UTF_8)) {
            int character = value & 0xff;
            if (character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || character == '-'
                    || character == '.' || character == '_' || character == '~') {
                result.append((char) character);
            } else {
                result.append('%').append("0123456789ABCDEF".charAt(character >>> 4))
                        .append("0123456789ABCDEF".charAt(character & 15));
            }
        }
        return result.toString();
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("Invalid SMB address or path.");
    }
}