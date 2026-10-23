package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The football card's data: today's games and the team's next match, from the futebol service at
 * Config.FOOTBALL_URL (github.com/reidosbares/futebol-tv). Fetched off the UI thread like HomeData, with the last
 * good response kept so the card shows at once after a restart.
 */
final class FootballData {
    private static final long EVERY = 5 * 60_000L, LIVE_EVERY = 60_000L, RETRY = 2 * 60_000L;

    private static FootballData instance;

    static synchronized FootballData get(Context c) {
        if (instance == null) instance = new FootballData(c.getApplicationContext());
        return instance;
    }

    static boolean enabled() {
        return !Config.FOOTBALL_URL.trim().isEmpty();
    }

    /** The last good feed (null until there is one), with "today" trimmed to the current day. */
    volatile FootballRules.Feed feed;
    volatile long fetchedAt;

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HomeData.Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;

    private FootballData(Context c) {
        cache = c.getSharedPreferences("football", Context.MODE_PRIVATE);
        if (!enabled()) return;
        String body = cache.getString("body", null);
        if (body != null) {
            try {
                feed = parse(body, System.currentTimeMillis());
                fetchedAt = cache.getLong("at", 0);
            } catch (Exception e) {
                AppLog.w("football: cached feed unreadable: " + e.getMessage());
            }
        }
    }

    void addListener(HomeData.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(HomeData.Listener l) { listeners.remove(l); }

    private boolean anyLive(long now) {
        FootballRules.Feed f = feed;
        if (f == null) return false;
        if (f.next != null && f.next.liveAt(now)) return true;
        for (FootballRules.Game g : f.today) if (g.liveAt(now)) return true;
        return false;
    }

    /** Fetches when the data is older than 5 minutes (1 while a game is live). Called with HomeData's. */
    void refreshIfStale() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        // First, whatever happens next: with the service down overnight, yesterday's games must still go away.
        retrim(now);
        if (fetching) return;
        long every = anyLive(now) ? LIVE_EVERY : EVERY;
        if (now - fetchedAt < every && now >= fetchedAt) return;
        if (failedAt != 0 && now >= failedAt && now - failedAt < RETRY) return;
        fetching = true;
        io.execute(() -> {
            try {
                String url = Config.FOOTBALL_URL.trim() + "?team=" + URLEncoder.encode(Config.FOOTBALL_TEAM.trim(), "UTF-8");
                String body = new String(get(url), StandardCharsets.UTF_8);
                long at = System.currentTimeMillis();
                FootballRules.Feed f = parse(body, at);
                feed = f;
                fetchedAt = at;
                failedAt = 0;
                cache.edit().putString("body", body).putLong("at", at).apply();
                notifyListeners();
                AppLog.w("football: " + f.today.length + " today, next " + (f.next == null ? "none" : f.next.home.name + " x " + f.next.away.name));
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("football: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** At midnight a cached "today" becomes yesterday's: trim it without waiting for the next fetch. */
    private void retrim(long now) {
        FootballRules.Feed f = feed;
        if (f == null) return;
        FootballRules.Game[] t = FootballRules.todayOnly(f.today, now);
        if (t.length != f.today.length) {
            f.today = t;
            notifyListeners();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (HomeData.Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    // MARK: - Parsing the service's JSON

    private static long time(String iso) throws Exception {
        // "2026-10-26T20:00:00-03:00" or "2026-10-23T17:00:22.724668+00:00": drop the fraction.
        String s = iso.replaceFirst("\\.\\d+", "");
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s).getTime();
    }

    private static int color(JSONArray colors, int i, int fallback) {
        try {
            return Color.parseColor(colors.getString(i));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static FootballRules.Game game(JSONObject o) throws Exception {
        FootballRules.Game g = new FootballRules.Game();
        g.id = o.optLong("id");
        g.kickoffMs = time(o.getString("kickoff"));
        g.competition = o.optString("competition");
        g.status = o.optString("status", "scheduled");
        g.timeKnown = o.optBoolean("timeKnown", true);
        JSONObject h = o.getJSONObject("home"), a = o.getJSONObject("away");
        g.home.name = h.optString("name");
        g.home.crest = h.optString("crest");
        g.away.name = a.optString("name");
        g.away.crest = a.optString("crest");
        JSONObject score = o.optJSONObject("score");
        if (score != null) {
            g.homeScore = score.optInt("home", -1);
            g.awayScore = score.optInt("away", -1);
        }
        JSONArray chs = o.optJSONArray("channels");
        int n = chs == null ? 0 : chs.length();
        g.channels = new FootballRules.Channel[n];
        for (int i = 0; i < n; i++) {
            JSONObject c = chs.getJSONObject(i);
            g.channels[i] = new FootballRules.Channel();
            g.channels[i].name = c.optString("name");
            g.channels[i].logo = c.optString("logo");
        }
        return g;
    }

    static FootballRules.Feed parse(String body, long now) throws Exception {
        JSONObject o = new JSONObject(body);
        FootballRules.Feed f = new FootballRules.Feed();
        String at = o.optString("fetchedAt", "");
        f.fetchedAtMs = at.isEmpty() || "null".equals(at) ? 0 : time(at);
        JSONObject team = o.getJSONObject("team");
        f.teamName = team.optString("name");
        JSONArray colors = team.optJSONArray("colors");
        if (colors != null) {
            f.teamColor = color(colors, 0, f.teamColor);
            f.teamTextColor = color(colors, 1, f.teamTextColor);
        }
        JSONObject next = team.optJSONObject("next");
        if (next != null) f.next = game(next);
        JSONArray today = o.optJSONArray("today");
        List<FootballRules.Game> games = new ArrayList<>();
        for (int i = 0; today != null && i < today.length(); i++) games.add(game(today.getJSONObject(i)));
        f.today = FootballRules.todayOnly(games.toArray(new FootballRules.Game[0]), now);
        return f;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
