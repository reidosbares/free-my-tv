package io.freemytv;

/**
 * The glass text's math without Android: the mask (edge normals, surface height, drop shadow) built from the sharp
 * rendering, and a CPU port of `Shaders.GLASS_TEXT` for TVs without AGSL. Runs on a worker thread.
 */
final class GlassMath {
    private GlassMath() {}

    /** Per pixel: outward edge normal (-1..1), height (0.5 at the edge, 1 deep inside, 0 outside), shadow alpha (0..1). */
    static final class Mask {
        int w, h;
        float[] nx, ny, height, shadow;
    }

    /** The background behind the glass: screen px to 0xRRGGBB. */
    interface Sampler {
        int rgb(float x, float y);
    }

    static final class Look {
        float refraction, body = 0.72f, lift = 0.25f, specular = 0.7f, shadow = 0.28f, darken;
    }

    static Mask mask(int[] alpha255, int w, int h, float k) {
        // 16 times the alpha, so the blurs keep their precision in ints.
        int[] alpha = new int[w * h];
        for (int i = 0; i < alpha.length; i++) alpha[i] = Math.max(0, Math.min(255, alpha255[i])) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(14 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        Mask m = new Mask();
        m.w = w;
        m.h = h;
        m.nx = new float[w * h];
        m.ny = new float[w * h];
        m.height = new float[w * h];
        m.shadow = new float[w * h];
        int dy = Math.round(6 * k);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                // The gradient points into the stroke; the normal points out.
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                m.nx[i] = -gx / max;
                m.ny[i] = -gy / max;
                m.height[i] = Math.min(1f, height[i] / 4080f);
                m.shadow[i] = y >= dy ? Math.min(1f, soft[i - dy * w] / 4080f) : 0;
            }
        }
        return m;
    }

    /** Bilinear over a gw×gh ARGB grid stretched over the screen, clamped at the edges. */
    static Sampler grid(final int[] argb, final int gw, final int gh, float screenW, float screenH) {
        final float sx = gw / screenW, sy = gh / screenH;
        return (x, y) -> {
            float gx = Math.max(0, Math.min(gw - 1, x * sx - 0.5f)), gy = Math.max(0, Math.min(gh - 1, y * sy - 0.5f));
            int x0 = (int) gx, y0 = (int) gy, x1 = Math.min(gw - 1, x0 + 1), y1 = Math.min(gh - 1, y0 + 1);
            float fx = gx - x0, fy = gy - y0;
            int a = argb[y0 * gw + x0], b = argb[y0 * gw + x1], c = argb[y1 * gw + x0], d = argb[y1 * gw + x1];
            int out = 0;
            for (int s = 0; s < 24; s += 8) {
                float top = ((a >> s) & 255) + (((b >> s) & 255) - ((a >> s) & 255)) * fx;
                float bottom = ((c >> s) & 255) + (((d >> s) & 255) - ((c >> s) & 255)) * fx;
                out |= Math.min(255, Math.round(top + (bottom - top) * fy)) << s;
            }
            return out;
        };
    }

    /** The colors of `Ui.gradient`: top to bottom over the screen's height. */
    static Sampler vertical(final int top, final int bottom, final float screenH) {
        return (x, y) -> {
            float t = Math.max(0, Math.min(1, y / Math.max(1, screenH - 1)));
            int out = 0;
            for (int s = 0; s < 24; s += 8) {
                float a = (top >> s) & 255, b = (bottom >> s) & 255;
                out |= Math.min(255, Math.round(a + (b - a) * t)) << s;
            }
            return out;
        };
    }

    /** Non-premultiplied ARGB, m.w × m.h: the glass where `cover` > 0, over its shadow. (originX, originY) = top left on screen. */
    static int[] render(Mask m, int[] cover255, Sampler bg, float originX, float originY, Look look) {
        int w = m.w, h = m.h;
        int[] out = new int[w * h];
        float keep = 1 - Math.max(0, Math.min(1, look.darken));
        final float lx = -0.447f, ly = -0.894f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float a = Math.max(0, Math.min(255, cover255[i])) / 255f;
                float sa = look.shadow * m.shadow[i];
                if (a <= 0 && sa <= 0) continue;
                float alpha = Math.max(a, sa);
                if (a <= 0) {
                    out[i] = Math.round(alpha * 255) << 24;
                    continue;
                }
                // The shader's n points into the stroke.
                float nx = -m.nx[i], ny = -m.ny[i];
                float bend = (float) Math.sqrt(nx * nx + ny * ny);
                float px = originX + x + 0.5f + nx * look.refraction, py = originY + y + 0.5f + ny * look.refraction;
                float r, g, b;
                if (bend > 0.02f) {
                    float cx = nx * look.refraction * 0.2f, cy = ny * look.refraction * 0.2f;
                    r = ((bg.rgb(px + cx, py + cy) >> 16) & 255) / 255f;
                    g = ((bg.rgb(px, py) >> 8) & 255) / 255f;
                    b = (bg.rgb(px - cx, py - cy) & 255) / 255f;
                } else {
                    int c = bg.rgb(px, py);
                    r = ((c >> 16) & 255) / 255f;
                    g = ((c >> 8) & 255) / 255f;
                    b = (c & 255) / 255f;
                }
                r *= keep;
                g *= keep;
                b *= keep;
                float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = l + (r - l) * 1.15f;
                g = l + (g - l) * 1.15f;
                b = l + (b - l) * 1.15f;
                r = r * look.body + look.lift;
                g = g * look.body + look.lift;
                b = b * look.body + look.lift;
                float inv = 1 / Math.max(bend, 0.001f);
                float dot = (nx * lx + ny * ly) * inv;
                float facing = Math.max(0, Math.min(1, -dot)), back = Math.max(0, Math.min(1, dot));
                float edge = smoothstep(0.05f, 0.35f, bend);
                float height = m.height[i];
                float rim = (1 - smoothstep(0.5f, 0.6f, height)) * edge;
                float glow = (1 - smoothstep(0.5f, 0.86f, height)) * edge;
                float add = (rim * (0.28f + 0.9f * facing + 0.45f * back) + glow * (0.05f + 0.1f * facing)) * look.specular;
                float mul = 1 - 0.1f * back * bend * (1 - rim);
                // Over the black shadow: alpha = max(cover, shadow), so the color is weighted by the glass's share.
                float f = mul * a / alpha * 255;
                out[i] = (Math.round(alpha * 255) << 24) | (channel((r + add) * f) << 16)
                        | (channel((g + add) * f) << 8) | channel((b + add) * f);
            }
        }
        return out;
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }
}
