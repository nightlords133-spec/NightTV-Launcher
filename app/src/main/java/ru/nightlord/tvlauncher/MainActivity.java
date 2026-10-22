package ru.nightlord.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NightTV — домашний экран для Android TV.
 * Автор: NightLord РВЕ
 */
public class MainActivity extends Activity {

    private static final long WEATHER_PERIOD_MS = 30 * 60 * 1000L;
    private static final long STATUS_PERIOD_MS = 15 * 1000L;
    private static final long UPDATE_PERIOD_MS = 6 * 60 * 60 * 1000L;
    private static final String TAG_FAV = "fav:";
    private static final String TAG_REC = "rec:";
    private static final String TAG_ALL = "all:";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private AppRepository repo;
    private List<AppInfo> apps = new ArrayList<>();

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout favRow;
    private LinearLayout grid;
    private TextView favHint;
    private TextView recentTitle;
    private View recentScroll;
    private LinearLayout recentRow;
    private TextView statusView;
    private TextView weatherView;
    private TextView settingsButton;
    private GradientDrawable settingsBg;

    /** Тег плитки, на которую вернуть фокус после перерисовки. */
    private String pendingFocus;
    private BroadcastReceiver packageReceiver;

    private final Runnable weatherTask = new Runnable() {
        @Override
        public void run() {
            updateWeather();
            ui.postDelayed(this, WEATHER_PERIOD_MS);
        }
    };

