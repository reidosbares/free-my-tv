package io.freemytv;

/** The pure rules of the photo backgrounds: themes, what to ask Unsplash for, when to change. No Android. */
public final class BackgroundRules {
    private BackgroundRules() {}

    public static final String[] THEMES = {"nature", "aerials", "abstract", "architecture", "travel", "plain"};

    private static final long HOUR = 3_600_000L;
    private static final long RETRY = 10 * 60_000L;

    public static String theme(String stored) {
        for (String t : THEMES) if (t.equals(stored)) return t;
        return "nature";
    }

    /** The request params for a theme (a topic slug or a search), or null for plain. */
    public static String source(String theme) {
        switch (theme(theme)) {
            case "aerials": return "query=aerial view";
            case "abstract": return "query=abstract";
            case "architecture": return "topics=architecture-interior";
            case "travel": return "topics=travel";
            case "plain": return null;
            default: return "topics=nature";
        }
    }

    /** The random-photo request; the key goes in a header, never here. */
    public static String randomUrl(String theme) {
        String src = source(theme);
        String url = "https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&";
        if (src == null) return url;
        int eq = src.indexOf('=');
        return url + src.substring(0, eq + 1) + src.substring(eq + 1).replace(" ", "%20");
    }

    public static String sized(String raw, int w, int h) {
        return raw + (raw.contains("?") ? "&" : "?") + "w=" + w + "&h=" + h + "&fit=crop&crop=entropy&fm=jpg&q=92";
    }

    public static long interval(String every) {
        return "day".equals(every) ? 24 * HOUR : HOUR;
    }

    public static String every(String stored) {
        return "day".equals(stored) ? "day" : "hour";
    }

    public static boolean due(long shownAtMs, long nowMs, String every, boolean keep) {
        if (keep) return false;
        if (shownAtMs <= 0 || nowMs < shownAtMs) return true;
        return nowMs - shownAtMs >= interval(every);
    }

    public static long retryAt(long failedAtMs) {
        return failedAtMs + RETRY;
    }
}
