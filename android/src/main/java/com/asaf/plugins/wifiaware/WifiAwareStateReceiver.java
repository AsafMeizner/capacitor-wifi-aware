package com.asaf.plugins.wifiaware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;
import java.util.UUID;

public class WifiAwareStateReceiver extends BroadcastReceiver {

    public interface Listener {
        void onChange(AttachResult result);
    }

    private final Listener listener;

    public WifiAwareStateReceiver(Listener l) {
        this.listener = l;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        WifiAwareManager mgr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        boolean available = mgr != null && mgr.isAvailable();
        Boolean instant = null;
        if (Build.VERSION.SDK_INT >= 33 && mgr != null) {
            try {
                java.lang.reflect.Method m = android.net.wifi.aware.WifiAwareManager.class
                        .getMethod("isInstantCommunicationModeSupported");
                Object r = m.invoke(mgr);
                if (r instanceof Boolean)
                    instant = (Boolean) r;
            } catch (Throwable ignore) {
                instant = null;
            }
        }
        
        // Get device name
        String deviceName = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = Build.MODEL;
        }
        
        // Generate a stable device ID
        String deviceId = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
        }
        
        listener.onChange(new AttachResult(
                available,
                available ? null : "Wi-Fi Aware unavailable (Wi-Fi/Location off or conflicting modes)",
                Build.VERSION.SDK_INT,
                instant,
                deviceName,
                deviceId));
    }

    public static class AttachResult {
        public final boolean available;
        public final String reason;
        public final Integer androidApiLevel;
        public final Boolean instantCommSupported;
        public final String deviceName;
        public final String deviceId;

        public AttachResult(boolean a, String r, Integer lvl, Boolean instant, String deviceName, String deviceId) {
            this.available = a;
            this.reason = r;
            this.androidApiLevel = lvl;
            this.instantCommSupported = instant;
            this.deviceName = deviceName;
            this.deviceId = deviceId;
        }
        
        // Constructor for backward compatibility
        public AttachResult(boolean a, String r, Integer lvl, Boolean instant) {
            this(a, r, lvl, instant, Build.MODEL, UUID.randomUUID().toString());
        }
    }
}
