package ru.nightlord.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Обои главного экрана. Сетевые и файловые методы — только из фонового потока. */
final class Wallpaper {
    static final int THEME = 0;
    static final int BING = 1;
    static final int RANDOM = 2;
    static final int FILE = 3;
    static final int LINK = 4;

    static final String[] MODES = {
            "Градиент темы",
            "Фото дня Bing",
            "Случайное фото дня",
            "Своя картинка (флешка / память)",
            "Картинка по ссылке",
    };
    static final int[] DIMS = {0, 20, 40, 60};

    private Wallpaper() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.img");
    }

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** Скачивает картинку для режима «фото дня». */
    static void downloadDaily(Context c, int mode) throws Exception {
        String url;
        if (mode == BING) {
            String json = getText("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=ru-RU");
            String path = new JSONObject(json).getJSONArray("images").getJSONObject(0).getString("url");
            url = path.startsWith("http") ? path : "https://www.bing.com" + path;
        } else {
            url = "https://picsum.photos/seed/" + today() + "/1920/1080";
        }
        download(url, file(c));
    }

    static void download(String address, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NightTV-Launcher");
        try {
            if (c.getResponseCode() != 200) throw new Exception("код " + c.getResponseCode());
            File tmp = new File(out.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                copy(in, o);
            }
            if (!isImage(tmp)) {
                tmp.delete();
                throw new Exception("по ссылке не картинка");
            }
            if (!tmp.renameTo(out)) throw new Exception("не удалось сохранить");
        } finally {
            c.disconnect();
        }
    }

    static void copyFromUri(Context c, Uri uri) throws Exception {
        File out = file(c);
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("файл не открывается");
            copy(in, o);
        }
        if (!isImage(tmp)) {
            tmp.delete();
            throw new Exception("это не картинка");
        }
        if (!tmp.renameTo(out)) throw new Exception("не удалось сохранить");
    }

    static void copyFromFile(Context c, File src) throws Exception {
        File out = file(c);
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(tmp)) {
            copy(in, o);
        }
        if (!isImage(tmp)) {
            tmp.delete();
            throw new Exception("это не картинка");
        }
        if (!tmp.renameTo(out)) throw new Exception("не удалось сохранить");
    }

    /** Загружает обои, уменьшив под размер экрана (экономия памяти). */
    static Bitmap load(Context c, int reqW, int reqH) {
        File f = file(c);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    /** Ищет картинки на флешках и во внутренней памяти. */
    static List<File> findImages() {
        List<File> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                roots.add(v);
            }
        }
        for (File r : roots) scan(r, 0, out);
        return out;
    }

    private static void scan(File dir, int depth, List<File> out) {
        if (dir == null || depth > 4 || out.size() >= 300) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (out.size() >= 300) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && n.equals("Android")) continue;
                scan(f, depth + 1, out);
            } else {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")) {
                    if (f.length() > 20 * 1024) out.add(f);
                }
            }
        }
    }

    private static boolean isImage(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String getText(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "NightTV-Launcher");
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
