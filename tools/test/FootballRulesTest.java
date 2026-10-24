package io.freemytv;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import static io.freemytv.FootballRules.*;

/** Plain JVM checks for FootballRules (no Android). Run by tools/test.sh. */
public final class FootballRulesTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    private static Channel[] ch(String... names) {
        Channel[] out = new Channel[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = new Channel();
            out[i].name = names[i];
            out[i].logo = "";
        }
        return out;
    }

    /** Milliseconds for a time in Brasília (UTC−3). */
    private static long brt(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:00"));
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    private static Game at(int y, int mo, int d, int h, int mi, String status) {
        Game g = new Game();
        g.kickoffMs = brt(y, mo, d, h, mi);
        g.status = status;
        g.channels = new Channel[0];
        return g;
    }

    private static Game[] games(Game... g) { return g; }

    public static void main(String[] args) {
        Set<String> tv = set("com.globo.globotv", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
                "com.wbd.stream", "com.google.android.youtube.tv");
        check("com.globo.globotv".equals(appFor(ch("sportv", "Premiere", "globoplay"), tv)), "Globo channels open Globoplay");
        check("com.disney.disneyplus".equals(appFor(ch("SportyNet", "Disney+"), tv)), "unknown channel skipped");
        check(appFor(ch("Paramount+"), tv) == null, "not installed gives null");
        check("Paramount+".equals(firstKnownApp(ch("SportyNet", "Paramount+"))), "toast names Paramount+");
        check(appFor(ch("PRIME VÍDEO"), tv) != null, "names match case-insensitively");
        check(appFor(null, tv) == null && firstKnownApp(new Channel[0]) == null, "no channels, no app");

        check("drawer".equals(effectivePlacement("home", true)), "TMDB key moves A to the drawer");
        check("home".equals(effectivePlacement("home", false)), "A stays without a key");
        check("off".equals(effectivePlacement("off", true)), "off stays off");
        check("home".equals(effectivePlacement("bogus", false)), "unknown value falls back to home");

        check("drawer".equals(nextPlacement("home", 1, false)) && "off".equals(nextPlacement("drawer", 1, false))
                && "home".equals(nextPlacement("off", 1, false)), "right cycles home → drawer → off → home");
        check("off".equals(nextPlacement("home", -1, false)), "left goes back");
        check("off".equals(nextPlacement("home", 1, true)) && "drawer".equals(nextPlacement("off", 1, true))
                && "off".equals(nextPlacement("drawer", -1, true)), "with picks: only drawer and off");

        long sat1700 = brt(2026, 10, 24, 17, 0);
        Game[] t = todayOnly(games(at(2026, 10, 23, 20, 0, "finished"), at(2026, 10, 24, 16, 30, "live"),
                at(2026, 10, 24, 21, 0, "scheduled"), at(2026, 10, 25, 11, 0, "scheduled")), sat1700);
        check(t.length == 2, "yesterday and tomorrow dropped");
        check(todayOnly(games(at(2026, 10, 24, 21, 30, "live")), brt(2026, 10, 25, 0, 10)).length == 1, "live past midnight stays");
        check(todayOnly(null, sat1700).length == 0, "no games, empty");

        check(rowsShown(7, 3) == 2 && moreCount(7, 3) == 5, "7 games in 3 rows: 2 rows + '+5'");
        check(rowsShown(3, 3) == 3 && moreCount(3, 3) == 0, "3 games fit in 3 rows");
        check(rowsShown(2, 0) == 0 && moreCount(2, 0) == 2, "no room: nothing drawn, all counted");
        check(!showCard(null) && !showCard(new Feed()), "no data, no card");
        Game done1 = at(2026, 10, 24, 11, 0, "finished"), done2 = at(2026, 10, 24, 16, 0, "finished");
        Game live = at(2026, 10, 24, 17, 0, "live"), stale = at(2026, 10, 24, 9, 0, "live");
        Game later = at(2026, 10, 24, 21, 0, "scheduled"), soon = at(2026, 10, 24, 19, 0, "scheduled");
        long fivePm = brt(2026, 10, 24, 17, 30);
        check(Arrays.equals(ordered(games(done2, later, done1, live, stale, soon), fivePm),
                games(live, soon, later, stale, done1, done2)), "live and upcoming by kickoff, then finished by kickoff");
        Feed f = new Feed();
        f.next = at(2026, 10, 26, 20, 0, "scheduled");
        check(showCard(f), "a next match alone is enough");
        check(stale(0, 2 * 3_600_000L + 1) && !stale(0, 3_600_000L), "stale after 2 h");

        Game live1630 = at(2026, 10, 24, 16, 30, "live"), next1830 = at(2026, 10, 24, 18, 30, "scheduled");
        Game done1630 = at(2026, 10, 24, 16, 30, "finished");
        check(nextToday(games(next1830, live1630), brt(2026, 10, 24, 17, 0)) == live1630, "a live game comes first");
        check(nextToday(games(done1630, next1830), brt(2026, 10, 24, 17, 0)) == next1830, "then the next to kick off");
        check(nextToday(games(done1630), brt(2026, 10, 24, 19, 0)) == null, "none left today");
        check(nextToday(new Game[0], brt(2026, 10, 24, 19, 0)) == null && nextToday(null, 0) == null, "no games");
        Game late = at(2026, 10, 24, 18, 30, "scheduled");
        check(nextToday(games(late), brt(2026, 10, 24, 19, 0)) == late, "kicked off but not yet live in the feed: still shown");
        check(nextToday(games(late), brt(2026, 10, 24, 21, 0)) == null, "more than 2 h past kickoff: gone");

        // Stale data: a game "live" long after kickoff isn't live any more (the service died during it).
        Game stuck = at(2026, 10, 24, 21, 30, "live");
        check(todayOnly(games(stuck), brt(2026, 10, 25, 18, 0)).length == 0, "stale live game from yesterday dropped");
        check(todayOnly(games(at(2026, 10, 24, 12, 0, "live")), brt(2026, 10, 24, 17, 0)).length == 1, "same day stays listed");
        check(nextToday(games(at(2026, 10, 24, 12, 0, "live")), brt(2026, 10, 24, 17, 0)) == null, "stale live isn't next");

        // The screensaver follows Home Settings' Football row.
        Feed sf = new Feed();
        sf.today = games(next1830);
        check(screensaverGame(sf, "off", brt(2026, 10, 24, 17, 0)) == null, "football off: no screensaver line");
        check(screensaverGame(sf, "drawer", brt(2026, 10, 24, 17, 0)) == next1830, "drawer: line shown");
        check(screensaverGame(null, "home", 0) == null, "no feed: no line");

        // "No games today" isn't true when the team itself plays today.
        Feed tf = new Feed();
        tf.next = at(2026, 10, 24, 20, 0, "scheduled");
        check(teamPlaysToday(tf, brt(2026, 10, 24, 9, 0)), "team plays today");
        check(!teamPlaysToday(tf, brt(2026, 10, 23, 9, 0)) && !teamPlaysToday(new Feed(), 0), "not today, or no next");

        // The team's crest goes in front: it's the away side at Flamengo, the home side otherwise.
        Feed cf = new Feed();
        cf.teamName = "Atlético-MG";
        cf.next = at(2026, 10, 26, 20, 0, "scheduled");
        cf.next.home.name = "Flamengo";
        cf.next.away.name = "Atlético-MG";
        check(teamIsAway(cf), "away at Flamengo");
        cf.next.home.name = "Atlético-MG";
        cf.next.away.name = "Cruzeiro";
        check(!teamIsAway(cf) && !teamIsAway(new Feed()), "home, or no next match");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FootballRulesTest: all checks passed");
    }
}
