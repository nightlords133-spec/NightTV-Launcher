package ru.nightlord.tvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Связка с ByeByeDPI: проверка, работает ли она, и включение без открытия окна. */
final class DpiRescue {
    static final String PKG = "io.github.romanvht.byedpi";
    private static final String TOGGLE = PKG + ".activities.ToggleActivity";

    private DpiRescue() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** ByeByeDPI в режиме VPN поднимает VPN-сеть — по ней и узнаём, что она работает. */
    @SuppressWarnings("deprecation")
    static boolean vpnActive(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Идёт ли трафик самого NightTV через VPN. Если в ByeByeDPI включён «Фильтр приложений»
     * и NightTV в него не входит, VPN есть, но наш трафик идёт мимо него.
     */
    static boolean selfRouted(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) {
            return true;
        }
    }

    static boolean start(Context c) {
        return toggle(c, "only_start");
    }

    static boolean stop(Context c) {
        return toggle(c, "only_stop");
    }

    private static boolean toggle(Context c, String extra) {
        Intent i = new Intent()
                .setComponent(new ComponentName(PKG, TOGGLE))
                .putExtra(extra, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
