package ru.nightlord.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Настройки лаунчера. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    // ---- Избранное (порядок важен) ----

    List<String> favorites() {
        List<String> out = new ArrayList<>();
        for (String p : sp.getString("favs", "").split(",")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private void saveFavorites(List<String> list) {
        sp.edit().putString("favs", TextUtils.join(",", list)).apply();
    }

    boolean isFavorite(String pkg) {
        return favorites().contains(pkg);
    }

    void addFavorite(String pkg) {
        List<String> l = favorites();
        if (!l.contains(pkg)) {
            l.add(pkg);
            saveFavorites(l);
        }
    }

    void removeFavorite(String pkg) {
        List<String> l = favorites();
        l.remove(pkg);
        saveFavorites(l);
    }

    /** Сдвигает в избранном на delta позиций, пропуская удалённые приложения. */
    void moveFavorite(String pkg, int delta, List<String> installed) {
        List<String> l = favorites();
        l.retainAll(installed);
        int i = l.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        saveFavorites(l);
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
}
