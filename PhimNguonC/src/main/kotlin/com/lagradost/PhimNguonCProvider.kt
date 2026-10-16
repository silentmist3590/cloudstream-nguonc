package com.lagradost

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

// Dấu phân cách lưu thông tin tập: m3u8|||embed|||webUrl
private const val LINK_SEP = "|||"

// Context ứng dụng dùng cho WebView và mở trình duyệt
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

    private fun pageUrl(slug: String) = "$mainUrl/phim/$slug"

    private fun slugOf(url: String) =
        url.trimEnd('/').substringAfterLast('/').substringBefore('?')

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
        val yearInt = year?.toIntOrNull()
        return newMovieSearchResponse(title, pageUrl(slug), TvType.Movie) {
            this.posterUrl = poster
            this.year = yearInt
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOf(url)
        if (slug.isBlank()) return null
        val res = app.get("$mainUrl/api/film/$slug").parsedSafe<NguonCDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val poster = movie.posterUrl ?: movie.thumbUrl
        val desc = movie.description
        val webUrl = pageUrl(slug)
        val episodes = mutableListOf<Episode>()

        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "NguonC"
            server.items?.forEach { ep ->
                val epName = ep.name ?: "1"
                val epNum = epName.toIntOrNull()
                val m3u8 = ep.m3u8.orEmpty()
                val embed = ep.embed.orEmpty()

                if (m3u8.isNotBlank() || embed.isNotBlank()) {
                    // Lưu data theo cấu trúc: m3u8|||embed|||webUrl
                    val dataPayload = "$m3u8$LINK_SEP$embed$LINK_SEP$webUrl"
                    episodes.add(
                        newEpisode(dataPayload) {
                            this.name = "$serverName - Tập $epName"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        if (episodes.size == 1) {
            return newMovieLoadResponse(title, webUrl, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = desc
                this.year = movie.year
            }
        }

        return newTvSeriesLoadResponse(title, webUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = desc
            this.year = movie.year
        }
    }

    private fun openInBrowser(url: String) {
        val c = appCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(intent)
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
        val parts = data.split(LINK_SEP)
        val m3u8 = parts.getOrNull(0).orEmpty()
        val embed = parts.getOrNull(1).orEmpty()
        val pageUrl = parts.getOrNull(2).orEmpty().ifBlank { mainUrl }
        val ref = "$mainUrl/"

        var streamFound = false

        // BƯỚC 1: Nếu có link m3u8 trực tiếp, thử tải với header chuẩn
        if (m3u8.startsWith("http")) {
            val response = runCatching {
                app.get(
                    m3u8,
                    headers = mapOf(
                        "Referer" to ref,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                )
            }.getOrNull()

            val body = response?.text?.trimStart()
            if (body?.startsWith("#EXTM3U") == true) {
                runCatching {
                    M3u8Helper.generateM3u8(name, m3u8, ref).forEach {
                        callback(it)
                        streamFound = true
                    }
                }
                if (streamFound) return true
            }
        }

        // BƯỚC 2: Thử Extractor mặc định của Cloudstream nếu embed là các host phổ biến
        if (embed.startsWith("http")) {
            val extracted = runCatching {
                loadExtractor(embed, ref, subtitleCallback, callback)
            }.getOrDefault(false)

            if (extracted) return true
        }

        // BƯỚC 3: Dùng WebView ngầm bọc iframe + JS Hook WebCrypto
        val context = appCtx
        if (!streamFound && embed.startsWith("http") && context != null) {
            val extractedData = NguonCHelper.extractM3u8FromEmbed(context, embed, pageUrl)

            if (!extractedData.isNullOrBlank()) {
                // Trường hợp 1: WebView bắt được trực tiếp URL .m3u8
                if (extractedData.startsWith("http")) {
                    runCatching {
                        M3u8Helper.generateM3u8(name, extractedData, ref).forEach {
                            callback(it)
                            streamFound = true
                        }
                    }
                    if (streamFound) return true
                }

                // Trường hợp 2: Dữ liệu là nội dung text của file M3U8 sau khi decrypt
                if (extractedData.contains("#EXTM3U")) {
                    // Tìm xem bên trong có URL sub-stream .m3u8 khác không
                    val subStreamRegex = Regex("""https?://[^\s"]+\.m3u8[^\s"]*""")
                    val matchUrl = subStreamRegex.find(extractedData)?.value

                    if (matchUrl != null) {
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "${this.name} HLS",
                                url = matchUrl,
                                referer = ref,
                                quality = Qualities.P1080.value,
                                isM3u8 = true
                            )
                        )
                        return true
                    }

                    // Nếu là Playlist chứa trực tiếp các chunk, chuyển sang Data URI
                    val base64M3u8 = android.util.Base64.encodeToString(
                        extractedData.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    val dataUrl = "data:application/vnd.apple.mpegurl;base64,$base64M3u8"

                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} Decrypted Stream",
                            url = dataUrl,
                            referer = ref,
                            quality = Qualities.P1080.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
        }

        // BƯỚC 4: Fallback cuối cùng nếu không giải mã được
        openInBrowser(pageUrl)
        return false
    }

    // Các data model phản hồi từ API NguonC
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
