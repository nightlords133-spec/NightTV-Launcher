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

import java.io.File;
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
    private static final String TAG_YT = "yt:";
    private static final String TAG_TR = "tr:";
    private static final long YT_PERIOD_MS = 30 * 60 * 1000L;
    private static final String YT_REGION = "RU";

    /** Плееры YouTube в порядке предпочтения для режима «Автоматически». */
    private static final String[] YT_PLAYERS = {
            "com.google.android.youtube.tv",
            "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv",
            "org.smarttube.stable",
            "org.smarttube.beta",
            "com.google.android.youtube",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService ytWorker = Executors.newSingleThreadExecutor();
    private final ImageLoader images = new ImageLoader();

    // YouTube: данные рядов и их состояние
    private List<Video> ytVideos = new ArrayList<>();
    private List<Video> trVideos = new ArrayList<>();
    private String ytError;
    private String trError;
    private boolean ytLoading;
    private long ytLastLoad;

    private TextView ytTitle;
    private TextView ytHint;
    private View ytScroll;
    private LinearLayout ytRow;
    private TextView trTitle;
    private TextView trHint;
    private View trScroll;
    private LinearLayout trRow;

    private Prefs prefs;
    private AppRepository repo;
    private List<AppInfo> apps = new ArrayList<>();

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout favRow;
    private LinearLayout grid;
    private TextView allTitle;
    private android.app.Dialog folderDialog;
    private static final String[] GROUP_MODES = {"Все вместе", "Разделы по категориям", "Папки"};
    private TextView favHint;
    private TextView recentTitle;
    private View recentScroll;
    private LinearLayout recentRow;
    private TextView statusView;
    private TextView weatherView;
    private TextView settingsButton;
    private TextView sleepButton;

    // Заставка, ночной режим
    private FrameLayout saverView;
    private LinearLayout saverContent;
    private TextView saverWeather;
    private boolean saverShowing;
    private boolean saverSwallowUp;
    private long lastInput = System.currentTimeMillis();
    private View launcherDim;
    private ImageView wallView;
    private View wallDim;
    private ImageView saverImage;
    private List<File> wallFiles;
    private static final int REQ_PICK_IMAGE = 41;
    private static final int REQ_READ_IMAGES = 42;
    private static final long SAVER_MOVE_MS = 20 * 1000L;
    private static final int[] SAVER_OPTIONS = {0, 2, 5, 10, 15, 30};

    // Автоспасение YouTube
    private Boolean ytReachable;
    private long lastRescueAt;
    private boolean rescueHintShown;
    private boolean filterHintShown;
    private int ytFails;
    private int rescueAttempts;
    /** VPN ByeByeDPI работает, но NightTV не входит в её «Фильтр приложений». */
    private boolean dpiBypassesUs;
    private static final long RESCUE_PAUSE_MS = 5 * 60 * 1000L;

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

    private final Runnable saverTask = new Runnable() {
        @Override
        public void run() {
            int min = prefs.saverMinutes();
            // hasWindowFocus() == false — открыто меню или окно, заставку не показываем
            if (!saverShowing && min > 0 && hasWindowFocus()
                    && System.currentTimeMillis() - lastInput > min * 60_000L) {
                showSaver(false);
            }
            updateSleepButton();
            ui.postDelayed(this, 15_000L);
        }
    };

    private final Runnable saverMove = new Runnable() {
        @Override
        public void run() {
            if (!saverShowing || saverContent == null) return;
            saverContent.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                int w = saverView.getWidth() - saverContent.getWidth();
                int h = saverView.getHeight() - saverContent.getHeight();
                saverContent.setTranslationX((float) (Math.random() * Math.max(0, w)));
                saverContent.setTranslationY((float) (Math.random() * Math.max(0, h)));
                saverContent.animate().alpha(1f).setDuration(600).start();
            }).start();
            ui.postDelayed(this, SAVER_MOVE_MS);
        }
    };

    /** Работает всегда (и когда открыто другое приложение): включает/выключает ночное затемнение. */
    private final Runnable nightTask = new Runnable() {
        @Override
        public void run() {
            applyNight();
            ui.postDelayed(this, 60_000L);
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
        applyWallpaper();
        registerPackageReceiver();
        ui.post(nightTask);
        if (getIntent() != null && getIntent().getBooleanExtra(SleepTimer.EXTRA_BLACK, false)) {
            showSaver(true);
        }
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
        loadYouTube(false);
        refreshDailyWallpaper(false);
        lastInput = System.currentTimeMillis();
        netCheckedAt = 0; // при возврате на главный экран — сразу свежая проверка
        ui.removeCallbacks(saverTask);
        ui.post(saverTask);
        applyNight();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(weatherTask);
        ui.removeCallbacks(statusTask);
        ui.removeCallbacks(saverTask);
        if (saverShowing) hideSaver();
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
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        netWorker.shutdownNow();
        ytWorker.shutdownNow();
        images.shutdown();
    }

    /** Нажали «Домой», уже находясь на домашнем экране — вернуться наверх. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(SleepTimer.EXTRA_BLACK, false)) {
            showSaver(true);
            return;
        }
        if (saverShowing) hideSaver();
        lastInput = System.currentTimeMillis();
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusFirstTile();
    }

    /** Любая кнопка пульта будит заставку; кнопка поиска — голосовой поиск. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInput = System.currentTimeMillis();
        if (saverShowing) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                hideSaver();
                saverSwallowUp = true;
            }
            return true;
        }
        if (saverSwallowUp && event.getAction() == KeyEvent.ACTION_UP) {
            saverSwallowUp = false;
            return true;
        }
        int k = event.getKeyCode();
        if ((k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST)
                && event.getAction() == KeyEvent.ACTION_UP) {
            voiceSearch();
            return true;
        }
        return super.dispatchKeyEvent(event);
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

        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallView.setVisibility(View.GONE);
        root.addView(wallView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallDim = new View(this);
        wallDim.setVisibility(View.GONE);
        root.addView(wallDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        ytTitle = sectionTitle("YouTube · новое на ваших каналах");
        column.addView(ytTitle);
        ytHint = text(15, 0.6f);
        ytHint.setPadding(0, dp(4), 0, dp(12));
        column.addView(ytHint);
        ytRow = newRow();
        ytScroll = wrapRow(ytRow);
        column.addView(ytScroll);

        trTitle = sectionTitle("YouTube · в тренде");
        column.addView(trTitle);
        trHint = text(15, 0.6f);
        trHint.setPadding(0, dp(4), 0, dp(12));
        column.addView(trHint);
        trRow = newRow();
        trScroll = wrapRow(trRow);
        column.addView(trScroll);

        allTitle = sectionTitle("Все приложения");
        column.addView(allTitle);
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

        launcherDim = new View(this);
        launcherDim.setFocusable(false);
        launcherDim.setClickable(false);
        launcherDim.setVisibility(View.GONE);
        root.addView(launcherDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildSaver(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        statusView.setPadding(dp(10), dp(4), dp(10), dp(4));
        // Строку состояния можно выбрать пультом: ОК — подробная проверка интернета
        final GradientDrawable statusBg = new GradientDrawable();
        statusBg.setCornerRadius(dp(14));
        statusBg.setColor(0x00000000);
        statusView.setBackground(statusBg);
        statusView.setFocusable(true);
        statusView.setClickable(true);
        statusView.setTag("status");
        statusView.setOnClickListener(v -> runNetworkTest());
        statusView.setOnFocusChangeListener((v, focused) -> {
            statusBg.setColor(focused ? 0x33FFFFFF : 0x00000000);
            statusBg.setStroke(focused ? dp(2) : 0, Themes.accent(prefs.theme()));
            v.setAlpha(focused ? 1f : 0.6f);
        });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(-dp(10), dp(4), 0, 0);
        timeBox.addView(statusView, stLp);

        header.addView(timeBox);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        weatherView = text(20, 0.9f);
        weatherView.setPadding(0, 0, dp(28), 0);
        header.addView(weatherView);

        header.addView(headerButton("🎤", "voice", v -> voiceSearch()));
        header.addView(headerButton("🧹", "clean", v -> cleanMemory()));
        sleepButton = headerButton("⏾", "sleep", v -> showSleepTimer());
        header.addView(sleepButton);
        settingsButton = headerButton("⚙  Настройки", "settings", v -> showSettings());
        header.addView(settingsButton);
        return header;
    }

    private TextView headerButton(String label, String tag, View.OnClickListener click) {
        final TextView b = text(18, 0.85f);
        b.setText(label);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0x22FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setTag(tag);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? 0x55FFFFFF : 0x22FFFFFF);
            bg.setStroke(dp(2), focused ? Themes.accent(prefs.theme()) : 0x00000000);
            v.setAlpha(focused ? 1f : 0.85f);
            v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f)
                    .setDuration(150).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
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
        // Рамка выделения рисуется ПОВЕРХ картинки
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(0x00000000);
        card.setForeground(ring);

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
            highlight(card, ring, label, focused, accent, 0.7f);
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
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

    private View makeVideoTile(Video video, int width, String tag) {
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
        // Рамка выделения рисуется ПОВЕРХ картинки
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(0x00000000);
        card.setForeground(ring);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        images.load(video.thumb, img);

        TextView play = new TextView(this);
        play.setText("▶");
        play.setTextColor(0xEEFFFFFF);
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        play.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setCornerRadius(dp(6));
        playBg.setColor(0xCCE53935);
        play.setBackground(playBg);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        playLp.setMargins(0, 0, dp(6), dp(6));
        card.addView(play, playLp);

        tile.addView(card, new LinearLayout.LayoutParams(width, height));

        final TextView title = text(14, 0.75f);
        title.setText(video.title);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(8), 0, 0);
        tile.addView(title, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView channel = text(12, 0.5f);
        channel.setText(video.channel);
        channel.setSingleLine(true);
        channel.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(channel, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));

        tile.setOnFocusChangeListener((v, focused) -> {
            highlight(card, ring, title, focused, accent, 0.75f);
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
                    .setDuration(150).start();
        });
        tile.setOnClickListener(v -> openVideo(video, false));
        tile.setOnLongClickListener(v -> {
            showVideoMenu(video);
            return true;
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) showVideoMenu(video);
                return true;
            }
            return false;
        });
        return tile;
    }

    /** Выделение плитки в фокусе: яркая рамка поверх картинки, подсветка, тень, подпись цветом темы. */
    private void highlight(FrameLayout card, GradientDrawable ring, TextView label,
                           boolean focused, int accent, float normalAlpha) {
        ring.setStroke(focused ? dp(4) : 0, focused ? accent : 0x00000000);
        ring.setColor(focused ? 0x1AFFFFFF : 0x00000000);
        card.setElevation(focused ? dp(16) : 0);
        ((View) card.getParent()).setTranslationZ(focused ? dp(16) : 0);
        label.setAlpha(focused ? 1f : normalAlpha);
        label.setTextColor(focused ? accent : 0xFFFFFFFF);
        label.setTypeface(null, focused ? Typeface.BOLD : Typeface.NORMAL);
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

        renderYouTube(width);

        if (folderDialog != null && folderDialog.isShowing()) folderDialog.dismiss();
        int mode = prefs.groupMode();
        allTitle.setVisibility(mode == 1 ? View.GONE : View.VISIBLE);
        if (mode == 0) {
            addGrid(grid, visible, width, TAG_ALL);
        } else {
            Map<String, List<AppInfo>> groups = groupApps(visible);
            if (mode == 1) {
                for (Map.Entry<String, List<AppInfo>> e : groups.entrySet()) {
                    TextView t = sectionTitle(e.getKey() + "  ·  " + e.getValue().size());
                    grid.addView(t);
                    addGrid(grid, e.getValue(), width, TAG_ALL);
                }
            } else {
                List<View> folders = new ArrayList<>();
                for (Map.Entry<String, List<AppInfo>> e : groups.entrySet()) {
                    folders.add(makeFolderTile(e.getKey(), e.getValue(), width));
                }
                addRows(grid, folders);
            }
        }

        View target = pendingFocus != null ? root.findViewWithTag(pendingFocus) : null;
        pendingFocus = null;
        if (target != null) {
            target.requestFocus();
        } else {
            focusFirstTile();
        }
    }

    // ------------------------------------------------------------ категории и папки

    private String categoryOf(AppInfo a) {
        String c = prefs.categoryOverride(a.pkg);
        return c != null ? c : Categories.auto(a);
    }

    private List<String> allCategories() {
        List<String> out = new ArrayList<>(java.util.Arrays.asList(Categories.DEFAULTS));
        for (String c : prefs.customCategories()) if (!out.contains(c)) out.add(c);
        return out;
    }

    /** Группы в постоянном порядке: стандартные, затем свои; пустые пропускаются. */
    private Map<String, List<AppInfo>> groupApps(List<AppInfo> list) {
        Map<String, List<AppInfo>> byCat = new java.util.LinkedHashMap<>();
        for (String c : allCategories()) byCat.put(c, new ArrayList<>());
        for (AppInfo a : list) {
            String c = categoryOf(a);
            if (!byCat.containsKey(c)) byCat.put(c, new ArrayList<>());
            byCat.get(c).add(a);
        }
        // «Другое» всегда в конце
        List<AppInfo> other = byCat.remove(Categories.OTHER);
        byCat.put(Categories.OTHER, other != null ? other : new ArrayList<>());
        Map<String, List<AppInfo>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<AppInfo>> e : byCat.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private void addGrid(LinearLayout target, List<AppInfo> list, int width, String tagPrefix) {
        List<View> tiles = new ArrayList<>();
        for (AppInfo a : list) tiles.add(makeTile(a, width, tagPrefix + a.pkg));
        addRows(target, tiles);
    }

    private void addRows(LinearLayout target, List<View> tiles) {
        int cols = prefs.columns();
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                target.addView(row);
            }
            row.addView(tiles.get(i), tileParams());
        }
    }

    private View makeFolderTile(final String category, final List<AppInfo> list, int width) {
        final int accent = Themes.accent(prefs.theme());
        int height = width * 9 / 16;

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag("dir:" + category);

        FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(10));
        cardBg.setColor(0x40FFFFFF);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(0x00000000);
        card.setForeground(ring);

        // Слева значок папки, справа до 4 иконок приложений
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER);
        TextView emoji = new TextView(this);
        emoji.setText(Categories.icon(category));
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.42f);
        emoji.setPadding(0, 0, dp(10), 0);
        inner.addView(emoji);

        android.widget.GridLayout icons = new android.widget.GridLayout(this);
        icons.setColumnCount(2);
        int iconSize = (int) (height * 0.3f);
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(list.get(i).icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = iconSize;
            lp.height = iconSize;
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            icons.addView(iv, lp);
        }
        inner.addView(icons);
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(card, new LinearLayout.LayoutParams(width, height));

        final TextView label = text(14, 0.7f);
        label.setText(category + " · " + list.size());
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));

        tile.setOnFocusChangeListener((v, focused) -> {
            highlight(card, ring, label, focused, accent, 0.7f);
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
                    .setDuration(150).start();
        });
        tile.setOnClickListener(v -> openFolder(category, list));
        return tile;
    }

    private void openFolder(String category, List<AppInfo> list) {
        if (folderDialog != null && folderDialog.isShowing()) folderDialog.dismiss();
        android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);

        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setBackgroundColor(0xE6101418);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(dp(48), dp(27), dp(48), dp(27));

        TextView title = text(30, 1f);
        title.setText(Categories.icon(category) + "  " + category);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(4));
        box.addView(title);
        TextView hint = text(14, 0.5f);
        hint.setText("«Назад» — закрыть папку · удерживать ОК — сменить категорию");
        hint.setPadding(0, 0, 0, dp(16));
        box.addView(hint);

        addGrid(box, list, tileWidth(), "fld:");
        sv.addView(box);
        dlg.setContentView(sv);
        folderDialog = dlg;
        dlg.setOnShowListener(d -> {
            for (int i = 0; i < box.getChildCount(); i++) {
                View row = box.getChildAt(i);
                if (row instanceof LinearLayout && ((ViewGroup) row).getChildCount() > 0) {
                    ((ViewGroup) row).getChildAt(0).requestFocus();
                    break;
                }
            }
        });
        dlg.show();
    }

    private void chooseCategory(final AppInfo app) {
        final List<String> cats = allCategories();
        final String current = categoryOf(app);
        final List<String> labels = new ArrayList<>();
        for (String c : cats) labels.add((c.equals(current) ? "✓ " : "    ") + c);
        labels.add("＋ Новая категория…");
        labels.add("Определять автоматически (" + Categories.auto(app) + ")");

        dialog().setTitle("Категория: " + app.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < cats.size()) {
                        prefs.setCategoryOverride(app.pkg, cats.get(which));
                        render();
                    } else if (which == cats.size()) {
                        newCategory(app);
                    } else {
                        prefs.setCategoryOverride(app.pkg, null);
                        render();
                    }
                })
                .show();
    }

    private void newCategory(final AppInfo app) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setHint("Например: Детям");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        dialog().setTitle("Новая категория")
                .setView(box)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    prefs.addCustomCategory(name);
                    prefs.setCategoryOverride(app.pkg, name);
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseGroupMode() {
        dialog().setTitle("Вид списка приложений")
                .setSingleChoiceItems(GROUP_MODES, prefs.groupMode(), (d, which) -> {
                    prefs.setGroupMode(which);
                    pendingFocus = "settings";
                    render();
                    d.dismiss();
                })
                .show();
    }

    private void focusFirstTile() {
        if (favRow != null && favRow.getChildCount() > 0) {
            favRow.getChildAt(0).requestFocus();
            return;
        }
        if (grid != null) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                View row = grid.getChildAt(i);
                if (row instanceof LinearLayout && ((ViewGroup) row).getChildCount() > 0
                        && ((ViewGroup) row).getChildAt(0).isFocusable()) {
                    ((ViewGroup) row).getChildAt(0).requestFocus();
                    return;
                }
            }
        }
        if (settingsButton != null) {
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
        ensureDpiThen(app.pkg, () -> launchNow(app));
    }

    private void launchNow(AppInfo app) {
        prefs.pushRecent(app.pkg);
        if (folderDialog != null && folderDialog.isShowing()) folderDialog.dismiss();
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

        labels.add("Категория: " + categoryOf(app) + "…");
        actions.add(() -> chooseCategory(app));

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
                "Обои: " + Wallpaper.MODES[Math.max(0, Math.min(4, prefs.wallMode()))] + "…",
                "Колонок в сетке: " + prefs.columns(),
                "Вид приложений: " + GROUP_MODES[Math.max(0, Math.min(2, prefs.groupMode()))],
                "Погода: " + weather,
                "YouTube…",
                "Автоспасение YouTube (ByeByeDPI): " + (prefs.dpiAuto() ? "вкл" : "выкл"),
                "Заставка: " + (prefs.saverMinutes() == 0 ? "выключена" : "через " + prefs.saverMinutes() + " мин"),
                "Ночной режим: " + NightMode.MODES[Math.max(0, Math.min(2, prefs.nightMode()))] + "…",
                "Скрытые приложения (" + prefs.hidden().size() + ")",
                "Очистить «Недавние»",
                "Проверить обновления",
                "Проверить интернет и скорость",
                "Системные настройки Android",
                "О программе",
        };
        dialog().setTitle("Настройки")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: chooseTheme(); break;
                        case 1: showWallpaperSettings(); break;
                        case 2: chooseColumns(); break;
                        case 3: chooseGroupMode(); break;
                        case 4: chooseCity(); break;
                        case 5: showYouTubeSettings(); break;
                        case 6: chooseDpiAuto(); break;
                        case 7: chooseSaver(); break;
                        case 8: showNightSettings(); break;
                        case 9: chooseHidden(); break;
                        case 10:
                            prefs.clearRecents();
                            pendingFocus = "settings";
                            render();
                            toast("Список недавних очищен");
                            break;
                        case 11: checkUpdates(true); break;
                        case 12: runNetworkTest(); break;
                        case 13: safeStart(new Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
                        default: showAbout(); break;
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ YouTube

    private String ytKey() {
        String own = prefs.ytKey().trim();
        return own.isEmpty() ? BuildConfig.YT_API_KEY : own;
    }

    private void loadYouTube(boolean force) {
        if (!prefs.ytShow() || ytLoading) return;
        if (!force && System.currentTimeMillis() - ytLastLoad < YT_PERIOD_MS) return;
        final List<YouTubeClient.Channel> channels = prefs.ytChannels();
        final String key = ytKey();
        if (channels.isEmpty() && key.isEmpty()) return;

        ytLoading = true;
        ytWorker.execute(() -> {
            List<Video> fromChannels = new ArrayList<>();
            List<Video> trending = new ArrayList<>();
            String chErr = null;
            String trErr = null;
            if (!channels.isEmpty()) {
                fromChannels = YouTubeClient.latestFromChannels(channels, 20);
                if (fromChannels.isEmpty()) chErr = "Не удалось загрузить видео. Проверьте интернет.";
            }
            if (!key.isEmpty()) {
                try {
                    trending = YouTubeClient.trending(key, YT_REGION);
                } catch (Exception e) {
                    trErr = "Не удалось загрузить тренды: " + e.getMessage();
                }
            }
            final List<Video> a = fromChannels;
            final List<Video> b = trending;
            final String ea = chErr;
            final String eb = trErr;
            ui.post(() -> {
                ytLoading = false;
                ytLastLoad = System.currentTimeMillis();
                if (!a.isEmpty() || ea != null || channels.isEmpty()) {
                    ytVideos = a;
                    ytError = ea;
                }
                if (!b.isEmpty() || eb != null || key.isEmpty()) {
                    trVideos = b;
                    trError = eb;
                }
                render();
            });
        });
    }

    private void renderYouTube(int width) {
        ytRow.removeAllViews();
        trRow.removeAllViews();

        boolean show = prefs.ytShow();

        // Новое на каналах
        boolean noChannels = prefs.ytChannels().isEmpty();
        for (Video v : ytVideos) ytRow.addView(makeVideoTile(v, width, TAG_YT + v.id), tileParams());
        ytTitle.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            ytHint.setVisibility(View.GONE);
        } else if (noChannels) {
            ytHint.setText("Добавьте любимые каналы: ⚙ Настройки → YouTube… → Мои каналы");
            ytHint.setVisibility(View.VISIBLE);
        } else if (ytVideos.isEmpty()) {
            ytHint.setText(ytError != null ? ytError : "Загрузка…");
            ytHint.setVisibility(View.VISIBLE);
        } else {
            ytHint.setVisibility(View.GONE);
        }
        ytScroll.setVisibility(show && !ytVideos.isEmpty() ? View.VISIBLE : View.GONE);

        // Тренды — только если есть ключ
        boolean hasKey = !ytKey().isEmpty();
        for (Video v : trVideos) trRow.addView(makeVideoTile(v, width, TAG_TR + v.id), tileParams());
        boolean trShow = show && hasKey;
        trTitle.setVisibility(trShow ? View.VISIBLE : View.GONE);
        if (trShow && trVideos.isEmpty()) {
            trHint.setText(trError != null ? trError : "Загрузка…");
            trHint.setVisibility(View.VISIBLE);
        } else {
            trHint.setVisibility(View.GONE);
        }
        trScroll.setVisibility(trShow && !trVideos.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private Intent videoIntent(Video v) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + v.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private boolean canHandle(Intent base, String pkg) {
        Intent probe = new Intent(base).setPackage(pkg);
        try {
            return !getPackageManager().queryIntentActivities(probe, 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void openVideo(Video v, boolean choose) {
        ensureDpiThen("youtube", () -> openVideoNow(v, choose));
    }

    private void openVideoNow(Video v, boolean choose) {
        Intent i = videoIntent(v);
        if (choose) {
            safeStart(Intent.createChooser(i, "Открыть видео в…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        String chosen = prefs.ytPlayer();
        if (!chosen.isEmpty() && canHandle(i, chosen)) {
            i.setPackage(chosen);
        } else {
            for (String pkg : YT_PLAYERS) {
                if (canHandle(i, pkg)) {
                    i.setPackage(pkg);
                    break;
                }
            }
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            safeStart(i);
        }
    }

    private void showVideoMenu(Video v) {
        String[] items = {"Смотреть", "Открыть в другом приложении…"};
        dialog().setTitle(v.title)
                .setItems(items, (d, which) -> openVideo(v, which == 1))
                .show();
    }

    private void showYouTubeSettings() {
        String keyState;
        if (!prefs.ytKey().trim().isEmpty()) keyState = "введён на приставке";
        else if (!BuildConfig.YT_API_KEY.isEmpty()) keyState = "из сборки";
        else keyState = "нет";

        String player = "автоматически";
        if (!prefs.ytPlayer().isEmpty()) player = labelOf(prefs.ytPlayer());

        String[] items = {
                "Показывать на главном: " + (prefs.ytShow() ? "да" : "нет"),
                "Мои каналы (" + prefs.ytChannels().size() + ")",
                "Ключ API для трендов: " + keyState,
                "Открывать видео в: " + player,
                "Обновить сейчас",
        };
        dialog().setTitle("YouTube")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            prefs.setYtShow(!prefs.ytShow());
                            pendingFocus = "settings";
                            render();
                            loadYouTube(true);
                            break;
                        case 1: manageChannels(); break;
                        case 2: enterYtKey(); break;
                        case 3: choosePlayer(); break;
                        default:
                            toast("Обновляю YouTube…");
                            loadYouTube(true);
                            break;
                    }
                })
                .show();
    }

    private String labelOf(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void manageChannels() {
        final List<YouTubeClient.Channel> channels = prefs.ytChannels();
        final List<String> labels = new ArrayList<>();
        labels.add("＋ Добавить канал…");
        for (YouTubeClient.Channel ch : channels) labels.add(ch.name);

        dialog().setTitle("Мои каналы")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        addChannel();
                        return;
                    }
                    final YouTubeClient.Channel ch = channels.get(which - 1);
                    dialog().setTitle(ch.name)
                            .setMessage("Убрать канал из ряда YouTube?")
                            .setPositiveButton("Убрать", (d2, w2) -> {
                                List<YouTubeClient.Channel> l = prefs.ytChannels();
                                for (int i = 0; i < l.size(); i++) {
                                    if (l.get(i).id.equals(ch.id)) {
                                        l.remove(i);
                                        break;
                                    }
                                }
                                prefs.setYtChannels(l);
                                ytVideos = new ArrayList<>();
                                render();
                                loadYouTube(true);
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                })
                .show();
    }

    private void addChannel() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint("@имя_канала или ссылка");

        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);

        dialog().setTitle("Добавить канал")
                .setMessage("Например: @varlamov или youtube.com/@varlamov")
                .setView(box)
                .setPositiveButton("Добавить", (d, w) -> {
                    final String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    toast("Ищу канал…");
                    final String key = ytKey();
                    ytWorker.execute(() -> {
                        try {
                            final YouTubeClient.Channel ch = YouTubeClient.resolve(text, key);
                            ui.post(() -> {
                                List<YouTubeClient.Channel> l = prefs.ytChannels();
                                for (YouTubeClient.Channel c : l) {
                                    if (c.id.equals(ch.id)) {
                                        toast("Канал уже добавлен: " + ch.name);
                                        return;
                                    }
                                }
                                l.add(ch);
                                prefs.setYtChannels(l);
                                toast("Добавлен: " + ch.name);
                                loadYouTube(true);
                            });
                        } catch (Exception e) {
                            final String m = e.getMessage();
                            ui.post(() -> toast("Не удалось добавить: " + m));
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void enterYtKey() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setText(prefs.ytKey());
        input.setHint("AIza…");

        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);

        String msg = "Ключ YouTube Data API v3 нужен только для ряда «В тренде».";
        if (!BuildConfig.YT_API_KEY.isEmpty()) msg += "\nЕсли оставить пустым — используется ключ из сборки.";

        dialog().setTitle("Ключ API")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    prefs.setYtKey(input.getText().toString().trim());
                    trVideos = new ArrayList<>();
                    trError = null;
                    render();
                    loadYouTube(true);
                })
                .setNeutralButton("Очистить", (d, w) -> {
                    prefs.setYtKey("");
                    trVideos = new ArrayList<>();
                    trError = null;
                    render();
                    loadYouTube(true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void choosePlayer() {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        pkgs.add("");
        labels.add("Автоматически");
        try {
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(probe, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkgs.contains(pkg)) continue;
                pkgs.add(pkg);
                labels.add(String.valueOf(ri.loadLabel(getPackageManager())));
            }
        } catch (Exception ignored) {
        }
        int current = Math.max(0, pkgs.indexOf(prefs.ytPlayer()));
        dialog().setTitle("Открывать видео в")
                .setSingleChoiceItems(labels.toArray(new String[0]), current, (d, which) -> {
                    prefs.setYtPlayer(pkgs.get(which));
                    d.dismiss();
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

    // ------------------------------------------------------------ автоспасение YouTube

    private boolean isYouTubeTarget(String pkg) {
        if (pkg.contains("youtube") || pkg.contains("smarttube")) return true;
        for (String p : YT_PLAYERS) if (p.equals(pkg)) return true;
        return false;
    }

    /** Перед YouTube включаем ByeByeDPI, если она установлена и выключена. */
    private void ensureDpiThen(String pkg, Runnable action) {
        if (prefs.dpiAuto() && isYouTubeTarget(pkg)
                && DpiRescue.installed(this) && !DpiRescue.vpnActive(this)
                && DpiRescue.start(this)) {
            toast("Включаю ByeByeDPI…");
            ui.postDelayed(action, 1500);
        } else {
            action.run();
        }
    }

    /** Интернет есть, а YouTube нет — включаем или перезапускаем ByeByeDPI. */
    private void rescueYouTube(boolean manual) {
        if (!DpiRescue.installed(this)) {
            if (manual) toast("ByeByeDPI не установлена");
            return;
        }
        if (!manual && (!prefs.dpiAuto()
                || System.currentTimeMillis() - lastRescueAt < RESCUE_PAUSE_MS
                || rescueAttempts >= 2)) return; // после двух безуспешных попыток не дёргаем ByeByeDPI
        lastRescueAt = System.currentTimeMillis();
        rescueAttempts++;

        if (!DpiRescue.vpnActive(this)) {
            toast("YouTube недоступен — включаю ByeByeDPI");
            DpiRescue.start(this);
        } else {
            toast("YouTube недоступен — перезапускаю ByeByeDPI");
            DpiRescue.stop(this);
            ui.postDelayed(() -> DpiRescue.start(this), 2500);
        }
        // Через 10 секунд проверяем ещё раз
        ui.postDelayed(() -> {
            if (!DpiRescue.vpnActive(this) && !rescueHintShown) {
                rescueHintShown = true;
                dialog().setTitle("ByeByeDPI не включилась")
                        .setMessage("Откройте ByeByeDPI и один раз нажмите «Подключить», "
                                + "разрешив ей режим VPN. Дальше NightTV будет включать её сам.")
                        .setPositiveButton("Открыть ByeByeDPI", (d, w) -> {
                            Intent i = getPackageManager().getLaunchIntentForPackage(DpiRescue.PKG);
                            if (i != null) safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        })
                        .setNegativeButton("Позже", null)
                        .show();
            }
            netCheckedAt = 0;
            checkInternetQuick();
        }, 10_000L);
    }

    private void showFilterHint() {
        if (filterHintShown || !hasWindowFocus()) return;
        filterHintShown = true;
        dialog().setTitle("NightTV не видит YouTube")
                .setMessage("ByeByeDPI работает, но в ней включён «Фильтр приложений», "
                        + "и NightTV в него не входит. Поэтому проверка из лаунчера показывает, "
                        + "что YouTube недоступен, хотя в самом YouTube всё работает. "
                        + "Ряды YouTube в NightTV из-за этого тоже могут не загружаться.\n\n"
                        + "Как исправить — в ByeByeDPI:\n"
                        + "Настройки → Фильтр приложений:\n"
                        + "• «Белый список» → Выбрать приложения → отметьте NightTV;\n"
                        + "• или «Чёрный список» / «Нет».\n\n"
                        + "Пока так, автоспасение ByeByeDPI не перезапускает.")
                .setPositiveButton("Открыть ByeByeDPI", (d, w) -> {
                    Intent i = getPackageManager().getLaunchIntentForPackage(DpiRescue.PKG);
                    if (i != null) safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                })
                .setNegativeButton("Понятно", null)
                .show();
    }

    private void chooseDpiAuto() {
        String[] items = {
                prefs.dpiAuto() ? "Выключить автоспасение" : "Включить автоспасение",
                "Спасти YouTube сейчас",
                "Как это работает",
        };
        dialog().setTitle("Автоспасение YouTube")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        prefs.setDpiAuto(!prefs.dpiAuto());
                        toast(prefs.dpiAuto() ? "Автоспасение включено" : "Автоспасение выключено");
                    } else if (which == 1) {
                        rescueYouTube(true);
                    } else {
                        dialog().setTitle("Автоспасение YouTube")
                                .setMessage("Раз в минуту, пока открыт главный экран, NightTV проверяет "
                                        + "интернет и YouTube. Если интернет есть, а YouTube нет — "
                                        + "включает ByeByeDPI или перезапускает её (не чаще раза в 5 минут).\n\n"
                                        + "Перед запуском YouTube, SmartTube и видео из рядов NightTV тоже "
                                        + "включает ByeByeDPI, если она выключена.\n\n"
                                        + "Работает с режимом VPN в ByeByeDPI.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ голосовой поиск

    private void voiceSearch() {
        String[] actions = {
                "android.intent.action.ASSIST",
                "android.intent.action.VOICE_ASSIST",
                "android.speech.action.WEB_SEARCH",
                "android.search.action.GLOBAL_SEARCH",
                "android.intent.action.WEB_SEARCH",
        };
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        toast("Голосовой поиск на этой приставке недоступен");
    }

    // ------------------------------------------------------------ очистка памяти

    private void cleanMemory() {
        toast("Очищаю память…");
        final List<String> pkgs = new ArrayList<>(installedPackages());
        worker.execute(() -> {
            final MemoryCleaner.Result r = MemoryCleaner.clean(this, pkgs);
            ui.post(() -> {
                long gained = r.afterFree - r.beforeFree;
                String msg = "Закрыто фоновых приложений: " + r.closed + "\n"
                        + "Свободно памяти: " + MemoryCleaner.size(this, r.afterFree)
                        + " из " + MemoryCleaner.size(this, r.total);
                if (gained > 0) msg += "\nОсвобождено: " + MemoryCleaner.size(this, gained);
                dialog().setTitle("🧹 Очистка памяти")
                        .setMessage(msg + "\n\nByeByeDPI и клавиатура не закрываются.")
                        .setPositiveButton("OK", null)
                        .show();
                updateStatus();
            });
        });
    }

    // ------------------------------------------------------------ таймер сна

    private void updateSleepButton() {
        if (sleepButton == null) return;
        long left = SleepTimer.remaining(this);
        sleepButton.setText(left > 0 ? "⏾ " + ((left + 59_999) / 60_000) + " мин" : "⏾");
    }

    private void showSleepTimer() {
        final int[] mins = {0, 15, 30, 45, 60, 90, 120};
        String[] items = new String[mins.length + 1];
        long left = SleepTimer.remaining(this);
        items[0] = left > 0 ? "Выключить таймер (осталось " + ((left + 59_999) / 60_000) + " мин)" : "Таймер выключен";
        for (int i = 1; i < mins.length; i++) items[i] = "Через " + mins[i] + " мин";
        items[mins.length] = "Выключить экран сейчас";
        dialog().setTitle("⏾ Таймер сна")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        SleepTimer.cancel(this);
                        toast("Таймер сна выключен");
                    } else if (which == mins.length) {
                        SleepTimer.sleepNow(this);
                    } else {
                        SleepTimer.set(this, mins[which]);
                        toast("Экран выключится через " + mins[which] + " мин");
                        if (!SleepTimer.adminActive(this)) askAdmin();
                    }
                    updateSleepButton();
                })
                .show();
    }

    private void askAdmin() {
        dialog().setTitle("Разрешить выключение экрана")
                .setMessage("Чтобы таймер сна мог выключать экран, разрешите NightTV «блокировку экрана». "
                        + "Без этого по таймеру видео встанет на паузу и включится чёрный экран.\n\n"
                        + "Если позже захотите удалить NightTV, сначала снимите это разрешение: "
                        + "Настройки Android → Безопасность → Администраторы устройства.")
                .setPositiveButton("Разрешить", (d, w) -> safeStart(
                        new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, SleepTimer.admin(this))
                                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Нужно таймеру сна NightTV, чтобы выключать экран.")))
                .setNegativeButton("Не нужно", null)
                .show();
    }

    // ------------------------------------------------------------ обои

    private void applyWallpaper() {
        if (prefs.wallMode() == Wallpaper.THEME || !Wallpaper.file(this).exists()) {
            wallView.setVisibility(View.GONE);
            wallDim.setVisibility(View.GONE);
            wallView.setImageDrawable(null);
            if (saverImage != null) {
                saverImage.setImageDrawable(null);
                saverImage.setVisibility(View.GONE);
            }
            return;
        }
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        worker.execute(() -> {
            final android.graphics.Bitmap bmp = Wallpaper.load(this, w, h);
            ui.post(() -> {
                if (bmp == null) {
                    wallView.setVisibility(View.GONE);
                    wallDim.setVisibility(View.GONE);
                    return;
                }
                wallView.setImageBitmap(bmp);
                wallView.setAlpha(0f);
                wallView.setVisibility(View.VISIBLE);
                wallView.animate().alpha(1f).setDuration(500).start();
                // затемнение + снизу темнее, чтобы плитки и подписи читались
                int a = Math.round(prefs.wallDim() * 2.55f);
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{android.graphics.Color.argb(a, 0, 0, 0),
                                android.graphics.Color.argb(Math.min(255, a + 60), 0, 0, 0)});
                wallDim.setBackground(g);
                wallDim.setVisibility(View.VISIBLE);
                if (saverImage != null) {
                    saverImage.setImageBitmap(bmp);
                    saverImage.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /** Фото дня скачивается раз в сутки. */
    private void refreshDailyWallpaper(boolean force) {
        final int mode = prefs.wallMode();
        if (mode != Wallpaper.BING && mode != Wallpaper.RANDOM) return;
        final String key = mode + ":" + Wallpaper.today();
        if (!force && key.equals(prefs.wallDay()) && Wallpaper.file(this).exists()) return;
        worker.execute(() -> {
            try {
                Wallpaper.downloadDaily(this, mode);
                ui.post(() -> {
                    prefs.setWallDay(key);
                    applyWallpaper();
                });
            } catch (Exception e) {
                final String m = e.getMessage();
                if (force) ui.post(() -> toast("Не удалось загрузить фото: " + m));
            }
        });
    }

    private void showWallpaperSettings() {
        String[] items = {
                "Источник: " + Wallpaper.MODES[Math.max(0, Math.min(4, prefs.wallMode()))],
                "Затемнение: " + prefs.wallDim() + "%",
                "Обновить фото дня сейчас",
        };
        dialog().setTitle("🖼 Обои")
                .setItems(items, (d, which) -> {
                    if (which == 0) chooseWallpaperSource();
                    else if (which == 1) chooseWallDim();
                    else {
                        if (prefs.wallMode() == Wallpaper.BING || prefs.wallMode() == Wallpaper.RANDOM) {
                            toast("Загружаю фото…");
                            refreshDailyWallpaper(true);
                        } else {
                            toast("Сначала выберите «Фото дня Bing» или «Случайное фото дня»");
                        }
                    }
                })
                .show();
    }

    private void chooseWallpaperSource() {
        dialog().setTitle("Источник обоев")
                .setSingleChoiceItems(Wallpaper.MODES, prefs.wallMode(), (d, which) -> {
                    d.dismiss();
                    switch (which) {
                        case Wallpaper.THEME:
                            prefs.setWallMode(Wallpaper.THEME);
                            applyWallpaper();
                            break;
                        case Wallpaper.BING:
                        case Wallpaper.RANDOM:
                            prefs.setWallMode(which);
                            prefs.setWallDay("");
                            toast("Загружаю фото дня…");
                            refreshDailyWallpaper(true);
                            break;
                        case Wallpaper.FILE:
                            pickImageFile();
                            break;
                        default:
                            enterWallpaperLink();
                            break;
                    }
                })
                .show();
    }

    private void chooseWallDim() {
        String[] names = new String[Wallpaper.DIMS.length];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = Wallpaper.DIMS[i] == 0 ? "Без затемнения" : Wallpaper.DIMS[i] + "%";
            if (Wallpaper.DIMS[i] == prefs.wallDim()) cur = i;
        }
        dialog().setTitle("Затемнение обоев")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    prefs.setWallDim(Wallpaper.DIMS[w]);
                    applyWallpaper();
                    d.dismiss();
                })
                .show();
    }

    private void enterWallpaperLink() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint("https://…/картинка.jpg");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        dialog().setTitle("Ссылка на картинку")
                .setView(box)
                .setPositiveButton("Установить", (d, w) -> {
                    final String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    toast("Загружаю картинку…");
                    worker.execute(() -> {
                        try {
                            Wallpaper.download(url.startsWith("http") ? url : "https://" + url, Wallpaper.file(this));
                            ui.post(() -> {
                                prefs.setWallMode(Wallpaper.LINK);
                                applyWallpaper();
                                toast("Обои установлены");
                            });
                        } catch (Exception e) {
                            final String m = e.getMessage();
                            ui.post(() -> toast("Не удалось: " + m));
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Сначала системный выбор файла; если его на приставке нет — свой поиск картинок. */
    private void pickImageFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (Exception e) {
            browseImages();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        worker.execute(() -> {
            try {
                Wallpaper.copyFromUri(this, uri);
                ui.post(() -> {
                    prefs.setWallMode(Wallpaper.FILE);
                    applyWallpaper();
                    toast("Обои установлены");
                });
            } catch (Exception e) {
                final String m = e.getMessage();
                ui.post(() -> toast("Не удалось: " + m));
            }
        });
    }

    private void browseImages() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_READ_IMAGES);
            return;
        }
        toast("Ищу картинки на флешках и в памяти…");
        worker.execute(() -> {
            final List<File> found = Wallpaper.findImages();
            ui.post(() -> showImageList(found));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_READ_IMAGES) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            browseImages();
        } else {
            toast("Без доступа к фото картинку не выбрать");
        }
    }

    private void showImageList(List<File> found) {
        if (found.isEmpty()) {
            dialog().setTitle("Картинки не найдены")
                    .setMessage("Скопируйте картинку (JPG или PNG) на флешку или в папку Download "
                            + "на приставке и попробуйте снова. Или выберите «Картинка по ссылке».")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        wallFiles = found;
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) {
            File f = found.get(i);
            File parent = f.getParentFile();
            names[i] = (parent != null ? parent.getName() + " / " : "") + f.getName();
        }
        dialog().setTitle("Выберите картинку")
                .setItems(names, (d, which) -> {
                    final File src = wallFiles.get(which);
                    worker.execute(() -> {
                        try {
                            Wallpaper.copyFromFile(this, src);
                            ui.post(() -> {
                                prefs.setWallMode(Wallpaper.FILE);
                                applyWallpaper();
                                toast("Обои установлены");
                            });
                        } catch (Exception e) {
                            final String m = e.getMessage();
                            ui.post(() -> toast("Не удалось: " + m));
                        }
                    });
                })
                .show();
    }

    // ------------------------------------------------------------ заставка

    private View buildSaver() {
        saverView = new FrameLayout(this);
        saverView.setBackgroundColor(0xFF000000);
        saverImage = new ImageView(this);
        saverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        saverImage.setAlpha(0.35f);
        saverImage.setVisibility(View.GONE);
        saverView.addView(saverImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        saverView.setVisibility(View.GONE);
        saverView.setFocusable(false);

        saverContent = new LinearLayout(this);
        saverContent.setOrientation(LinearLayout.VERTICAL);
        saverContent.setPadding(dp(48), dp(32), dp(48), dp(32));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 110);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        saverContent.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        date.setTextColor(0xCCFFFFFF);
        saverContent.addView(date);

        saverWeather = new TextView(this);
        saverWeather.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        saverWeather.setTextColor(0xAAFFFFFF);
        saverWeather.setPadding(0, dp(12), 0, 0);
        saverContent.addView(saverWeather);

        saverView.addView(saverContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return saverView;
    }

    /** black = true — просто чёрный экран (таймер сна без права блокировки). */
    private void showSaver(boolean black) {
        if (saverView == null) return;
        if (folderDialog != null && folderDialog.isShowing()) folderDialog.dismiss();
        saverShowing = true;
        saverContent.setVisibility(black ? View.GONE : View.VISIBLE);
        CharSequence w = weatherView != null && weatherView.getVisibility() == View.VISIBLE
                ? weatherView.getText() : "";
        saverWeather.setText(w);
        saverWeather.setVisibility(w.length() > 0 ? View.VISIBLE : View.GONE);
        // ночью заставка темнее
        saverContent.setAlpha(1f);
        saverView.setAlpha(0f);
        saverView.setVisibility(View.VISIBLE);
        saverView.animate().alpha(1f).setDuration(800).start();
        float dim = NightMode.isNight(prefs.nightMode(), prefs.nightSchedule()) ? 0.45f : 0.8f;
        saverContent.setScaleX(1f);
        for (int i = 0; i < saverContent.getChildCount(); i++) saverContent.getChildAt(i).setAlpha(dim);
        ui.removeCallbacks(saverMove);
        if (!black) ui.postDelayed(saverMove, 1000);
    }

    private void hideSaver() {
        saverShowing = false;
        ui.removeCallbacks(saverMove);
        if (saverView != null) saverView.setVisibility(View.GONE);
        lastInput = System.currentTimeMillis();
    }

    private void chooseSaver() {
        String[] labels = new String[SAVER_OPTIONS.length];
        int current = 0;
        for (int i = 0; i < SAVER_OPTIONS.length; i++) {
            labels[i] = SAVER_OPTIONS[i] == 0 ? "Выключена" : "Через " + SAVER_OPTIONS[i] + " мин простоя";
            if (SAVER_OPTIONS[i] == prefs.saverMinutes()) current = i;
        }
        dialog().setTitle("Заставка")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    prefs.setSaverMinutes(SAVER_OPTIONS[which]);
                    d.dismiss();
                })
                .setNeutralButton("Показать сейчас", (d, w) -> showSaver(false))
                .show();
    }

    // ------------------------------------------------------------ ночной режим

    private void applyNight() {
        boolean night = NightMode.isNight(prefs.nightMode(), prefs.nightSchedule());
        boolean system = NightMode.applySystem(this, night, prefs.nightDim());
        // Если затемнение поверх всех приложений недоступно — затемняем хотя бы главный экран
        if (launcherDim != null) {
            launcherDim.setBackgroundColor(NightMode.color(prefs.nightDim()));
            launcherDim.setVisibility(night && !system ? View.VISIBLE : View.GONE);
        }
    }

    private void showNightSettings() {
        String perm = NightMode.canOverlay(this) ? "есть" : "нет — затемняется только NightTV";
        String[] items = {
                "Режим: " + NightMode.MODES[Math.max(0, Math.min(2, prefs.nightMode()))],
                "Время: " + NightMode.scheduleName(prefs.nightSchedule()),
                "Затемнение: " + prefs.nightDim() + "%",
                "Затемнять все приложения: " + perm,
        };
        dialog().setTitle("🌙 Ночной режим")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        dialog().setTitle("Режим")
                                .setSingleChoiceItems(NightMode.MODES, prefs.nightMode(), (d2, w) -> {
                                    prefs.setNightMode(w);
                                    applyNight();
                                    d2.dismiss();
                                    if (w != 0 && !NightMode.canOverlay(this)) askOverlay();
                                })
                                .show();
                    } else if (which == 1) {
                        String[] names = new String[NightMode.SCHEDULES.length];
                        for (int i = 0; i < names.length; i++) names[i] = NightMode.scheduleName(i);
                        dialog().setTitle("Время ночного режима")
                                .setSingleChoiceItems(names, prefs.nightSchedule(), (d2, w) -> {
                                    prefs.setNightSchedule(w);
                                    applyNight();
                                    d2.dismiss();
                                })
                                .show();
                    } else if (which == 2) {
                        String[] names = new String[NightMode.DIMS.length];
                        int cur = 0;
                        for (int i = 0; i < names.length; i++) {
                            names[i] = NightMode.DIMS[i] + "%";
                            if (NightMode.DIMS[i] == prefs.nightDim()) cur = i;
                        }
                        dialog().setTitle("Сила затемнения")
                                .setSingleChoiceItems(names, cur, (d2, w) -> {
                                    prefs.setNightDim(NightMode.DIMS[w]);
                                    applyNight();
                                    d2.dismiss();
                                })
                                .show();
                    } else {
                        askOverlay();
                    }
                })
                .show();
    }

    private void askOverlay() {
        if (NightMode.canOverlay(this)) {
            toast("Разрешение уже есть — затемняются все приложения");
            return;
        }
        dialog().setTitle("Затемнять все приложения")
                .setMessage("Чтобы ночной режим затемнял фильмы и YouTube, а не только главный экран, "
                        + "разрешите NightTV «показ поверх других приложений».\n\n"
                        + "Если на приставке такого пункта нет, выполните с компьютера:\n"
                        + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow")
                .setPositiveButton("Открыть настройки", (d, w) -> safeStart(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                .setNegativeButton("Позже", null)
                .show();
    }

    // ------------------------------------------------------------ строка состояния

    /** Последний результат проверки интернета (null — ещё не проверяли). */
    private NetCheck.Quick netState;
    private long netCheckedAt;
    private boolean netChecking;
    private final ExecutorService netWorker = Executors.newSingleThreadExecutor();
    private static final long NET_PERIOD_MS = 60 * 1000L;

    private void updateStatus() {
        if (statusView == null) return;
        worker.execute(() -> {
            final String s = NetStatus.read(this);
            ui.post(() -> showStatus(s));
        });
        if (!netChecking && System.currentTimeMillis() - netCheckedAt > NET_PERIOD_MS) {
            checkInternetQuick();
        }
    }

    private String lastLocalStatus = "";

    private void checkInternetQuick() {
        netChecking = true;
        netWorker.execute(() -> {
            final NetCheck.Quick q = NetCheck.quick();
            final Boolean yt = q.online ? NetCheck.youtube() : null;
            final boolean bypass = DpiRescue.installed(this) && DpiRescue.vpnActive(this)
                    && !DpiRescue.selfRouted(this);
            ui.post(() -> {
                ytReachable = yt;
                dpiBypassesUs = bypass;
                if (yt != null && yt) {
                    ytFails = 0;
                    rescueAttempts = 0;
                } else if (yt != null) {
                    ytFails++;
                    if (bypass) {
                        // Проверка из NightTV ничего не говорит о YouTube — ByeByeDPI не трогаем
                        showFilterHint();
                    } else if (ytFails >= 2) {
                        // Две неудачи подряд — тогда спасаем
                        rescueYouTube(false);
                    }
                }
                boolean wasOffline = netState != null && !netState.online;
                netChecking = false;
                netCheckedAt = System.currentTimeMillis();
                netState = q;
                showStatus(lastLocalStatus);
                // Интернет вернулся — сразу обновляем погоду и YouTube
                if (wasOffline && q.online) {
                    updateWeather();
                    loadYouTube(true);
                }
            });
        });
    }

    /** Строка состояния: цветная точка интернета + сеть, IP и память. */
    private void showStatus(String local) {
        lastLocalStatus = local;
        String net;
        int color;
        if (netState == null) {
            net = "Проверка интернета…";
            color = 0xFFB0BEC5;
        } else if (!netState.online) {
            net = "Нет интернета";
            color = 0xFFEF5350;
        } else if (netState.latencyMs > 800) {
            net = "Интернет медленный · " + netState.latencyMs + " мс";
            color = 0xFFFFCA28;
        } else {
            net = "Интернет есть · " + netState.latencyMs + " мс";
            color = 0xFF66BB6A;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append("● ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        sb.append(net);
        if (ytReachable != null) {
            if (!ytReachable && dpiBypassesUs) {
                sb.append("   ·   YouTube ? (NightTV не в списке ByeByeDPI)");
            } else {
                sb.append("   ·   YouTube ").append(ytReachable ? "✓" : "✕");
            }
        }
        if (DpiRescue.installed(this)) {
            sb.append("   ·   DPI ").append(DpiRescue.vpnActive(this) ? "вкл" : "выкл");
        }
        sb.append("   ·   ").append(local);
        statusView.setText(sb);
    }

    /** Подробная проверка: сеть, DNS, интернет, YouTube, скорость. */
    private void runNetworkTest() {
        final android.app.AlertDialog dlg = dialog()
                .setTitle("Проверка интернета")
                .setMessage("Проверяю… это займёт до 15 секунд")
                .setPositiveButton("Закрыть", null)
                .show();
        netWorker.execute(() -> {
            StringBuilder r = new StringBuilder();
            r.append("Сеть: ").append(NetStatus.read(this)).append("\n\n");

            long dns = NetCheck.dns("ya.ru");
            r.append(dns >= 0 ? "✓" : "✕").append(" DNS: ").append(NetCheck.ms(dns)).append('\n');

            long google = NetCheck.ping("https://connectivitycheck.gstatic.com/generate_204", 5000);
            r.append(google >= 0 ? "✓" : "✕").append(" Google: ").append(NetCheck.ms(google)).append('\n');

            long yandex = NetCheck.ping("https://ya.ru/", 5000);
            r.append(yandex >= 0 ? "✓" : "✕").append(" Яндекс: ").append(NetCheck.ms(yandex)).append('\n');

            long yt = NetCheck.ping("https://www.youtube.com/generate_204", 7000);
            if (yt < 0 && NetCheck.youtube()) yt = 0;
            r.append(yt >= 0 ? "✓" : "✕").append(" YouTube: ").append(yt > 0 ? NetCheck.ms(yt) : (yt == 0 ? "доступен" : "нет ответа")).append('\n');
            boolean bypass = DpiRescue.installed(this) && DpiRescue.vpnActive(this) && !DpiRescue.selfRouted(this);
            if (DpiRescue.installed(this)) {
                r.append("ByeByeDPI: ").append(DpiRescue.vpnActive(this) ? "включена" : "выключена");
                if (bypass) r.append(", но NightTV не в её списке приложений");
                r.append('\n');
            }

            long ytImg = NetCheck.ping("https://i.ytimg.com/generate_204", 6000);
            r.append(ytImg >= 0 ? "✓" : "✕").append(" Превью YouTube: ").append(NetCheck.ms(ytImg)).append('\n');

            boolean online = google >= 0 || yandex >= 0;
            if (online) {
                ui.post(() -> dlg.setMessage(r + "\nИзмеряю скорость…"));
                double speed = NetCheck.downloadMbps(6000);
                r.append("\nСкорость загрузки: ").append(NetCheck.mbps(speed)).append('\n');
            }

            r.append('\n');
            if (!online && dns < 0) {
                r.append("Итог: интернета нет. Проверьте Wi‑Fi или кабель и роутер.");
            } else if (!online) {
                r.append("Итог: сеть есть, но сайты не открываются. Перезагрузите роутер.");
            } else if (yt < 0 && bypass) {
                r.append("Итог: интернет работает. YouTube из NightTV не виден, потому что NightTV "
                        + "не входит в «Фильтр приложений» ByeByeDPI — добавьте его туда.");
            } else if (yt < 0) {
                r.append("Итог: интернет работает, YouTube недоступен. Проверьте ByeByeDPI.");
            } else {
                r.append("Итог: всё работает.");
            }

            final NetCheck.Quick q = new NetCheck.Quick();
            q.online = online;
            q.latencyMs = google >= 0 ? google : yandex;
            ui.post(() -> {
                netState = q;
                netCheckedAt = System.currentTimeMillis();
                showStatus(lastLocalStatus);
                if (dlg.isShowing()) dlg.setMessage(r.toString());
            });
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
