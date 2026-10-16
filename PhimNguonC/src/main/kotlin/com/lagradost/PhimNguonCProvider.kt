package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

private const val LINK_SEP = "|||"
private const val TAG = "PhimNguonC"

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load(context: android.content.Context) {
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
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "the-loai/hanh-dong" to "Phim Hành Động",
        "the-loai/phim-hai" to "Phim Hài",
        "the-loai/chien-tranh" to "Phim Chiến Tranh",
        "the-loai/lich-su" to "Phim Lịch Sử",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/phim-le" to "Phim Lẻ"
    )

    private val jsonMapper = jacksonObjectMapper()

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(LINK_SEP)
        val m3u8 = parts.getOrNull(0).orEmpty()
        val embed = parts.getOrNull(1).orEmpty()
        val webUrl = parts.getOrNull(2).orEmpty()
        val ref = if (webUrl.isNotBlank()) webUrl else "$mainUrl/"

        var success = false

        // 1. Xử lý trường m3u8 nếu có sẵn
        if (m3u8.isNotBlank()) {
            if (m3u8.contains(".m3u8") && !m3u8.contains("embed") && !m3u8.contains("player")) {
                success = runCatching {
                    M3u8Helper.generateM3u8(name, m3u8, ref).forEach { link ->
                        callback(link)
                    }
                    true
                }.getOrDefault(false)
            }
            if (!success) {
                success = runCatching {
                    loadExtractor(m3u8, ref, subtitleCallback, callback)
                }.getOrDefault(false)
            }
        }

        if (success) return true

        // 2. Xử lý trường embed và quét mã nguồn trang phát
        if (embed.isNotBlank()) {
            // Thử dùng loadExtractor trước
            success = runCatching {
                loadExtractor(embed, ref, subtitleCallback, callback)
            }.getOrDefault(false)

            if (success) return true

            // Nếu loadExtractor không bắt được, tải HTML trang embed và quét tìm link stream / m3u8 / mp4
            val embedHtml = runCatching {
                app.get(
                    embed,
                    headers = mapOf(
                        "Referer" to ref,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                ).text
            }.getOrNull()

            if (!embedHtml.isNullOrBlank()) {
                // Quét tìm các định dạng m3u8 hoặc mp4 trong mã nguồn HTML / Script
                val urlRegex = Regex("\"(https?://[^\"]+?\\.(?:m3u8|mp4)[^\"]*)\"|'(https?://[^']+?\\.(?:m3u8|mp4)[^']*)'")
                urlRegex.findAll(embedHtml).forEach { match ->
                    val link = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (link.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} (HLS)",
                                url = link,
                                type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.Video
                            ) {
                                this.referer = embed
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    }
                }

                // Quét tìm các biến cấu hình file/source trong JS
                val fileRegex = Regex("(?:file|url|source|src)\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']")
                fileRegex.findAll(embedHtml).forEach { match ->
                    val link = match.groupValues[1]
                    if (link.isNotBlank() && (link.contains("m3u8") || link.contains("mp4") || link.contains("manifest"))) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} (Stream)",
                                url = link,
                                type = if (link.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.Video
                            ) {
                                this.referer = embed
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    }
                }
            }
        }

        return success
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
