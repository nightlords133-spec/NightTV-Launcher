package ru.nightlord.tvlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Таймер сна: в назначенное время ставит видео на паузу и выключает экран. */
final class SleepTimer {
    static final String ACTION_SLEEP = "ru.nightlord.tvlauncher.SLEEP";
    static final String ACTION_WARN = "ru.nightlord.tvlauncher.SLEEP_WARN";
    static final String EXTRA_BLACK = "sleep_black";

    private SleepTimer() {
    }

    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    static void set(Context c, int minutes) {
        cancel(c);
        long end = System.currentTimeMillis() + minutes * 60_000L;
        sp(c).edit().putLong("sleep_end", end).apply();
        long now = SystemClock.elapsedRealtime();
        schedule(c, ACTION_SLEEP, now + minutes * 60_000L);
        if (minutes > 1) schedule(c, ACTION_WARN, now + (minutes - 1) * 60_000L);
    }

    static void cancel(Context c) {
        sp(c).edit().remove("sleep_end").apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c, ACTION_SLEEP));
        am.cancel(pending(c, ACTION_WARN));
    }

    /** Сколько осталось, мс (0 — таймер не запущен). */
    static long remaining(Context c) {
        long end = sp(c).getLong("sleep_end", 0);
        long left = end - System.currentTimeMillis();
        if (end == 0 || left <= 0) return 0;
        return left;
    }

    private static void schedule(Context c, String action, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c, action);
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context c, String action) {
        Intent i = new Intent(c, SleepReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, action.hashCode(), i, flags);
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static boolean adminActive(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    /** Пауза видео и выключение экрана. */
    static void sleepNow(Context c) {
        sp(c).edit().remove("sleep_end").apply();
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (Exception ignored) {
        }
        if (adminActive(c)) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                dpm.lockNow();
                return;
            } catch (Exception ignored) {
            }
        }
        // Без прав блокировки — уходим на главный экран и показываем чёрный экран
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .putExtra(EXTRA_BLACK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {
        }
    }
}
