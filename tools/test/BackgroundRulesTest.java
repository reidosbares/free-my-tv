package io.freemytv;

import static io.freemytv.BackgroundRules.*;

/** Plain JVM checks for BackgroundRules (no Android). Run by tools/test.sh. */
public final class BackgroundRulesTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        check("nature".equals(theme(null)) && "nature".equals(theme("bogus")) && "surprise".equals(theme("surprise")), "theme default/valid");
        boolean hasSurprise = false, hasTravel = false;
        for (String t : THEMES) { hasSurprise |= t.equals("surprise"); hasTravel |= t.equals("travel"); }
        check(hasSurprise && !hasTravel, "surprise replaces travel");
        check("surprise".equals(theme("travel")), "a stored travel is surprise");
        check(SURPRISE_WORDS.length == 16 && java.util.Arrays.asList(SURPRISE_WORDS).contains("stadium"), "16 surprise words");
        java.util.Random rnd = new java.util.Random(7);
        boolean never = true;
        for (String w : SURPRISE_WORDS) for (int i = 0; i < 200; i++) never &= !w.equals(surpriseWord(w, rnd));
        check(never, "surpriseWord never repeats");
        check(java.util.Arrays.asList(SURPRISE_WORDS).contains(surpriseWord(null, rnd)) && java.util.Arrays.asList(SURPRISE_WORDS).contains(surpriseWord("zzz", rnd)), "surpriseWord from the list");
        check(randomUrl("surprise", "night sky").contains("query=wallpaper%20night%20sky") && !randomUrl("surprise", "night sky").contains("client_id"), "surprise url");
        check(topic("surprise") == null && source("surprise") != null, "surprise is a search, not a topic");
        check(source("plain") == null, "plain has no source");
        check(randomUrl("aerials").contains("query=aerial%20view") || randomUrl("aerials").contains("query=aerial+view"), "aerials is a search");
        check(randomUrl("nature").startsWith("https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&"), "base url");
        check(!randomUrl("nature").contains("client_id"), "key never in the URL");
        check(randomUrl("nature").contains("topics=nature&count=10"), "several photos per request");
        check("nature".equals(topic("nature")) && "architecture-interior".equals(topic("architecture")) && topic("aerials") == null && topic("plain") == null, "topic slug");
        check(sized("https://images.unsplash.com/photo-1?ixid=x", 1920, 1080).endsWith("&w=1920&h=1080&fit=crop&crop=entropy&fm=jpg&q=92"), "sized");
        long h = 3_600_000L, now = 100 * h;
        check(due(0, now, "hour", false), "never shown is due");
        check(!due(now - h / 2, now, "hour", false) && due(now - h, now, "hour", false), "hourly");
        check(!due(now - 23 * h, now, "day", false) && due(now - 24 * h, now, "day", false), "daily");
        check(!due(1, now, "hour", true), "kept is never due");
        check(due(now + h, now, "hour", false), "clock moved back is due");
        check(retryAt(now) == now + 600_000L, "10 min back-off");
        check("day".equals(every("day")) && "hour".equals(every("x")) && "hour".equals(every(null)), "every");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("BackgroundRulesTest: all checks passed");
    }
}
