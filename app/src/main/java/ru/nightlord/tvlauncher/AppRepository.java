package ru.nightlord.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Собирает список запускаемых приложений: сначала ТВ (Leanback), потом обычные. */
final class AppRepository {
    private final Context ctx;

    AppRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    List<AppInfo> load() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppInfo> map = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, map);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, map);

        List<AppInfo> list = new ArrayList<>(map.values());
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private void collect(PackageManager pm, String category, boolean leanback, Map<String, AppInfo> out) {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(query, 0);
        } catch (Exception e) {
            return;
        }
        String self = ctx.getPackageName();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || out.containsKey(pkg)) continue;

            AppInfo a = new AppInfo();
            a.pkg = pkg;
            a.activity = ri.activityInfo.name;
            a.leanback = leanback;
            a.label = String.valueOf(ri.loadLabel(pm));
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai != null) {
                a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                a.game = isGame(ai);
                if (Build.VERSION.SDK_INT >= 26) a.sysCategory = ai.category;
            }

            Drawable banner = null;
            try {
                banner = ri.activityInfo.loadBanner(pm);
                if (banner == null && ri.activityInfo.applicationInfo != null) {
                    banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                }
            } catch (Exception ignored) {
            }
            a.banner = banner;
            try {
                a.icon = ri.loadIcon(pm);
            } catch (Exception ignored) {
            }
            out.put(pkg, a);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isGame(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }
}
