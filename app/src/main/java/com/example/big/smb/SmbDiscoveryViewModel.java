package com.example.big.smb;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SmbDiscoveryViewModel extends AndroidViewModel {
    public enum Status { IDLE, SEARCHING, COMPLETE, STOPPED, NO_NETWORK, ERROR, PERMISSION_REQUIRED }

    public static final class Server {
        public final String name;
        public final SmbLocation location;

        Server(String name, SmbLocation location) {
            this.name = name;
            this.location = location;
        }
    }

    public static final class State {
        public final List<Server> servers;
        public final Status status;

        State(List<Server> servers, Status status) {
            this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
            this.status = status;
        }
    }

    public interface Listener {
        void found(String name, SmbLocation location);
        void finished(Status status);
    }

    /** Callbacks and lifecycle methods run on the main thread. */
    public interface Scanner {
        void start(Listener listener);
        void stop();
    }

    private final Scanner scanner;
    private final Map<SmbLocation, Server> servers = new LinkedHashMap<>();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(Collections.emptyList(), Status.IDLE));
    private int generation;

    public SmbDiscoveryViewModel(Application application) {
        this(application, new AndroidSmbDiscovery(application));
    }

    public SmbDiscoveryViewModel(Application application, Scanner scanner) {
        super(application);
        this.scanner = scanner;
    }

    public LiveData<State> getState() {
        return state;
    }

    public void start() {
        stop();
        int request = ++generation;
        servers.clear();
        publish(Status.SEARCHING);
        scanner.start(new Listener() {
            @Override
            public void found(String name, SmbLocation location) {
                if (request != generation || state.getValue().status != Status.SEARCHING
                        || location == null || !location.isServerRoot()) {
                    return;
                }
                Server previous = servers.get(location);
                String label = name == null || name.trim().isEmpty() ? location.getHost() : name.trim();
                if (previous == null || !label.equals(location.getHost())) {
                    servers.put(location, new Server(label, location));
                    publish(Status.SEARCHING);
                }
            }

            @Override
            public void finished(Status status) {
                if (request == generation && state.getValue().status == Status.SEARCHING) {
                    generation++;
                    scanner.stop();
                    publish(status);
                }
            }
        });
    }

    public void permissionRequired() {
        stop();
        publish(Status.PERMISSION_REQUIRED);
    }

    public void stop() {
        generation++;
        scanner.stop();
        if (state.getValue().status == Status.SEARCHING) {
            publish(Status.STOPPED);
        }
    }

    private void publish(Status status) {
        List<Server> sorted = new ArrayList<>(servers.values());
        sorted.sort(Comparator.comparing((Server server) -> server.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(server -> server.location.getUrl()));
        state.setValue(new State(sorted, status));
    }

    @Override
    protected void onCleared() {
        stop();
    }
}