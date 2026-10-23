package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's photo background when there are no picks: Unsplash photos of the chosen theme. Two are kept on the
 * TV (files/backgrounds): the current one and the next, already downloaded, so a change never waits on the network.
 * All the work runs on one thread (io), so nothing here races.
 */
final class Backgrounds {
    interface Listener { void onBackground(); }

    static final class Photo {
        String id, author, downloadLocation, theme;
        long shownAt;
    }

    private static final String API = "https://api.unsplash.com/";

    private static Backgrounds instance;

    static synchronized Backgrounds get(Context c) {
        if (instance == null) instance = new Backgrounds(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, String> topicIds = new HashMap<>();   // Unsplash filters topics by id, not by slug
    private volatile Photo current, next;
    private volatile Backdrop decoded;
    private volatile boolean decoding;
    private volatile long failedAt;
    private volatile int failCode;   // the HTTP code of the last failure (0: none, or not HTTP)
    private boolean loaded;

    private Backgrounds(Context c) {
        context = c;
        dir = new File(c.getFilesDir(), "backgrounds");
    }

    static boolean hasKey() {
        return !Config.UNSPLASH_KEY.trim().isEmpty();
    }

    boolean active(Context c) {
        return hasKey() && !Recs.hasKey(c) && !"plain".equals(Prefs.bgTheme());
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The current photo, ready to draw; null while there is none or it is being decoded again (after trim). */
    Backdrop current() {
        Backdrop b = decoded;
        if (b == null && current != null && !decoding) {
            decoding = true;
            io.execute(() -> {
                try {
                    if (decoded == null && current != null && decodeCurrent()) notifyListeners();
                } finally {
                    decoding = false;
                }
            });
        }
        return b;
    }

    Photo currentPhoto() { return current; }

    /** The next photo is on the TV, without touching the disk (for drawing): kept in step with next on io. */
    boolean hasNext() { return next != null; }

    /** current.jpg, for Home Settings' preview (read it off the UI thread). */
    File currentFile() { return file("current.jpg"); }

    /** Home Settings opened: reads the state if it wasn't, says so, and fetches the next photo if it's missing. */
    void prepare() {
        io.execute(() -> {
            load();
            notifyListeners();
            ensureNext();
        });
    }

    boolean nextReady() {
        Photo n = next;
        return n != null && file("next.jpg").exists();
    }

    /** The home screen came back: changes the photo if it's time, and makes sure the next one is on the TV. */
    void resume() {
        io.execute(() -> {
            load();
            if (!active(context)) return;
            String theme = Prefs.bgTheme();
            if (next != null && !theme.equals(next.theme)) dropNext();
            Photo c = current;
            boolean changed = false;
            boolean due = c == null || !theme.equals(c.theme)
                    || BackgroundRules.due(c.shownAt, System.currentTimeMillis(), Prefs.bgEvery(), Prefs.bgKeep());
            if (due && nextReady()) changed = promote();
            if (decoded == null && current != null) changed |= decodeCurrent();
            if (changed) notifyListeners();
            ensureNext();
        });
    }

    /** Shows the next photo now (nothing when it isn't on the TV yet), then fetches another. */
    void skip() {
        io.execute(() -> {
            load();
            if (!nextReady()) return;
            if (promote()) notifyListeners();
            ensureNext();
        });
    }

    /**
     * The theme changed: a next or current photo of another theme goes, and one of the new theme is fetched. Nothing
     * to do when both are already of this theme (flipping through the themes and back).
     */
    void themeChanged() {
        io.execute(() -> {
            load();
            String theme = Prefs.bgTheme();
            Photo c = current, n = next;
            boolean keepNext = n != null && theme.equals(n.theme);
            if (keepNext && c != null && theme.equals(c.theme)) return;
            if (!keepNext) dropNext();
            if (c != null && !theme.equals(c.theme)) {
                current = null;
                decoded = null;
                delete(file("current.jpg"));
                notifyListeners();
            }
            // A choice on the TV is tried at once, unless Unsplash refused the key or the hour's quota is spent.
            if (failCode != 403 && failCode != 429) failedAt = 0;
            save();
            if (!active(context)) return;
            if (current == null && keepNext && promote()) notifyListeners();
            ensureNext();
        });
    }

    /** Memory is short: the decoded photo goes; current() decodes it again. */
    void trim() {
        decoded = null;
    }

    // MARK: - Work on io

    /**
     * Next becomes current: its file, shownAt now, and Unsplash is told it was used. The new photo is decoded before
     * it's published, so current() keeps the old one until then, never null in between.
     */
    private boolean promote() {
        Photo n = next;
        File from = file("next.jpg"), to = file("current.jpg");
        delete(to);
        if (n == null || !from.renameTo(to)) {
            dropNext();
            return false;
        }
        n.shownAt = System.currentTimeMillis();
        next = null;
        Backdrop b;
        try {
            b = decode(to);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            current = n;
            decoded = null;
            save();
            lost(e);
            return true;
        }
        decoded = b;
        current = n;
        save();
        if (n.downloadLocation != null && !n.downloadLocation.isEmpty()) {
            try {
                get(n.downloadLocation);
            } catch (Exception e) {   // only counts the use: no reason to wait before the next download
                AppLog.w("backgrounds: download_location " + e.getClass().getSimpleName());
            }
        }
        return true;
    }

    /** Builds the backdrop of current.jpg; a missing or unreadable file means there is no current photo. */
    private boolean decodeCurrent() {
        try {
            decoded = decode(file("current.jpg"));
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            lost(e);
            return false;
        }
    }

    private static Backdrop decode(File f) throws IOException {
        if (!f.exists()) throw new IOException();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException();
        return Backdrop.photo(b);
    }

    /** current.jpg couldn't be decoded: unreadable, it goes (out of memory, it stays for current() to try again). */
    private void lost(Throwable e) {
        AppLog.w("background image: " + e.getClass().getSimpleName());
        if (e instanceof OutOfMemoryError) return;
        delete(file("current.jpg"));
        current = null;
        decoded = null;
        save();
    }

    /** Downloads the next photo unless it's there or the last failure was under 10 minutes ago. */
    private void ensureNext() {
        if (!active(context) || nextReady()) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now >= failedAt && now < BackgroundRules.retryAt(failedAt)) return;
        String theme = Prefs.bgTheme();
        try {
            String url = BackgroundRules.randomUrl(theme);
            String src = BackgroundRules.source(theme);
            if (src != null && src.startsWith("topics=")) {
                String slug = src.substring("topics=".length());
                url = url.replace("topics=" + slug, "topics=" + topicId(slug));
            }
            JSONObject o = pick(new JSONArray(get(url)), BackgroundRules.topic(theme));
            String raw = o.getJSONObject("urls").getString("raw");
            Photo p = new Photo();
            p.id = o.getString("id");
            JSONObject user = o.optJSONObject("user");
            p.author = user == null ? "" : user.optString("name");
            JSONObject links = o.optJSONObject("links");
            p.downloadLocation = links == null ? "" : links.optString("download_location");
            p.theme = theme;
            download(BackgroundRules.sized(raw, Backdrop.W, Backdrop.H), file("next.jpg"));
            next = p;
            failedAt = 0;
            failCode = 0;
            save();
            AppLog.w("backgrounds: next " + p.id + " (" + theme + ")");
            notifyListeners();   // Home Settings' "New background now" stops saying Loading
        } catch (Exception e) {
            failed(e);
            save();
            return;
        }
        if (current == null && promote()) {   // nothing on screen yet: show it now, and fetch the one after
            notifyListeners();
            if (current != null) ensureNext();
        }
    }

    /**
     * The first photo that's not the one on screen and, for a topic, that Unsplash approved for it: the random endpoint
     * also returns photos only submitted to the topic, rejected ones included (a subway platform in Nature).
     */
    private JSONObject pick(JSONArray photos, String topic) throws IOException {
        Photo c = current;
        for (int i = 0; i < photos.length(); i++) {
            JSONObject o = photos.optJSONObject(i);
            if (o == null || o.optJSONObject("urls") == null) continue;
            if (c != null && c.id != null && c.id.equals(o.optString("id"))) continue;
            if (topic != null) {
                JSONObject subs = o.optJSONObject("topic_submissions");
                JSONObject sub = subs == null ? null : subs.optJSONObject(topic);
                if (sub == null || !"approved".equals(sub.optString("status"))) continue;
            }
            return o;
        }
        throw new IOException("no photo of the theme");
    }

    private String topicId(String slug) throws Exception {
        String id = topicIds.get(slug);
        if (id != null) return id;
        id = new JSONObject(get(API + "topics/" + slug)).getString("id");
        topicIds.put(slug, id);
        save();
        return id;
    }

    private void dropNext() {
        next = null;
        delete(file("next.jpg"));
    }

    private void failed(Exception e) {
        failedAt = System.currentTimeMillis();
        failCode = e instanceof HttpError ? ((HttpError) e).code : 0;
        AppLog.w("backgrounds: " + e.getClass().getSimpleName() + (e instanceof HttpError ? " HTTP " + ((HttpError) e).code : ""));
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onBackground(); });
    }

