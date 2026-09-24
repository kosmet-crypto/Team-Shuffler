package app.teamshuffler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Hosts the Team Shuffler web app (bundled in assets/www) in a full-screen WebView.
 * Pages are served from https://appassets.androidplatform.net so localStorage
 * behaves like on a normal https site.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B1120);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.addJavascriptInterface(new Bridge(), "ShufflerAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Anything outside the app opens in the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);

        if (savedInstanceState == null) checkForUpdate(false);
    }

    /* ---------- update check ---------- */

    private static final long UPDATE_CHECK_INTERVAL = 12 * 60 * 60 * 1000L;

    /**
     * Looks up the latest GitHub Release (tagged v1.0.<versionCode>) and offers to download it
     * when it is newer than this install. The automatic check on launch is throttled and silent;
     * a manual check (the "Check for updates" button) always runs and reports the result.
     */
    private void checkForUpdate(final boolean manual) {
        final SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("lastCheck", 0) < UPDATE_CHECK_INTERVAL) return;
        prefs.edit().putLong("lastCheck", now).apply();
        if (manual) toast(text(CHECKING));

        new Thread(() -> {
            try {
                URL api = new URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
                HttpURLConnection c = (HttpURLConnection) api.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                String body;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                    body = buf.toString("UTF-8");
                }
                String tag = new JSONObject(body).optString("tag_name", "");
                final long latest = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                final String name = tag.startsWith("v") ? tag.substring(1) : tag;
                if (latest > installedVersionCode()) runOnUiThread(() -> showUpdateDialog(name));
                else if (manual) toast(text(LATEST));
            } catch (Exception e) {
                // No network, rate limit or unexpected response: the automatic check tries again later.
                if (manual) toast(text(CANT_CHECK));
            }
        }).start();
    }

    /* ---------- texts for the native dialog and toasts ---------- */

    // Keys: checking, latest, cantCheck, title, message (%s = version), download, later.
    private static final String[] EN = {"Checking for updates…", "You have the latest version",
            "Could not check. Are you online?", "Update available",
            "Team Shuffler %s is ready. Download it and open the file to update. Your saved lists stay in place.",
            "Download", "Later"};
    private static final String[] SR = {"Проверавам ажурирања…", "Имате најновију верзију",
            "Провера није успела. Јесте ли на интернету?", "Ново ажурирање",
            "Team Shuffler %s је спреман. Преузмите га и отворите фајл да ажурирате. Сачуване листе остају.",
            "Преузми", "Касније"};
    private static final String[] NB = {"Ser etter oppdateringer…", "Du har nyeste versjon",
            "Kunne ikke sjekke. Er du på nett?", "Oppdatering tilgjengelig",
            "Team Shuffler %s er klar. Last den ned og åpne filen for å oppdatere. Lagrede lister beholdes.",
            "Last ned", "Senere"};
    private static final int CHECKING = 0, LATEST = 1, CANT_CHECK = 2, TITLE = 3, MESSAGE = 4, DOWNLOAD = 5, LATER = 6;

    /** The language picked in the page (saved by setLanguage), else the phone's language. */
    private String text(int key) {
        String lang = getSharedPreferences("update", MODE_PRIVATE).getString("lang", null);
        if (lang == null) {
            String sys = Locale.getDefault().getLanguage();
            lang = sys.matches("sr|hr|bs|sh") ? "sr" : sys.matches("nb|nn|no") ? "nb" : "en";
        }
        return ("sr".equals(lang) ? SR : "nb".equals(lang) ? NB : EN)[key];
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private long installedVersionCode() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void showUpdateDialog(String version) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(text(TITLE))
                .setMessage(String.format(text(MESSAGE), version))
                .setPositiveButton(text(DOWNLOAD), (d, w) -> {
                    Uri apk = Uri.parse("https://github.com/" + BuildConfig.UPDATE_REPO
                            + "/releases/latest/download/team-shuffler.apk");
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, apk));
                    } catch (ActivityNotFoundException ignored) {
                    }
                })
                .setNegativeButton(text(LATER), null)
                .show();
    }

    /** Methods index.html can call as window.ShufflerAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public String getVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void checkForUpdate() {
            runOnUiThread(() -> MainActivity.this.checkForUpdate(true));
        }

        /** The page reports its language (en, sr or nb) so native texts match it. */
        @JavascriptInterface
        public void setLanguage(String lang) {
            getSharedPreferences("update", MODE_PRIVATE).edit().putString("lang", lang).apply();
        }
    }

    @Override
    public void onBackPressed() {
        // The app is a single page; back closes it unless the WebView has history.
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
