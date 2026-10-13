package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load(context: Context) {
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

    // Địa chỉ trang web của phim (dùng cho nút mở trình duyệt trong CloudStream)
    private fun pageUrl(slug: String) = "$mainUrl/phim/$slug"

    // Lấy slug từ địa chỉ trang web hoặc địa chỉ API cũ (đã lưu trong lịch sử/yêu thích)
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
                // Chỉ giữ lại link m3u8 từ API; nếu trống thì tập vẫn hiện nhưng chưa phát được
                if (!ep.m3u8.isNullOrBlank() || !ep.embed.isNullOrBlank()) {
                    episodes.add(
                        newEpisode(ep.m3u8.orEmpty()) {
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

    // Chỉ phát khi API cung cấp một playlist HLS thật (nội dung bắt đầu bằng #EXTM3U).
    // Nếu không, trả về false để CloudStream báo "không tìm thấy liên kết" thay vì lỗi trình phát.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        val ref = "$mainUrl/"
        val body = runCatching { app.get(data, referer = ref).text }.getOrNull() ?: return false
        if (!body.trimStart().startsWith("#EXTM3U")) return false

        var ok = false
        runCatching {
            M3u8Helper.generateM3u8(name, data, ref).forEach {
                callback(it)
                ok = true
            }
        }
        return ok
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
