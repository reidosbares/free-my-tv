# Glass clock and photo backgrounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the person's TCL (Android 11, no TMDB key), the home screen shows rotating Unsplash photos, sharp, with white text and clear glass, and a bold frosted-glass clock; the screensaver's clock becomes the same glass.

**Architecture:** The glass is baked on the CPU: `GlassMath` (pure Java, JVM-tested) turns a text mask plus a background sampler into ARGB pixels, and `GlassClock` (Android) wraps it on a worker thread and hands back a Bitmap that the UI just draws. `Backgrounds` (a singleton in `:home`, mirroring `Recs`) fetches Unsplash photos into `files/backgrounds/`, keeps the current and the next, and builds them into a `Backdrop` with the existing `forLight == false` path. `HomeView` gets a "photo" amount that pushes text and glass to the dark side of `tone()` while a photo is up; Home Settings gets four rows.

**Tech Stack:** Java 8, Android SDK only (`HttpURLConnection`, `org.json`), `build.sh`, plain `javac`/`java` tests in `tools/test/`.

**Spec:** `specs/2026-10-23-glass-clock-and-backgrounds-design.md` (read it first). Visual reference: `.superpowers/brainstorm/87515-1792785292/content/glass-frost.html` ("C2 + more frost") and `day-look.html` (B).

## Global Constraints

- Target: only the TCL Smart TV Pro, Android 11 (`Ui.LEGACY`, no AGSL), 1080p UI, at `192.168.15.8:5555`. No work for AGSL paths, other TVs or `web/`; but everything must still compile and `tools/check.sh` must pass.
- CLAUDE.md rules: Java 8, minSdk 26, no libraries; `./build.sh` ends with `Built FreeMyTV.apk`; run `tools/check.sh android` and `python3 tools/translate.py --check` before every commit that touches code or text; never commit `config.properties`, `*.keystore` or a key.
- Builds use `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- 60 fps: no allocations, I/O or glass math in `onDraw`; glass baked on a worker, at most once per minute / background / night change.
- `unsplash_key` is secret: sent only as the header `Authorization: Client-ID <key>`, never in a URL, a log line or an exception message that gets logged (log `e.getClass().getSimpleName()`, not `getMessage()` of a URL exception). Never print `config.properties`.
- Unsplash: `GET https://api.unsplash.com/photos/random` with `orientation=landscape&content_filter=high`; show `urls.raw` + size params; call `links.download_location` (same header) once when a photo becomes the current one; credit `user.name`. Stay under 50 requests/hour (expect 1–3).
- Photos picked only when `!Recs.hasKey(context)` and `Backgrounds.hasKey()` and theme ≠ `plain`. Otherwise today's light look, untouched.
- Clock: Inter weight 700, 250 px (×k), letter spacing −0.03; glass numbers (start here, tune on the TV against the mockup): refraction `7k` px, body `bg × 0.72 + 0.25`, rim and highlight per `Shaders.GLASS_TEXT` with specular `0.7`, shadow alpha `0.28` with blur `14k`, offset `6k` down.
- Text: `Ui.tr`/`Ui.trf`, English keys; the build ships `en`, `pt`, `es`: write `pt` and `es` by hand (table in Task 7).
- Install: `adb -s 192.168.15.8:5555 install -r FreeMyTV.apk` (authorized, same keystore). Screenshots: `adb -s 192.168.15.8:5555 exec-out screencap -p > out/shot.png`. The person does any taps on the TV; tell them what to press.

## Review Focus

