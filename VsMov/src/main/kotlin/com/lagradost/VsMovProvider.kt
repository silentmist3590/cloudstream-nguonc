package com.lagradost

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

// Ngăn cách giữa m3u8 | embed | địa chỉ trang phim trong dữ liệu mỗi tập
private const val VS_SEP = "|||"

// Context của ứng dụng, dùng để mở trình duyệt khi không có link phát được
private var vsAppCtx: Context? = null

@CloudstreamPlugin
class VsMovPlugin : Plugin() {
    override fun load(context: Context) {
        vsAppCtx = context.applicationContext ?: context
        registerMainAPI(VsMovProvider())
    }
}

class VsMovProvider : MainAPI() {
    override var mainUrl = "https://vsmov.com"
    override var name = "VSMov"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val apiUrl = "$mainUrl/api"

    // Hai mục đầu có trong ví dụ của tài liệu API (đã kiểm tra).
    // Các mục sau chưa kiểm tra: nếu endpoint không tồn tại thì mục đó sẽ trống.
    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "subteam" to "Phim Subteam",
        "phim-bo" to "Phim Bộ",
        "phim-le" to "Phim Lẻ",
        "hoat-hinh" to "Hoạt Hình"
    )

    // Địa chỉ trang web của phim (dùng cho nút mở trình duyệt). Dạng /phim/<slug> là suy đoán.
    private fun pageUrl(slug: String) = "$mainUrl/phim/$slug"

    private fun slugOf(url: String) =
        url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    // Ảnh có thể là chuỗi (đường dẫn tuyệt đối/tương đối) hoặc đối tượng rỗng {}
    private fun fixImg(v: Any?, base: String?): String? {
        val s = (v as? String)?.takeIf { it.isNotBlank() } ?: return null
        return if (s.startsWith("http")) s else (base ?: "") + s.trimStart('/')
    }

    private fun stripHtml(s: String?): String? =
        s?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun VsItem.toSearchResponse(base: String?): SearchResponse? {
        val title = name ?: return null
        val slug = slug ?: return null
        val poster = fixImg(posterUrl, base) ?: fixImg(thumbUrl, base)
        val isSeries = tmdb?.type == "tv"
        return newMovieSearchResponse(
            title,
            pageUrl(slug),
            if (isSeries) TvType.TvSeries else TvType.Movie
        ) {
            this.posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$apiUrl/danh-sach/${request.data}?page=$page").parsedSafe<VsListResponse>()
        val items = (res?.items ?: res?.data?.items)
            ?.mapNotNull { it.toSearchResponse(res?.pathImage) } ?: emptyList()
        val cur = res?.pagination?.currentPage ?: page
        val total = res?.pagination?.totalPages ?: 0
        return newHomePageResponse(request.name, items, hasNext = cur < total)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val res = app.get("$apiUrl/tim-kiem?keyword=$q&limit=30").parsedSafe<VsListResponse>()
        return (res?.items ?: res?.data?.items)
            ?.mapNotNull { it.toSearchResponse(res?.pathImage) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOf(url)
        if (slug.isBlank()) return null
        val res = app.get("$apiUrl/phim/$slug").parsedSafe<VsDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val webUrl = pageUrl(slug)
        val base = res.pathImage
        val poster = fixImg(movie.posterUrl, base) ?: fixImg(movie.thumbUrl, base)
        val backdrop = fixImg(movie.thumbUrl, base)
        val plot = stripHtml(movie.content)
        val tags = movie.category?.mapNotNull { it.name }
        val duration = movie.time?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val servers = res.episodes ?: emptyList()
        val multiServer = servers.size > 1
        val episodes = mutableListOf<Episode>()
        servers.forEach { server ->
            val serverName = server.serverName ?: "Server"
            (server.serverData ?: server.items)?.forEach { ep ->
                val m3u8 = ep.linkM3u8 ?: ep.m3u8
                val embed = ep.linkEmbed ?: ep.embed
                if (m3u8.isNullOrBlank() && embed.isNullOrBlank()) return@forEach
                val epName = ep.name ?: ep.filename ?: "1"
                episodes.add(
                    newEpisode("${m3u8.orEmpty()}$VS_SEP${embed.orEmpty()}$VS_SEP$webUrl") {
                        this.name = if (multiServer) "$serverName - $epName" else epName
                        this.episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                    }
                )
            }
        }

        val isSingle = movie.type == "single" || episodes.size <= 1
        val tvType = when {
            movie.type == "hoathinh" -> TvType.Anime
            isSingle -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (isSingle) {
            newMovieLoadResponse(title, webUrl, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = movie.year
                this.tags = tags
                this.duration = duration
                addActors(movie.actor)
            }
        } else {
            newTvSeriesLoadResponse(title, webUrl, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = movie.year
                this.tags = tags
                this.duration = duration
                addActors(movie.actor)
            }
        }
    }

    private fun openInBrowser(url: String) {
        val c = vsAppCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    c.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
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
        val parts = data.split(VS_SEP, limit = 3)
        val m3u8 = parts.getOrNull(0).orEmpty()
        val embed = parts.getOrNull(1).orEmpty()
        val page = parts.getOrNull(2).orEmpty().ifBlank { mainUrl }
        val ref = "$mainUrl/"

        // 1) Link m3u8 từ API: chỉ dùng nếu là playlist HLS thật
        if (m3u8.startsWith("http")) {
            val body = runCatching { app.get(m3u8, referer = ref).text }.getOrNull()
            if (body?.trimStart()?.startsWith("#EXTM3U") == true) {
                var ok = false
                runCatching {
                    M3u8Helper.generateM3u8(name, m3u8, ref).forEach {
                        callback(it)
                        ok = true
                    }
                }
                if (ok) return true
            }
        }

        // 2) Link embed: chỉ nhờ các extractor có sẵn của CloudStream
        if (embed.startsWith("http")) {
            var found = false
            runCatching {
                loadExtractor(embed, ref, subtitleCallback) {
                    found = true
                    callback(it)
                }
            }
            if (found) return true
        }

        // 3) Không có link phát được: mở trang phim trên trình duyệt
        openInBrowser(page)
        return false
    }

    // ---------- Mô hình dữ liệu ----------
    data class VsTmdb(
        val type: String?,
        val id: String?
    )

    data class VsItem(
        val tmdb: VsTmdb?,
        val name: String?,
        @JsonProperty("origin_name") val originName: String?,
        val slug: String?,
        @JsonProperty("poster_url") val posterUrl: Any?,
        @JsonProperty("thumb_url") val thumbUrl: Any?,
        val year: Int?
    )

    data class VsPagination(
        val totalItems: Int?,
        val totalItemsPerPage: Int?,
        val currentPage: Int?,
        val totalPages: Int?
    )

    data class VsListData(
        val items: List<VsItem>?
    )

    data class VsListResponse(
        val status: Boolean?,
        val items: List<VsItem>?,
        val data: VsListData?,
        val pathImage: String?,
        val pagination: VsPagination?
    )

    data class VsNamed(
        val id: Int?,
        val name: String?,
        val slug: String?
    )

    data class VsMovie(
        val name: String?,
        @JsonProperty("origin_name") val originName: String?,
        val slug: String?,
        val content: String?,
        val type: String?,
        @JsonProperty("poster_url") val posterUrl: Any?,
        @JsonProperty("thumb_url") val thumbUrl: Any?,
        val time: String?,
        val year: Int?,
        val actor: List<String>?,
        val director: List<String>?,
        val category: List<VsNamed>?,
        val country: List<VsNamed>?
    )

    // Cấu trúc tập phim chưa kiểm tra (ví dụ trong tài liệu có episodes rỗng),
    // nên khai báo cả hai kiểu tên trường thường gặp
    data class VsEpisodeItem(
        val name: String?,
        val slug: String?,
        val filename: String?,
        @JsonProperty("link_embed") val linkEmbed: String?,
        @JsonProperty("link_m3u8") val linkM3u8: String?,
        val embed: String?,
        val m3u8: String?
    )

    data class VsServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("server_data") val serverData: List<VsEpisodeItem>?,
        val items: List<VsEpisodeItem>?
    )

    data class VsDetailResponse(
        val status: Boolean?,
        val movie: VsMovie?,
        val episodes: List<VsServer>?,
        val pathImage: String?
    )
}
