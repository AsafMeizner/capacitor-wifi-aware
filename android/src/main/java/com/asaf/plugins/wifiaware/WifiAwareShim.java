package com.asaf.plugins.wifiaware;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WifiAwareShim {

    public interface MessageSink {
        void onMessageReceived(String peerId, String dataBase64);
    }

    public static class PeerFound {
        public final String peerId;
        public final android.net.wifi.aware.PeerHandle peerHandle;
        public final @Nullable String serviceInfoBase64;
        public final @Nullable Integer distanceMm;

        public PeerFound(String id, android.net.wifi.aware.PeerHandle h, @Nullable String info,
                @Nullable Integer dist) {
            this.peerId = id;
            this.peerHandle = h;
            this.serviceInfoBase64 = info;
            this.distanceMm = dist;
        }
    }

    public static class SocketInfo {
        public final String role; // "publisher" or "subscriber"
        public final @Nullable Integer localPort;
        public final @Nullable String peerIpv6;
        public final @Nullable Integer peerPort;

        public SocketInfo(String role, @Nullable Integer localPort, @Nullable String peerIpv6,
                @Nullable Integer peerPort) {
            this.role = role;
            this.localPort = localPort;
            this.peerIpv6 = peerIpv6;
            this.peerPort = peerPort;
        }
    }

    private final AppCompatActivity activity;
    private final MessageSink sink;
    private final WifiAwareManager awareMgr;
    private final ConnectivityManager connMgr;

    private @Nullable WifiAwareSession session;
    private @Nullable PublishDiscoverySession pubSession;
    private @Nullable SubscribeDiscoverySession subSession;

    private final Map<String, android.net.wifi.aware.PeerHandle> peers = new ConcurrentHashMap<>();

    // datapath
    private @Nullable ServerSocket serverSocket;
    private @Nullable ConnectivityManager.NetworkCallback networkCallback;

    public WifiAwareShim(AppCompatActivity activity, MessageSink sink) {
        this.activity = activity;
        this.sink = sink;
        this.awareMgr = (WifiAwareManager) activity.getSystemService(Context.WIFI_AWARE_SERVICE);
        this.connMgr = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void destroy() {
        stopPublish();
        stopSubscribe();
        stopSocket();
        if (session != null)
            session.close();
        session = null;
    }

    public WifiAwareStateReceiver.AttachResult attach() {
        boolean available = awareMgr != null && awareMgr.isAvailable();
        Boolean instant = null;
        if (Build.VERSION.SDK_INT >= 33 && awareMgr != null) {
            try {
                java.lang.reflect.Method m = android.net.wifi.aware.WifiAwareManager.class
                        .getMethod("isInstantCommunicationModeSupported");
                Object r = m.invoke(awareMgr);
                if (r instanceof Boolean)
                    instant = (Boolean) r;
            } catch (Throwable ignore) {
                instant = null;
            }
        }
        if (!available) {
            return new WifiAwareStateReceiver.AttachResult(false, "Wi-Fi Aware unavailable", Build.VERSION.SDK_INT,
                    instant);
        }
        awareMgr.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession s) {
                session = s;
            }

            @Override
            public void onAttachFailed() {
                /* leave session null */ }
        }, null);
        return new WifiAwareStateReceiver.AttachResult(true, null, Build.VERSION.SDK_INT, instant);
    }

    // ===== Publish =====

    public void publish(WifiAwarePlugin.PublishOptions opts, java.util.function.Consumer<PeerFound> onFound) {
        if (session == null)
            throw new IllegalStateException("Call attach() first");
        PublishConfig.Builder b = new PublishConfig.Builder().setServiceName(opts.serviceName);
        if (opts.serviceInfoBase64 != null) {
            byte[] bytes = Base64.decode(opts.serviceInfoBase64, Base64.DEFAULT);
            b.setServiceSpecificInfo(bytes);
        }
        if (Build.VERSION.SDK_INT >= 33 && opts.instantMode && awareMgr.isInstantCommunicationModeSupported()) {
            b.setInstantCommunicationModeEnabled(true);
        }
        if (opts.rangingEnabled) {
            b.setRangingEnabled(true);
        }
        PublishConfig cfg = b.build();

        session.publish(cfg, new DiscoverySessionCallback() {
            @Override
            public void onPublishStarted(PublishDiscoverySession session) {
                pubSession = session;
            }

            @Override
            public void onMessageReceived(android.net.wifi.aware.PeerHandle peerHandle, byte[] message) {
                String peerId = idFor(peerHandle);
                String dataB64 = Base64.encodeToString(message, Base64.NO_WRAP);
                sink.onMessageReceived(peerId, dataB64);
            }
        }, null);
    }

    public void stopPublish() {
        try {
            if (pubSession != null)
                pubSession.close();
        } catch (Throwable ignore) {
        }
        pubSession = null;
    }

    // ===== Subscribe =====

    public void subscribe(WifiAwarePlugin.SubscribeOptions opts,
            java.util.function.Consumer<PeerFound> onFound,
            java.util.function.Consumer<String> onLost) {
        if (session == null)
            throw new IllegalStateException("Call attach() first");
        SubscribeConfig.Builder b = new SubscribeConfig.Builder().setServiceName(opts.serviceName);
        if (Build.VERSION.SDK_INT >= 33 && opts.instantMode && awareMgr.isInstantCommunicationModeSupported()) {
            b.setInstantCommunicationModeEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (opts.minDistanceMm != null)
                b.setMinDistanceMm(opts.minDistanceMm);
            if (opts.maxDistanceMm != null)
                b.setMaxDistanceMm(opts.maxDistanceMm);
        }
        SubscribeConfig cfg = b.build();

        session.subscribe(cfg, new DiscoverySessionCallback() {
            @Override
            public void onSubscribeStarted(SubscribeDiscoverySession session) {
                subSession = session;
            }

            @Override
            public void onServiceDiscovered(android.net.wifi.aware.PeerHandle peerHandle, byte[] serviceSpecificInfo,
                    java.util.List<byte[]> matchFilter) {
                String peerId = idFor(peerHandle);
                String infoB64 = serviceSpecificInfo != null
                        ? Base64.encodeToString(serviceSpecificInfo, Base64.NO_WRAP)
                        : null;
                onFound.accept(new PeerFound(peerId, peerHandle, infoB64, null));
            }

            @Override
            public void onServiceLost(android.net.wifi.aware.PeerHandle peerHandle, int reason) {
                onLost.accept(idFor(peerHandle));
            }

            @Override
            public void onServiceDiscoveredWithinRange(android.net.wifi.aware.PeerHandle peerHandle,
                    byte[] serviceSpecificInfo, java.util.List<byte[]> matchFilter, int distanceMm) {
                String peerId = idFor(peerHandle);
                String infoB64 = serviceSpecificInfo != null
                        ? Base64.encodeToString(serviceSpecificInfo, Base64.NO_WRAP)
                        : null;
                onFound.accept(new PeerFound(peerId, peerHandle, infoB64, distanceMm));
            }
        }, null);
    }

    public void stopSubscribe() {
        try {
            if (subSession != null)
                subSession.close();
        } catch (Throwable ignore) {
        }
        subSession = null;
    }

    // ===== Messages =====

    public void sendMessage(String peerId, String dataBase64) {
        byte[] bytes = Base64.decode(dataBase64, Base64.DEFAULT);
        android.net.wifi.aware.PeerHandle handle = peers.get(peerId);
        if (handle == null)
            throw new IllegalArgumentException("Unknown peerId: " + peerId);
        DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
        if (sess == null)
            throw new IllegalStateException("No discovery session active");
        sess.sendMessage(handle, 0, bytes);
    }

    // ===== Socket / datapath =====

    public void startSocket(String peerId, String psk, boolean asServer,
            java.util.function.Consumer<SocketInfo> onReady,
            Runnable onClosed) {
        android.net.wifi.aware.PeerHandle handle = peers.get(peerId);
        if (handle == null)
            throw new IllegalArgumentException("Unknown peerId: " + peerId);
        DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
        if (sess == null)
            throw new IllegalStateException("No discovery session active");

        if (asServer) {
            new Thread(() -> {
                try {
                    ServerSocket ss = new ServerSocket(0);
                    serverSocket = ss;

                    WifiAwareNetworkSpecifier spec = new WifiAwareNetworkSpecifier.Builder(sess, handle)
                            .setPskPassphrase(psk)
                            .setPort(ss.getLocalPort())
                            .build();

                    NetworkRequest req = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(spec)
                            .build();

                    ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            onReady.accept(new SocketInfo("publisher", ss.getLocalPort(), null, null));
                            new Thread(() -> {
                                try {
                                    Socket s = ss.accept();
                                    try (BufferedReader r = new BufferedReader(
                                            new InputStreamReader(s.getInputStream()))) {
                                        String line = r.readLine(); // demo read
                                    }
                                } catch (Throwable ignore) {
                                }
                            }).start();
                        }

                        @Override
                        public void onLost(Network network) {
                            onClosed.run();
                        }
                    };

                    networkCallback = cb;
                    connMgr.requestNetwork(req, cb);
                } catch (Exception e) {
                    onClosed.run();
                }
            }).start();
        } else {
            WifiAwareNetworkSpecifier spec = new WifiAwareNetworkSpecifier.Builder(sess, handle)
                    .setPskPassphrase(psk)
                    .build();

            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(spec)
                    .build();

            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        WifiAwareNetworkInfo info = (WifiAwareNetworkInfo) caps.getTransportInfo();
                        if (info == null)
                            return;
                        if (info.getPeerIpv6Addr() == null)
                            return;
                        String ip = info.getPeerIpv6Addr().getHostAddress();
                        int port = info.getPort();
                        onReady.accept(new SocketInfo("subscriber", null, ip, port));

                        // Demo connect once
                        new Thread(() -> {
                            try {
                                Socket s = network.getSocketFactory().createSocket(info.getPeerIpv6Addr(), port);
                                try (PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                                    out.println("hello");
                                }
                            } catch (Throwable ignore) {
                            }
                        }).start();
                    }
                }

                @Override
                public void onLost(Network network) {
                    onClosed.run();
                }
            };

            networkCallback = cb;
            connMgr.requestNetwork(req, cb);
        }
    }

    public void stopSocket() {
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (Throwable ignore) {
        }
        serverSocket = null;
        if (networkCallback != null) {
            try {
                connMgr.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignore) {
            }
            networkCallback = null;
        }
    }

    // ===== Utils =====

    private String idFor(android.net.wifi.aware.PeerHandle handle) {
        for (Map.Entry<String, android.net.wifi.aware.PeerHandle> e : peers.entrySet()) {
            if (e.getValue().peerId == handle.peerId)
                return e.getKey();
        }
        String id = UUID.randomUUID().toString();
        peers.put(id, handle);
        return id;
    }
}