    private final Runnable statusTask = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            ui.postDelayed(this, STATUS_PERIOD_MS);
        }
    };

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        repo = new AppRepository(this);
        buildUi();
        registerPackageReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        ui.removeCallbacks(weatherTask);
        ui.post(weatherTask);
        ui.removeCallbacks(statusTask);
        ui.post(statusTask);
        if (System.currentTimeMillis() - prefs.lastUpdateCheck() > UPDATE_PERIOD_MS) {
            checkUpdates(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(weatherTask);
        ui.removeCallbacks(statusTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (Exception ignored) {
            }
        }
        worker.shutdownNow();
    }

    /** Нажали «Домой», уже находясь на домашнем экране — вернуться наверх. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusFirstTile();
    }

    /** «Назад» на домашнем экране ничего не делает. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusFirstTile();
    }

    // ------------------------------------------------------------ построение экрана

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, float alpha) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFFFFFFF);
        t.setAlpha(alpha);
        t.setShadowLayer(dp(3), 0, dp(1), 0x80000000);
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(Themes.background(prefs.theme()));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // безопасные поля ТВ (~5% экрана)
        column.setPadding(dp(48), dp(27), dp(48), dp(27));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(sectionTitle("Избранное"));
        favRow = newRow();
        column.addView(wrapRow(favRow));

        favHint = text(15, 0.6f);
        favHint.setText("Удерживайте ОК на приложении (или нажмите «Меню» на пульте), "
                + "чтобы добавить его в избранное");
        favHint.setPadding(0, dp(4), 0, dp(12));
        column.addView(favHint);

        recentTitle = sectionTitle("Недавние");
        column.addView(recentTitle);
        recentRow = newRow();
        recentScroll = wrapRow(recentRow);
        column.addView(recentScroll);

        column.addView(sectionTitle("Все приложения"));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(8), 0, dp(8));
        column.addView(grid);

        TextView footer = text(12, 0.35f);
        footer.setText("NightTV · NightLord РВЕ");
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(24), 0, 0);
        column.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(column);
        root.addView(scroll);
        setContentView(root);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(14), 0, dp(14));
        return row;
    }

    private View wrapRow(LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.addView(row);
        return hs;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.setPadding(0, 0, 0, dp(16));

        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(dp(3), 0, dp(1), 0x80000000);
        timeBox.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTextColor(0xCCFFFFFF);
        date.setShadowLayer(dp(3), 0, dp(1), 0x80000000);
        timeBox.addView(date);

        statusView = text(14, 0.6f);
        statusView.setPadding(0, dp(6), 0, 0);
        timeBox.addView(statusView);

        header.addView(timeBox);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        weatherView = text(20, 0.9f);
        weatherView.setPadding(0, 0, dp(28), 0);
        header.addView(weatherView);

        settingsButton = text(18, 0.85f);
        settingsButton.setText("⚙  Настройки");
        settingsButton.setPadding(dp(20), dp(10), dp(20), dp(10));
        settingsBg = new GradientDrawable();
        settingsBg.setCornerRadius(dp(24));
        settingsBg.setColor(0x22FFFFFF);
        settingsButton.setBackground(settingsBg);
        settingsButton.setFocusable(true);
        settingsButton.setClickable(true);
        settingsButton.setTag("settings");
        settingsButton.setOnClickListener(v -> showSettings());
        settingsButton.setOnFocusChangeListener((v, focused) -> {
            settingsBg.setColor(focused ? 0x55FFFFFF : 0x22FFFFFF);
            settingsBg.setStroke(dp(2), focused ? Themes.accent(prefs.theme()) : 0x00000000);
            v.setAlpha(focused ? 1f : 0.85f);
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .setDuration(150).start();
        });
        header.addView(settingsButton);
        return header;
    }

    private TextView sectionTitle(String title) {
        TextView t = text(20, 0.9f);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    /** Ширина плитки под выбранное число колонок. */
    private int tileWidth() {
        int cols = prefs.columns();
        int screen = getResources().getDisplayMetrics().widthPixels;
        int available = screen - dp(48) * 2;
        int gap = dp(20);
        return Math.max(dp(120), (available - gap * cols) / cols);
    }

    private View makeTile(AppInfo app, int width, String tag) {
        final int accent = Themes.accent(prefs.theme());
        int height = width * 9 / 16;

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(tag);

        FrameLayout card = new FrameLayout(this);
        final GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(10));
        cardBg.setColor(0x33FFFFFF);
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        ImageView img = new ImageView(this);
        if (app.banner != null) {
            img.setImageDrawable(app.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setImageDrawable(app.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int size = (int) (height * 0.6f);
            card.addView(img, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }
        tile.addView(card, new LinearLayout.LayoutParams(width, height));

        final TextView label = text(14, 0.7f);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));

        tile.setOnFocusChangeListener((v, focused) -> {
            cardBg.setStroke(focused ? dp(3) : 0, focused ? accent : 0x00000000);
            label.setAlpha(focused ? 1f : 0.7f);
            v.setTranslationZ(focused ? dp(8) : 0);
            v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f)
                    .setDuration(150).start();
        });
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) showAppMenu(app);
                return true;
            }
            return false;
        });
        return tile;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = dp(10);
        lp.setMargins(gap, dp(8), gap, dp(8));
        return lp;
    }

    // ------------------------------------------------------------ данные

    private void reloadApps() {
        worker.execute(() -> {
            final List<AppInfo> loaded = repo.load();
            ui.post(() -> {
                apps = loaded;
                render();
            });
        });
    }

    private void render() {
        if (pendingFocus == null) {
            View current = getCurrentFocus();
            if (current != null && current.getTag() instanceof String) {
                pendingFocus = (String) current.getTag();
            }
        }

        favRow.removeAllViews();
        recentRow.removeAllViews();
        grid.removeAllViews();

        int width = tileWidth();
        Set<String> hidden = prefs.hidden();
        List<AppInfo> visible = new ArrayList<>();
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (AppInfo a : apps) {
            if (hidden.contains(a.pkg)) continue;
            visible.add(a);
            byPkg.put(a.pkg, a);
        }

        int favCount = 0;
        for (String pkg : prefs.favorites()) {
            AppInfo a = byPkg.get(pkg);
            if (a == null) continue;
            favRow.addView(makeTile(a, width, TAG_FAV + pkg), tileParams());
            favCount++;
        }
        favHint.setVisibility(favCount == 0 ? View.VISIBLE : View.GONE);

        int recCount = 0;
        for (String pkg : prefs.recents()) {
            AppInfo a = byPkg.get(pkg);
            if (a == null) continue;
            recentRow.addView(makeTile(a, width, TAG_REC + pkg), tileParams());
            recCount++;
        }
        int recVis = recCount == 0 ? View.GONE : View.VISIBLE;
        recentTitle.setVisibility(recVis);
        recentScroll.setVisibility(recVis);

        int cols = prefs.columns();
        LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row);
            }
            AppInfo a = visible.get(i);
            row.addView(makeTile(a, width, TAG_ALL + a.pkg), tileParams());
        }

        View target = pendingFocus != null ? root.findViewWithTag(pendingFocus) : null;
        pendingFocus = null;
        if (target != null) {
            target.requestFocus();
        } else {
            focusFirstTile();
        }
    }

    private void focusFirstTile() {
        if (favRow != null && favRow.getChildCount() > 0) {
            favRow.getChildAt(0).requestFocus();
        } else if (grid != null && grid.getChildCount() > 0) {
            View firstRow = grid.getChildAt(0);
            if (firstRow instanceof ViewGroup && ((ViewGroup) firstRow).getChildCount() > 0) {
                ((ViewGroup) firstRow).getChildAt(0).requestFocus();
            }
        } else if (settingsButton != null) {
            settingsButton.requestFocus();
        }
    }

    private List<String> installedPackages() {
        List<String> out = new ArrayList<>();
        for (AppInfo a : apps) out.add(a.pkg);
        return out;
    }

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadApps();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, f);
        }
    }

    // ------------------------------------------------------------ действия

    private void launch(AppInfo app) {
        prefs.pushRecent(app.pkg);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(app.pkg, app.activity));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent fallback = getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                safeStart(fallback);
            } else {
                toast("Не удалось открыть " + app.label);
            }
        }
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Действие недоступно на этом устройстве");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void showAppMenu(AppInfo app) {
        final boolean fav = prefs.isFavorite(app.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add("Открыть");
        actions.add(() -> launch(app));

        if (fav) {
            labels.add("Сдвинуть влево");
            actions.add(() -> {
                prefs.moveFavorite(app.pkg, -1, installedPackages());
                pendingFocus = TAG_FAV + app.pkg;
                render();
            });
            labels.add("Сдвинуть вправо");
            actions.add(() -> {
                prefs.moveFavorite(app.pkg, +1, installedPackages());
                pendingFocus = TAG_FAV + app.pkg;
                render();
            });
            labels.add("Убрать из избранного");
            actions.add(() -> {
                prefs.removeFavorite(app.pkg);
                pendingFocus = TAG_ALL + app.pkg;
                render();
            });
        } else {
            labels.add("Добавить в избранное");
            actions.add(() -> {
                prefs.addFavorite(app.pkg);
                pendingFocus = TAG_FAV + app.pkg;
                render();
            });
        }

        labels.add("Скрыть с главного экрана");
        actions.add(() -> {
            prefs.hide(app.pkg);
            render();
            toast("Скрыто. Вернуть: Настройки → Скрытые приложения");
        });

        labels.add("О приложении");
        actions.add(() -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        labels.add("Удалить");
        actions.add(() -> safeStart(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        dialog().setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    // ------------------------------------------------------------ настройки

    private void showSettings() {
        String weather = prefs.weatherOn() ? prefs.city() : "выключена";
        String[] items = {
                "Тема: " + Themes.NAMES[Themes.safe(prefs.theme())],
                "Колонок в сетке: " + prefs.columns(),
                "Погода: " + weather,
                "Скрытые приложения (" + prefs.hidden().size() + ")",
                "Очистить «Недавние»",
                "Проверить обновления",
                "Системные настройки Android",
                "О программе",
        };
        dialog().setTitle("Настройки")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: chooseTheme(); break;
                        case 1: chooseColumns(); break;
                        case 2: chooseCity(); break;
                        case 3: chooseHidden(); break;
                        case 4:
                            prefs.clearRecents();
                            pendingFocus = "settings";
                            render();
                            toast("Список недавних очищен");
                            break;
                        case 5: checkUpdates(true); break;
                        case 6: safeStart(new Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
                        default: showAbout(); break;
                    }
                })
                .show();
    }

    private void chooseHidden() {
        if (apps.isEmpty()) return;
        final Set<String> hidden = prefs.hidden();
        final String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = hidden.contains(apps.get(i).pkg);
        }
        dialog().setTitle("Отметьте, что скрыть")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Set<String> result = new HashSet<>();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) result.add(apps.get(i).pkg);
                    }
                    prefs.setHidden(result);
                    pendingFocus = "settings";
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------ строка состояния

    private void updateStatus() {
        if (statusView == null) return;
        worker.execute(() -> {
            final String s = NetStatus.read(this);
            ui.post(() -> statusView.setText(s));
        });
    }

    // ------------------------------------------------------------ обновления

    private void checkUpdates(final boolean manual) {
        prefs.setLastUpdateCheck(System.currentTimeMillis());
        if (manual) toast("Проверяю обновления…");
        worker.execute(() -> {
            Updater.Release rel = null;
            String err = null;
            try {
                rel = Updater.fetchLatest();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Updater.Release r = rel;
            final String error = err;
            ui.post(() -> onUpdateChecked(r, error, manual));
        });
    }

    private void onUpdateChecked(Updater.Release r, String error, boolean manual) {
        if (isFinishing()) return;
        int current = Updater.currentBuild(this);
        if (r == null) {
            if (manual) toast("Не удалось проверить обновления" + (error != null ? ": " + error : ""));
            return;
        }
        if (r.build <= current) {
            if (manual) toast("У вас последняя версия (сборка " + current + ")");
            return;
        }
        if (!manual && r.build == prefs.skippedBuild()) return;

        String msg = "Новая сборка: " + r.build + "\nУстановлена: " + current;
        if (r.notes != null && !r.notes.trim().isEmpty()) msg += "\n\n" + r.notes.trim();
        dialog().setTitle("Доступно обновление NightTV")
                .setMessage(msg)
                .setPositiveButton("Установить", (d, w) -> installUpdate(r, false))
                .setNeutralButton("Пропустить", (d, w) -> prefs.setSkippedBuild(r.build))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void installUpdate(final Updater.Release r, boolean force) {
        if (!force && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            dialog().setTitle("Нужно разрешение")
                    .setMessage("Разрешите NightTV устанавливать приложения "
                            + "(«Неизвестные источники»), потом снова нажмите «Установить».")
                    .setPositiveButton("Открыть настройки", (d, w) -> safeStart(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .setNeutralButton("Всё равно установить", (d, w) -> installUpdate(r, true))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        toast("Загружаю обновление…");
        worker.execute(() -> {
            try {
                Updater.install(this, r);
            } catch (Exception e) {
                final String m = e.getMessage();
                ui.post(() -> toast("Ошибка обновления: " + m));
            }
        });
    }

    private void chooseTheme() {
        dialog().setTitle("Тема")
                .setSingleChoiceItems(Themes.NAMES, Themes.safe(prefs.theme()), (d, which) -> {
                    prefs.setTheme(which);
                    root.setBackground(Themes.background(which));
                    pendingFocus = "settings";
                    render();
                    d.dismiss();
                })
                .show();
    }

    private void chooseColumns() {
        final String[] options = {"4", "5", "6", "7"};
        int current = Math.max(0, prefs.columns() - 4);
        dialog().setTitle("Колонок в сетке")
                .setSingleChoiceItems(options, current, (d, which) -> {
                    prefs.setColumns(which + 4);
                    pendingFocus = "settings";
                    render();
                    d.dismiss();
                })
                .show();
    }

    private void chooseCity() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(prefs.city());
        input.setSelection(input.getText().length());

        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);

        dialog().setTitle("Город для погоды")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String city = input.getText().toString().trim();
                    if (!city.isEmpty()) prefs.setCity(city);
                    prefs.setWeatherOn(true);
                    updateWeather();
                })
                .setNeutralButton("Выключить погоду", (d, w) -> {
                    prefs.setWeatherOn(false);
                    updateWeather();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showAbout() {
        String version = "1.0";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        dialog().setTitle("NightTV")
                .setMessage("Домашний экран для Android TV\n"
                        + "Версия " + version + "\n"
                        + "Автор: NightLord РВЕ\n\n"
                        + "ОК — открыть приложение\n"
                        + "Удерживать ОК или «Меню» — избранное, порядок, скрыть, удалить\n"
                        + "«Домой» или «Назад» — вернуться к началу экрана")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ погода

    private void updateWeather() {
        if (weatherView == null) return;
        if (!prefs.weatherOn()) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        weatherView.setVisibility(View.VISIBLE);
        final String city = prefs.city();
        worker.execute(() -> {
            String result;
            try {
                result = WeatherClient.fetch(city);
            } catch (Exception e) {
                result = null; // нет сети — просто не показываем
            }
            final String shown = result;
            ui.post(() -> {
                if (shown != null) {
                    weatherView.setText(shown);
                } else if (weatherView.getText().length() == 0) {
                    weatherView.setVisibility(View.GONE);
                }
            });
        });
    }
}
