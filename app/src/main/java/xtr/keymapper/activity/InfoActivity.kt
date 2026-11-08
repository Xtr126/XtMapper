package xtr.keymapper.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import xtr.keymapper.R

class InfoActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val docsUrl = "https://xtr126.github.io/XtMapper-docs/guides/about/"
        val webView: WebView = findViewById(R.id.rootView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.apply {
            webViewClient = object : WebViewClientCompat() {
                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    val path = request.url.path
                    if (path != null && !path.contains("index.html")) {
                        loadUrl("${request.url}/index.html")
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    var url = request.url
                    val urlPath = url.path

                    if (urlPath != null && urlPath.endsWith("/") && !urlPath.contains("index.html")) {
                        url = url.buildUpon().appendPath("index.html").build()
                    }

                    return assetLoader.shouldInterceptRequest(url)
                }
            }

            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                domStorageEnabled = true
            }

            loadUrl(docsUrl)
        }
    }
}