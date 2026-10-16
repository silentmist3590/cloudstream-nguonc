package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object NguonCHelper {

    class DecryptInterface(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun passDecryptedData(data: String) {
            if (data.contains("#EXTM3U") || data.contains(".ts") || data.contains(".m4s")) {
                onResult(data)
            }
        }
    }

    /**
     * Chạy WebView ngầm, giả lập iframe hợp lệ và hook WebCrypto để tóm M3U8
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractM3u8FromEmbed(
        context: Context,
        embedUrl: String,
        referer: String = "https://phim.nguonc.com/"
    ): String? = withTimeoutOrNull(15000L) {
        suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context)
                var isFinished = false

                val finish = { result: String? ->
                    if (!isFinished) {
                        isFinished = true
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                }

                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                // Cầu nối giao tiếp JS -> Android Kotlin
                webView.addJavascriptInterface(DecryptInterface { decryptedM3u8 ->
                    finish(decryptedM3u8)
                }, "AndroidBridge")

                // Script hook vào WebCrypto Decrypt và URL.createObjectURL
                val hookScript = """
                    (function() {
                        if (window.__cloudstream_hooked) return;
                        window.__cloudstream_hooked = true;

                        // 1. Hook Web Crypto Decrypt
                        if (window.crypto && window.crypto.subtle) {
                            const origDecrypt = window.crypto.subtle.decrypt;
                            window.crypto.subtle.decrypt = async function(...args) {
                                const result = await origDecrypt.apply(this, args);
                                try {
                                    const text = new TextDecoder().decode(result);
                                    if (text.includes("#EXTM3U")) {
                                        window.AndroidBridge.passDecryptedData(text);
                                    }
                                } catch(e) {}
                                return result;
                            };
                        }

                        // 2. Hook URL.createObjectURL (trường hợp nạp Blob vào HLS)
                        const origCreateObjectURL = URL.createObjectURL;
                        URL.createObjectURL = function(blob) {
                            const url = origCreateObjectURL.apply(this, arguments);
                            try {
                                if (blob && blob.type && blob.type.toLowerCase().includes("mpegurl")) {
                                    const reader = new FileReader();
                                    reader.onload = function() {
                                        if (reader.result && reader.result.includes("#EXTM3U")) {
                                            window.AndroidBridge.passDecryptedData(reader.result);
                                        }
                                    };
                                    reader.readAsText(blob);
                                }
                            } catch(e) {}
                            return url;
                        };
                    })();
                """.trimIndent()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        // Nếu bắt gặp link playlist trực tiếp trong lúc request
                        if (reqUrl.contains(".m3u8") && !reqUrl.startsWith("blob:")) {
                            finish(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                // Gói embedUrl vào thẻ iframe để vượt qua điều kiện (window.parent !== window)
                val iframeHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script>$hookScript</script>
                    </head>
                    <body style="margin:0;padding:0;background:#000;">
                        <iframe id="stream_frame" src="$embedUrl" style="width:100vw;height:100vh;border:none;" allowfullscreen allow="autoplay; encrypted-media"></iframe>
                    </body>
                    </html>
                """.trimIndent()

                // Load với baseUrl của trang nguồn để header Referer/Origin chuẩn 100%
                webView.loadDataWithBaseURL(
                    referer,
                    iframeHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }
}
