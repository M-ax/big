package com.example.big.smb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the connection across rotations; all SMB work stays off the UI thread. */
public class SmbBrowserViewModel extends ViewModel {
    public static final class State {
        public final SmbLocation location;
        public final List<SmbEntry> entries;
        public final boolean busy;
        public final SmbException.Kind error;

        State(SmbLocation location, List<SmbEntry> entries, boolean busy, SmbException.Kind error) {
            this.location = location;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.busy = busy;
            this.error = error;
        }
    }

    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(null, Collections.emptyList(), false, null));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SmbClient client;
    private String sessionToken;
    private SmbLocation retryLocation;
    private SmbLocation loginLocation;
    // setFavoritesContext retains only getApplicationContext(), never an Activity.
    @SuppressLint("StaticFieldLeak")
    private Context favoritesContext;
    private State rememberedState;
    private boolean connectionAttempted;
    private int generation;
    // Browser-lifetime cache; saved activity state contains only locations and row coordinates.
    private final Map<String, SmbScrollPosition> scrollPositions = new HashMap<>();

    public void saveScrollPosition(SmbLocation location, SmbScrollPosition position) {
        scrollPositions.put(location.getUrl(), position);
    }

    public SmbScrollPosition getScrollPosition(SmbLocation location) {
        return scrollPositions.get(location.getUrl());
    }

    public Bundle saveScrollPositions() {
        Bundle saved = new Bundle();
        for (Map.Entry<String, SmbScrollPosition> entry : scrollPositions.entrySet()) {
            SmbScrollPosition position = entry.getValue();
            Bundle value = new Bundle();
            value.putInt("position", position.position);
            value.putInt("top", position.top);
            value.putString("anchor", position.anchor);
            value.putBoolean("title", position.titleVisible);
            saved.putBundle(entry.getKey(), value);
        }
        return saved;
    }

    public void restoreScrollPositions(Bundle saved) {
        if (saved == null || !scrollPositions.isEmpty()) {
            return;
        }
        for (String key : saved.keySet()) {
            try {
                SmbLocation location = SmbLocation.parse(key);
                Bundle value = saved.getBundle(key);
                if (value != null) {
                    saveScrollPosition(location, new SmbScrollPosition(value.getInt("position"),
                            value.getInt("top"), value.getString("anchor"), value.getBoolean("title")));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid saved locations cannot be used as connection addresses.
            }
        }
    }

    public LiveData<State> getState() {
        return state;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setFavoritesContext(Context context) {
        favoritesContext = context.getApplicationContext();
    }

    public SmbLocation getLoginLocation() {
        return loginLocation;
    }

    public boolean hasConnectionAttempted() {
        return connectionAttempted;
    }

    public boolean rememberSuccessfulListing(State value) {
        if (rememberedState == value || value.location == null || value.busy || value.error != null) {
            return true;
        }
        rememberedState = value;
        return favoritesContext == null || SmbFavoritesSessions.remember(
                favoritesContext, sessionToken, value.location);
    }

    public void connect(SmbLocation location, SmbSession session) {
        if (state.getValue().busy) {
            return;
        }
        if (session == null || !session.accepts(location)) {
            throw new IllegalArgumentException("Incompatible session");
        }
        disconnect();
        connectionAttempted = true;
        loginLocation = location;
        int request = generation;
        state.setValue(new State(null, Collections.emptyList(), true, null));
        worker.execute(() -> {
            SmbClient opened = null;
            try {
                opened = session.newClient();
                List<SmbEntry> entries = opened.list(location);
                SmbClient connected = opened;
                main.post(() -> {
                    if (request != generation) {
                        closeLater(connected);
                        return;
                    }
                    client = connected;
                    sessionToken = session.register();
                    retryLocation = location;
                    state.setValue(new State(location, entries, false, null));
                });
            } catch (IOException | RuntimeException exception) {
                close(opened);
                main.post(() -> {
                    if (request == generation) {
                        if (kind(exception) == SmbException.Kind.AUTHENTICATION) {
                            invalidateLogin(session, location);
                        }
                        state.setValue(new State(null, Collections.emptyList(), false, kind(exception)));
                    }
                });
            }
        });
    }

    public void navigate(SmbLocation location) {
        State previous = state.getValue();
        if (client == null || previous.busy || location == null
                || SmbSession.find(sessionToken, location.getUrl()) == null) {
            return;
        }
        SmbClient connection = client;
        int request = generation;
        retryLocation = location;
        state.setValue(new State(previous.location, previous.entries, true, null));
        worker.execute(() -> {
            try {
                List<SmbEntry> entries = connection.list(location);
                main.post(() -> {
                    if (request == generation) {
                        state.setValue(new State(location, entries, false, null));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                main.post(() -> {
                    if (request == generation) {
                        if (kind(exception) == SmbException.Kind.AUTHENTICATION) {
                            invalidateLogin(SmbSession.find(sessionToken, location.getUrl()), location);
                            loginLocation = location;
                            disconnect();
                            state.setValue(new State(null, Collections.emptyList(), false, kind(exception)));
                        } else {
                            state.setValue(new State(previous.location, previous.entries, false, kind(exception)));
                        }
                    }
                });
            }
        });
    }

    public void retry() {
        navigate(retryLocation);
    }

    public void disconnect() {
        generation++;
        connectionAttempted = true;
        SmbSession.remove(sessionToken);
        sessionToken = null;
        closeLater(client);
        client = null;
        retryLocation = null;
        state.setValue(new State(null, Collections.emptyList(), false, null));
    }

    private void invalidateLogin(SmbSession session, SmbLocation location) {
        if (favoritesContext != null) {
            SmbFavoritesSessions.invalidate(favoritesContext, session, location);
        } else if (session != null) {
            session.invalidate();
        }
    }

    private static SmbException.Kind kind(Exception exception) {
        return exception instanceof SmbException ? ((SmbException) exception).kind
                : SmbException.Kind.NETWORK;
    }

    private void closeLater(SmbClient connection) {
        if (connection != null) {
            // A late connect completion can arrive after onCleared shut down the worker.
            if (worker.isShutdown()) {
                new Thread(() -> close(connection), "smb-close").start();
            } else {
                worker.execute(() -> close(connection));
            }
        }
    }

    private static void close(SmbClient connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException | RuntimeException ignored) {
                // Disconnection must not expose server errors or retain credentials in the UI.
            }
        }
    }

    @Override
    protected void onCleared() {
        disconnect();
        worker.shutdown();
    }
}