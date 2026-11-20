package com.ollee.pulse;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Leave-by: how long it takes to get to an event's place, so the day pack can say when to go.
 * Free services, no key: Android's own geocoder (OpenStreetMap's Nominatim if it has none) finds
 * the place, and the OSRM route server gives the driving time. Walking is worked out from the
 * distance. Commute is the driving time stretched for jeeps, buses and waiting.
 * Everything here blocks: call it off the main thread.
 */
final class LeaveBy {

    private LeaveBy() { }

    static final String[] MODES = {"Car", "Commute", "Walk"};

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("pulse", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return p(c).getBoolean("lb_on", false); }
    static int mode(Context c) { return p(c).getInt("lb_mode", 0); }
    static int buffer(Context c) { return p(c).getInt("lb_buffer", 10); }      // minutes to spare
    static String from(Context c) { return p(c).getString("lb_from", ""); }     // empty: where the phone is

    static boolean canLocate(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** {lat, lon} of a place name, or null. */
    static double[] find(Context c, String place) {
        if (place == null || place.trim().isEmpty()) return null;
        try {
            if (Geocoder.isPresent()) {
                List<Address> a = new Geocoder(c, Locale.getDefault()).getFromLocationName(place, 1);
                if (a != null && !a.isEmpty()) return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
            }
        } catch (Exception ignored) { }
        try {
            JSONArray r = new JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                    + Uri.encode(place)));
            if (r.length() > 0) return new double[]{r.getJSONObject(0).getDouble("lat"), r.getJSONObject(0).getDouble("lon")};
        } catch (Exception ignored) { }
        return null;
    }

    /** Where the trip starts: the address in settings, else the phone's last known spot. */
    static double[] start(Context c) {
        String f = from(c);
        if (!f.isEmpty()) return find(c, f);
        if (!canLocate(c)) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String pr : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (SecurityException ignored) { }
        return null;
    }

    /** Minutes to get from a to b by the chosen mode (without the spare minutes), or -1. */
    static int minutes(Context c, double[] a, double[] b) {
        double km = km(a, b);
        if (km < 0.15) return 0;                                       // already there
        int m = mode(c);
        if (m == 2) return (int) Math.ceil(km * 1.3 / 4.8 * 60);       // walking, streets aren't straight
        double drive = -1;
        try {
            JSONObject o = new JSONObject(get(String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false",
                    a[1], a[0], b[1], b[0])));
            JSONArray rs = o.optJSONArray("routes");
            if (rs != null && rs.length() > 0) drive = rs.getJSONObject(0).getDouble("duration") / 60.0;
        } catch (Exception ignored) { }
        if (drive < 0) drive = km * 1.4 / 25 * 60;                     // no route answer: a city average
        return (int) Math.ceil(m == 1 ? drive * 1.7 + 5 : drive * 1.15);   // commute: rides + waiting; car: traffic
    }

    /** When to leave for an event at place starting at begin; 0 if it can't be worked out. */
    static long leaveAt(Context c, String place, long begin) {
        if (!on(c) || place == null || place.trim().isEmpty()) return 0;
        String key = "lb:" + mode(c) + ":" + from(c) + ":" + place.trim().toLowerCase(Locale.US);
        int mins = p(c).getInt(key, -2);
        long cachedAt = p(c).getLong(key + ":t", 0);
        boolean fresh = System.currentTimeMillis() - cachedAt < (from(c).isEmpty() ? 2 : 24) * 3_600_000L;
        if (mins == -2 || !fresh) {
            double[] a = start(c), b = find(c, place);
            mins = a == null || b == null ? -1 : minutes(c, a, b);
            p(c).edit().putInt(key, mins).putLong(key + ":t", System.currentTimeMillis()).apply();
        }
        if (mins < 0) return 0;
        long t = begin - (mins + buffer(c)) * 60_000L;
        return t - t % 60_000L;
    }

    /** The cached travel time if there is one, without going online (for planning); -1 if none. */
    static int cached(Context c, String place) {
        if (!on(c) || place == null || place.trim().isEmpty()) return -1;
        String key = "lb:" + mode(c) + ":" + from(c) + ":" + place.trim().toLowerCase(Locale.US);
        return p(c).getInt(key, -1);
    }

    private static double km(double[] a, double[] b) {
        double r = 6371, dLat = Math.toRadians(b[0] - a[0]), dLon = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0])) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    private static String get(String url) throws Exception {
        HttpURLConnection k = (HttpURLConnection) new URL(url).openConnection();
        k.setConnectTimeout(10000);
        k.setReadTimeout(15000);
        k.setRequestProperty("User-Agent", "Pulse-watch-app/2 (personal use)");
        try (InputStream in = k.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }
}
