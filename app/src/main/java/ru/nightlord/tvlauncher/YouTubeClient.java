package ru.nightlord.tvlauncher;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Видео YouTube:
 *  - новое на выбранных каналах — через открытые RSS-ленты (ключ не нужен);
 *  - тренды — через YouTube Data API v3 (нужен ключ).
 * Все методы сетевые — вызывать только из фонового потока.
 */
final class YouTubeClient {
    private static final String FEED = "https://www.youtube.com/feeds/videos.xml?channel_id=";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final Pattern CHANNEL_ID = Pattern.compile("(UC[\\w-]{22})");

    static final class Channel {
        String id;
        String name;
    }

    private static final class Feed {
        String title;
        List<Video> videos = new ArrayList<>();
    }

    private YouTubeClient() {
    }

    // ------------------------------------------------------------ каналы (RSS)

    static List<Video> latestFromChannels(List<Channel> channels, int limit) {
        List<Video> all = new ArrayList<>();
        for (Channel ch : channels) {
            try {
                all.addAll(parseFeed(get(FEED + ch.id)).videos);
            } catch (Exception ignored) {
                // один недоступный канал не должен ломать весь ряд
            }
        }
        // ISO-даты одного формата сортируются как строки
        Collections.sort(all, (a, b) -> String.valueOf(b.published).compareTo(String.valueOf(a.published)));
        Set<String> seen = new HashSet<>();
        List<Video> out = new ArrayList<>();
        for (Video v : all) {
            if (seen.add(v.id)) out.add(v);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Принимает @имя, ссылку на канал или ID (UC…). */
    static Channel resolve(String input, String apiKey) throws Exception {
        String s = input.trim();
        String id = null;

        Matcher m = CHANNEL_ID.matcher(s);
        if (m.find()) {
            id = m.group(1);
        } else {
            String handle;
            Matcher h = Pattern.compile("@([^/?#\\s]+)").matcher(s);
            if (h.find()) {
                handle = h.group(1);
            } else {
                handle = s.replaceAll("\\s+", "");
            }
            if (handle.isEmpty()) throw new Exception("пустое имя канала");

            if (apiKey != null && !apiKey.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(get(API + "channels?part=id&forHandle="
                            + URLEncoder.encode("@" + handle, "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8")));
                    JSONArray items = json.optJSONArray("items");
                    if (items != null && items.length() > 0) id = items.getJSONObject(0).optString("id", null);
                } catch (Exception ignored) {
                }
            }
            if (id == null) {
                String html = get("https://www.youtube.com/@" + URLEncoder.encode(handle, "UTF-8"));
                Matcher c = Pattern.compile("rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[\\w-]{22})")
                        .matcher(html);
                if (c.find()) {
                    id = c.group(1);
                } else {
                    Matcher e = Pattern.compile("\"externalId\":\"(UC[\\w-]{22})\"").matcher(html);
                    if (e.find()) id = e.group(1);
                }
            }
        }
        if (id == null) throw new Exception("канал не найден");

        Feed feed = parseFeed(get(FEED + id)); // заодно проверяем, что лента открывается
        Channel ch = new Channel();
        ch.id = id;
        ch.name = feed.title != null && !feed.title.isEmpty() ? feed.title : id;
        return ch;
    }

    private static Feed parseFeed(String xml) throws Exception {
        Feed feed = new Feed();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        Video cur = null;
        boolean inAuthor = false;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("entry".equals(n)) {
                    cur = new Video();
                } else if ("author".equals(n)) {
                    inAuthor = true;
                } else if ("yt:videoId".equals(n) && cur != null) {
                    cur.id = p.nextText();
                } else if ("title".equals(n)) {
                    String t = p.nextText();
                    if (cur != null) cur.title = t;
                    else if (feed.title == null) feed.title = t;
                } else if ("published".equals(n) && cur != null) {
                    cur.published = p.nextText();
                } else if ("name".equals(n) && inAuthor) {
                    String t = p.nextText();
                    if (cur != null) cur.channel = t;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("entry".equals(n) && cur != null) {
                    if (cur.id != null && !cur.id.isEmpty()) {
                        cur.thumb = Video.thumbFor(cur.id);
                        feed.videos.add(cur);
                    }
                    cur = null;
                } else if ("author".equals(n)) {
                    inAuthor = false;
                }
            }
        }
        return feed;
    }

    // ------------------------------------------------------------ тренды (API)

    static List<Video> trending(String apiKey, String region) throws Exception {
        String json = get(API + "videos?part=snippet&chart=mostPopular&maxResults=20"
                + "&regionCode=" + region + "&hl=ru&key=" + URLEncoder.encode(apiKey, "UTF-8"));
        JSONArray items = new JSONObject(json).optJSONArray("items");
        List<Video> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject sn = it.optJSONObject("snippet");
            Video v = new Video();
            v.id = it.optString("id");
            if (v.id.isEmpty()) continue;
            v.title = sn != null ? sn.optString("title") : "";
            v.channel = sn != null ? sn.optString("channelTitle") : "";
            v.thumb = Video.thumbFor(v.id);
            out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------ сеть

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String reason = readAll(c.getErrorStream());
                try {
                    reason = new JSONObject(reason).getJSONObject("error").getString("message");
                } catch (Exception e) {
                    reason = "код " + code;
                }
                throw new Exception(reason);
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
