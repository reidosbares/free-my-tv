package io.freemytv;

import static io.freemytv.GlassMath.*;

/** Plain JVM checks for GlassMath (no Android). Run by tools/test.sh. */
public final class GlassMathTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        // A 60×40 filled rect at (30, 20) in a 120×80 alpha mask.
        int[] rect = new int[120 * 80];
        for (int y = 20; y < 60; y++) for (int x = 30; x < 90; x++) rect[y * 120 + x] = 255;

        Mask m = mask(rect, 120, 80, 1f);
        int c = 40 * 120 + 60;                                  // center of the rect
        check(Math.abs(m.nx[c]) < 0.05f && Math.abs(m.ny[c]) < 0.05f && m.height[c] > 0.95f, "flat, full height inside");
        int left = 40 * 120 + 30;                               // on the left edge
        check(m.nx[left] < -0.3f && Math.abs(m.height[left] - 0.5f) < 0.15f, "left edge points left, half height");
        check(m.shadow[75 * 120 + 60] > m.shadow[15 * 120 + 60], "shadow falls downward");
        Look look = new Look(); look.refraction = 7f;
        int[] px = render(m, rect, vertical(0x808080, 0x808080, 80), 0, 0, look);
        int g = (px[c] >> 8) & 0xFF;
        check(Math.abs(g - (int) (128 * 0.72f + 255 * 0.25f)) <= 6, "body = bg*0.72+0.25 on flat gray");
        check((px[c] >>> 24) == 255 && (px[2 * 120 + 2] >>> 24) == 0, "opaque inside, clear far outside");
        Sampler s = grid(new int[] {0xFF000000, 0xFFFFFFFF}, 2, 1, 200, 100);
        check(((s.rgb(100, 50) >> 8) & 0xFF) > 100 && ((s.rgb(100, 50) >> 8) & 0xFF) < 155, "bilinear midpoint");
        look.darken = 0.5f;
        check(((render(m, rect, vertical(0x808080, 0x808080, 80), 0, 0, look)[c] >> 8) & 0xFF) < g, "darken dims the body");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("GlassMathTest: all checks passed");
    }
}
