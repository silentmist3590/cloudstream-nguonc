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
private const val KK_SEP = "|||"

// Context của ứng dụng, dùng để mở trình duyệt khi không có link phát được
private var kkAppCtx: Context? = null

@CloudstreamPlugin
class KkPhimPlugin : Plugin() {
    override fun load(context: Context) {
        kkAppCtx = context.applicationContext ?: context
        registerMainAPI(KkPhimProvider())
    }
}

class KkPhimProvider : MainAPI() {
    override var mainUrl = "https://kkphim.com"
    override var name = "KKPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // Địa chỉ gốc của API (theo tài liệu KKPhim)
    private val apiUrl = "https://phimapi.com"

    // Địa chỉ ảnh dự phòng khi API trả đường dẫn tương đối (CHƯA KIỂM TRA)
    private val defaultImageBase = "https://phimimg.com"

    // Hàng phim trên trang chủ: 3 phân loại cơ bản (giống NguonC) và 5 thể loại được chọn.
    // Muốn bớt hàng nào thì xóa dòng tương ứng.
    override val mainPage = mainPageOf(
        "/v1/api/danh-sach" to "Phim Mới Cập Nhật",
        "/v1/api/danh-sach/phim-bo" to "Phim Bộ",
        "/v1/api/danh-sach/phim-le" to "Phim Lẻ",
        "/v1/api/the-loai/tre-em" to "Phim Trẻ Em",
        "/v1/api/the-loai/chien-tranh" to "Phim Chiến Tranh",
        "/v1/api/the-loai/hai-huoc" to "Phim Hài Hước",
        "/v1/api/the-loai/hanh-dong" to "Phim Hành Động",
        "/v1/api/the-loai/phim-18" to "Phim 18+"
    )

    // Toàn bộ thể loại trên kkphim.com (slug suy từ tên hiển thị, chưa đối chiếu với /the-loai).
    // Dùng để lọc theo thể loại ngay trong ô tìm kiếm: gõ tên thể loại, ví dụ "hành động".
    private val genres = listOf(
        "Bí Ẩn" to "bi-an", "Chiến Tranh" to "chien-tranh", "Chính Kịch" to "chinh-kich",
        "Cổ Trang" to "co-trang", "Gia Đình" to "gia-dinh", "Hài Hước" to "hai-huoc",
        "Hành Động" to "hanh-dong", "Hình Sự" to "hinh-su", "Học Đường" to "hoc-duong",
        "Khoa Học" to "khoa-hoc", "Kinh Dị" to "kinh-di", "Kinh Điển" to "kinh-dien",
        "Lịch Sử" to "lich-su", "Miền Tây" to "mien-tay", "Phim 18+" to "phim-18",
        "Phim Ngắn" to "phim-ngan", "Phiêu Lưu" to "phieu-luu", "Thần Thoại" to "than-thoai",
        "Thể Thao" to "the-thao", "Trẻ Em" to "tre-em", "Tài Liệu" to "tai-lieu",
        "Tâm Lý" to "tam-ly", "Tình Cảm" to "tinh-cam", "Viễn Tưởng" to "vien-tuong",
        "Võ Thuật" to "vo-thuat", "Âm Nhạc" to "am-nhac"
    )

    // Bỏ dấu tiếng Việt, về chữ thường, chỉ giữ chữ và số
    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    // Địa chỉ trang web của phim (dùng cho nút mở trình duyệt). Dạng /phim/<slug> là suy đoán.
    private fun pageUrl(slug: String) = "$mainUrl/phim/$slug"

    private fun slugOf(url: String) =
        url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    // Ảnh có thể là chuỗi tuyệt đối, tương đối, hoặc đối tượng rỗng {}
    private fun fixImg(v: Any?, base: String?): String? {
        val s = (v as? String)?.takeIf { it.isNotBlank() } ?: return null
        if (s.startsWith("http")) return s
        val b = (base?.takeIf { it.isNotBlank() } ?: defaultImageBase).trimEnd('/')
        return b + "/" + s.trimStart('/')
    }

