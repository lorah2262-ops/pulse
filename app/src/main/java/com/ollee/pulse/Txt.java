package com.ollee.pulse;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Turns phone text into plain ASCII the watch's segment display can show. */
final class Txt {

    private Txt() { }

    static String clean(CharSequence in, int max) {
        if (in == null) return "";
        String t = Normalizer.normalize(in.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case '‘': case '’': c = '\''; break;
                case '“': case '”': c = '"'; break;
                case '–': case '—': c = '-'; break;
                case '₱': b.append('P'); continue;          // peso sign
                case '…': b.append("..."); continue;
                case '|': c = '/'; break;                         // | separates fields
                case '\n': case '\r': case '\t': case ':': case ';': c = ' '; break;   // : and ; don't show
                default: break;
            }
            if (c >= 32 && c < 127) b.append(c);
        }
        String r = b.toString().replaceAll(" +", " ").trim();
        if (r.length() > max) r = r.substring(0, Math.max(1, max - 2)).trim() + "..";
        return r;
    }

    private static final Map<String, String> TAGS = new HashMap<>();

    static {
        TAGS.put("com.facebook.orca", "MSGR");
        TAGS.put("com.facebook.mlite", "MSGR");
        TAGS.put("com.facebook.katana", "Fb");
        TAGS.put("com.facebook.lite", "Fb");
        TAGS.put("com.whatsapp", "WA");
        TAGS.put("org.telegram.messenger", "TG");
        TAGS.put("com.viber.voip", "VIbEr");
        TAGS.put("com.instagram.android", "IG");
        TAGS.put("com.discord", "dISCrd");
        TAGS.put("com.google.android.gm", "GMAIL");
        TAGS.put("com.microsoft.office.outlook", "MAIL");
        TAGS.put("com.samsung.android.messaging", "SMS");
        TAGS.put("com.google.android.apps.messaging", "SMS");
        TAGS.put("com.android.mms", "SMS");
        TAGS.put("com.globe.gcash.android", "GCASH");
        TAGS.put("com.paymaya", "MAYA");
        TAGS.put("com.shopee.ph", "SHOPEE");
        TAGS.put("com.lazada.android", "LAZAdA");
        TAGS.put("com.grabtaxi.passenger", "GrAb");
        TAGS.put("com.google.android.youtube", "YT");
        TAGS.put("com.twitter.android", "X");
        TAGS.put("com.zhiliaoapp.musically", "TIKTOK");
        TAGS.put("com.ss.android.ugc.trill", "TIKTOK");
        TAGS.put("com.google.android.apps.classroom", "CLASS");
        TAGS.put("com.google.android.calendar", "CAL");
        TAGS.put("com.samsung.android.calendar", "CAL");
    }

    /** Short app name for the watch, or null to use the app's own name. */
    static String tag(String pkg) {
        return TAGS.get(pkg);
    }

    /**
     * Picks, letter by letter, the case that reads best on 7-segment digits:
     * b d n o q r t look clearer small, the rest capital.
     */
    static String seg(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lo = Character.toLowerCase(c);
            b.append("bdnoqrt".indexOf(lo) >= 0 ? lo : Character.toUpperCase(c));
        }
        return b.toString();
    }

    /** Up to 6 letters and digits of a name, upper case. */
    static String shortName(CharSequence label) {
        String s = clean(label, 40).toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (s.isEmpty()) return "APP";
        return s.length() > 6 ? s.substring(0, 6) : s;
    }
}
