# Football card (TV) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The football card on Free My TV's Android home screen: today's games and the team's next match from the futebol service, beside the clock or in the drawer, with OK opening the broadcasting app.

**Architecture:** Pure rules (no Android imports) in `FootballRules.java`, unit-tested on the JVM by a small runner that `tools/check.sh` calls. `FootballData` fetches and caches the service's JSON off the UI thread, following `HomeData`. `HomeView` draws the card in one of two slots and adds a focus zone for its rows. `Prefs` and Home Settings hold the placement.

**Tech Stack:** Java 8 language level, Android SDK only (`org.json`, `HttpURLConnection`), `build.sh`, plain `javac`/`java` for the rules tests.

**Spec:** `specs/2026-10-23-football-card-design.md`. **Depends on:** the service plan `specs/plans/2026-10-23-futebol-service.md` (its response shape; for device tests, a running service: local `docker run -p 8080:8080` reached as `http://<Mac IP>:8080/futebol`, or `https://tv.cravada.net/futebol`).

## Global Constraints

- CLAUDE.md rules: Java 8, minSdk 26, no libraries; `./build.sh` ends with `Built FreeMyTV.apk`; run `tools/check.sh android` and `python3 tools/translate.py --check` before any commit that touches code or text; never commit `config.properties` or `*.keystore`.
- Builds use `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- 60 fps: no allocations or I/O in `onDraw`; text laid out once per data/settings/language/minute change; on `Ui.LEGACY` (this TCL, Android 11) the card's content goes through a `Snapshot` like `quotesSnapshot`.
- Empty `football_url` = no card, no Settings row, no network calls.
- Placement values (stored in `Prefs` key `football`): `"home"` (A), `"drawer"` (C), `"off"`; default `"home"`. With a TMDB key (`Recs.hasKey`), `"home"` behaves as `"drawer"` and Home Settings offers only "In the drawer" / "Off".
- In the drawer, the football card takes the prices card's slot (`quotesCard`); while it's there the prices card isn't drawn.
- Team colors come from the service (`team.colors[0]` background, `[1]` text). Team card text = the fixture, then the date and time, then the channels where room allows. No heading. No channels yet → "Broadcast to be confirmed".
- On-screen text through `Ui.tr`/`Ui.trf`/`Ui.plural`, English keys, Portuguese translations written by hand (the reference for this feature): "Today" → "Hoje", "+{n} more" → plural "+{n} jogo"/"+{n} jogos", "No games today" → "Nenhum jogo hoje", "Broadcast to be confirmed" → "Transmissão a confirmar", "updated {time} ago" → "atualizado há {time}", "{app} isn't installed on this TV." → "{app} não está instalado nesta TV.", "Football" → "Futebol", "On the home screen" → "Na tela inicial", "In the drawer" → "Na gaveta". Team and channel names untranslated.
- Install on the TV with `adb -s 192.168.15.8:5555 install -r FreeMyTV.apk`; it's already authorized and signed with the existing keystore.

## Review Focus

- The service is unreachable at boot (no cache yet) → no card and no empty box: the slot goes back to the prices card in the drawer, and the clock area stays empty in A. Test in Task 1 (`showCard`), check on device in Task 7.
- The cached data is a day old (TV was off) → "today" in the cache is yesterday's: drop games whose kickoff date isn't today in São Paulo time unless live, so a stale cache never shows yesterday as today. Test in Task 1.
- A game lists only channels whose apps aren't installed (Paramount+) → OK shows the toast, nothing crashes; a channel name not in the table (SportyNet) is skipped when picking. Test in Task 1.
- More games than rows fit (Saturday: 7 games, C fits about 3) → the last row becomes "+N more", with N counted correctly. Test in Task 1.
- The TMDB key is added later with placement `"home"` → the card shows in the drawer, and the Settings row reads "In the drawer". Test in Task 1 (`effectivePlacement`), check on device in Task 7.

---

### Task 1: Settings keys, pure rules and their test runner

**Files:**
- Modify: `build.sh:49-62` (Config generation), `config.example.properties` (append keys with comments)
- Create: `app/src/io/freemytv/FootballRules.java`, `tools/test/FootballRulesTest.java`, `tools/test.sh`
- Modify: `tools/check.sh` (android step: run `tools/test.sh` after `./build.sh`)

**Interfaces:**
- Produces: `Config.FOOTBALL_URL` (`prop football_url ''`), `Config.FOOTBALL_TEAM` (`prop football_team atletico-mg`).
- Produces, in `FootballRules` (package `io.freemytv`, final, no Android imports):
  - `static final class Side { String name, crest; }`, `static final class Channel { String name, logo; }`,
    `static final class Game { long id; long kickoffMs; String competition; Side home, away; String status; int homeScore = -1, awayScore = -1; Channel[] channels; }` (`status` is `"scheduled"`, `"live"` or `"finished"`),
    `static final class Feed { long fetchedAtMs; String teamName; int teamColor, teamTextColor; Game next; Game[] today; }`.
  - `static final String[][] CHANNEL_APPS`: `{channel name, package}` rows in this order: `sportv`, `Premiere`, `Globo`, `globoplay`, `Ge TV` → `com.globo.globotv`; `Disney+`, `ESPN` → `com.disney.disneyplus`; `Prime Vídeo` → `com.amazon.amazonvideo.livingroom`; `HBO Max` → `com.wbd.stream`; `CazéTV`, `YouTube` → `com.google.android.youtube.tv`; `Paramount+` → `com.cbs.ott`. Match names case-insensitively.
  - `static String appFor(Channel[] channels, Set<String> installed)`: the package of the first channel, in order, whose app is installed; `null` if none.
  - `static String firstKnownApp(Channel[] channels)`: the first channel's name that is in the table (for the toast), or `null`.
  - `static String effectivePlacement(String stored, boolean picksOn)`: `"home"` + picks → `"drawer"`; unknown values → `"home"`.
  - `static Game[] todayOnly(Game[] games, long nowMs)`: keeps games whose kickoff is on `nowMs`'s calendar day at UTC−3, plus any `live` game.
  - `static int rowsShown(int games, int rowsThatFit)`: how many game rows to draw; when `games > rowsThatFit` the last slot is "+N more", so this returns `rowsThatFit - 1`, else `games`. `static int moreCount(int games, int rowsThatFit)` = the N (0 when they all fit).
  - `static boolean showCard(Feed feed)`: `false` when `feed == null` or it has neither `next` nor any `today` game.
  - `static boolean stale(long fetchedAtMs, long nowMs)`: older than 2 h.

- [ ] **Step 1: Add the keys and run the build** — in `build.sh` add `FOOTBALL_URL` and `FOOTBALL_TEAM` lines to the generated `Config`, in `config.example.properties` add `football_url=` and `football_team=atletico-mg` with a comment each. Run `./build.sh` → `Built FreeMyTV.apk`. Set `football_url` in the local `config.properties` to the test address (never committed).
- [ ] **Step 2: Write the failing tests** in `tools/test/FootballRulesTest.java` (a `main` with a tiny `check(boolean, String)` that counts failures and exits 1)

```java
Set<String> tv = set("com.globo.globotv", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream", "com.google.android.youtube.tv");
check("com.globo.globotv".equals(appFor(ch("sportv", "Premiere", "globoplay"), tv)), "Globo channels open Globoplay");
check("com.disney.disneyplus".equals(appFor(ch("SportyNet", "Disney+"), tv)), "unknown channel skipped");
check(appFor(ch("Paramount+"), tv) == null, "not installed gives null");
check("Paramount+".equals(firstKnownApp(ch("SportyNet", "Paramount+"))), "toast names Paramount+");
check(appFor(ch("PRIME VÍDEO"), tv) != null, "names match case-insensitively");

check("drawer".equals(effectivePlacement("home", true)), "TMDB key moves A to the drawer");
check("home".equals(effectivePlacement("home", false)), "A stays without a key");
check("off".equals(effectivePlacement("off", true)), "off stays off");
check("home".equals(effectivePlacement("bogus", false)), "unknown value falls back to home");

long sat1700 = brt(2026, 10, 24, 17, 0);
Game[] t = todayOnly(games(at(2026, 10, 23, 20, 0, "finished"), at(2026, 10, 24, 16, 30, "live"),
        at(2026, 10, 24, 21, 0, "scheduled"), at(2026, 10, 25, 11, 0, "scheduled")), sat1700);
check(t.length == 2, "yesterday and tomorrow dropped");
check(todayOnly(games(at(2026, 10, 24, 21, 30, "live")), brt(2026, 10, 25, 0, 10)).length == 1, "live past midnight stays");

check(rowsShown(7, 3) == 2 && moreCount(7, 3) == 5, "7 games in 3 rows: 2 rows + '+5'");
check(rowsShown(3, 3) == 3 && moreCount(3, 3) == 0, "3 games fit in 3 rows");
check(!showCard(null) && !showCard(new Feed()), "no data, no card");
check(stale(0, 2 * 3_600_000L + 1) && !stale(0, 3_600_000L), "stale after 2 h");
```

`tools/test.sh`: `javac -d out/test app/src/io/freemytv/FootballRules.java tools/test/FootballRulesTest.java && java -cp out/test io.freemytv.FootballRulesTest` (the test lives in package `io.freemytv` to reach package-private members).
- [ ] **Step 3: Run, expect FAIL** — `tools/test.sh` → compile error, `FootballRules` missing.
- [ ] **Step 4: Implement `FootballRules.java`.**
- [ ] **Step 5: Run, expect PASS** — `tools/test.sh` → all checks pass, exit 0; `tools/check.sh android` → `All checks passed.`
- [ ] **Step 6: Commit** — `git add build.sh config.example.properties app/src/io/freemytv/FootballRules.java tools/test tools/test.sh tools/check.sh && git commit -m "Football card: settings keys and rules"`

### Task 2: Fetching and caching (`FootballData`)

**Files:**
- Create: `app/src/io/freemytv/FootballData.java`

**Interfaces:**
- Consumes: `FootballRules.Feed/Game/Side/Channel`, `Config.FOOTBALL_URL`, `Config.FOOTBALL_TEAM`.
- Produces: `static FootballData get(Context c)`; `volatile FootballRules.Feed feed`; `void addListener(HomeData.Listener l)` / `removeListener`; `void refreshIfStale()` (called with `HomeData.refreshIfStale()`, at appear and every minute); `Bitmap crest(String url, int px)` (null until it's downloaded, then a notification).

Follow `HomeData`: one `newSingleThreadExecutor`, results posted to the main `Handler`, failures logged with `AppLog.w("football: …")` and retried after 2 min. Poll every 5 min, every 60 s while any game in `feed.today` or `feed.next` is live. Request `<FOOTBALL_URL>?team=<FOOTBALL_TEAM>`, cache the raw body in `SharedPreferences("football")` and parse it at start, so the card shows before the first fetch. Parse with `org.json`: colors with `Color.parseColor` (fallback: dark gray on white), kickoff with `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)`, then `feed.today = FootballRules.todayOnly(...)`. Crests: downloaded once per URL off the UI thread, scaled to the drawn size, kept in an in-memory `HashMap` (fewer than 40 per day). Nothing runs when `FOOTBALL_URL` is empty.

- [ ] **Step 1: Implement `FootballData.java`** and call `FootballData.get(c).refreshIfStale()` wherever `HomeView` calls `data.refreshIfStale()`.
- [ ] **Step 2: Build** — `./build.sh` → `Built FreeMyTV.apk`.
- [ ] **Step 3: Verify on the TV** — install, press Home, then `adb -s 192.168.15.8:5555 logcat -d -s FreeMyTV | grep -i football`. Expected: no `football:` warnings with the service running; stop the service and expect one `football:` warning, with no crash.
- [ ] **Step 4: Commit** — `git add app/src/io/freemytv/FootballData.java app/src/io/freemytv/HomeView.java && git commit -m "Football card: fetch and cache the feed"`

### Task 3: Placement setting in Prefs and Home Settings

**Files:**
- Modify: `app/src/io/freemytv/Prefs.java` (next to `quotes()` under "Widgets and coins")
- Modify: `app/src/io/freemytv/SettingsActivity.java:640-760` (`settingLabels`, `settingValues`, the change handler)
- Modify: `app/assets/i18n/*.json` via `python3 tools/translate.py`, then hand-write the `pt` strings in Global Constraints

**Interfaces:**
- Produces: `static String football()` (`"home"`/`"drawer"`/`"off"`, default `"home"`) and `static void setFootball(String v)` in `Prefs`.
- Consumes: `FootballRules.effectivePlacement`, `Recs.hasKey(Context)`.

The row is the LAST row, after the coins, and exists only when `Config.FOOTBALL_URL` is not empty: compute its index as `labels.size()` at the point it's added (`footballRow()`), and handle it before the `switch` in the change handler so `COIN_ROWS` and the coin rows keep their indices. Left/right (and OK) cycles `home → drawer → off`; with a TMDB key it cycles `drawer ↔ off` and shows the effective value. After a change, call `HomeData.get(getContext()).settingsChanged()` as the other rows do.

- [ ] **Step 1: Implement** the Prefs pair and the row.
- [ ] **Step 2: Strings** — `python3 tools/translate.py`, add the Portuguese values, then `python3 tools/translate.py --check` → no errors.
- [ ] **Step 3: Build and check** — `tools/check.sh android` → `All checks passed.`; on the TV, *Free My TV* → *Home Settings* shows "Futebol · Na tela inicial" as the last row and it cycles.
- [ ] **Step 4: Commit** — `git add app/src/io/freemytv/Prefs.java app/src/io/freemytv/SettingsActivity.java app/assets/i18n && git commit -m "Football card: placement setting"`

### Task 4: Drawing the card in the drawer (C)

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java` (`layoutDrawerCards` near line 1476, `drawDrawer` near 1542, `drawerCardsKey` near 1666)

**Interfaces:**
- Consumes: `FootballData.get(ctx).feed`, `crest(...)`, `FootballRules.showCard/rowsShown/moreCount/stale`, `Prefs.football()`, `FootballRules.effectivePlacement`.
- Produces: `private void drawFootballCard(Canvas c, RectF r, boolean wide)`, used by Tasks 4 and 5 (`wide` = placement A, which has room for channels on every row); `private final RectF footballCard`; `private final RectF[] footballRows` (screen rects of the team card and each game row, for focus in Task 6); `private final Snapshot footballSnapshot`.

In the drawer, when the effective placement is `"drawer"` and `showCard(feed)`, `quotesCard` gives its rect to `footballCard` and the prices aren't drawn. Layout inside the card, top down, in the 1920×1080 space:
- the team card, a rounded rect in the team's colors: line 1 the fixture ("Flamengo x Atlético-MG"), line 2 date and time from the format key `"format|EEE dd/MM · HH:mm"` (pt: "EEE dd/MM · HH:mm"), plus the channels joined with " · " when `wide`, or "Broadcast to be confirmed" when there are none;
- then the game rows: time or live score, then "home x away", channels right-aligned when `wide`, the last slot "+N more" per `rowsShown/moreCount`;
- "No games today" when `today` is empty;
- "updated {time} ago" in the card's corner when `stale`.

Paints are built in the existing paint setup, colored for day and night like `cardTitle`/`cardDim`. Include `FootballData`'s fetch time and `Prefs.football()` in `drawerCardsKey()` so the legacy snapshot refreshes.

- [ ] **Step 1: Implement** as above.
- [ ] **Step 2: Build and install** — `./build.sh && adb -s 192.168.15.8:5555 install -r FreeMyTV.apk`.
- [ ] **Step 3: Verify on the TV** with the placement set to "Na gaveta": press ↓ on the shelf. Expected: weather on the left, the football card on the right with the Galo card in black and white, rows and "+N jogos" when they don't fit. `adb -s 192.168.15.8:5555 shell dumpsys gfxinfo io.freemytv:home framestats | grep -E "Janky frames"` while opening and closing the drawer five times: janky frames no higher than with the prices card. To compare, set the placement to "Desligado" and repeat.
- [ ] **Step 4: Commit** — `git commit -am "Football card: drawer placement"`

### Task 5: Drawing the card beside the clock (A)

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java` (`drawClock` near line 2850, its layer's invalidation, the layout constants near line 100)

**Interfaces:**
- Consumes: `drawFootballCard(Canvas, RectF, boolean)` from Task 4.

When `!picksOn()`, the effective placement is `"home"` and `showCard(feed)`: set `footballCard` to the right half above the shelf, `left = 900*k`, `right = (MARGIN + CONTENT_R)*k`, `top = 250*k`, `bottom = 760*k` (adjust only if it collides with the status buttons or the shelf glass), and draw it with `wide = true` on the same layer as the clock. Redraw that layer when the football data changes, not on every frame: use the snapshot on `Ui.LEGACY`.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build, install and verify on the TV** with "Na tela inicial": the clock on the left, the card on the right, channels on every row; drawer unchanged (prices card back); janky frames over 30 s of idle home screen no higher than before.
- [ ] **Step 3: Verify the TMDB fallback without a real key.** For one local build only, set `tmdb_key` to any non-empty value in `config.properties`, build, install: the card moves to the drawer and Home Settings reads "Na gaveta". Set `tmdb_key` empty again and rebuild before going on.
- [ ] **Step 4: Commit** — `git commit -am "Football card: beside the clock"`

### Task 6: Focus, OK and the toast

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java` (focus zones near line 93, the move handler near line 2120, `activate()`, accessibility ids near line 2601)

**Interfaces:**
- Consumes: `footballRows` (Task 4), `FootballRules.appFor/firstKnownApp`, `HomeApps`' launch path (`HomeApps.java:430-445`), `showToast(String)`.
- Produces: a new zone `Z_FOOTBALL` and `private int footballIndex` (0 = team card, 1… = game rows; the "+N more" row isn't focusable).

Navigation:
- **A:** ↑ from the shelf goes to `Z_FOOTBALL` (team card first) when the card is shown; ↑/↓ move between rows; ↑ from the team card goes to the status buttons; ↓ from the last row goes back to the shelf.
- **C:** ↓ from the drawer's items goes to `Z_FOOTBALL` whether or not TalkBack is on, replacing the TalkBack-only `WIDGETS` path into the quotes slot; ↑ from the team card goes back to the drawer items. The weather card keeps its TalkBack path.

Focused row: the same focus treatment as a drawer item (scale and a lighter glass), driven by a `Spring` like `drawerFocus`.

OK: `appFor(game.channels, installed)`, where `installed` = the packages from `getLaunchIntentForPackage != null` for the table's packages, computed once per `onResume`. A package → launch it as the shelf does. `null` → if `firstKnownApp` isn't null, `showToast(Ui.trf("{app} isn't installed on this TV.", "app", name))`, else do nothing. TalkBack reads each row as "<home> x <away>, <time or score>, <channels>".

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build, install, verify on the TV:**
  - in each placement, moving through the rows and back out works;
  - OK on a sportv game opens Globoplay, OK on a Disney+ game opens Disney+, Back returns to the home screen;
  - for the toast, temporarily point `football_url` at a local service with a fixture edited to list only "Paramount+", check the toast appears, then restore it.
- [ ] **Step 3: Commit** — `git commit -am "Football card: focus and open the broadcasting app"`

### Task 6b: Today's next game on the screensaver instead of prices (added by the person mid-run)

**Files:**
- Modify: `app/src/io/freemytv/FootballRules.java`, `tools/test/FootballRulesTest.java`
- Modify: `app/src/io/freemytv/DreamView.java` (both layouts: the big clock without photos, `quotePaint`; the corner with photos, `cornerQuote`, near line 532)

**Interfaces:**
- Consumes: `FootballData.get(ctx).feed`, `FootballRules.Game`.
- Produces: `static Game nextToday(Game[] today, long nowMs)` in `FootballRules`: the first `live` game, else the first `scheduled` game with `kickoffMs >= nowMs - 2 h` in kickoff order, else `null`.

The screensaver never draws crypto prices any more (whatever `Prefs.quotes()` says; the home screen's prices are unchanged). Where they were, one line: `"16:30  Vitória x Athletico-PR · Premiere"` (time, fixture, first channel), or `"Vitória 1 x 0 Athletico-PR · Premiere"` when live; nothing when `nextToday` is null or there's no feed. Rebuilt when the feed or the minute changes (like the stale prices line), never per frame.

- [ ] **Step 1: Failing tests** — `nextToday(games(live at 16:30, scheduled 18:30), 17:00)` is the live one; `nextToday(games(finished 16:30, scheduled 18:30), 17:00)` is 18:30; `nextToday(games(finished 16:30), 19:00)` is null; `nextToday(new Game[0], …)` is null. Run `tools/test.sh` → FAIL (no `nextToday`).
- [ ] **Step 2: Implement** `nextToday`, run `tools/test.sh` → PASS.
- [ ] **Step 3: DreamView** — remove the prices from both layouts, draw the line as above. Build, install, start the screensaver (`adb -s 192.168.15.8:5555 shell am start -n com.android.systemui/.Somnambulator`, or wait for it) and check it shows the line and no prices.
- [ ] **Step 4: Commit** — `git commit -am "Screensaver: today's next game instead of prices"`

### Task 7: Final check on the TV

- [ ] **Step 1:** `tools/check.sh android` and `python3 tools/translate.py --check` both pass.
- [ ] **Step 2: Service down:** stop the service, then restart the home screen (`adb -s 192.168.15.8:5555 shell am force-stop io.freemytv` and press Home). The card shows the cached data, with no crash and no `football:` error loop in the log (one warning every 2 min at most). The no-cache case is covered by `showCard` in Task 1. Never clear the app's data on the TV: it holds the person's settings.
- [ ] **Step 3: Way back:** *Free My TV* → *Home Screen* → *Original* and back to *Free My TV*: both switches complete (`adb -s 192.168.15.8:5555 logcat -d -s FreeMyTV | tail -20`).
- [ ] **Step 4:** Ask the person to look at both placements on the TV and confirm, before any push to the fork.
