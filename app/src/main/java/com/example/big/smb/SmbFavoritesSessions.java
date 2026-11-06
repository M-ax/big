package com.example.big.smb;

import android.content.Context;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Live-session hints plus exact-favorite, device-local credential recovery. */
public final class SmbFavoritesSessions {
    private static final Map<String, String> TOKENS = new LinkedHashMap<>();
    private static final Map<String, String> RECOVERED_TOKENS = new LinkedHashMap<>();

    private SmbFavoritesSessions() {
    }

    public static synchronized void remember(String token, SmbLocation location) {
        prune();
        if (location != null && SmbSession.find(token, location.getUrl()) != null) {
            TOKENS.remove(token);
            TOKENS.put(token, location.getUrl());
        }
    }

    public static synchronized String findToken(SmbLocation location) {
        prune();
        String result = null;
        if (location != null) {
            for (String token : TOKENS.keySet()) {
                SmbSession session = SmbSession.find(token, location.getUrl());
                if (session != null && (session.getCredentialLocation() == null
                        || session.getCredentialLocation().equals(location))) {
                    result = token;
                }
            }
        }
        return result;
    }

    /** Call only after successfully listing this location, or starring the successful listing. */
    public static boolean remember(Context context, String token, SmbLocation location) {
        SmbSession session = location == null ? null : SmbSession.find(token, location.getUrl());
        if (session == null) {
            return false;
        }
        remember(token, location);
        return new SmbFavoritesStore(context).saveCredentials(location, session);
    }

    /** Unlike the legacy live-only lookup, recovery never borrows another folder's login. */
    public static String findToken(Context context, SmbLocation location) {
        SmbFavoritesStore favorites = new SmbFavoritesStore(context);
        if (!favorites.contains(location)) {
            return null;
        }
        synchronized (SmbFavoritesSessions.class) {
            prune();
            String result = null;
            for (Map.Entry<String, String> entry : TOKENS.entrySet()) {
                if (location.getUrl().equals(entry.getValue())) {
                    result = entry.getKey();
                }
            }
            if (result != null) {
                return result;
            }
        }
        SmbSession session = new SmbCredentialsStore(context).load(location);
        if (session == null || !favorites.contains(location)) {
            return null;
        }
        synchronized (SmbFavoritesSessions.class) {
            String token = session.register();
            RECOVERED_TOKENS.put(token, location.getUrl());
            return token;
        }
    }

    /** A recovered intent token is one-use; the browser registers its own token after success. */
    public static synchronized SmbSession takeToken(String token, SmbLocation location) {
        SmbSession session = SmbSession.find(token, location.getUrl());
        String recoveredLocation = RECOVERED_TOKENS.remove(token);
        if (recoveredLocation != null) {
            SmbSession.remove(token);
            if (!location.getUrl().equals(recoveredLocation)) {
                return null;
            }
        }
        return session;
    }

    public static void invalidate(Context context, SmbSession session, SmbLocation location) {
        SmbCredentialsStore credentials = new SmbCredentialsStore(context);
        credentials.remove(location);
        forget(location);
        if (session != null) {
            for (SmbLocation saved : session.getSavedCredentials()) {
                credentials.remove(saved);
                forget(saved);
            }
            session.invalidate();
        }
    }

    static synchronized void forget(SmbLocation location) {
        if (location != null) {
            TOKENS.entrySet().removeIf(entry -> location.getUrl().equals(entry.getValue()));
            RECOVERED_TOKENS.entrySet().removeIf(entry -> {
                if (location.getUrl().equals(entry.getValue())) {
                    SmbSession.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    private static void prune() {
        RECOVERED_TOKENS.entrySet().removeIf(entry ->
                SmbSession.find(entry.getKey(), entry.getValue()) == null);
        Iterator<Map.Entry<String, String>> iterator = TOKENS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            if (SmbSession.find(entry.getKey(), entry.getValue()) == null) {
                iterator.remove();
            }
        }
    }
}