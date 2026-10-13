package com.lagradost

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val LINK_SEP = "|||"
private var appCtx: Context? = null

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load(context: Context) {
        appCtx = context.applicationContext ?: context
        registerMainAPI(PhimNguonCProvider())
    }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Phim NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "the-loai/hanh-dong" to "Phim Hành Động",
        "the-loai/phim-hai" to "Phim Hài",
        "the-loai/chien-tranh" to "Phim Chiến Tranh",
        "the-loai/lich-su" to "Phim Lịch Sử",
        "the-loai/phim-18" to "Phim 18+",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/phim-le" to "Phim Lẻ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/api/films/${request.data}?page=$page"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        val homeItems = res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        val hasNext = (res?.paginate?.currentPage ?: 0) < (res?.paginate?.totalPage ?: 0)
        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/films/search?keyword=$q"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        return res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val slug = slug ?: return null
        val poster = posterUrl ?: thumbUrl
        return newMovieSearchResponse(title, "$mainUrl/api/film/$slug", TvType.Movie) {
            this.posterUrl = poster
            this.year = year?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).parsedSafe<NguonCDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val poster = movie.posterUrl ?: movie.thumbUrl
        val desc = movie.description
        val episodes = mutableListOf<Episode>()

        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "NguonC"
            server.items?.forEach { ep ->
                val epName = ep.name ?: "1"
                val embed = ep.embed
                val m3u8 = ep.m3u8
                if (!embed.isNullOrBlank() || !m3u8.isNullOrBlank()) {
                    episodes.add(
                        newEpisode("${m3u8.orEmpty()}$LINK_SEP${embed.orEmpty()}") {
                            this.name = "$serverName - Tập $epName"
                            this.episode = epName.toIntOrNull()
                        }
                    )
                }
            }
        }

        if (episodes.size == 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = desc
                this.year = movie.year
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = desc
            this.year = movie.year
        }
    }

    class FetchBridge(val onDataCaptured: (String) -> Unit) {
        @JavascriptInterface
        fun postMessage(data: String) {
            onDataCaptured(data)
        }
    }

    private suspend fun getStreamLinkViaFetchHook(context: Context, embedUrl: String, mainUrl: String): String? =
        suspendCancellableCoroutine { continuation ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                }

                var isResumed = false

                webView.addJavascriptInterface(FetchBridge { capturedData ->
                    if (!isResumed && (capturedData.contains(".m3u8") || capturedData.contains("manifest"))) {
                        isResumed = true
                        if (continuation.isActive) {
                            continuation.resume(capturedData)
                        }
                    }
                }, "AndroidBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val jsHook = """
                            (function() {
                                if (window.fetchHooked) return;
                                window.fetchHooked = true;
                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const [resource, config] = args;
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('.m3u8') || text.includes('playlist')) {
                                            AndroidBridge.postMessage(text);
                                        }
                                        if (typeof resource === 'string' && resource.includes('.m3u8')) {
                                            AndroidBridge.postMessage(resource);
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsHook, null)
                    }
                }

                val htmlContent = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta charset="utf-8"></head>
                    <body style="margin:0;background:#000;">
                        <iframe src="$embedUrl" style="width:100%;height:100vh;border:none;" allowfullscreen></iframe>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(mainUrl, htmlContent, "text/html", "UTF-8", null)
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(LINK_SEP, limit = 2)
        val embed = (if (parts.size == 2) parts[1] else data).takeIf { it.isNotBlank() }
        val apiM3u8 = (if (parts.size == 2) parts[0] else "").takeIf { it.isNotBlank() }
        val embedRef = embed?.let { originOf(it) } ?: "$mainUrl/"

        suspend fun isHls(url: String): Boolean {
            val r = runCatching { app.get(url, referer = embedRef) }.getOrNull()
            return r?.text?.trimStart()?.startsWith("#EXTM3U") == true
        }

        suspend fun emit(url: String): Boolean {
            var ok = false
            runCatching {
                M3u8Helper.generateM3u8(name, url, embedRef).forEach {
                    callback(it)
                    ok = true
                }
            }
            return ok
        }

        // 1) Thử m3u8 từ API trước
        if (apiM3u8 != null && isHls(apiM3u8) && emit(apiM3u8)) return true

        // 2) Nếu không có hoặc lỗi, sử dụng WebView Hook vượt kiểm tra origin của embed
        if (embed != null) {
            val context = appCtx
            if (context != null) {
                val sniffed = getStreamLinkViaFetchHook(context, embed, mainUrl)
                if (!sniffed.isNullOrBlank()) {
                    if (sniffed.startsWith("http") && (sniffed.contains(".m3u8") || sniffed.contains("manifest"))) {
                        if (emit(sniffed)) return true
                    }
                }
            }
        }

        return false
    }

    private fun originOf(url: String): String {
        val m = Regex("""^(https?://[^/]+)""").find(url)
        return (m?.groupValues?.get(1) ?: mainUrl) + "/"
    }

    // --- Data Classes ---
    data class NguonCPaginate(
        @JsonProperty("current_page") val currentPage: Int?,
        @JsonProperty("total_page") val totalPage: Int?,
        @JsonProperty("total_items") val totalItems: Int?,
        @JsonProperty("items_per_page") val itemsPerPage: Int?
    )

    data class NguonCItem(
        val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        val slug: String?,
        val year: String?,
        val description: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?
    )

    data class NguonCListResponse(
        val status: String?,
        val paginate: NguonCPaginate?,
        val items: List<NguonCItem>?
    )

    data class NguonCEpisodeItem(
        val name: String?,
        val slug: String?,
        val embed: String?,
        val m3u8: String?
    )

    data class NguonCServer(
        @JsonProperty("server_name") val serverName: String?,
        val items: List<NguonCEpisodeItem>?
    )

    data class NguonCMovie(
        val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        val slug: String?,
        val year: Int?,
        val description: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?,
        val episodes: List<NguonCServer>?
    )

    data class NguonCDetailResponse(
        val status: String?,
        val movie: NguonCMovie?
    )
}