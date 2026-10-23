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
        Feed f = new Feed();
        f.next = at(2026, 10, 26, 20, 0, "scheduled");
        check(showCard(f), "a next match alone is enough");
        check(stale(0, 2 * 3_600_000L + 1) && !stale(0, 3_600_000L), "stale after 2 h");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FootballRulesTest: all checks passed");
    }
}
