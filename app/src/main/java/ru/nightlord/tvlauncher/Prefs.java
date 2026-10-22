package ru.nightlord.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Настройки лаунчера. */
final class Prefs {
    private static final int MAX_RECENTS = 10;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        for (String p : sp.getString(key, "").split(",")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private void writeList(String key, List<String> list) {
        sp.edit().putString(key, TextUtils.join(",", list)).apply();
    }

    // ---- Избранное (порядок важен) ----

    List<String> favorites() {
        return readList("favs");
    }

    boolean isFavorite(String pkg) {
        return favorites().contains(pkg);
    }

    void addFavorite(String pkg) {
        List<String> l = favorites();
        if (!l.contains(pkg)) {
            l.add(pkg);
            writeList("favs", l);
        }
    }

    void removeFavorite(String pkg) {
        List<String> l = favorites();
        l.remove(pkg);
        writeList("favs", l);
    }

    /** Сдвигает в избранном на delta позиций, пропуская удалённые приложения. */
    void moveFavorite(String pkg, int delta, List<String> installed) {
        List<String> l = favorites();
        l.retainAll(installed);
        int i = l.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        writeList("favs", l);
    }

    // ---- Недавние (самое свежее первым) ----

    List<String> recents() {
        return readList("recents");
    }

    void pushRecent(String pkg) {
        List<String> l = recents();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > MAX_RECENTS) l.remove(l.size() - 1);
        writeList("recents", l);
    }

    void clearRecents() {
        writeList("recents", new ArrayList<>());
    }

    // ---- Скрытые ----

    Set<String> hidden() {
        return new HashSet<>(readList("hidden"));
    }

    void setHidden(Set<String> pkgs) {
        writeList("hidden", new ArrayList<>(pkgs));
    }

    void hide(String pkg) {
        Set<String> s = hidden();
        s.add(pkg);
        setHidden(s);
    }

    // ---- Внешний вид ----

    int theme() {
        return sp.getInt("theme", 0);
    }

    void setTheme(int t) {
        sp.edit().putInt("theme", t).apply();
    }

    int columns() {
        return sp.getInt("columns", 5);
    }

    void setColumns(int c) {
        sp.edit().putInt("columns", c).apply();
    }

    // ---- Погода ----

    boolean weatherOn() {
        return sp.getBoolean("weather", true);
    }

    void setWeatherOn(boolean on) {
        sp.edit().putBoolean("weather", on).apply();
    }

    String city() {
        return sp.getString("city", "Москва");
    }

    void setCity(String city) {
        sp.edit().putString("city", city).apply();
    }

    // ---- YouTube ----

    boolean ytShow() {
        return sp.getBoolean("yt_show", true);
    }

    void setYtShow(boolean on) {
        sp.edit().putBoolean("yt_show", on).apply();
    }

    List<YouTubeClient.Channel> ytChannels() {
        List<YouTubeClient.Channel> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("yt_channels", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                YouTubeClient.Channel ch = new YouTubeClient.Channel();
                ch.id = o.getString("id");
                ch.name = o.optString("name", ch.id);
                out.add(ch);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void setYtChannels(List<YouTubeClient.Channel> list) {
        JSONArray arr = new JSONArray();
        try {
            for (YouTubeClient.Channel ch : list) {
                arr.put(new JSONObject().put("id", ch.id).put("name", ch.name));
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString("yt_channels", arr.toString()).apply();
    }

    /** Ключ, введённый на приставке (пусто — используется ключ из сборки). */
    String ytKey() {
        return sp.getString("yt_key", "");
    }

    void setYtKey(String key) {
        sp.edit().putString("yt_key", key).apply();
    }

    /** Пакет плеера для видео ("" — автоматически). */
    String ytPlayer() {
        return sp.getString("yt_player", "");
    }

    void setYtPlayer(String pkg) {
        sp.edit().putString("yt_player", pkg).apply();
    }

    // ---- Обновления ----

    long lastUpdateCheck() {
        return sp.getLong("upd_last", 0);
    }

    void setLastUpdateCheck(long t) {
        sp.edit().putLong("upd_last", t).apply();
    }

    int skippedBuild() {
        return sp.getInt("upd_skip", 0);
    }

    void setSkippedBuild(int b) {
        sp.edit().putInt("upd_skip", b).apply();
    }
}