    // MARK: - HTTP

    private static final class HttpError extends IOException {
        final int code;
        HttpError(int code) { this.code = code; }
    }

    private static String get(String url) throws IOException {
        if (!url.startsWith(API)) throw new IOException();   // the key goes only to Unsplash's API
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Client-ID " + Config.UNSPLASH_KEY.trim());
        c.setRequestProperty("Accept-Version", "v1");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** The photo itself, from Unsplash's image servers (no key), via a .part file; it must decode. */
    private static void download(String url, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException();
            if (!part.renameTo(f)) throw new IOException();
        } finally {
            delete(part);
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // MARK: - State on disk

    private File file(String name) {
        return new File(dir, name);
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));   // left by a download the system cut off
        for (int i = 0; parts != null && i < parts.length; i++) delete(parts[i]);
        File f = file("state.json");
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(read(in));
            failedAt = o.optLong("failedAt");
            failCode = o.optInt("failCode");
            JSONObject topics = o.optJSONObject("topics");
            if (topics != null) {
                for (Iterator<String> it = topics.keys(); it.hasNext(); ) {
                    String k = it.next();
                    topicIds.put(k, topics.getString(k));
                }
            }
            Photo c = photo(o.optJSONObject("current")), n = photo(o.optJSONObject("next"));
            current = c != null && file("current.jpg").exists() ? c : null;
            next = n != null && file("next.jpg").exists() ? n : null;
            AppLog.w("backgrounds: current " + (current == null ? "none" : current.id + " (" + current.theme + ")")
                    + ", next " + (next == null ? "none" : next.id + " (" + next.theme + ")"));
        } catch (Exception e) {
            AppLog.w("backgrounds state: " + e.getClass().getSimpleName());
        }
    }

    private void save() {
        try {
            JSONObject topics = new JSONObject();
            for (Map.Entry<String, String> e : topicIds.entrySet()) topics.put(e.getKey(), e.getValue());
            JSONObject o = new JSONObject().put("failedAt", failedAt).put("failCode", failCode).put("topics", topics);
            if (current != null) o.put("current", json(current));
            if (next != null) o.put("next", json(next));
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = file("state.json"), part = file("state.json.part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) delete(part);
        } catch (Exception e) {
            AppLog.w("backgrounds state: " + e.getClass().getSimpleName());
        }
    }

    private static JSONObject json(Photo p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("author", p.author).put("downloadLocation", p.downloadLocation)
                .put("theme", p.theme).put("shownAt", p.shownAt);
    }

    private static Photo photo(JSONObject o) {
        if (o == null || o.optString("id").isEmpty()) return null;
        Photo p = new Photo();
        p.id = o.optString("id");
        p.author = o.optString("author");
        p.downloadLocation = o.optString("downloadLocation");
        p.theme = BackgroundRules.theme(o.optString("theme"));
        p.shownAt = o.optLong("shownAt");
        return p;
    }
}