- First start with a key but no network → no photo yet: the light look and gradient, no blank or black screen, and a retry later (10 min back-off like `Recs.refreshIfStale`). Test `BackgroundRules.retryAt` in Task 1; check on the TV in Task 8 (Wi-Fi off is theirs to do; or a wrong key).
- The current photo file is missing or corrupt on disk (deleted cache, half-written download) → treated as no photo, the next is fetched; never a crash. Downloads go to `.part` then rename. Check by deleting `files/backgrounds/current.jpg` via `adb shell run-as io.freemytv` in Task 4.
- Home Settings changes recreate `HomeActivity` (`Prefs.version`) → the new `HomeView` must get the current photo at once from the `Backgrounds` singleton (already decoded), with no fade from gradient on every settings change. Check in Task 6.
- "New background now" pressed several times fast → each press shows a new photo (the next is promoted; when no next is ready yet, the press is a no-op with the row reading "Loading…"), never two fetches racing (all of `Backgrounds`' work runs on its one `io` thread). Check on the TV in Task 7, Step 5.
- A very bright photo (snow, white architecture) → date, clock, weather and the credit stay readable: the `forLight == false` left scrim with its brightness boost is applied; the glass clock's body reads the scrimmed frost. Check with Architecture/Abstract themes in Task 8.

---

### Task 1: The key, the pure background rules and their tests

**Files:**
- Modify: `build.sh:44-66` (add `static final String UNSPLASH_KEY = "$(prop unsplash_key '')";` next to `WATCHMODE_KEY`), `config.example.properties` (append `unsplash_key=` with a comment like `watchmode_key`'s: free Access Key from unsplash.com/developers, photos as the home screen background)
- Create: `app/src/io/freemytv/BackgroundRules.java`, `tools/test/BackgroundRulesTest.java`
- Modify: `tools/test.sh` (compile and run the new test too)

**Interfaces — Produces** (`BackgroundRules`, final, no Android imports):
- `static final String[] THEMES = {"nature", "aerials", "abstract", "architecture", "travel", "plain"}`; `static String theme(String stored)` → a valid theme, default `"nature"`.
- `static String source(String theme)` → the request params: `nature` → `topics=nature`, `aerials` → `query=aerial drone`, `abstract` → `query=abstract`, `architecture` → `topics=architecture-interior`, `travel` → `topics=travel`; `plain` → `null`. *(Unsplash's `topics` param documents topic IDs; Task 4 checks whether slugs work and, if not, resolves each slug once with `GET /topics/{slug}` and caches the id. Keep `source` returning the slug form either way.)*
- `static String randomUrl(String theme)` → `https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&` + URL-encoded `source(theme)`.
- `static String sized(String raw, int w, int h)` → `raw + (raw.contains("?") ? "&" : "?") + "w=" + w + "&h=" + h + "&fit=crop&crop=entropy&fm=jpg&q=82"`.
- `static long interval(String every)`: `"day"` → 24 h, anything else → 1 h. `static String every(String stored)` → `"hour"` / `"day"`, default `"hour"`.
- `static boolean due(long shownAtMs, long nowMs, String every, boolean keep)`: false when `keep`; true when `shownAtMs <= 0` or `nowMs - shownAtMs >= interval(every)`; a `nowMs` earlier than `shownAtMs` (clock moved back) counts as due.
- `static long retryAt(long failedAtMs)` = `failedAtMs + 10 min`.

- [ ] **Step 1: Add the key** to `build.sh` and `config.example.properties`; run `./build.sh` → `Built FreeMyTV.apk`. Tell the person to create their Unsplash app (unsplash.com/developers → New Application, free "Demo") and paste the **Access Key** after `unsplash_key=` in `config.properties` themselves. Don't read or print it.
- [ ] **Step 2: Write the failing tests** in `tools/test/BackgroundRulesTest.java` (same `check` style as `FootballRulesTest`, `main` exits 1 on failures):

```java
check("nature".equals(theme(null)) && "nature".equals(theme("bogus")) && "travel".equals(theme("travel")), "theme default/valid");
check(source("plain") == null, "plain has no source");
check(randomUrl("aerials").contains("query=aerial%20drone") || randomUrl("aerials").contains("query=aerial+drone"), "aerials is a search");
check(randomUrl("nature").startsWith("https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&"), "base url");
check(!randomUrl("nature").contains("client_id"), "key never in the URL");
check(sized("https://images.unsplash.com/photo-1?ixid=x", 1920, 1080).endsWith("&w=1920&h=1080&fit=crop&crop=entropy&fm=jpg&q=82"), "sized");
long h = 3_600_000L, now = 100 * h;
check(due(0, now, "hour", false), "never shown is due");
check(!due(now - h / 2, now, "hour", false) && due(now - h, now, "hour", false), "hourly");
check(!due(now - 23 * h, now, "day", false) && due(now - 24 * h, now, "day", false), "daily");
check(!due(1, now, "hour", true), "kept is never due");
check(due(now + h, now, "hour", false), "clock moved back is due");
check(retryAt(now) == now + 600_000L, "10 min back-off");
```

- [ ] **Step 3: Run** `tools/test.sh` → fails (no `BackgroundRules`).
- [ ] **Step 4: Implement** `BackgroundRules` with the signatures above; add it and its test to `tools/test.sh`'s `javac` line plus `java -cp out/test io.freemytv.BackgroundRulesTest`.
- [ ] **Step 5: Run** `tools/test.sh` → both tests pass, no `FAIL:` lines. `./build.sh` → `Built FreeMyTV.apk`.
- [ ] **Step 6: Commit** `build.sh config.example.properties tools/test.sh tools/test/BackgroundRulesTest.java app/src/io/freemytv/BackgroundRules.java` — "Backgrounds: the Unsplash key and the rotation rules".

### Task 2: GlassMath, the CPU glass, shared with DreamView

**Files:**
- Create: `app/src/io/freemytv/GlassMath.java`, `tools/test/GlassMathTest.java`
- Modify: `app/src/io/freemytv/DreamView.java:598-660` (`makeGlass` builds its `slope`/`shadow` from `GlassMath.mask`; delete its private `blur`/`box`), `tools/test.sh`

**Interfaces — Produces** (`GlassMath`, final, no Android imports):
- `static final class Mask { int w, h; float[] nx, ny, height, shadow; }` — per pixel: outward edge normal (−1..1, from the central difference of the blurred mask, normalised by its max, as `makeGlass` does), height (0.5 at the edge, 1 deep inside strokes, 0 outside), drop-shadow alpha (0..1).
- `static Mask mask(int[] alpha255, int w, int h, float k)`: blur radii as `makeGlass` today (`height`: r = max(2, 6k), 3 passes; `shadow`: r = max(3, 14k), 3 passes), shadow shifted `round(6k)` px down.
- `interface Sampler { int rgb(float x, float y); }` (screen px → 0xRRGGBB).
- `static Sampler grid(int[] argb, int gw, int gh, float screenW, float screenH)`: bilinear, clamped at the edges (reads `Backdrop.frost`/`glass` arrays).
- `static Sampler vertical(int top, int bottom, float screenH)`: the `Ui.gradient` colors.
- `static final class Look { float refraction, body = 0.72f, lift = 0.25f, specular = 0.7f, shadow = 0.28f, darken; }` (`darken` 0..1 multiplies the background, for night).
- `static int[] render(Mask m, int[] cover255, Sampler bg, float originX, float originY, Look look)` → non-premultiplied ARGB, `m.w × m.h`: glass where `cover` > 0 (port of `Shaders.GLASS_TEXT`: sample at `p + n·refraction` with the 0.2 chromatic split when |n| > 0.02, saturation 1.15, `col = bg·body + lift`, rim/glow/facing/back terms with L = (−0.447, −0.894), times `specular`), composited over black at `shadow × m.shadow` alpha; alpha = max(cover, shadow).

- [ ] **Step 1: Write the failing tests** in `tools/test/GlassMathTest.java`, on a 120×80 mask with a filled 60×40 rect at (30, 20) and `k = 1`:

```java
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
```

- [ ] **Step 2: Run** `tools/test.sh` → fails.
- [ ] **Step 3: Implement** `GlassMath`; switch `DreamView.makeGlass` to fill its `slope` bitmap from `Mask` (R = 128 + 127·nx, G = 128 + 127·ny, B = 255·height) and `shadow` from `Mask.shadow`, so the AGSL path keeps working unchanged on Android 13+. Add the files to `tools/test.sh`.
- [ ] **Step 4: Run** `tools/test.sh` → passes; `./build.sh` → `Built FreeMyTV.apk`; compile-check against `platforms/android-26/android.jar` shows nothing new.
- [ ] **Step 5: Commit** — "Glass: the CPU version of the glass text, shared with the screensaver".

### Task 3: Bold font, GlassClock, and the screensaver's glass clock

**Files:**
- Modify: `app/src/io/freemytv/Ui.java:50-66` (add `static Typeface bold` = `font(a, 32, 700)`)
- Create: `app/src/io/freemytv/GlassClock.java`
- Modify: `app/src/io/freemytv/DreamView.java` (`onSizeChanged` 228-260: `clockPaint` → `Ui.bold`, 280k, letter spacing −0.03; `drawContent` 734-764: in fallback mode draw the baked bitmap instead of the text)

**Interfaces:**
- Consumes: `GlassMath` (Task 2).
- Produces (`GlassClock`, Android): `GlassClock(java.util.concurrent.Executor worker, android.os.Handler main)`; `void request(String text, Paint paint, float left, float baseline, GlassMath.Sampler bg, GlassMath.Look look, Runnable onReady)` — bakes off the UI thread (cover from `paint` drawn white into an ALPHA_8/ARGB mask sized ink + `pad = round(40k)`), drops stale requests (a generation counter), posts `onReady` on `main`; `Bitmap bitmap()`, `float x()`, `float y()` (top-left on screen of the last ready bake); `void release()` (recycle). Same request (text, bg identity, look values) as the last one → no work. Keeps the previous bitmap until the new one is ready, and exposes `Bitmap previous()` + `long readyAt()` so callers can crossfade over `FADE_MS`.

- [ ] **Step 1: Implement** `Ui.bold`, `GlassClock`; in `DreamView` fallback mode request a bake when the minute text changes, with `GlassMath.vertical(palette.color(1), palette.color(0), h)` (the colors `Scene.fallback` uses), `look.refraction = 7k`; draw `bitmap()` at `x(), y()` inside `content` (it drifts with it). Until the first bake is ready, draw the bold text in white with the soft shadow. On Android 13+ the existing shader path stays as is.
- [ ] **Step 2: Build** `./build.sh` → `Built FreeMyTV.apk`; install.
- [ ] **Step 3: Check on the TV:** ask the person to open *Free My TV* → *Screensaver* → *Preview*; take a screenshot; compare with `glass-frost.html` C2 + more frost (bold, frosted, thin bright rim, top-left highlight, soft shadow). Tune `Look` numbers if needed; log nothing per frame. `adb logcat -s FreeMyTV` shows no errors.
- [ ] **Step 4: Commit** — "Screensaver: bold frosted-glass clock on Android 11".

### Task 4: Backgrounds: fetch, cache, rotate

**Files:**
- Create: `app/src/io/freemytv/Backgrounds.java`
- Modify: `app/src/io/freemytv/Backdrop.java:63-112` (the `forLight == false` path also keeps `frostImage`, a 192×108 mutable Bitmap of `frost`, for the drawer), `app/src/io/freemytv/Prefs.java` (keys below)

**Interfaces:**
- Consumes: `BackgroundRules` (Task 1), `Backdrop.make(src, false)`.
- Produces in `Prefs`: `bgTheme()`/`setBgTheme(String)` (key `bg_theme`, through `BackgroundRules.theme`), `bgEvery()`/`setBgEvery(String)` (`bg_every`), `bgKeep()`/`setBgKeep(boolean)` (`bg_keep`, default false). `setBgTheme` also sets keep false.
- Produces (`Backgrounds`, singleton like `Recs`: `static synchronized Backgrounds get(Context)`, own single-thread `io` executor, main `Handler`):
  - `static boolean hasKey()` → `!Config.UNSPLASH_KEY.trim().isEmpty()`.
  - `boolean active(Context c)` → `hasKey() && !Recs.hasKey(c) && !"plain".equals(Prefs.bgTheme())`.
  - `interface Listener { void onBackground(); }`, `addListener`/`removeListener`.
  - `Backdrop current()` (decoded, or null), `Photo currentPhoto()` with `static final class Photo { String id, author, downloadLocation, theme; long shownAt; }`, `boolean nextReady()`.
  - `void resume()` — called when the home screen comes back: loads state from disk if not loaded; if `BackgroundRules.due(...)` and next is ready → promote; makes sure a next is fetched (respecting `retryAt`). Notifies listeners when `current` changes.
  - `void skip()` — promote next now (no-op when not ready); then fetch a new next. With keep on, keep stays on (the new one is kept).
  - `void themeChanged()` — drop next (and current if its theme differs), fetch for the new theme.
  - `void trim()` — drop the decoded `current` (re-decoded on next `current()` request) for `onTrimMemory`.
  - Storage: `files/backgrounds/current.jpg`, `next.jpg`, `state.json` (both photos' `Photo` fields, `failedAt`); writes via `.part` + rename. Downloads of `BackgroundRules.sized(urls.raw, Backdrop.W, Backdrop.H)`; decode then `Backdrop.make(bitmap, false)` on `io`; promoting = rename `next.jpg` → `current.jpg`, `shownAt = now`, then `GET downloadLocation` (header auth, result ignored).
  - HTTP: own private `get(String url)` like `Recs.get` (8 s timeouts) with `Authorization: Client-ID` + `Accept-Version: v1`; on any failure set `failedAt`, log the exception's class name and HTTP code only.

- [ ] **Step 1: Check the topics param** with a one-off request in a scratch Java/`curl` run that reads the key from `config.properties` without echoing it (e.g. `curl -s -H "Authorization: Client-ID $(sed -n 's/^unsplash_key=//p' config.properties)" 'https://api.unsplash.com/photos/random?topics=nature&orientation=landscape' | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id"))'`). If it errors or returns unfiltered results, resolve slugs to ids via `/topics/{slug}` in `Backgrounds` and cache them in `state.json`. Look at a few photos per theme (print only `urls.small`) and adjust `BackgroundRules.source` queries if a theme returns non-wallpaper photos; re-run Task 1's tests.
- [ ] **Step 2: Implement** the `Prefs` keys, `Backdrop.frostImage`, and `Backgrounds`.
- [ ] **Step 3: Build** → `Built FreeMyTV.apk`. (Not on screen yet; Task 5 draws it.) Commit — "Backgrounds: Unsplash photos, the current and the next kept on the TV".

### Task 5: The photo look on the home screen

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java` — `resume()` (434), `updateCards` (2919), `showBackdrop`/`tickBackgroundFade` (883-1029), `dark()`/`tone` (982-991), `drawLightSky`/`drawLightImage` (2935-2968), `drawPlatter` (1201), `drawLightButtons` (3104), `drawLightTop` (2970, credit), `onTrimMemory` path via `HomeActivity:69`
- Modify: `app/src/io/freemytv/Scene.java` (a `clear(Canvas, RectF, float radius, float alpha)` flat white fill + 0x33FFFFFF rim, without the `max(0.35…)` floor of `glass`)

**Interfaces:**
- Consumes: `Backgrounds` (Task 4).
- Produces in `HomeView`: `private float photo` (0..1, eased with the background crossfade: 1 while the shown backdrop is an Unsplash one); `dark()` returns `Math.max(night, photo)`; `private boolean isPhoto(Backdrop b)` (identity with `Backgrounds.current()` or a flag set when shown).

- [ ] **Step 1: Wire it:** `HomeView` listens to `Backgrounds`; in `resume()` call `backgrounds.resume()`; with no picks, `updateCards` shows `backgrounds.current()` when `active`, else `null` (today). On attach after a recreate, if `current()` is ready, set `bgTo` directly with no fade (Review Focus 3).
- [ ] **Step 2: Draw the photo:** in `drawLightSky`, for a photo backdrop draw `b.image` full screen; night darkens it with `veil(0.45·night, black)` (Theme Light → `night` stays 0 via `HomeData.isNight`, Dark → 1, as today); the drawer draws `b.frostImage` scaled to the screen (filtered) at alpha `drawer.value` plus `veil(0.15·drawer.value, black)` instead of the white veil. Non-photo backdrops and the gradient: unchanged.
- [ ] **Step 3: Glass and text:** with `photo` > 0, `drawPlatter` and `drawLightButtons` blend toward `scene.clear(...)` at alpha 0.16 (day) / today's smoke at night, by `photo`; all text follows `tone()` to white. Focus circle on buttons stays white.
- [ ] **Step 4: Credit:** `Ui.trf("Photo: {name}, Unsplash", "name", author)`, 22k, 0x99FFFFFF with the soft shadow, right-aligned at `CONTENT_R·k`, baseline `platterRect.top − 18k`; cached string, rebuilt when the photo or `Ui.generation` changes; alpha × `photo`.
- [ ] **Step 5: Build, install, check on the TV:** with the key in, theme Nature: a sharp photo, white date/weather, the clear shelf, credit above it; Home → an app → back, after changing `bg_every`… (rotation is checked in Task 6). Screenshots by day; ask the person to set Theme to Dark for the night look and the drawer (↓ on the shelf) for the frost. `adb logcat -s FreeMyTV`: no errors, no key.
- [ ] **Step 6: Commit** — "Home screen: Unsplash photos, sharp, with white text and clear glass".

### Task 6: The glass clock on the home screen

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java` — `makePaints` (623-660: `lClock` → `Ui.bold`, 250k, spacing −0.03), `drawClock` (3013-3031), `updateClock` (~792), `nightChanged` (960), `tickBackgroundFade` end

**Interfaces:**
- Consumes: `GlassClock` (Task 3), `GlassMath.grid` over the shown backdrop's `frost` array (`Backdrop.FW × FH`, screen 1920k × 1080k).

- [ ] **Step 1: Bake:** with a photo shown, request a bake (on `LOADER`) on minute change, on the end of a background fade, and when `night` settles at 0 or 1; `look.refraction = 7k`, `look.darken = 0.45·night`. Left-aligned at `MARGIN·k − leftBearing`, baseline `L_CLOCK_BASE·k`. Move `L_DATE_BASE` up so the date clears the taller digits (≈ `L_CLOCK_BASE − 250·0.73 − 50`); check it doesn't touch the football card at x = 900k.
- [ ] **Step 2: Draw:** the baked bitmap, crossfading from `previous()` over `FADE_MS` after `readyAt()`, × `photo`; until a bake exists (and on Plain / no photo) draw `lClock` as bold solid text in `tone(L_INK, WHITE)`. *(Plain keeps today's light look: white glass over the near-white gradient would vanish; bold solid ink there.)*
- [ ] **Step 3: Release** the bitmaps in `pause()`/detach; no allocation in `onDraw`.
- [ ] **Step 4: Build, install, check:** screenshot vs `glass-frost.html` C2 + more frost; the minute changes without a flash; Home Settings → back (activity recreate) shows photo and glass at once.
- [ ] **Step 5: Commit** — "Home screen: the bold frosted-glass clock".

### Task 7: Home Settings rows, the preview, and the text

**Files:**
- Modify: `app/src/io/freemytv/SettingsActivity.java` — `settingLabels`/`settingValues` (648-677), `adjust` (731-760), `activate` (986-1050), Back targets (615-618), spoken labels (227-231), `onDraw` left column (353-378)
- Modify: `app/assets/i18n/pt.json`, `es.json` (then `python3 tools/translate.py`), `README.md` (settings table row `unsplash_key`, a short "### The Unsplash key" section after the TMDB one, Privacy: photos come from Unsplash with the key)

**Interfaces:**
- Consumes: `Prefs.bg*`, `Backgrounds.get(..).skip()/themeChanged()/currentPhoto()/nextReady()`.

- [ ] **Step 1: Rows** (only when `Backgrounds.hasKey()`), right after Theme: *Background* (← → / OK cycle `THEMES` → `setBgTheme` + `themeChanged()`), *Change background* (Every hour / Every day), *Keep this background* (Off/On), *New background now* (action: OK → `skip()`, value "Loading…" while `!nextReady()`). Replace the hard-coded row indices (`CITY_ROW`, `case 3/4`, Back targets) with named indices computed from whether the rows are present.
- [ ] **Step 2: Preview:** on Home Settings, when a current photo exists, the left column shows it (decoded small from `current.jpg` on a worker, 480×270, rounded like the cards) with "Now on the home screen" and the credit under it, instead of the sparkles symbol; refreshed after `skip()`.
- [ ] **Step 3: Text** — `pt` / `es`:

| English | pt | es |
|---|---|---|
| Background | Fundo | Fondo |
| Change background | Trocar fundo | Cambiar fondo |
| Keep this background | Manter este fundo | Mantener este fondo |
| New background now | Novo fundo agora | Nuevo fondo ahora |
| Nature / Aerials / Abstract / Architecture / Travel / Plain | Natureza / Aéreas / Abstrato / Arquitetura / Viagem / Simples | Naturaleza / Aéreas / Abstracto / Arquitectura / Viajes / Sencillo |
| Every hour / Every day | A cada hora / Todo dia | Cada hora / Cada día |
| Loading… | Carregando… | Cargando… |
| Now on the home screen | Agora na tela inicial | Ahora en la pantalla de inicio |
| Photo: {name}, Unsplash | Foto: {name}, Unsplash | Foto: {name}, Unsplash |

- [ ] **Step 4: Run** `python3 tools/translate.py` then `--check` → no missing keys; `tools/check.sh android` passes.
- [ ] **Step 5: Install and check** with the person: each row cycles; *New background now* swaps the photo (back on the home screen it's the new one); *Keep* on → Home after the interval keeps it; theme change → a photo of the new theme.
- [ ] **Step 6: Commit** — "Home Settings: background theme, rotation, keep and new now".

### Task 8: Whole-feature check on the TV

- [ ] **Step 1:** `tools/check.sh` (all) passes; `git status` shows no `config.properties` or key.
- [ ] **Step 2:** Walk the Review Focus list on the TV. No photo at first start: build once with a wrong key (keep the real one aside, never printed), clear `files/backgrounds/` with `adb shell run-as io.freemytv rm -r files/backgrounds`, check the light look stays and the log shows an HTTP 401 with no key in it; then rebuild with the real key. Bright photos: switch to Architecture and Abstract, *New background now* a few times, screenshot each.
- [ ] **Step 3:** `adb shell dumpsys gfxinfo io.freemytv:home` after moving along the shelf: janky frames no worse than before the change (compare with a run on the previous APK if in doubt). Memory: `dumpsys meminfo io.freemytv:home` PSS within ~20 MB of before.
- [ ] **Step 4:** Tell the person what changed and how to switch back (Home Settings → Background → Plain).
