package com.xbrowser.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final String START_URL = "https://x.com";
    private static final String[] ALLOWED_DOMAINS = {"x.com", "twitter.com", "t.co", "twimg.com"};

    private WebView webView;
    private String injectedJs = "";
    private final Set<Long> activeDownloads = ConcurrentHashMap.newKeySet();

    @SuppressLint({"SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNeededPermissions();

        webView = new WebView(this);
        setContentView(webView);

        injectedJs = readAsset("inject.js");

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // X is happier with a normal mobile Chrome UA than the "; wv" WebView one
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "XBridge");
        webView.setWebChromeClient(new WebChromeClient());

        // Preferred: inject before any page script runs so fetch/XHR hooks see every API response
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>(Arrays.asList(
                    "https://x.com", "https://*.x.com",
                    "https://twitter.com", "https://*.twitter.com"));
            WebViewCompat.addDocumentStartJavaScript(webView, injectedJs, origins);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (isAllowed(u)) return false;       // stay in the app
                Toast.makeText(MainActivity.this, "Blocked: " + u.getHost(), Toast.LENGTH_SHORT).show();
                return true;                           // block everything else
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Fallback / safety net; inject.js is idempotent
                if (isAllowed(Uri.parse(url))) {
                    view.evaluateJavascript(injectedJs, null);
                }
            }
        });

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(this, doneReceiver, f, ContextCompat.RECEIVER_EXPORTED);

        webView.loadUrl(START_URL);
    }

    // ---------- domain lock ----------

    private static boolean isAllowed(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String host = u.getHost();
        if (host == null) return false;
        for (String d : ALLOWED_DOMAINS) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    // ---------- permissions ----------

    private boolean needsLegacyStoragePermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (needsLegacyStoragePermission()) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    // ---------- JS bridge ----------

    private class Bridge {
        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void download(String url, String name) {
            runOnUiThread(() -> startDownload(url, name));
        }
    }

    @SuppressWarnings("deprecation")
    private void startDownload(String url, String name) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            // Never trust the page: only https mp4 files from twimg.com
            boolean ok = "https".equals(uri.getScheme()) && host != null
                    && (host.equals("twimg.com") || host.endsWith(".twimg.com"))
                    && path != null && path.endsWith(".mp4");
            if (!ok) {
                Toast.makeText(this, "Rejected download URL", Toast.LENGTH_SHORT).show();
                return;
            }
            if (needsLegacyStoragePermission()) {
                requestNeededPermissions();
                Toast.makeText(this, "Storage permission needed", Toast.LENGTH_SHORT).show();
                return;
            }

            String safe = (name == null ? "video.mp4" : name).replaceAll("[^A-Za-z0-9._-]", "_");
            if (!safe.endsWith(".mp4")) safe += ".mp4";

            DownloadManager.Request req = new DownloadManager.Request(uri);
            req.setTitle(safe);
            req.setDescription("X Browser Downloader");
            req.setMimeType("video/mp4");
            req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "XBrowser/" + safe);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            activeDownloads.add(id);
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (!activeDownloads.remove(id)) return;

            boolean success = false;
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur != null && cur.moveToFirst()) {
                    int col = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    success = col >= 0 && cur.getInt(col) == DownloadManager.STATUS_SUCCESSFUL;
                }
            }
            Toast.makeText(c, success ? "Download complete (Download/XBrowser)" : "Download failed",
                    Toast.LENGTH_LONG).show();
        }
    };

    // ---------- misc ----------

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(doneReceiver); } catch (Exception ignored) {}
        webView.destroy();
        super.onDestroy();
    }
}
