package io.freemytv;

/** The pure rules of the photo backgrounds: themes, what to ask Unsplash for, when to change. No Android. */
public final class BackgroundRules {
    private BackgroundRules() {}

    public static final String[] THEMES = {"nature", "aerials", "abstract", "architecture", "surprise", "plain"};

    /** Surprise me: each new photo searches "wallpaper" plus one of these, never the same twice in a row. */
    public static final String[] SURPRISE_WORDS = {"mountains", "ocean", "forest", "desert", "night sky", "aurora", "lake",
            "waterfall", "clouds", "snow", "sunset", "minimal", "city lights", "coast", "canyon", "stadium"};

    private static final long HOUR = 3_600_000L;
    private static final long RETRY = 10 * 60_000L;
    private static final int COUNT = 10;

    public static String theme(String stored) {
        if ("travel".equals(stored)) return "surprise";   // Travel was replaced
        for (String t : THEMES) if (t.equals(stored)) return t;
        return "nature";
    }

    /** The request params for a theme (a topic slug or a search), or null for plain. */
    public static String source(String theme) {
        switch (theme(theme)) {
            case "aerials": return "query=aerial view";
            case "abstract": return "query=abstract";
            case "architecture": return "topics=architecture-interior";
            case "surprise": return "query=wallpaper";
            case "plain": return null;
            default: return "topics=nature";
        }
    }

    /** The theme's Unsplash topic slug, or null for a search (or plain). */
    public static String topic(String theme) {
        String src = source(theme);
        return src != null && src.startsWith("topics=") ? src.substring("topics=".length()) : null;
    }

    /**
     * The random-photo request, for several photos in one go (one is picked: see Backgrounds.pick); the key goes in a
     * header, never here.
     */
    public static String randomUrl(String theme) {
        return request(source(theme));
    }

    /** Surprise me's request: the search is "wallpaper" and the word. */
    public static String randomUrl(String theme, String word) {
        return request("query=wallpaper " + word);
    }

    private static String request(String src) {
        String url = "https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&";
        if (src == null) return url;
        int eq = src.indexOf('=');
        return url + src.substring(0, eq + 1) + src.substring(eq + 1).replace(" ", "%20") + "&count=" + COUNT;
    }

    /** A word of SURPRISE_WORDS other than the previous one (null or unknown: any). */
    public static String surpriseWord(String previous, java.util.Random r) {
        String w;
        do w = SURPRISE_WORDS[r.nextInt(SURPRISE_WORDS.length)]; while (w.equals(previous));
        return w;
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
