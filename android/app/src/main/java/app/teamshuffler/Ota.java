package app.teamshuffler;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.webkit.WebResourceResponse;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silent updates of the web app (index.html) without a new APK.
 *
 * The files of the newest commit on main are downloaded into files/ota-next and switched on at the
 * next launch (or right away after a manual check). They are served from the same in-app origin,
 * so the saved data stays in place. A page declares the Android bridge it needs with
 * <meta name="app-native" content="N">; content needing a newer app waits for the APK update.
 * The APK itself is only offered when its Android part changed: CI writes a fingerprint of the
 * bundled non-web files into BuildConfig.NATIVE_HASH and into the release notes ("native: ...").
 */
final class Ota {

    /** Bump when the bridge gains something index.html depends on, and set the meta tag to match. */
    static final int NATIVE_API = 1;
    private static final String[] FILES = {"index.html"};
    private static final Pattern NATIVE_META = Pattern.compile("<meta name=\"app-native\" content=\"(\\d+)\"");
    private static final Pattern NATIVE_NOTE = Pattern.compile("native: ([0-9a-f]{12})");

    private Ota() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("ota", Context.MODE_PRIVATE);
    }

    private static File live(Context ctx) {
        return new File(ctx.getFilesDir(), "ota");
    }

    private static File next(Context ctx) {
        return new File(ctx.getFilesDir(), "ota-next");
    }

    static String currentSha(Context ctx) {
        return prefs(ctx).getString("sha", BuildConfig.WEB_SHA);
    }

    /** " (content abc1234)" when running downloaded content, for the version shown in the page. */
    static String label(Context ctx) {
        String sha = currentSha(ctx);
        return new File(live(ctx), "www/index.html").isFile() && sha.length() >= 7 ? " (content " + sha.substring(0, 7) + ")" : "";
    }

    /** True when the newest release (GitHub API JSON) has the same Android part as this APK. */
    static boolean sameNative(String releaseJson) {
        try {
            Matcher m = NATIVE_NOTE.matcher(new JSONObject(releaseJson).optString("body", ""));
            return m.find() && m.group(1).equals(BuildConfig.NATIVE_HASH);
        } catch (Exception e) {
            return false;
        }
    }

    /** Before loading the page: a new APK drops downloads from older installs; staged content is switched on. */
    static void prepare(Context ctx) {
        long versionCode;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return;
        }
        SharedPreferences p = prefs(ctx);
        if (p.getLong("versionCode", -1) != versionCode) {
            deleteTree(live(ctx));
            deleteTree(next(ctx));
            p.edit().clear().putLong("versionCode", versionCode).putString("sha", BuildConfig.WEB_SHA).apply();
            return;
        }
        apply(ctx);
    }

    /** Switches to the staged download, if any. Returns true when it did. */
    static boolean apply(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String staged = p.getString("staged", null);
        File n = next(ctx);
        if (staged == null || !new File(n, "ready").isFile()) return false;
        deleteTree(live(ctx));
        if (!n.renameTo(live(ctx))) return false;
        p.edit().putString("sha", staged).remove("staged").apply();
        return true;
    }

    /** Stages newer content from main. True when something is waiting for apply(). Network: off the main thread. */
    static boolean check(Context ctx) throws IOException {
        SharedPreferences p = prefs(ctx);
        String sha = new String(get("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/commits/main",
                "application/vnd.github.sha"), StandardCharsets.UTF_8).trim();
        if (!sha.matches("[0-9a-f]{40}")) throw new IOException("unexpected commit id");
        if (sha.equals(currentSha(ctx))) return false;
        if (sha.equals(p.getString("staged", null)) && new File(next(ctx), "ready").isFile()) return true;
        File n = next(ctx);
        deleteTree(n);
        for (String f : FILES) {
            byte[] body = get("https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO + "/" + sha + "/" + f, null);
            if (f.equals("index.html") && !compatible(new String(body, StandardCharsets.UTF_8))) {
                deleteTree(n);
                return false;
            }
            File out = new File(n, "www/" + f);
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            try (FileOutputStream o = new FileOutputStream(out)) {
                o.write(body);
            }
        }
        if (!new File(n, "ready").createNewFile()) throw new IOException("cannot mark download");
        p.edit().putString("staged", sha).apply();
        return true;
    }

    private static boolean compatible(String html) {
        if (!html.contains("</html>")) return false;
        Matcher m = NATIVE_META.matcher(html);
        return m.find() && Integer.parseInt(m.group(1)) <= NATIVE_API;
    }

    /** Downloaded file for /assets/<path>, or null to use the copy inside the APK. */
    static WebResourceResponse serve(Context ctx, String path) {
        File root = live(ctx), f = new File(root, path);
        try {
            if (!f.isFile() || !f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) return null;
            String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".html") ? "text/html" : null;
            if (mime == null) return null;
            return new WebResourceResponse(mime, "utf-8", new FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] get(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (accept != null) c.setRequestProperty("Accept", accept);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                for (int r; (r = in.read(b)) > 0; ) buf.write(b, 0, r);
                return buf.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
