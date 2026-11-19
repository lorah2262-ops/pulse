package com.ollee.pulse;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** YouTube search for the watch: suggestions, finding a video, and which YouTube app to open. */
final class YouTube {

    private YouTube() { }

    /** ReVanced first, then the normal YouTube app. */
    static final String[] PACKAGES = {"app.revanced.android.youtube", "com.google.android.youtube"};

    static String installedApp(Context c) {
        for (String p : PACKAGES) {
            try {
                c.getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** YouTube's own autocomplete. No key needed. */
    static List<String> suggest(String q) throws IOException {
        List<String> out = new ArrayList<>();
        String body = get("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&q="
                + URLEncoder.encode(q, "UTF-8"));
        try {
            JSONArray list = new JSONArray(body).getJSONArray(1);
            for (int i = 0; i < list.length(); i++) out.add(list.getString(i));
        } catch (Exception e) {
            throw new IOException("bad suggestions");
        }
        return out;
    }

    /** The top video for a search: the API with a key, else YouTube's search page. Null if none. */
    static String findVideo(String q, String key) throws IOException {
        String enc = URLEncoder.encode(q, "UTF-8");
        if (key != null && !key.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(get("https://www.googleapis.com/youtube/v3/search?part=snippet"
                        + "&type=video&maxResults=1&q=" + enc + "&key=" + URLEncoder.encode(key.trim(), "UTF-8")));
                JSONArray items = o.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    String id = items.getJSONObject(0).getJSONObject("id").optString("videoId", "");
                    if (!id.isEmpty()) return id;
                }
            } catch (Exception ignored) {
                // daily limit used up, bad key, ...: fall through to the search page
            }
        }
        String html = get("https://www.youtube.com/results?search_query=" + enc);
        Matcher m = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "en");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            String charset = "UTF-8";
            String type = c.getContentType();
            if (type != null && type.toLowerCase().contains("charset=")) {
                charset = type.substring(type.toLowerCase().indexOf("charset=") + 8).split(";")[0].trim();
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString(charset);
        } finally {
            c.disconnect();
        }
    }
}
