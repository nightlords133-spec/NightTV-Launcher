package ru.nightlord.tvlauncher;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;

/** Проверка интернета. Все методы сетевые — вызывать только из фонового потока. */
final class NetCheck {

    /** Результат быстрой проверки. latencyMs < 0 — нет ответа. */
    static final class Quick {
        boolean online;
        long latencyMs = -1;
    }

    // Несколько адресов: если один заблокирован или лежит, проверяем следующий
    private static final String[] PROBES = {
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://ya.ru/",
            "https://www.cloudflare.com/cdn-cgi/trace",
    };

    private NetCheck() {
    }

    static Quick quick() {
        Quick q = new Quick();
        for (String url : PROBES) {
            long t = ping(url, 4000);
            if (t >= 0) {
                q.online = true;
                q.latencyMs = t;
                return q;
            }
        }
        return q;
    }

    /** Время ответа сервера в мс или -1. */
    static long ping(String address, int timeoutMs) {
        HttpURLConnection c = null;
        long start = System.nanoTime();
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "NightTV-Launcher");
            int code = c.getResponseCode();
            if (code <= 0 || code >= 500) return -1;
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Время DNS-запроса в мс или -1. */
    static long dns(String host) {
        long start = System.nanoTime();
        try {
            InetAddress.getByName(host);
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Скорость загрузки в Мбит/с (не дольше maxMs) или -1. */
    static double downloadMbps(int maxMs) {
        String[] sources = {
                "https://speed.cloudflare.com/__down?bytes=25000000",
                "https://speedtest.selectel.ru/10MB",
                "https://proof.ovh.net/files/10Mb.dat",
        };
        for (String src : sources) {
            double r = download(src, maxMs);
            if (r > 0) return r;
        }
        return -1;
    }

    private static double download(String address, int maxMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "NightTV-Launcher");
            if (c.getResponseCode() != 200) return -1;
            byte[] buf = new byte[64 * 1024];
            long bytes = 0;
            long start = System.nanoTime();
            long deadline = start + maxMs * 1_000_000L;
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes += n;
                    if (System.nanoTime() > deadline) break;
                }
            }
            double sec = (System.nanoTime() - start) / 1e9;
            if (bytes < 256 * 1024 || sec <= 0) return -1;
            return bytes * 8 / sec / 1_000_000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String ms(long v) {
        return v < 0 ? "нет ответа" : v + " мс";
    }

    static String mbps(double v) {
        if (v < 0) return "не удалось измерить";
        return String.format(Locale.US, v >= 10 ? "%.0f Мбит/с" : "%.1f Мбит/с", v);
    }
}
