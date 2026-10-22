package ru.nightlord.tvlauncher;

import android.content.pm.ApplicationInfo;

import java.util.Locale;

/** Автоматическое определение категории приложения. */
final class Categories {
    static final String VIDEO = "Кино и ТВ";
    static final String MUSIC = "Музыка";
    static final String GAMES = "Игры";
    static final String TOOLS = "Инструменты";
    static final String OTHER = "Другое";

    static final String[] DEFAULTS = {VIDEO, MUSIC, GAMES, TOOLS, OTHER};

    private static final String[] VIDEO_WORDS = {
            "youtube", "smarttube", "ivi", "kinopoisk", "кинопоиск", "okko", "wink", "moretv", "more.tv",
            "premier", "kion", "rutube", "netflix", "iptv", "tivimate", "ottplayer", "ott", "kodi",
            "vlc", "mxtech", "mx player", "mediaplayer", "media player", "медиапроигр", "плеер", "player",
            "видео", "video", "кино", "movie", "cinema", "megogo", "start.", "amediateka", "peers.tv",
            "limehd", "lime hd", "hdrezka", "rezka", "twitch", "vk video", "vkvideo", "смотрешка",
            "smotreshka", "24h.tv", "ntv", "1tv", "первый", "tv.", ".tv", "телевид",
    };
    private static final String[] MUSIC_WORDS = {
            "music", "музык", "spotify", "zvuk", "soundcloud", "deezer", "radio", "радио",
            "podcast", "подкаст", "audio", "аудио", "boom",
    };
    private static final String[] GAME_WORDS = {"game", "игр", "games"};
    private static final String[] TOOL_WORDS = {
            "settings", "настройк", "file", "файл", "manager", "менеджер", "browser", "браузер",
            "chrome", "firefox", "dpi", "vpn", "install", "установ", "cleaner", "очист", "speedtest",
            "vending", "market", "store", "магазин", "remote", "пульт", "keyboard", "клавиат",
            "clock", "часы", "launcher", "лаунчер", "update", "обновл", "torrent", "download", "загруз",
            "x-plore", "explorer", "проводник", "adb", "terminal",
    };

    private Categories() {
    }

    @SuppressWarnings("deprecation")
    static String auto(AppInfo a) {
        String text = (a.pkg + " " + a.label).toLowerCase(Locale.ROOT);

        if (a.game || a.sysCategory == ApplicationInfo.CATEGORY_GAME || has(text, GAME_WORDS)) return GAMES;
        if (has(text, MUSIC_WORDS) || a.sysCategory == ApplicationInfo.CATEGORY_AUDIO) return MUSIC;
        if (has(text, VIDEO_WORDS) || a.sysCategory == ApplicationInfo.CATEGORY_VIDEO) return VIDEO;
        if (has(text, TOOL_WORDS)
                || a.sysCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY
                || a.sysCategory == ApplicationInfo.CATEGORY_ACCESSIBILITY
                || a.system) return TOOLS;
        return OTHER;
    }

    /** Значок папки по названию категории. */
    static String icon(String category) {
        switch (category) {
            case VIDEO: return "🎬";
            case MUSIC: return "🎵";
            case GAMES: return "🎮";
            case TOOLS: return "🛠";
            case OTHER: return "📦";
            default: return "📁";
        }
    }

    private static boolean has(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }
}
