package app.griscal.notif;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class WebAppActivity extends AppCompatActivity {

    public static final String EXTRA_ID_TOKEN = "id_token";
    private static final String BASE_URL = "https://griscal.app";

    private WebView webView;
    private ProgressBar progressBar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        // Not logged in → go to login screen, no flash
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

        // Already logged in — get fresh ID token and auto-sign into web app
        String idToken = getIntent().getStringExtra(EXTRA_ID_TOKEN);
        if (idToken != null && !idToken.isEmpty()) {
            // Token passed directly from LoginActivity
            webView.loadUrl(BASE_URL + "/mobile-auth?idToken=" + idToken);
        } else {
            // Returning user — get a fresh token silently
            user.getIdToken(false).addOnSuccessListener(result -> {
                String token = result.getToken();
                ReminderSyncService.start(this);
                webView.loadUrl(BASE_URL + "/mobile-auth?idToken=" + token);
            }).addOnFailureListener(e -> webView.loadUrl(BASE_URL));
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
