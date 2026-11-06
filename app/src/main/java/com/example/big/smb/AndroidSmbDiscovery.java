package com.example.big.smb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A bounded, credential-free discovery pass on the current physical LAN. */
public final class AndroidSmbDiscovery implements SmbDiscoveryViewModel.Scanner {
    private final ConnectivityManager connectivity;
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Scan active;

    public AndroidSmbDiscovery(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        nsd = context.getSystemService(NsdManager.class);
    }

    @Override
    public void start(SmbDiscoveryViewModel.Listener listener) {
        stop();
        try {
            Network network = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (network == null || capabilities == null || properties == null
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                listener.finished(SmbDiscoveryViewModel.Status.NO_NETWORK);
                return;
            }
            active = new Scan(network, listener);
            active.start(properties);
        } catch (SecurityException exception) {
            stop();
            listener.finished(SmbDiscoveryViewModel.Status.PERMISSION_REQUIRED);
        } catch (RuntimeException exception) {
            stop();
            listener.finished(SmbDiscoveryViewModel.Status.ERROR);
        }
    }

    @Override
    public void stop() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private final class Scan {
        private final Network network;
        private final SmbDiscoveryViewModel.Listener listener;
        private final ExecutorService workers = Executors.newFixedThreadPool(24);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Set<String> services = new HashSet<>();
        private final List<NsdManager.ServiceInfoCallback> resolutions = new ArrayList<>();
        private volatile boolean stopped;
        private boolean discoveryRegistered;
        private boolean networkRegistered;
        private boolean partial;
        private final Runnable deadline = () -> finish(partial
                ? SmbDiscoveryViewModel.Status.ERROR : SmbDiscoveryViewModel.Status.COMPLETE);
        private final ConnectivityManager.NetworkCallback networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onLost(Network lost) {
                        if (network.equals(lost)) {
                            finish(SmbDiscoveryViewModel.Status.NO_NETWORK);
                        }
                    }
                };
        private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) { }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                discoveryRegistered = false;
                partial = true;
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) { }

            @Override
            public void onDiscoveryStopped(String type) { }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (!stopped && services.size() < 128 && services.add(service.getServiceName())) {
                    resolve(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) { }
        };

        Scan(Network network, SmbDiscoveryViewModel.Listener listener) {
            this.network = network;
            this.listener = listener;
        }

        void start(LinkProperties properties) {
            main.postDelayed(deadline, 20_000);
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkCallback, main);
            networkRegistered = true;
            Set<InetAddress> addresses = new HashSet<>();
            for (LinkAddress link : properties.getLinkAddresses()) {
                for (InetAddress address : SmbDiscoveryProbe.candidates(link.getAddress(), link.getPrefixLength())) {
                    if (addresses.size() < 254 && addresses.add(address)) {
                        probe(address);
                    }
                }
            }
            try {
                nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, network,
                        main::post, discovery);
                discoveryRegistered = true;
            } catch (RuntimeException exception) {
                partial = true;
            }
        }

        private void probe(InetAddress address) {
            workers.execute(() -> {
                if (stopped) {
                    return;
                }
                Socket socket = new Socket();
                sockets.add(socket);
                try {
                    if (!stopped) {
                        network.bindSocket(socket);
                        if (SmbDiscoveryProbe.isSmbServer(socket, address, 445, 600)) {
                            main.post(() -> found(address.getHostAddress(), address, 445));
                        }
                    }
                } catch (SecurityException exception) {
                    main.post(() -> finish(SmbDiscoveryViewModel.Status.PERMISSION_REQUIRED));
                } catch (IOException ignored) {
                    // Most addresses have no SMB server; no connection is an ordinary scan result.
                } finally {
                    sockets.remove(socket);
                    closeSocket(socket);
                }
            });
        }

        private void resolve(NsdServiceInfo service) {
            service.setNetwork(network);
            NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
                @Override
                public void onServiceInfoCallbackRegistrationFailed(int error) {
                    partial = true;
                    resolutions.remove(this);
                }

                @Override
                public void onServiceInfoCallbackUnregistered() { }

                @Override
                public void onServiceLost() { }

                @Override
                public void onServiceUpdated(NsdServiceInfo info) {
                    if (stopped || info.getPort() < 1 || info.getPort() > 65535) {
                        return;
                    }
                    for (InetAddress address : info.getHostAddresses()) {
                        // Prefer numeric IPv4 endpoints, avoiding .local DNS and IPv6 scope loss in SMB URLs.
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()
                                && !address.isAnyLocalAddress() && !address.isMulticastAddress()) {
                            found(info.getServiceName(), address, info.getPort());
                        }
                    }
                }
            };
            try {
                nsd.registerServiceInfoCallback(service, main::post, callback);
                resolutions.add(callback);
            } catch (RuntimeException exception) {
                partial = true;
            }
        }

        private void found(String name, InetAddress address, int port) {
            if (stopped) {
                return;
            }
            try {
                SmbLocation location = SmbLocation.parse("smb://" + address.getHostAddress() + ":" + port);
                String label = name == null ? "" : name.replaceAll("\\p{Cntrl}", "");
                listener.found(label.substring(0, Math.min(label.length(), 128)), location);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed advertisements rather than feeding them to login or playback.
            }
        }

        private void finish(SmbDiscoveryViewModel.Status status) {
            if (!stopped) {
                close();
                listener.finished(status);
            }
        }

        void close() {
            if (stopped) {
                return;
            }
            stopped = true;
            main.removeCallbacks(deadline);
            workers.shutdownNow();
            for (Socket socket : sockets) {
                closeSocket(socket);
            }
            if (discoveryRegistered) {
                try {
                    nsd.stopServiceDiscovery(discovery);
                } catch (IllegalArgumentException | SecurityException ignored) { }
                discoveryRegistered = false;
            }
            for (NsdManager.ServiceInfoCallback callback : resolutions) {
                try {
                    nsd.unregisterServiceInfoCallback(callback);
                } catch (IllegalArgumentException | SecurityException ignored) { }
            }
            resolutions.clear();
            if (networkRegistered) {
                try {
                    connectivity.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException | SecurityException ignored) { }
                networkRegistered = false;
            }
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) { }
    }
}