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
        val url = "$mainUrl/api/films/search?keyword=$query"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        return res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = desc
            this.year = movie.year
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

        suspend fun emit(url: String, ref: String): Boolean {
            var ok = false
            runCatching {
                M3u8Helper.generateM3u8(name, url, ref).forEach {
                    callback(it)
                    ok = true
                }
            }
            if (!ok) {
                callback(
                    newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value
                    }
                )
                ok = true
            }
            return ok
        }

        // 1) Link m3u8 trực tiếp từ API (nếu có)
        if (apiM3u8 != null && emit(apiM3u8, embedRef)) return true

        if (embed != null) {
            // 2) Trang embed giấu link: mở trong WebView ẩn và bắt yêu cầu .m3u8
            val sniffed = runCatching {
                app.get(
                    embed,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8"""))
                ).url
            }.getOrNull()
            if (sniffed != null && sniffed.contains(".m3u8") && emit(sniffed, embedRef)) {
                return true
            }

            // 3) Tìm trực tiếp trong mã nguồn trang embed, chỉ nhận playlist hợp lệ
            val html = runCatching {
                app.get(embed, referer = "$mainUrl/").text.replace("\\/", "/")
            }.getOrNull()
            if (html != null) {
                val urls = Regex("""https?:[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                    .findAll(html).map { it.value }.distinct().toList()
                for (u in urls) {
                    val valid = runCatching {
                        app.get(u, referer = embedRef).text.trimStart().startsWith("#EXTM3U")
                    }.getOrDefault(false)
                    if (valid && emit(u, embedRef)) return true
                }
            }

            // 4) Dự phòng: để CloudStream tự tìm extractor phù hợp
            if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) return true
        }
        return false
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
