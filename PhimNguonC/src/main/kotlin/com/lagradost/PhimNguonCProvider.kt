package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

// Ngăn cách giữa link m3u8 và link embed trong dữ liệu của mỗi tập
private const val LINK_SEP = "|||"

// Context của ứng dụng, dùng để hiện thông báo chẩn đoán
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
        // Mã hóa từ khóa để tìm được tiếng Việt có dấu và khoảng trắng
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/films/search?keyword=$q"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        return res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val slug = slug ?: return null // bỏ qua mục không có slug
        val poster = posterUrl ?: thumbUrl
        val yearInt = year?.toIntOrNull()
        return newMovieSearchResponse(title, "$mainUrl/api/film/$slug", TvType.Movie) {
            this.posterUrl = poster
            this.year = yearInt
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
                val epNum = epName.toIntOrNull()
                val embed = ep.embed
                val m3u8 = ep.m3u8
                if (!embed.isNullOrBlank() || !m3u8.isNullOrBlank()) {
                    episodes.add(
                        newEpisode("${m3u8.orEmpty()}$LINK_SEP${embed.orEmpty()}") {
                            this.name = "$serverName - Tập $epName"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        // Phim lẻ (chỉ có 1 tập): trả về dạng Movie cho khớp với danh sách
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

    private fun headOf(text: String?, n: Int = 70): String =
        text?.trimStart()?.take(n)?.replace(Regex("\\s+"), " ") ?: "null"

    // Hiện thông báo nổi để xem chẩn đoán khi không có công cụ xem log
    // (xóa các lệnh toast(...) khi phát hành chính thức)
    private fun toast(msg: String) {
        val c = appCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(c, msg.take(200), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Chép log vào clipboard để dán ra chat (không cần kịp đọc thông báo)
    private fun copyToClipboard(text: String) {
        val c = appCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PhimNguonC log", text))
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val log = StringBuilder()
        fun note(s: String) { log.append(s).append('\n') }
        fun finish(result: Boolean): Boolean {
            copyToClipboard(log.toString())
            toast("v9: kết quả=$result. Log đã chép vào clipboard, hãy dán vào chat")
            return result
        }

        val parts = data.split(LINK_SEP, limit = 2)
        val embed = (if (parts.size == 2) parts[1] else data).takeIf { it.isNotBlank() }
        val apiM3u8 = (if (parts.size == 2) parts[0] else "").takeIf { it.isNotBlank() }
        val embedRef = embed?.let { originOf(it) } ?: "$mainUrl/"
        note("v9 m3u8=${apiM3u8?.take(120)}")
        note("v9 embed=${embed?.take(120)}")

        // Chỉ coi là hợp lệ khi nội dung thật sự là playlist HLS
        suspend fun isHls(url: String): Boolean {
            val r = runCatching { app.get(url, referer = embedRef) }.getOrNull()
            note("isHls ${url.take(80)} code=${r?.code} nd=${headOf(r?.text, 40)}")
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

        // 1) m3u8 từ API: chỉ dùng nếu kiểm tra ra đúng HLS
        if (apiM3u8 != null && isHls(apiM3u8) && emit(apiM3u8)) return finish(true)
        if (embed == null) return finish(false)

        // 2) Gọi thẳng API của trang embed với Origin/Referer của phim.nguonc.com
        val hdr = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        val page = runCatching { app.get(embed, headers = hdr).text }.getOrNull()
        note("embed html len=${page?.length} head=${headOf(page, 60)}")
        val api = page?.let {
            Regex("\"api\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)?.replace("\\/", "/")
        }
        note("api=${api?.take(120)}")
        if (api != null) {
            val g = runCatching { app.get(api, headers = hdr) }.getOrNull()
            note("api GET code=${g?.code} body=${headOf(g?.text, 150)}")
            val p = runCatching { app.post(api, headers = hdr, data = emptyMap()) }.getOrNull()
            note("api POST code=${p?.code} body=${headOf(p?.text, 150)}")
            val found = listOfNotNull(g?.text, p?.text).firstNotNullOfOrNull { body ->
                Regex("https?:[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
                    .find(body.replace("\\/", "/"))?.value
            }
            note("m3u8 trong api=${found?.take(150)}")
            if (found != null && isHls(found) && emit(found)) return finish(true)
        }

        // 3) Dự phòng: mở trang embed trong WebView (giới hạn 25 giây)
        val sniffed = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                app.get(
                    embed,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8"""))
                ).url
            }
        }.getOrElse {
            note("webview lỗi ${it.javaClass.simpleName} ${it.message?.take(80)}")
            null
        }
        note("webview sniff=${sniffed?.take(150)}")
        if (sniffed != null && sniffed.contains(".m3u8") && isHls(sniffed) && emit(sniffed)) {
            return finish(true)
        }
        return finish(false)
    }

    private fun originOf(url: String): String {
        val m = Regex("""^(https?://[^/]+)""").find(url)
        return (m?.groupValues?.get(1) ?: mainUrl) + "/"
    }

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
