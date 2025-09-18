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
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WifiAwareShim {

    // --- helpers for Android 13 "instant communication" without requiring
    // compileSdk 33+ ---

    private static boolean isInstantSupported(android.net.wifi.aware.WifiAwareManager mgr) {
        if (android.os.Build.VERSION.SDK_INT < 33 || mgr == null)
            return false;
        try {
            java.lang.reflect.Method m = android.net.wifi.aware.WifiAwareManager.class
                    .getMethod("isInstantCommunicationModeSupported");
            Object r = m.invoke(mgr);
            return (r instanceof Boolean) && ((Boolean) r);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** Works for both signatures: (boolean) and (boolean,int) */
    private static void enableInstantIfAvailable(Object builder, boolean enable) {
        if (!enable || android.os.Build.VERSION.SDK_INT < 33)
            return;
        try {
            // Try 1-arg signature first
            java.lang.reflect.Method m1 = builder.getClass()
                    .getMethod("setInstantCommunicationModeEnabled", boolean.class);
            m1.invoke(builder, true);
            return;
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable ignored) {
        }

        try {
            // Fallback: 2-arg signature (boolean, int windowSeconds)
            java.lang.reflect.Method m2 = builder.getClass()
                    .getMethod("setInstantCommunicationModeEnabled", boolean.class, int.class);
            m2.invoke(builder, true, 30); // 30s window
        } catch (Throwable ignored) {
        }
    }

    public interface MessageSink {
        void onMessageReceived(String peerId, String dataBase64);
        void onFileTransferRequest(String peerId, String transferId, String fileName, String mimeType, long fileSize);
        void onFileTransferProgress(String peerId, String transferId, String fileName, long bytesTransferred, long totalBytes, String direction, String status);
        void onFileTransferCompleted(String peerId, String transferId, String fileName, String filePath, String fileBase64);
        void onPeerConnected(String socketId, String peerId, Map<String, Object> deviceInfo);
        void onPeerDisconnected(String socketId, String peerId);
        void onSocketClosed(String socketId);
    }
    
    public static class DeviceInfo {
        public final String deviceName;
        public final String deviceType;
        public final String modelName;
        public final String osVersion;
        public final List<String> capabilities;
        
        public DeviceInfo(String deviceName, String deviceType, String modelName, String osVersion, List<String> capabilities) {
            this.deviceName = deviceName;
            this.deviceType = deviceType;
            this.modelName = modelName;
            this.osVersion = osVersion;
            this.capabilities = capabilities;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("deviceName", deviceName);
            map.put("deviceType", deviceType);
            map.put("modelName", modelName);
            map.put("osVersion", osVersion);
            map.put("capabilities", capabilities);
            return map;
        }
        
        public static DeviceInfo getLocalDeviceInfo(Context context) {
            String deviceName = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = android.os.Build.MODEL;
            }
            
            String deviceType = "Android";
            String modelName = android.os.Build.MODEL;
            String osVersion = android.os.Build.VERSION.RELEASE;
            
            List<String> capabilities = new ArrayList<>();
            capabilities.add("messaging");
            capabilities.add("file-transfer");
            if (Build.VERSION.SDK_INT >= 31) {
                capabilities.add("ranging");
            }
            if (isInstantSupported((WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE))) {
                capabilities.add("instant-mode");
            }
            
            return new DeviceInfo(deviceName, deviceType, modelName, osVersion, capabilities);
        }
    }

    public static class PeerFound {
        public final String peerId;
        public final android.net.wifi.aware.PeerHandle peerHandle;
        public final @Nullable String serviceInfoBase64;
        public final @Nullable Integer distanceMm;
        public final @Nullable DeviceInfo deviceInfo;

        public PeerFound(String id, android.net.wifi.aware.PeerHandle h, @Nullable String info,
                @Nullable Integer dist, @Nullable DeviceInfo deviceInfo) {
            this.peerId = id;
            this.peerHandle = h;
            this.serviceInfoBase64 = info;
            this.distanceMm = dist;
            this.deviceInfo = deviceInfo;
        }
        
        public PeerFound(String id, android.net.wifi.aware.PeerHandle h, @Nullable String info,
                @Nullable Integer dist) {
            this(id, h, info, dist, null);
        }
    }

    public static class SocketInfo {
        public final String socketId;      // Unique identifier for this socket
        public final String role;          // "publisher" or "subscriber"
        public final @Nullable Integer localPort;
        public final @Nullable String peerIpv6;
        public final @Nullable Integer peerPort;
        public final boolean multicastEnabled;
        public final List<String> connectedPeers;

        public SocketInfo(String socketId, String role, @Nullable Integer localPort, 
                @Nullable String peerIpv6, @Nullable Integer peerPort, 
                boolean multicastEnabled, List<String> connectedPeers) {
            this.socketId = socketId;
            this.role = role;
            this.localPort = localPort;
            this.peerIpv6 = peerIpv6;
            this.peerPort = peerPort;
            this.multicastEnabled = multicastEnabled;
            this.connectedPeers = connectedPeers;
        }
        
        // Constructor for backward compatibility
        public SocketInfo(String role, @Nullable Integer localPort, @Nullable String peerIpv6,
                @Nullable Integer peerPort) {
            this(UUID.randomUUID().toString(), role, localPort, peerIpv6, peerPort, false, new ArrayList<>());
        }
    }

    private final AppCompatActivity activity;
    private final MessageSink sink;
    private final WifiAwareManager awareMgr;
    private final ConnectivityManager connMgr;
    private final Handler mainHandler;
    private final ExecutorService executorService;
    private final DeviceInfo localDeviceInfo;

    private @Nullable WifiAwareSession session;
    private @Nullable PublishDiscoverySession pubSession;
    private @Nullable SubscribeDiscoverySession subSession;

    private final Map<String, android.net.wifi.aware.PeerHandle> peers = new ConcurrentHashMap<>();
    private final Map<String, DeviceInfo> peerDeviceInfo = new ConcurrentHashMap<>();

    // Socket connections
    private final Map<String, ServerSocket> serverSockets = new ConcurrentHashMap<>();
    private final Map<String, Socket> clientSockets = new ConcurrentHashMap<>();
    private final Map<String, List<Socket>> peerSockets = new ConcurrentHashMap<>();
    private final Map<String, ConnectivityManager.NetworkCallback> networkCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Network> activeNetworks = new ConcurrentHashMap<>();
    
    // File transfer tracking
    private final Map<String, FileTransferInfo> activeTransfers = new ConcurrentHashMap<>();
    private final int BUFFER_SIZE = 8192;
    
    // For backward compatibility
    private @Nullable ServerSocket serverSocket;
    private @Nullable ConnectivityManager.NetworkCallback networkCallback;
    
    // Multicast support
    private final Map<String, List<String>> multicastGroups = new ConcurrentHashMap<>();
    
    // Inner class to track file transfers
    private static class FileTransferInfo {
        public final String transferId;
        public final String peerId;
        public final String fileName;
        public final String mimeType;
        public final long fileSize;
        public final String filePath;  // Local path or null for Base64 content
        public final String fileBase64; // Base64 content or null for file path
        public final String direction;  // "incoming" or "outgoing"
        public String status;           // "in-progress", "completed", "failed", "cancelled"
        public long bytesTransferred;
        public Socket socket;
        public InputStream inputStream;
        public OutputStream outputStream;
        public boolean cancelled;
        
        public FileTransferInfo(String transferId, String peerId, String fileName, String mimeType, 
                                long fileSize, String filePath, String fileBase64, String direction) {
            this.transferId = transferId;
            this.peerId = peerId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.fileSize = fileSize;
            this.filePath = filePath;
            this.fileBase64 = fileBase64;
            this.direction = direction;
            this.status = "in-progress";
            this.bytesTransferred = 0;
            this.cancelled = false;
        }
    }

    public WifiAwareShim(AppCompatActivity activity, MessageSink sink) {
        this.activity = activity;
        this.sink = sink;
        this.awareMgr = (WifiAwareManager) activity.getSystemService(Context.WIFI_AWARE_SERVICE);
        this.connMgr = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newCachedThreadPool();
        this.localDeviceInfo = DeviceInfo.getLocalDeviceInfo(activity);
    }

    public void destroy() {
        stopPublish();
        stopSubscribe();
        stopAllSockets();
        cancelAllFileTransfers();
        
        if (session != null)
            session.close();
        session = null;
        
        executorService.shutdown();
    }
    
    public DeviceInfo getLocalDeviceInfo() {
        return localDeviceInfo;
    }
    
    public DeviceInfo getPeerDeviceInfo(String peerId) {
        return peerDeviceInfo.get(peerId);
    }
    
    private void cancelAllFileTransfers() {
        for (String transferId : new ArrayList<>(activeTransfers.keySet())) {
            cancelFileTransfer(transferId);
        }
    }
    
    private void stopAllSockets() {
        // Stop the legacy socket
        stopSocket();
        
        // Stop all modern sockets
        for (String socketId : new ArrayList<>(serverSockets.keySet())) {
            stopSocket(socketId);
        }
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
                    instant, localDeviceInfo.deviceName, UUID.randomUUID().toString());
        }
        
        awareMgr.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession s) {
                session = s;
            }

            @Override
            public void onAttachFailed() {
                /* leave session null */ 
            }
        }, null);
        
        return new WifiAwareStateReceiver.AttachResult(true, null, Build.VERSION.SDK_INT, instant, 
                localDeviceInfo.deviceName, UUID.randomUUID().toString());
    }

    // ===== Publish =====

    public void publish(WifiAwarePlugin.PublishOptions opts, java.util.function.Consumer<PeerFound> onFound) {
        if (session == null)
            throw new IllegalStateException("Call attach() first");
        
        PublishConfig.Builder b = new PublishConfig.Builder().setServiceName(opts.serviceName);
        
        // Handle service info and device info
        ByteArrayOutputStream serviceInfoBytes = new ByteArrayOutputStream();
        
        if (opts.serviceInfoBase64 != null) {
            try {
                byte[] userInfo = Base64.decode(opts.serviceInfoBase64, Base64.DEFAULT);
                serviceInfoBytes.write(userInfo);
            } catch (IOException e) {
                // Ignore errors in service info handling
            }
        }
        
        // Add device info if requested
        if (opts.deviceInfo) {
            try {
                // Format: [user-data][0x00][json-device-info]
                serviceInfoBytes.write(0x00);  // Separator
                
                // Simple JSON format for device info
                String deviceInfoJson = String.format(
                    "{\"n\":\"%s\",\"t\":\"%s\",\"m\":\"%s\",\"o\":\"%s\"}", 
                    localDeviceInfo.deviceName.replace("\"", "\\\""),
                    localDeviceInfo.deviceType,
                    localDeviceInfo.modelName.replace("\"", "\\\""), 
                    localDeviceInfo.osVersion
                );
                
                serviceInfoBytes.write(deviceInfoJson.getBytes());
            } catch (IOException e) {
                // Ignore errors in device info handling
            }
        }
        
        if (serviceInfoBytes.size() > 0) {
            b.setServiceSpecificInfo(serviceInfoBytes.toByteArray());
        }
        
        // Configure other options
        enableInstantIfAvailable(b, opts.instantMode && isInstantSupported(awareMgr));
        
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
                handleIncomingMessage(peerHandle, message);
            }
        }, null);
    }
    
    private void handleIncomingMessage(android.net.wifi.aware.PeerHandle peerHandle, byte[] message) {
        String peerId = idFor(peerHandle);
        
        // Check for special message types with prefix
        if (message.length > 2 && message[0] == 0x01) {
            // Protocol message types:
            // 0x01 0x01 - File transfer request
            // 0x01 0x02 - File transfer response
            // 0x01 0x03 - File transfer cancel
            
            if (message[1] == 0x01) {
                // File transfer request
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(message, 2, message.length - 2);
                    String transferId = new String(message, 2, 36); // UUID is 36 chars
                    
                    int fileNameLength = buffer.getInt(38);
                    String fileName = new String(message, 42, fileNameLength);
                    
                    int mimeTypeLength = buffer.getInt(42 + fileNameLength);
                    String mimeType = "";
                    if (mimeTypeLength > 0) {
                        mimeType = new String(message, 46 + fileNameLength, mimeTypeLength);
                    }
                    
                    long fileSize = buffer.getLong(46 + fileNameLength + mimeTypeLength);
                    
                    // Notify the application of the file transfer request
                    mainHandler.post(() -> {
                        sink.onFileTransferRequest(peerId, transferId, fileName, mimeType, fileSize);
                    });
                    
                    return;
                } catch (Exception e) {
                    // If parsing fails, treat as regular message
                }
            } else if (message[1] == 0x03) {
                // Cancel file transfer
                try {
                    String transferId = new String(message, 2, 36); // UUID is 36 chars
                    FileTransferInfo info = activeTransfers.get(transferId);
                    if (info != null) {
                        info.cancelled = true;
                        info.status = "cancelled";
                        
                        // Close resources if needed
                        try {
                            if (info.inputStream != null) info.inputStream.close();
                            if (info.outputStream != null) info.outputStream.close();
                            if (info.socket != null) info.socket.close();
                        } catch (IOException e) {
                            // Ignore close errors
                        }
                        
                        // Notify of cancellation
                        mainHandler.post(() -> {
                            sink.onFileTransferProgress(
                                info.peerId, info.transferId, info.fileName,
                                info.bytesTransferred, info.fileSize, 
                                info.direction, "cancelled"
                            );
                        });
                        
                        activeTransfers.remove(transferId);
                    }
                    return;
                } catch (Exception e) {
                    // If parsing fails, treat as regular message
                }
            }
        }
        
        // Regular message
        String dataB64 = Base64.encodeToString(message, Base64.NO_WRAP);
        mainHandler.post(() -> {
            sink.onMessageReceived(peerId, dataB64);
        });
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
        enableInstantIfAvailable(b, opts.instantMode && isInstantSupported(awareMgr));
        
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
                processDiscoveredService(peerHandle, serviceSpecificInfo, null, onFound);
            }

            @Override
            public void onServiceLost(android.net.wifi.aware.PeerHandle peerHandle, int reason) {
                String peerId = idFor(peerHandle);
                peerDeviceInfo.remove(peerId);
                onLost.accept(peerId);
            }

            @Override
            public void onServiceDiscoveredWithinRange(android.net.wifi.aware.PeerHandle peerHandle,
                    byte[] serviceSpecificInfo, java.util.List<byte[]> matchFilter, int distanceMm) {
                processDiscoveredService(peerHandle, serviceSpecificInfo, distanceMm, onFound);
            }
            
            @Override
            public void onMessageReceived(android.net.wifi.aware.PeerHandle peerHandle, byte[] message) {
                handleIncomingMessage(peerHandle, message);
            }
        }, null);
    }
    
    private void processDiscoveredService(android.net.wifi.aware.PeerHandle peerHandle, 
                                          byte[] serviceSpecificInfo, 
                                          Integer distanceMm,
                                          java.util.function.Consumer<PeerFound> onFound) {
        String peerId = idFor(peerHandle);
        
        // Process the service info
        String infoB64 = null;
        DeviceInfo deviceInfo = null;
        
        if (serviceSpecificInfo != null) {
            // Extract device info if present (marked with 0x00 separator)
            for (int i = 0; i < serviceSpecificInfo.length; i++) {
                if (serviceSpecificInfo[i] == 0x00 && i + 1 < serviceSpecificInfo.length) {
                    // Found separator - extract device info after separator
                    try {
                        String jsonDeviceInfo = new String(serviceSpecificInfo, i + 1, 
                                                          serviceSpecificInfo.length - i - 1);
                        
                        // Very basic JSON parsing - in a real app use a proper JSON library
                        String deviceName = extractJsonValue(jsonDeviceInfo, "n");
                        String deviceType = extractJsonValue(jsonDeviceInfo, "t");
                        String modelName = extractJsonValue(jsonDeviceInfo, "m");
                        String osVersion = extractJsonValue(jsonDeviceInfo, "o");
                        
                        if (deviceName != null) {
                            List<String> capabilities = new ArrayList<>();
                            capabilities.add("messaging");
                            
                            deviceInfo = new DeviceInfo(deviceName, deviceType, modelName, 
                                                      osVersion, capabilities);
                            
                            // Store device info for this peer
                            peerDeviceInfo.put(peerId, deviceInfo);
                            
                            // Use only user part of service info for Base64
                            infoB64 = Base64.encodeToString(
                                serviceSpecificInfo, 0, i, Base64.NO_WRAP);
                            break;
                        }
                    } catch (Exception e) {
                        // If parsing fails, use the full service info
                    }
                }
            }
            
            // If we haven't set infoB64 yet, use the full service info
            if (infoB64 == null) {
                infoB64 = Base64.encodeToString(serviceSpecificInfo, Base64.NO_WRAP);
            }
        }
        
        onFound.accept(new PeerFound(peerId, peerHandle, infoB64, distanceMm, deviceInfo));
    }
    
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start >= 0) {
            start += pattern.length();
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end);
            }
        }
        return null;
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

    public void sendMessage(String peerId, String dataBase64, boolean multicast, List<String> targetPeerIds) {
        byte[] bytes = Base64.decode(dataBase64, Base64.DEFAULT);
        DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
        
        if (sess == null)
            throw new IllegalStateException("No discovery session active");
            
        if (multicast || (targetPeerIds != null && !targetPeerIds.isEmpty())) {
            // Multicast mode - send to multiple peers
            List<String> peers = targetPeerIds;
            if (multicast) {
                // Send to all known peers
                peers = new ArrayList<>(this.peers.keySet());
            }
            
            for (String targetPeerId : peers) {
                android.net.wifi.aware.PeerHandle handle = this.peers.get(targetPeerId);
                if (handle != null) {
                    try {
                        sess.sendMessage(handle, 0, bytes);
                    } catch (Exception e) {
                        // Continue sending to other peers even if one fails
                    }
                }
            }
        } else {
            // Single target
            android.net.wifi.aware.PeerHandle handle = peers.get(peerId);
            if (handle == null)
                throw new IllegalArgumentException("Unknown peerId: " + peerId);
                
            sess.sendMessage(handle, 0, bytes);
        }
    }
    
    // For backward compatibility
    public void sendMessage(String peerId, String dataBase64) {
        sendMessage(peerId, dataBase64, false, null);
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
    
    // New socket implementation with socket IDs
    public void startSocketWithId(String peerId, String psk, boolean asServer, boolean multicastEnabled, int maxConnections,
            java.util.function.Consumer<SocketInfo> onReady,
            java.util.function.BiConsumer<String, String> onPeerConnected,
            java.util.function.BiConsumer<String, String> onPeerDisconnected) {
            
        android.net.wifi.aware.PeerHandle handle = peers.get(peerId);
        if (handle == null)
            throw new IllegalArgumentException("Unknown peerId: " + peerId);
        
        DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
        if (sess == null)
            throw new IllegalStateException("No discovery session active");
        
        String socketId = UUID.randomUUID().toString();
        
        if (asServer) {
            // For server mode
            new Thread(() -> {
                try {
                    ServerSocket ss = new ServerSocket(0);
                    serverSockets.put(socketId, ss);
                    
                    // Use port in network specifier
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
                            // Store network for future use
                            activeNetworks.put(socketId, network);
                            
                            // Create empty list for connected peers
                            List<String> connectedPeers = new ArrayList<>();
                            multicastGroups.put(socketId, connectedPeers);
                            
                            // Notify socket is ready
                            onReady.accept(new SocketInfo(socketId, "publisher", ss.getLocalPort(), 
                                          null, null, multicastEnabled, connectedPeers));
                            
                            // Start accepting connections (up to maxConnections)
                            for (int i = 0; i < maxConnections; i++) {
                                final int connectionId = i;
                                executorService.submit(() -> {
                                    try {
                                        while (!ss.isClosed() && connectionId < maxConnections) {
                                            Socket clientSocket = ss.accept();
                                            String clientPeerId = "peer-" + UUID.randomUUID().toString();
                                            
                                            // Add to peer sockets
                                            List<Socket> sockets = peerSockets.getOrDefault(socketId, new ArrayList<>());
                                            sockets.add(clientSocket);
                                            peerSockets.put(socketId, sockets);
                                            
                                            // Add to multicast group
                                            if (multicastEnabled) {
                                                multicastGroups.get(socketId).add(clientPeerId);
                                            }
                                            
                                            // Notify peer connected
                                            mainHandler.post(() -> {
                                                onPeerConnected.accept(socketId, clientPeerId);
                                            });
                                            
                                            // Start reading from socket
                                            handleClientSocket(socketId, clientSocket, clientPeerId);
                                        }
                                    } catch (IOException e) {
                                        // Socket closed or error
                                        if (!ss.isClosed()) {
                                            try {
                                                ss.close();
                                            } catch (IOException ignore) { }
                                        }
                                    }
                                });
                            }
                        }
                        
                        @Override
                        public void onLost(Network network) {
                            stopSocket(socketId);
                            mainHandler.post(() -> {
                                sink.onSocketClosed(socketId);
                            });
                        }
                    };
                    
                    networkCallbacks.put(socketId, cb);
                    connMgr.requestNetwork(req, cb);
                    
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        sink.onSocketClosed(socketId);
                    });
                }
            }).start();
        } else {
            // For client mode
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
                        if (info == null || info.getPeerIpv6Addr() == null || info.getPort() <= 0)
                            return;
                            
                        // Store network
                        activeNetworks.put(socketId, network);
                        
                        String ip = info.getPeerIpv6Addr().getHostAddress();
                        int port = info.getPort();
                        
                        // Create empty connected peers list
                        List<String> connectedPeers = new ArrayList<>();
                        if (multicastEnabled) {
                            connectedPeers.add(peerId);
                            multicastGroups.put(socketId, connectedPeers);
                        }
                        
                        // Notify socket ready
                        onReady.accept(new SocketInfo(
                            socketId, "subscriber", null, ip, port, multicastEnabled, connectedPeers
                        ));
                        
                        // Connect to server
                        executorService.submit(() -> {
                            try {
                                Socket s = network.getSocketFactory().createSocket(info.getPeerIpv6Addr(), port);
                                clientSockets.put(socketId, s);
                                
                                // Notify peer connected
                                mainHandler.post(() -> {
                                    onPeerConnected.accept(socketId, peerId);
                                });
                                
                                // Start reading from socket
                                handleClientSocket(socketId, s, peerId);
                                
                            } catch (Throwable e) {
                                mainHandler.post(() -> {
                                    onPeerDisconnected.accept(socketId, peerId);
                                    sink.onSocketClosed(socketId);
                                });
                            }
                        });
                    }
                }
                
                @Override
                public void onLost(Network network) {
                    stopSocket(socketId);
                    mainHandler.post(() -> {
                        sink.onSocketClosed(socketId);
                    });
                }
            };
            
            networkCallbacks.put(socketId, cb);
            connMgr.requestNetwork(req, cb);
        }
    }
    
    // Handle communication on a connected socket
    private void handleClientSocket(String socketId, Socket socket, String peerId) {
        try {
            // Keep connection open and read messages
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            
            while (!socket.isClosed()) {
                // Read message type
                int messageType = in.readInt();
                
                switch (messageType) {
                    case 1: // Regular message
                        int messageLength = in.readInt();
                        byte[] messageData = new byte[messageLength];
                        in.readFully(messageData);
                        String messageBase64 = Base64.encodeToString(messageData, Base64.NO_WRAP);
                        
                        // Notify message received
                        mainHandler.post(() -> {
                            sink.onMessageReceived(peerId, messageBase64);
                        });
                        break;
                        
                    case 2: // File transfer
                        String transferId = UUID.randomUUID().toString();
                        int fileNameLength = in.readInt();
                        byte[] fileNameBytes = new byte[fileNameLength];
                        in.readFully(fileNameBytes);
                        String fileName = new String(fileNameBytes);
                        
                        int mimeTypeLength = in.readInt();
                        String mimeType = "";
                        if (mimeTypeLength > 0) {
                            byte[] mimeTypeBytes = new byte[mimeTypeLength];
                            in.readFully(mimeTypeBytes);
                            mimeType = new String(mimeTypeBytes);
                        }
                        
                        long fileSize = in.readLong();
                        
                        // Create file in cache directory
                        File outputFile = new File(activity.getCacheDir(), transferId + "_" + fileName);
                        FileOutputStream fileOut = new FileOutputStream(outputFile);
                        BufferedOutputStream bufferedOut = new BufferedOutputStream(fileOut);
                        
                        // Create transfer info
                        FileTransferInfo transferInfo = new FileTransferInfo(
                            transferId, peerId, fileName, mimeType, fileSize,
                            outputFile.getAbsolutePath(), null, "incoming"
                        );
                        
                        transferInfo.outputStream = bufferedOut;
                        activeTransfers.put(transferId, transferInfo);
                        
                        // Notify file transfer request
                        mainHandler.post(() -> {
                            sink.onFileTransferRequest(peerId, transferId, fileName, mimeType, fileSize);
                        });
                        
                        // Read file data with progress updates
                        byte[] buffer = new byte[BUFFER_SIZE];
                        long totalBytesRead = 0;
                        int lastProgressPercent = 0;
                        
                        while (totalBytesRead < fileSize && !transferInfo.cancelled) {
                            int bytesRemaining = (int) Math.min(BUFFER_SIZE, fileSize - totalBytesRead);
                            int bytesRead = in.read(buffer, 0, bytesRemaining);
                            
                            if (bytesRead == -1) {
                                // End of stream reached prematurely
                                transferInfo.status = "failed";
                                break;
                            }
                            
                            // Write to file
                            bufferedOut.write(buffer, 0, bytesRead);
                            
                            // Update progress
                            totalBytesRead += bytesRead;
                            transferInfo.bytesTransferred = totalBytesRead;
                            
                            // Calculate progress percentage
                            int progressPercent = (int) ((totalBytesRead * 100) / fileSize);
                            
                            // Report progress every 5%
                            if (progressPercent - lastProgressPercent >= 5) {
                                lastProgressPercent = progressPercent;
                                
                                final long finalBytesRead = totalBytesRead;
                                mainHandler.post(() -> {
                                    sink.onFileTransferProgress(
                                        peerId, transferId, fileName,
                                        finalBytesRead, fileSize, 
                                        "incoming", transferInfo.status
                                    );
                                });
                            }
                        }
                        
                        // Close file
                        bufferedOut.close();
                        
                        if (transferInfo.status.equals("in-progress")) {
                            transferInfo.status = "completed";
                            
                            // Read the file to base64 if it's not too large
                            String fileBase64 = null;
                            if (fileSize <= 1024 * 1024) { // 1MB limit for base64
                                try {
                                    byte[] fileBytes = new byte[(int)fileSize];
                                    FileInputStream fis = new FileInputStream(outputFile);
                                    fis.read(fileBytes);
                                    fis.close();
                                    fileBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                                } catch (Exception e) {
                                    // If reading fails, we'll still have the file path
                                }
                            }
                            
                            // Notify completion
                            final String finalFileBase64 = fileBase64;
                            mainHandler.post(() -> {
                                sink.onFileTransferCompleted(
                                    peerId, transferId, fileName,
                                    outputFile.getAbsolutePath(), finalFileBase64
                                );
                            });
                        }
                        
                        activeTransfers.remove(transferId);
                        break;
                }
            }
        } catch (IOException e) {
            // Socket closed or error
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignore) { }
        }
    }
    
    public void stopSocket(String socketId) {
        ConnectivityManager.NetworkCallback callback = networkCallbacks.remove(socketId);
        if (callback != null) {
            try {
                connMgr.unregisterNetworkCallback(callback);
            } catch (Exception ignore) { }
        }
        
        // Close server socket
        ServerSocket ss = serverSockets.remove(socketId);
        if (ss != null) {
            try {
                ss.close();
            } catch (Exception ignore) { }
        }
        
        // Close client socket
        Socket cs = clientSockets.remove(socketId);
        if (cs != null) {
            try {
                cs.close();
            } catch (Exception ignore) { }
        }
        
        // Close all peer sockets for this socket ID
        List<Socket> sockets = peerSockets.remove(socketId);
        if (sockets != null) {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (Exception ignore) { }
            }
        }
        
        // Remove from multicast groups
        multicastGroups.remove(socketId);
        
        // Remove from active networks
        activeNetworks.remove(socketId);
    }
    
    // Method to send data through an established socket
    public void sendDataThroughSocket(String socketId, String peerId, byte[] data, int messageType) 
            throws IOException {
        
        Network network = activeNetworks.get(socketId);
        if (network == null) {
            throw new IOException("No active network for socket ID: " + socketId);
        }
        
        // Get appropriate socket
        Socket socket = null;
        
        if ("subscriber".equals(socketId)) {
            // Client mode - use the client socket
            socket = clientSockets.get(socketId);
        } else {
            // Server mode - find the peer socket
            List<Socket> sockets = peerSockets.get(socketId);
            if (sockets != null) {
                // For now, just use the first socket (in a real app, map peers to sockets)
                if (!sockets.isEmpty()) {
                    socket = sockets.get(0);
                }
            }
        }
        
        if (socket == null || socket.isClosed()) {
            throw new IOException("No active socket connection");
        }
        
        // Send the data with message type header
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(messageType);  // 1=Message, 2=File
        
        if (messageType == 1) {
            // Regular message
            out.writeInt(data.length);
            out.write(data);
        } else if (messageType == 2) {
            // File transfer - data should already be formatted correctly
            out.write(data);
        }
        out.flush();
    }
    
    public String sendFile(String peerId, String filePath, String fileBase64, String fileName, 
                         String mimeType, String socketId) {
        String transferId = UUID.randomUUID().toString();
        
        executorService.submit(() -> {
            try {
                // Determine file size and prepare data
                long fileSize;
                InputStream fileData;
                
                if (filePath != null) {
                    // Read from file path
                    File file = new File(filePath);
                    fileSize = file.length();
                    fileData = new FileInputStream(file);
                } else if (fileBase64 != null) {
                    // Convert base64 to byte array
                    byte[] fileBytes = Base64.decode(fileBase64, Base64.DEFAULT);
                    fileSize = fileBytes.length;
                    fileData = new ByteArrayInputStream(fileBytes);
                } else {
                    throw new IllegalArgumentException("Either filePath or fileBase64 must be provided");
                }
                
                // Determine MIME type if not provided
                String actualMimeType = mimeType;
                if (actualMimeType == null || actualMimeType.isEmpty()) {
                    if (filePath != null) {
                        String extension = MimeTypeMap.getFileExtensionFromUrl(filePath);
                        if (extension != null) {
                            actualMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
                        }
                    }
                    
                    // Default MIME type if still not determined
                    if (actualMimeType == null || actualMimeType.isEmpty()) {
                        actualMimeType = "application/octet-stream";
                    }
                }
                
                // Create file transfer info object
                FileTransferInfo transferInfo = new FileTransferInfo(
                    transferId, peerId, fileName, actualMimeType, fileSize,
                    filePath, fileBase64, "outgoing"
                );
                
                activeTransfers.put(transferId, transferInfo);
                
                // First, send a small L2 message to notify the peer about the upcoming file transfer
                // Use special message format for file transfer requests
                ByteBuffer headerBuffer = ByteBuffer.allocate(2 + 36 + 4 + fileName.length() + 4 + 
                                                            actualMimeType.length() + 8);
                headerBuffer.put((byte) 0x01); // Protocol message marker
                headerBuffer.put((byte) 0x01); // File transfer request
                headerBuffer.put(transferId.getBytes()); // 36 bytes for UUID
                
                // File name
                headerBuffer.putInt(fileName.length());
                headerBuffer.put(fileName.getBytes());
                
                // MIME type
                headerBuffer.putInt(actualMimeType.length());
                if (!actualMimeType.isEmpty()) {
                    headerBuffer.put(actualMimeType.getBytes());
                }
                
                // File size
                headerBuffer.putLong(fileSize);
                
                // Send notification using L2 message
                DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
                if (sess != null) {
                    android.net.wifi.aware.PeerHandle handle = peers.get(peerId);
                    if (handle != null) {
                        sess.sendMessage(handle, 0, headerBuffer.array());
                    }
                }
                
                // If we have an active socket connection, use it for the actual transfer
                if (socketId != null) {
                    // Prepare file transfer header for socket
                    ByteBuffer fileHeader = ByteBuffer.allocate(4 + fileName.length() + 4 + 
                                                              actualMimeType.length() + 8);
                    // File name
                    fileHeader.putInt(fileName.length());
                    fileHeader.put(fileName.getBytes());
                    
                    // MIME type
                    fileHeader.putInt(actualMimeType.length());
                    if (!actualMimeType.isEmpty()) {
                        fileHeader.put(actualMimeType.getBytes());
                    }
                    
                    // File size
                    fileHeader.putLong(fileSize);
                    
                    // Send file transfer header
                    sendDataThroughSocket(socketId, peerId, fileHeader.array(), 2);
                    
                    // Send file data with progress updates
                    try (BufferedInputStream bis = new BufferedInputStream(fileData)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        long totalBytesRead = 0;
                        int lastProgressPercent = 0;
                        
                        // Get output stream from socket
                        Network network = activeNetworks.get(socketId);
                        Socket socket = clientSockets.get(socketId);
                        
                        if (socket == null || socket.isClosed()) {
                            // Try to find the right peer socket
                            List<Socket> sockets = peerSockets.get(socketId);
                            if (sockets != null && !sockets.isEmpty()) {
                                socket = sockets.get(0); // Assuming first socket for now
                            }
                        }
                        
                        if (socket != null && !socket.isClosed()) {
                            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                            
                            while ((bytesRead = bis.read(buffer)) != -1 && !transferInfo.cancelled) {
                                // Write chunk to socket
                                dos.write(buffer, 0, bytesRead);
                                
                                // Update progress
                                totalBytesRead += bytesRead;
                                transferInfo.bytesTransferred = totalBytesRead;
                                
                                // Calculate progress percentage
                                int progressPercent = (int) ((totalBytesRead * 100) / fileSize);
                                
                                // Report progress every 5%
                                if (progressPercent - lastProgressPercent >= 5) {
                                    lastProgressPercent = progressPercent;
                                    
                                    final long finalBytesRead = totalBytesRead;
                                    mainHandler.post(() -> {
                                        sink.onFileTransferProgress(
                                            peerId, transferId, fileName,
                                            finalBytesRead, fileSize, 
                                            "outgoing", transferInfo.status
                                        );
                                    });
                                }
                            }
                            
                            dos.flush();
                            
                            // Complete the transfer
                            if (!transferInfo.cancelled) {
                                transferInfo.status = "completed";
                                mainHandler.post(() -> {
                                    sink.onFileTransferProgress(
                                        peerId, transferId, fileName,
                                        fileSize, fileSize, 
                                        "outgoing", "completed"
                                    );
                                });
                            }
                        }
                    }
                } else {
                    // No socket available - notify that user needs to establish a socket
                    transferInfo.status = "failed";
                    mainHandler.post(() -> {
                        sink.onFileTransferProgress(
                            peerId, transferId, fileName,
                            0, fileSize, 
                            "outgoing", "failed"
                        );
                    });
                }
                
            } catch (Exception e) {
                FileTransferInfo info = activeTransfers.get(transferId);
                if (info != null) {
                    info.status = "failed";
                    mainHandler.post(() -> {
                        sink.onFileTransferProgress(
                            peerId, transferId, fileName,
                            info.bytesTransferred, info.fileSize, 
                            "outgoing", "failed"
                        );
                    });
                }
            } finally {
                // Remove from active transfers when done
                activeTransfers.remove(transferId);
            }
        });
        
        return transferId;
    }
    
    public void cancelFileTransfer(String transferId) {
        FileTransferInfo info = activeTransfers.get(transferId);
        if (info != null) {
            info.cancelled = true;
            info.status = "cancelled";
            
            // Send cancel message if we're still in discovery
            try {
                DiscoverySession sess = (pubSession != null) ? pubSession : subSession;
                if (sess != null) {
                    android.net.wifi.aware.PeerHandle handle = peers.get(info.peerId);
                    if (handle != null) {
                        // Create cancel message: 0x01 0x03 <transferId>
                        byte[] message = new byte[2 + transferId.length()];
                        message[0] = 0x01; // Protocol message marker
                        message[1] = 0x03; // Cancel transfer
                        System.arraycopy(transferId.getBytes(), 0, message, 2, transferId.length());
                        
                        sess.sendMessage(handle, 0, message);
                    }
                }
            } catch (Exception e) {
                // Ignore errors in cancel message
            }
            
            // Close resources
            try {
                if (info.inputStream != null) info.inputStream.close();
                if (info.outputStream != null) info.outputStream.close();
                if (info.socket != null) info.socket.close();
            } catch (IOException e) {
                // Ignore close errors
            }
            
            // Notify cancellation
            mainHandler.post(() -> {
                sink.onFileTransferProgress(
                    info.peerId, info.transferId, info.fileName,
                    info.bytesTransferred, info.fileSize, 
                    info.direction, "cancelled"
                );
            });
        }
    }

    // ===== Utils =====

    private String idFor(android.net.wifi.aware.PeerHandle handle) {
        // If we already assigned an ID to an equivalent handle, return it
        for (java.util.Map.Entry<String, android.net.wifi.aware.PeerHandle> e : peers.entrySet()) {
            if (e.getValue() != null && e.getValue().equals(handle)) {
                return e.getKey();
            }
        }
        // Otherwise create one and remember it
        String id = java.util.UUID.randomUUID().toString();
        peers.put(id, handle);
        return id;
    }
}
