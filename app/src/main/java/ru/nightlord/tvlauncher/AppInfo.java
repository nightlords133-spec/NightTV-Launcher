package ru.nightlord.tvlauncher;

import android.graphics.drawable.Drawable;

/** Одно приложение на главном экране. */
final class AppInfo {
    String pkg;
    String activity;
    String label;
    boolean leanback;
    Drawable banner; // 16:9, может быть null
    Drawable icon;
    boolean system;
    boolean game;
    int sysCategory = -1;
}
