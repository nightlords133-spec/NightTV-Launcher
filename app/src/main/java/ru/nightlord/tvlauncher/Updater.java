package ru.nightlord.tvlauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Обновление лаунчера из GitHub Releases. Сетевые методы — только из фонового потока. */
final class Updater {
    private static final String LATEST =
            "https://api.github.com/repos/nightlords133-spec/NightTV-Launcher/releases/latest";

    static final class Release {
        int build;
        String apkUrl;
        String notes;
    }

    private Updater() {
    }

    @SuppressWarnings("deprecation")
    static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static Release fetchLatest() throws Exception {
        HttpURLConnection c = open(LATEST);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new Exception("GitHub ответил " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            JSONObject json = new JSONObject(sb.toString());
            Release rel = new Release();
            String digits = json.optString("tag_name", "").replaceAll("\\D+", "");
            rel.build = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            rel.notes = json.optString("body", "");
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        rel.apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (rel.apkUrl == null) throw new Exception("в релизе нет APK");
            return rel;
        } finally {
            c.disconnect();
        }
    }

    /** Скачивает APK прямо в сессию установщика и запускает установку. */
    static void install(Context ctx, Release rel) throws Exception {
        Context app = ctx.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        int id = installer.createSession(params);

        try (PackageInstaller.Session session = installer.openSession(id)) {
            HttpURLConnection c = open(rel.apkUrl);
            try {
                if (c.getResponseCode() != 200) throw new Exception("загрузка: код " + c.getResponseCode());
                long len = c.getContentLength();
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, len > 0 ? len : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
            } finally {
                c.disconnect();
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(
                    app, id, new Intent(app, InstallReceiver.class), flags);
            session.commit(result.getIntentSender());
        } catch (Exception e) {
            try {
                installer.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NightTV-Launcher");
        return c;
    }
}
