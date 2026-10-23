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
        check("nature".equals(theme(null)) && "nature".equals(theme("bogus")) && "travel".equals(theme("travel")), "theme default/valid");
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
