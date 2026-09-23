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
        if (manual) toast("Checking for updates…");

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
                else if (manual) toast("You have the latest version");
            } catch (Exception e) {
                // No network, rate limit or unexpected response: the automatic check tries again later.
                if (manual) toast("Could not check. Are you online?");
            }
        }).start();
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
                .setTitle("Update available")
                .setMessage("Team Shuffler " + version + " is ready. Download it and open the file to update. Your saved lists stay in place.")
                .setPositiveButton("Download", (d, w) -> {
                    Uri apk = Uri.parse("https://github.com/" + BuildConfig.UPDATE_REPO
                            + "/releases/latest/download/team-shuffler.apk");
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, apk));
                    } catch (ActivityNotFoundException ignored) {
                    }
                })
                .setNegativeButton("Later", null)
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
