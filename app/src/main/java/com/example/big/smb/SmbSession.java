package com.example.big.smb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Live credentials. Intents carry an opaque handle; only exact favorites may cache a login. */
public final class SmbSession {
    public static final String EXTRA_SESSION = "com.example.big.SMB_SESSION";
    private static final Map<String, SmbSession> SESSIONS = new ConcurrentHashMap<>();
    private final String authority;
    private final String username;
    private final String password;
    private final String domain;
    private final boolean guest;
    private SmbLocation credentialLocation;
    private final Set<SmbLocation> savedCredentials = new HashSet<>();

    public SmbSession(SmbLocation location, String username, String password,
                      String domain, boolean guest) {
        this.authority = URI.create(location.getUrl()).getRawAuthority();
        this.username = guest ? "" : checked(username.trim());
        this.password = guest ? "" : checked(password);
        this.domain = guest ? "" : checked(domain.trim());
        this.guest = guest;
        if (!guest && this.username.isEmpty()) {
            throw new IllegalArgumentException("Username required");
        }
    }

    private static String checked(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Invalid credential characters");
            }
        }
        return value;
    }

    public SmbClient newClient() throws java.io.IOException {
        return new JcifsSmbClient(username, password, domain, guest);
    }

    boolean accepts(SmbLocation location) {
        return location != null && authority.equalsIgnoreCase(URI.create(location.getUrl()).getRawAuthority());
    }

    void writeCredentials(DataOutputStream output) throws IOException {
        output.writeBoolean(guest);
        output.writeUTF(username);
        output.writeUTF(password);
        output.writeUTF(domain);
    }

    static SmbSession readCredentials(DataInputStream input, SmbLocation location) throws IOException {
        boolean guest = input.readBoolean();
        SmbSession session = new SmbSession(location, input.readUTF(), input.readUTF(), input.readUTF(), guest);
        session.credentialLocation = location;
        return session;
    }

    SmbLocation getCredentialLocation() {
        return credentialLocation;
    }

    synchronized void rememberCredentials(SmbLocation location) {
        savedCredentials.add(location);
    }

    synchronized Set<SmbLocation> getSavedCredentials() {
        Set<SmbLocation> result = new HashSet<>(savedCredentials);
        if (credentialLocation != null) {
            result.add(credentialLocation);
        }
        return result;
    }

    void invalidate() {
        SESSIONS.entrySet().removeIf(entry -> entry.getValue() == this);
    }

    public String register() {
        String token = UUID.randomUUID().toString();
        SESSIONS.put(token, this);
        return token;
    }

    public static void remove(String token) {
        if (token != null) {
            SESSIONS.remove(token);
        }
    }

    public static SmbSession find(String token, String mediaUrl) {
        if (token == null) {
            return null;
        }
        SmbSession session = SESSIONS.get(token);
        try {
            URI uri = URI.create(mediaUrl);
            return session != null && "smb".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && session.authority.equalsIgnoreCase(uri.getRawAuthority()) ? session : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public List<String> mediaOptions() {
        return Arrays.asList(":smb-user=" + (guest ? "Guest" : username),
                ":smb-pwd=" + password, ":smb-domain=" + domain);
    }
}