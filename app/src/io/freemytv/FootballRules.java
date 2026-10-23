package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The football card's rules, kept free of Android so tools/test.sh can check them on the JVM: the feed's shape, which
 * app opens a game, where the card goes, and which of the cached games still count as today's.
 */
final class FootballRules {
    private FootballRules() {}

    static final class Side { String name = "", crest = ""; }

    static final class Channel { String name = "", logo = ""; }

    static final class Game {
        long id;
        long kickoffMs;
        String competition = "";
        Side home = new Side(), away = new Side();
        /** "scheduled", "live" or "finished". */
        String status = "scheduled";
        int homeScore = -1, awayScore = -1;
        Channel[] channels = new Channel[0];
        /** False while ge has the date but not the time (kickoffMs is then midnight). */
        boolean timeKnown = true;

        boolean live() { return "live".equals(status); }

        /** Live by the feed, and not so long after kickoff that the feed is just stale (the service died during it). */
        boolean liveAt(long nowMs) { return live() && nowMs - kickoffMs < LIVE_AT_MOST; }
        boolean hasScore() { return homeScore >= 0 && awayScore >= 0; }
    }

    static final class Feed {
        long fetchedAtMs;
        String teamName = "";
        int teamColor = 0xFF1C1C1E, teamTextColor = 0xFFFFFFFF;
        Game next;
        Game[] today = new Game[0];
    }

    /** ge's channel names and the TV app that shows each (in order of preference when a game has several). */
    static final String[][] CHANNEL_APPS = {
            {"sportv", "com.globo.globotv"},
            {"Premiere", "com.globo.globotv"},
            {"Globo", "com.globo.globotv"},
            {"globoplay", "com.globo.globotv"},
            {"Ge TV", "com.globo.globotv"},
            {"Disney+", "com.disney.disneyplus"},
            {"ESPN", "com.disney.disneyplus"},
            {"Prime Vídeo", "com.amazon.amazonvideo.livingroom"},
            {"HBO Max", "com.wbd.stream"},
            {"CazéTV", "com.google.android.youtube.tv"},
            {"YouTube", "com.google.android.youtube.tv"},
            {"Paramount+", "com.cbs.ott"},
    };

    private static String packageOf(String channel) {
        if (channel == null) return null;
        String c = channel.trim().toLowerCase(Locale.ROOT);
        for (String[] row : CHANNEL_APPS) if (row[0].toLowerCase(Locale.ROOT).equals(c)) return row[1];
        return null;
    }

    /** The app of the first channel, in ge's order, that is installed on this TV; null if none is. */
    static String appFor(Channel[] channels, Set<String> installed) {
        if (channels == null) return null;
        for (Channel ch : channels) {
            String pkg = packageOf(ch.name);
            if (pkg != null && installed.contains(pkg)) return pkg;
        }
        return null;
    }

    /** The first channel this table knows, to name in "isn't installed"; null if none is known. */
    static String firstKnownApp(Channel[] channels) {
        if (channels == null) return null;
        for (Channel ch : channels) if (packageOf(ch.name) != null) return ch.name;
        return null;
    }

    /** Where the card goes: beside the clock only while the picks (a TMDB key) don't need that space. */
    static String effectivePlacement(String stored, boolean picksOn) {
        if ("off".equals(stored)) return "off";
        if ("drawer".equals(stored)) return "drawer";
        return picksOn ? "drawer" : "home";
    }

    /** Home Settings' ← →: the next placement, among those that can be shown (no "home" while the picks need it). */
    static String nextPlacement(String stored, int dir, boolean picksOn) {
        String[] options = picksOn ? new String[] {"drawer", "off"} : new String[] {"home", "drawer", "off"};
        String now = effectivePlacement(stored, picksOn);
        int at = 0;
        for (int i = 0; i < options.length; i++) if (options[i].equals(now)) at = i;
        return options[((at + (dir < 0 ? -1 : 1)) % options.length + options.length) % options.length];
    }

    private static final TimeZone BRT = TimeZone.getTimeZone("GMT-03:00");
    /** The same limit as the service's: past it, a "live" game is old data, not a game in progress. */
    static final long LIVE_AT_MOST = 4 * 3_600_000L;

    private static int dayOf(long ms) {
        Calendar c = Calendar.getInstance(BRT);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    /** The cached games that are still today's in Brasília (a cache from yesterday must not show as today), plus live ones. */
    static Game[] todayOnly(Game[] games, long nowMs) {
        if (games == null) return new Game[0];
        int today = dayOf(nowMs);
        List<Game> out = new ArrayList<>();
        for (Game g : games) if (g.liveAt(nowMs) || dayOf(g.kickoffMs) == today) out.add(g);
        return out.toArray(new Game[0]);
    }

    /** How many game rows to draw: when they don't all fit, the last row becomes "+N more". */
    static int rowsShown(int games, int rowsThatFit) {
        if (rowsThatFit <= 0) return 0;
        return games > rowsThatFit ? rowsThatFit - 1 : games;
    }

    static int moreCount(int games, int rowsThatFit) {
        return games - rowsShown(games, rowsThatFit);
    }

    /**
     * The screensaver's one game: a live one first, else the next to kick off (one that kicked off less than 2 h ago
     * still counts, until the feed says it's live or over); null when none is left today.
     */
    static Game nextToday(Game[] today, long nowMs) {
        if (today == null) return null;
        for (Game g : today) if (g.liveAt(nowMs)) return g;
        Game best = null;
        for (Game g : today) {
            if (!"scheduled".equals(g.status) || g.kickoffMs < nowMs - 2 * 3_600_000L) continue;
            if (best == null || g.kickoffMs < best.kickoffMs) best = g;
        }
        return best;
    }

    /** The screensaver's game, following Home Settings' Football row ("off" hides it there too). */
    static Game screensaverGame(Feed feed, String stored, long nowMs) {
        if (feed == null || "off".equals(stored)) return null;
        return nextToday(feed.today, nowMs);
    }

    /** The team's next match is today (then "No games today" would contradict the team card above it). */
    static boolean teamPlaysToday(Feed feed, long nowMs) {
        return feed != null && feed.next != null && dayOf(feed.next.kickoffMs) == dayOf(nowMs);
    }

    static boolean showCard(Feed feed) {
        return feed != null && (feed.next != null || (feed.today != null && feed.today.length > 0));
    }

    static boolean stale(long fetchedAtMs, long nowMs) {
        return nowMs - fetchedAtMs > 2 * 3_600_000L;
    }
}
