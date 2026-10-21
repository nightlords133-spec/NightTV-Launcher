package ru.nightlord.tvlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Погода через бесплатный Open-Meteo (без ключа API). Вызывать НЕ из UI-потока. */
final class WeatherClient {
    private WeatherClient() {
    }

    static String fetch(String city) throws Exception {
        String geoJson = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&format=json&name="
                + URLEncoder.encode(city, "UTF-8"));
        JSONArray results = new JSONObject(geoJson).optJSONArray("results");
        if (results == null || results.length() == 0) {
            return "Город «" + city + "» не найден";
        }
        JSONObject place = results.getJSONObject(0);
        double lat = place.getDouble("latitude");
        double lon = place.getDouble("longitude");
        String name = place.optString("name", city);

        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code&timezone=auto", lat, lon);
        JSONObject current = new JSONObject(get(url)).getJSONObject("current");
        long t = Math.round(current.getDouble("temperature_2m"));
        int code = current.getInt("weather_code");

        String temp = (t > 0 ? "+" : "") + t + "°";
        return emoji(code) + "  " + temp + "  " + describe(code) + " · " + name;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String describe(int code) {
        if (code == 0) return "ясно";
        if (code == 1) return "малооблачно";
        if (code == 2) return "переменная облачность";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 57) return "морось";
        if (code >= 61 && code <= 67) return "дождь";
        if (code >= 71 && code <= 77) return "снег";
        if (code >= 80 && code <= 82) return "ливень";
        if (code == 85 || code == 86) return "снегопад";
        if (code >= 95) return "гроза";
        return "";
    }

    private static String emoji(int code) {
        if (code == 0) return "☀";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 95) return "⛈";
        return "🌧";
    }
}
