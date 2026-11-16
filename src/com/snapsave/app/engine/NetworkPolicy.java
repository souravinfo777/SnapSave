package com.snapsave.app.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import com.snapsave.app.util.Prefs;

/** Shared network policy for queued, running and restored downloads. */
public final class NetworkPolicy {
    private NetworkPolicy() { }

    public static boolean allowed(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
                return !Prefs.wifiOnly() || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && (!Prefs.wifiOnly() || info.getType() == ConnectivityManager.TYPE_WIFI);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static BroadcastReceiver listen(final Context context, final Runnable changed) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (Build.VERSION.SDK_INT >= 24) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { main.post(changed); }
                    @Override public void onLost(Network network) { main.post(changed); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        main.post(changed);
                    }
                });
            }
            return null; // Registration lives for the application process.
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { changed.run(); }
        };
        context.registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        return receiver;
    }
}
