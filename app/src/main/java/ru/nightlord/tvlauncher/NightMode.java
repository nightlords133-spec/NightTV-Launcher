package ru.nightlord.tvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;

/**
 * Ночной режим: тёплое затемнение экрана.
 * С разрешением «поверх других приложений» затемняется всё (фильмы, YouTube),
 * без него — только главный экран NightTV.
 */
final class NightMode {
    static final String[] MODES = {"Выключен", "По расписанию", "Всегда"};
    static final int[][] SCHEDULES = {{21, 6}, {22, 7}, {23, 7}, {0, 8}};
    static final int[] DIMS = {30, 50, 70};

    private static View overlay;

    private NightMode() {
    }

    static String scheduleName(int i) {
        int[] s = SCHEDULES[Math.max(0, Math.min(SCHEDULES.length - 1, i))];
        return String.format("%02d:00–%02d:00", s[0], s[1]);
    }

    static boolean isNight(int mode, int schedule) {
        if (mode == 2) return true;
        if (mode != 1) return false;
        int[] s = SCHEDULES[Math.max(0, Math.min(SCHEDULES.length - 1, schedule))];
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return s[0] < s[1] ? (h >= s[0] && h < s[1]) : (h >= s[0] || h < s[1]);
    }

    /** Цвет затемнения: чёрный с лёгким тёплым оттенком. */
    static int color(int dimPercent) {
        int a = Math.round(Math.max(0, Math.min(85, dimPercent)) * 2.55f);
        return Color.argb(a, 25, 12, 0);
    }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Включает или убирает затемнение поверх всех приложений. Возвращает true, если оно работает. */
    @SuppressWarnings("deprecation")
    static boolean applySystem(Context ctx, boolean on, int dimPercent) {
        Context c = ctx.getApplicationContext();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (!on || !canOverlay(c)) {
            if (overlay != null) {
                try {
                    wm.removeView(overlay);
                } catch (Exception ignored) {
                }
                overlay = null;
            }
            return false;
        }
        if (overlay == null) {
            overlay = new View(c);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            try {
                wm.addView(overlay, lp);
            } catch (Exception e) {
                overlay = null;
                return false;
            }
        }
        overlay.setBackgroundColor(color(dimPercent));
        return true;
    }
}
