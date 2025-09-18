package com.asaf.plugins.wifiaware;

import android.Manifest;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name = "WifiAware", permissions = {
        @Permission(strings = { Manifest.permission.NEARBY_WIFI_DEVICES }, alias = "nearby"),
        @Permission(strings = { Manifest.permission.ACCESS_FINE_LOCATION }, alias = "location")
})
public class WifiAwarePlugin extends Plugin implements WifiAwareShim.MessageSink {

    private WifiAwareShim aware;
    private WifiAwareStateReceiver stateReceiver;

    @Override
    public void load() {
        aware = new WifiAwareShim((BridgeActivity) getActivity(), this);

        stateReceiver = new WifiAwareStateReceiver(result -> {
            notifyListeners("stateChanged", resultToJS(result));
        });

        IntentFilter filter = new IntentFilter("android.net.wifi.aware.action.WIFI_AWARE_STATE_CHANGED");
        getContext().registerReceiver(stateReceiver, filter);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            getContext().unregisterReceiver(stateReceiver);
        } catch (Throwable ignore) {
        }
        if (aware != null)
            aware.destroy();
    }

    // ==== API ====

    @PluginMethod
    public void attach(PluginCall call) {
        WifiAwareStateReceiver.AttachResult res = aware.attach();
        notifyListeners("stateChanged", resultToJS(res));
        call.resolve(resultToJS(res));
    }

    @PluginMethod
    public void publish(PluginCall call) {
        final PublishOptions opts;
        try {
            opts = PublishOptions.fromCall(call);
        } catch (IllegalArgumentException e) {
            call.reject(e.getMessage());
            return;
        }
        ensurePermissions(call, () -> {
            try {
                aware.publish(opts, peer -> notifyListeners("serviceFound", peerToJS(peer, opts.serviceName)));
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void stopPublish(PluginCall call) {
        aware.stopPublish();
        call.resolve();
    }

    @PluginMethod
    public void subscribe(PluginCall call) {
        final SubscribeOptions opts;
        try {
            opts = SubscribeOptions.fromCall(call);
        } catch (IllegalArgumentException e) {
            call.reject(e.getMessage());
            return;
        }
        ensurePermissions(call, () -> {
            try {
                aware.subscribe(
                        opts,
                        peer -> notifyListeners("serviceFound", peerToJS(peer, opts.serviceName)),
                        peerId -> {
                            JSObject js = new JSObject();
                            js.put("peerId", peerId);
                            js.put("serviceName", opts.serviceName);
                            notifyListeners("serviceLost", js);
                        });
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void stopSubscribe(PluginCall call) {
        aware.stopSubscribe();
        call.resolve();
    }

    @PluginMethod
    public void sendMessage(PluginCall call) {
        String peerId = call.getString("peerId");
        String dataBase64 = call.getString("dataBase64");
        if (peerId == null || dataBase64 == null) {
            call.reject("peerId and dataBase64 required");
            return;
        }
        try {
            aware.sendMessage(peerId, dataBase64);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void startSocket(PluginCall call) {
        String peerId = call.getString("peerId");
        String psk = call.getString("pskPassphrase");
        boolean asServer = call.getBoolean("asServer", false);
        if (peerId == null || psk == null) {
            call.reject("peerId and pskPassphrase required");
            return;
        }
        try {
            aware.startSocket(peerId, psk, asServer,
                    info -> {
                        notifyListeners("socketReady", socketToJS(info));
                        call.resolve(socketToJS(info));
                    },
                    () -> notifyListeners("socketClosed", new JSObject()));
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void stopSocket(PluginCall call) {
        aware.stopSocket();
        notifyListeners("socketClosed", new JSObject());
        call.resolve();
    }
    
    @PluginMethod
    public void sendFileTransfer(PluginCall call) {
        String peerId = call.getString("peerId");
        String filePath = call.getString("filePath");
        String fileBase64 = call.getString("fileBase64");
        String fileName = call.getString("fileName");
        String mimeType = call.getString("mimeType");
        
        if (peerId == null) {
            call.reject("peerId is required");
            return;
        }
        
        if ((filePath == null && fileBase64 == null) || (filePath != null && fileBase64 != null)) {
            call.reject("Either filePath OR fileBase64 must be provided");
            return;
        }
        
        if (fileName == null) {
            if (filePath != null) {
                // Extract filename from path
                int lastPathSeparator = filePath.lastIndexOf('/');
                if (lastPathSeparator < 0) {
                    lastPathSeparator = filePath.lastIndexOf('\\');
                }
                if (lastPathSeparator >= 0 && lastPathSeparator < filePath.length() - 1) {
                    fileName = filePath.substring(lastPathSeparator + 1);
                } else {
                    call.reject("fileName is required when the filename cannot be determined from filePath");
                    return;
                }
            } else {
                call.reject("fileName is required when using fileBase64");
                return;
            }
        }
        
        try {
            String transferId = aware.sendFileTransfer(peerId, filePath, fileBase64, fileName, mimeType);
            JSObject result = new JSObject();
            result.put("transferId", transferId);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to initiate file transfer: " + e.getMessage());
        }
    }
    
    @PluginMethod
    public void respondToFileTransfer(PluginCall call) {
        String peerId = call.getString("peerId");
        String transferId = call.getString("transferId");
        Boolean accept = call.getBoolean("accept", false);
        String savePath = call.getString("savePath");
        
        if (peerId == null || transferId == null) {
            call.reject("peerId and transferId are required");
            return;
        }
        
        try {
            aware.respondToFileTransfer(peerId, transferId, accept, savePath);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to respond to file transfer: " + e.getMessage());
        }
    }

    @PluginMethod
    public void removeAllListeners(PluginCall call) {
        super.removeAllListeners(call);
        call.resolve();
    }

    // ==== MessageSink from shim ====

    @Override
    public void onMessageReceived(String peerId, String dataBase64) {
        JSObject js = new JSObject();
        js.put("peerId", peerId);
        js.put("dataBase64", dataBase64);
        notifyListeners("messageReceived", js);
    }
    
    @Override
    public void onFileTransferRequest(String peerId, String transferId, String fileName, String mimeType, long fileSize) {
        JSObject js = new JSObject();
        js.put("peerId", peerId);
        js.put("transferId", transferId);
        js.put("fileName", fileName);
        if (mimeType != null && !mimeType.isEmpty()) {
            js.put("mimeType", mimeType);
        }
        js.put("fileSize", fileSize);
        notifyListeners("fileTransferRequest", js);
    }
    
    @Override
    public void onFileTransferProgress(String peerId, String transferId, String fileName, 
                                       long bytesTransferred, long totalBytes, String direction, String status) {
        JSObject js = new JSObject();
        js.put("peerId", peerId);
        js.put("transferId", transferId);
        js.put("fileName", fileName);
        js.put("bytesTransferred", bytesTransferred);
        js.put("totalBytes", totalBytes);
        js.put("progress", (int)((bytesTransferred * 100) / Math.max(1, totalBytes)));
        js.put("direction", direction);
        js.put("status", status);
        notifyListeners("fileTransferProgress", js);
    }
    
    @Override
    public void onFileTransferCompleted(String peerId, String transferId, String fileName, 
                                       String filePath, String fileBase64) {
        JSObject js = new JSObject();
        js.put("peerId", peerId);
        js.put("transferId", transferId);
        js.put("fileName", fileName);
        if (filePath != null) {
            js.put("filePath", filePath);
        }
        if (fileBase64 != null) {
            js.put("fileBase64", fileBase64);
        }
        notifyListeners("fileTransferCompleted", js);
    }
    
    @Override
    public void onPeerConnected(String socketId, String peerId, Map<String, Object> deviceInfo) {
        JSObject js = new JSObject();
        js.put("socketId", socketId);
        js.put("peerId", peerId);
        
        if (deviceInfo != null) {
            JSObject deviceInfoJs = new JSObject();
            for (Map.Entry<String, Object> entry : deviceInfo.entrySet()) {
                deviceInfoJs.put(entry.getKey(), entry.getValue());
            }
            js.put("deviceInfo", deviceInfoJs);
        }
        
        notifyListeners("peerConnected", js);
    }
    
    @Override
    public void onPeerDisconnected(String socketId, String peerId) {
        JSObject js = new JSObject();
        js.put("socketId", socketId);
        js.put("peerId", peerId);
        notifyListeners("peerDisconnected", js);
    }
    
    @Override
    public void onSocketClosed(String socketId) {
        JSObject js = new JSObject();
        if (socketId != null) {
            js.put("socketId", socketId);
        }
        notifyListeners("socketClosed", js);
    }

    // ==== Permission handling ====

    private void ensurePermissions(PluginCall call, Runnable proceed) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(getContext(),
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("nearby", call, "permCallback");
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(getContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("location", call, "permCallback");
                return;
            }
        }
        proceed.run();
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        if (getPermissionState("nearby") == PermissionState.GRANTED
                || getPermissionState("location") == PermissionState.GRANTED) {
            String method = call.getMethodName();
            if ("publish".equals(method))
                publish(call);
            else if ("subscribe".equals(method))
                subscribe(call);
            else
                call.resolve();
        } else {
            call.reject("Permission denied");
        }
    }

    // ==== helpers ====

    private JSObject resultToJS(WifiAwareStateReceiver.AttachResult r) {
        JSObject js = new JSObject();
        js.put("available", r.available);
        if (r.reason != null)
            js.put("reason", r.reason);
        if (r.androidApiLevel != null)
            js.put("androidApiLevel", r.androidApiLevel);
        if (r.instantCommSupported != null)
            js.put("instantCommSupported", r.instantCommSupported);
        return js;
    }

    private JSObject peerToJS(WifiAwareShim.PeerFound p, String serviceName) {
        JSObject js = new JSObject();
        js.put("peerId", p.peerId);
        js.put("serviceName", serviceName);
        if (p.serviceInfoBase64 != null)
            js.put("serviceInfoBase64", p.serviceInfoBase64);
        if (p.distanceMm != null)
            js.put("distanceMm", p.distanceMm);
        return js;
    }

    private JSObject socketToJS(WifiAwareShim.SocketInfo info) {
        JSObject js = new JSObject();
        js.put("role", info.role);
        if (info.localPort != null)
            js.put("localPort", info.localPort);
        if (info.peerIpv6 != null)
            js.put("peerIpv6", info.peerIpv6);
        if (info.peerPort != null)
            js.put("peerPort", info.peerPort);
        return js;
    }

    // ==== Options containers ====

    static class PublishOptions {
        final String serviceName;
        final String serviceInfoBase64;
        final boolean instantMode;
        final boolean rangingEnabled;

        PublishOptions(String s, String info, boolean instant, boolean ranging) {
            this.serviceName = s;
            this.serviceInfoBase64 = info;
            this.instantMode = instant;
            this.rangingEnabled = ranging;
        }

        static PublishOptions fromCall(PluginCall call) {
            String name = call.getString("serviceName");
            if (name == null)
                throw new IllegalArgumentException("serviceName required");
            return new PublishOptions(
                    name,
                    call.getString("serviceInfoBase64"),
                    call.getBoolean("instantMode", false),
                    call.getBoolean("rangingEnabled", false));
        }
    }

    static class SubscribeOptions {
        final String serviceName;
        final boolean instantMode;
        final Integer minDistanceMm;
        final Integer maxDistanceMm;

        SubscribeOptions(String s, boolean instant, Integer min, Integer max) {
            this.serviceName = s;
            this.instantMode = instant;
            this.minDistanceMm = min;
            this.maxDistanceMm = max;
        }

        static SubscribeOptions fromCall(PluginCall call) {
            String name = call.getString("serviceName");
            if (name == null)
                throw new IllegalArgumentException("serviceName required");
            return new SubscribeOptions(
                    name,
                    call.getBoolean("instantMode", false),
                    call.getInt("minDistanceMm"),
                    call.getInt("maxDistanceMm"));
        }
    }
}
