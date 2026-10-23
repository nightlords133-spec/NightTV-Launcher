package ru.nightlord.tvlauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;
import android.text.format.Formatter;

import java.util.Collection;

/** Очистка памяти: закрывает фоновые приложения. */
final class MemoryCleaner {

    static final class Result {
        int closed;
        long beforeFree;
        long afterFree;
        long total;
    }

    private MemoryCleaner() {
    }

    static ActivityManager.MemoryInfo info(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    /** Закрывает фоновые процессы всех приложений, кроме самого лаунчера, ByeByeDPI и клавиатуры. */
    static Result clean(Context c, Collection<String> packages) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Result r = new Result();
        ActivityManager.MemoryInfo before = info(c);
        r.beforeFree = before.availMem;
        r.total = before.totalMem;

        String ime = "";
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (id != null) ime = id.split("/")[0];
        } catch (Exception ignored) {
        }

        for (String pkg : packages) {
            if (pkg.equals(c.getPackageName()) || pkg.equals(DpiRescue.PKG) || pkg.equals(ime)) continue;
            try {
                am.killBackgroundProcesses(pkg);
                r.closed++;
            } catch (Exception ignored) {
            }
        }
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
        r.afterFree = info(c).availMem;
        return r;
    }

    static String size(Context c, long bytes) {
        return Formatter.formatShortFileSize(c, Math.max(0, bytes));
    }
}
