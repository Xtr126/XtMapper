package xtr.keymapper.activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import xtr.keymapper.R;

public class InfoActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);
        WebView webView = findViewById(R.id.rootView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                        .build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public void onReceivedHttpError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceResponse errorResponse) {
                webView.loadUrl(request.getUrl() + "/index.html");
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.getPath().endsWith("/"))
                    url = request.getUrl().buildUpon().appendPath("index.html").build();

                return assetLoader.shouldInterceptRequest(url);
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/XtMapper-docs/index.html");
    }
}