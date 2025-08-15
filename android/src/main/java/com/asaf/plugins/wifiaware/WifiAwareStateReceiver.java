package com.asaf.plugins.wifiaware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;

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
            instant = mgr.isInstantCommunicationModeSupported();
        }
        listener.onChange(new AttachResult(
                available,
                available ? null : "Wi-Fi Aware unavailable (Wi-Fi/Location off or conflicting modes)",
                Build.VERSION.SDK_INT,
                instant));
    }

    public static class AttachResult {
        public final boolean available;
        public final String reason;
        public final Integer androidApiLevel;
        public final Boolean instantCommSupported;

        public AttachResult(boolean a, String r, Integer lvl, Boolean instant) {
            this.available = a;
            this.reason = r;
            this.androidApiLevel = lvl;
            this.instantCommSupported = instant;
        }
    }
}
