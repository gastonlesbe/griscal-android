package app.griscal.notif;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class WebAppActivity extends AppCompatActivity {

    public static final String EXTRA_ID_TOKEN = "id_token";
    private static final String BASE_URL = "https://griscal.app";

    private WebView webView;
    private ProgressBar progressBar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNotificationPermission();
        requestExactAlarmPermission();
        requestBatteryOptimizationExemption();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_webview);

        progressBar = findViewById(R.id.progressBar);
        webView     = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? ProgressBar.VISIBLE : ProgressBar.GONE);
            }
        });

        // Get fresh ID token → exchange for custom token → load /mobile-auth
        // The web page signs into Firebase client-side + creates session cookie
        String passedToken = getIntent().getStringExtra(EXTRA_ID_TOKEN);
        if (passedToken != null && !passedToken.isEmpty()) {
            fetchCustomTokenAndLoad(passedToken);
        } else {
            user.getIdToken(true).addOnSuccessListener(result -> {
                fetchCustomTokenAndLoad(result.getToken());
            }).addOnFailureListener(e -> webView.loadUrl(BASE_URL + "/login"));
        }
    }

    /**
     * Exchange Firebase ID token for a custom token via /api/auth/mobile,
     * then load /mobile-auth?customToken=... so the web page can sign in and create a session cookie.
     */
    private void fetchCustomTokenAndLoad(String idToken) {
        new Thread(() -> {
            try {
                URL url = URI.create(BASE_URL + "/api/auth/mobile").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String body = "{\"idToken\":\"" + idToken + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                Log.d("GriscalAuth", "Mobile auth response: " + responseCode);

                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    String json = sb.toString();
                    String customToken = null;
                    int start = json.indexOf("\"customToken\":\"");
                    if (start != -1) {
                        start += 15;
                        int end = json.indexOf("\"", start);
                        if (end != -1) customToken = json.substring(start, end);
                    }

                    if (customToken != null) {
                        Log.d("GriscalAuth", "Got custom token, loading /mobile-auth");
                        String finalToken = customToken;
                        runOnUiThread(() -> {
                            ReminderSyncService.start(WebAppActivity.this);
                            webView.loadUrl(BASE_URL + "/mobile-auth?customToken=" + finalToken);
                        });
                    } else {
                        Log.w("GriscalAuth", "No customToken in response, going to /login");
                        runOnUiThread(() -> webView.loadUrl(BASE_URL + "/login"));
                    }
                } else {
                    Log.w("GriscalAuth", "Mobile auth failed, going to /login");
                    runOnUiThread(() -> webView.loadUrl(BASE_URL + "/login"));
                }

                conn.disconnect();
            } catch (Exception e) {
                Log.e("GriscalAuth", "fetchCustomTokenAndLoad error: " + e.getMessage());
                runOnUiThread(() -> webView.loadUrl(BASE_URL));
            }
        }).start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
