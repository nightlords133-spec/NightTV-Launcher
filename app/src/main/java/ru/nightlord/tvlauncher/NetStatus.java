package ru.nightlord.tvlauncher;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Строка состояния: тип сети, IP-адрес приставки, свободная память. */
final class NetStatus {
    private NetStatus() {
    }

    static String read(Context c) {
        String type = null;
        String ip = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName();
                if (name.startsWith("tun") || name.startsWith("p2p") || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String t = name.startsWith("eth") ? "Кабель"
                            : name.startsWith("wlan") ? "Wi‑Fi" : name;
                    // кабель важнее Wi-Fi, если подключены оба
                    if (ip == null || "Кабель".equals(t)) {
                        ip = a.getHostAddress();
                        type = t;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ip == null ? "Нет сети" : type + "  " + ip);
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            sb.append("   ·   свободно ").append(Formatter.formatShortFileSize(c, st.getAvailableBytes()));
        } catch (Exception ignored) {
        }
        try {
            android.app.ActivityManager.MemoryInfo mi = MemoryCleaner.info(c);
            sb.append("   ·   ОЗУ ").append(Formatter.formatShortFileSize(c, mi.availMem));
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