    private fun stripHtml(s: String?): String? =
        s?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun KkItem.toSearchResponse(base: String?): SearchResponse? {
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

    private fun KkListResponse.allItems(): List<KkItem> = data?.items ?: items ?: emptyList()
    private fun KkListResponse.imageBase(): String? = pathImage ?: data?.cdnImage
    private fun KkListResponse.pager(): KkPagination? = pagination ?: data?.params?.pagination

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Một số hàng đã có sẵn tham số (?sort_lang=...), nên chọn dấu nối phù hợp
        val sep = if (request.data.contains("?")) "&" else "?"
        val res = app.get("$apiUrl${request.data}${sep}page=$page&limit=24").parsedSafe<KkListResponse>()
        val base = res?.imageBase()
        val list = res?.allItems()?.mapNotNull { it.toSearchResponse(base) } ?: emptyList()
        val cur = res?.pager()?.currentPage ?: page
        val total = res?.pager()?.totalPages ?: 0
        return newHomePageResponse(request.name, list, hasNext = cur < total)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Gõ đúng tên thể loại (có hoặc không dấu, có thể thêm "thể loại" phía trước) thì lọc theo thể loại
        val key = norm(query).removePrefix("the loai ").trim()
        val genre = genres.firstOrNull { norm(it.first) == key || it.second.replace("-", " ") == key }
        if (genre != null) {
            val g = app.get("$apiUrl/v1/api/the-loai/${genre.second}?page=1&limit=64")
                .parsedSafe<KkListResponse>()
            val gBase = g?.imageBase()
            val byGenre = g?.allItems()?.mapNotNull { it.toSearchResponse(gBase) } ?: emptyList()
            if (byGenre.isNotEmpty()) return byGenre
        }

        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val res = app.get("$apiUrl/v1/api/tim-kiem?keyword=$q&limit=30").parsedSafe<KkListResponse>()
        val base = res?.imageBase()
        return res?.allItems()?.mapNotNull { it.toSearchResponse(base) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOf(url)
        if (slug.isBlank()) return null
        val res = app.get("$apiUrl/phim/$slug").parsedSafe<KkDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val webUrl = pageUrl(slug)
        val poster = fixImg(movie.posterUrl, res.pathImage) ?: fixImg(movie.thumbUrl, res.pathImage)
        val backdrop = fixImg(movie.thumbUrl, res.pathImage)
        val tags = movie.category?.mapNotNull { it.name }
        val duration = movie.time?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val cast = movie.actor?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")?.let { "Diễn viên: $it" }
        val dir = movie.director?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")?.let { "Đạo diễn: $it" }
        val plot = listOfNotNull(stripHtml(movie.content), cast, dir)
            .joinToString("\n\n").takeIf { it.isNotBlank() }

        val servers = res.episodes ?: emptyList()
        val multiServer = servers.size > 1
        val episodes = mutableListOf<Episode>()
        servers.forEach { server ->
            val serverName = server.serverName?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.isNotBlank() } ?: "Server"
            server.serverData?.forEach { ep ->
                val m3u8 = ep.linkM3u8
                val embed = ep.linkEmbed
                if (m3u8.isNullOrBlank() && embed.isNullOrBlank()) return@forEach
                val epName = ep.name ?: ep.filename ?: "1"
                val label = if (epName.all { it.isDigit() }) "Tập $epName" else epName
                episodes.add(
                    newEpisode("${m3u8.orEmpty()}$KK_SEP${embed.orEmpty()}$KK_SEP$webUrl") {
                        this.name = if (multiServer) "$serverName - $label" else label
                        this.episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                    }
                )
            }
        }

        val isSingle = movie.type == "single" || episodes.size <= 1
        val tvType = when {
            movie.type == "hoathinh" -> TvType.Anime
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
            }
        } else {
            newTvSeriesLoadResponse(title, webUrl, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = movie.year
                this.tags = tags
                this.duration = duration
            }
        }
    }

    private fun openInBrowser(url: String) {
        val c = kkAppCtx ?: return
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
        val parts = data.split(KK_SEP, limit = 3)
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

    // ---------- Mô hình dữ liệu (khai báo rộng rãi, trường nào không có sẽ là null) ----------
    data class KkTmdb(
        val type: String?
    )

    data class KkItem(
        val tmdb: KkTmdb?,
        val name: String?,
        @JsonProperty("origin_name") val originName: String?,
        val slug: String?,
        @JsonProperty("poster_url") val posterUrl: Any?,
        @JsonProperty("thumb_url") val thumbUrl: Any?,
        val year: Int?
    )

    data class KkPagination(
        val totalItems: Int?,
        val totalItemsPerPage: Int?,
        val currentPage: Int?,
        val totalPages: Int?
    )

    data class KkParams(
        val pagination: KkPagination?
    )

    data class KkListData(
        val items: List<KkItem>?,
        val params: KkParams?,
        @JsonProperty("APP_DOMAIN_CDN_IMAGE") val cdnImage: String?
    )

    data class KkListResponse(
        val status: Any?,
        val items: List<KkItem>?,
        val data: KkListData?,
        val pathImage: String?,
        val pagination: KkPagination?
    )

    data class KkNamed(
        val id: Any?,
        val name: String?,
        val slug: String?
    )

    data class KkMovie(
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
        val category: List<KkNamed>?,
        val country: List<KkNamed>?
    )

    data class KkEpisodeItem(
        val name: String?,
        val slug: String?,
        val filename: String?,
        @JsonProperty("link_embed") val linkEmbed: String?,
        @JsonProperty("link_m3u8") val linkM3u8: String?
    )

    data class KkServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("server_data") val serverData: List<KkEpisodeItem>?
    )

    data class KkDetailResponse(
        val status: Any?,
        val movie: KkMovie?,
        val episodes: List<KkServer>?,
        val pathImage: String?
    )
}
