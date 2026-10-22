package ru.nightlord.tvlauncher;

/** Видео YouTube для плитки на главном экране. */
final class Video {
    String id;
    String title;
    String channel;
    String thumb;
    String published; // ISO-8601, для сортировки

    static String thumbFor(String id) {
        return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
    }
}
