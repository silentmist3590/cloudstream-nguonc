package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

private const val LINK_SEP = "|||"
private const val TAG = "PhimNguonCDebug"

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    companion object {
        lateinit var appContext: Context
    }

    override fun load(context: Context) {
        appContext = context
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

    private fun copyLogToClipboard(logText: String) {
        try {
            val clipboard = PhimNguonCPlugin.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PhimNguonC Error Log", logText)
            clipboard.setPrimaryClip(clip)
            runCatching {
                Toast.makeText(PhimNguonCPlugin.appContext, "Đã copy log lỗi vào clipboard!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không thể copy vào clipboard: ${e.message}")
        }
    }

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

    private suspend fun resolveEmbedPlaylist(embedUrl: String, refererUrl: String): String? {
        return runCatching {
            val uri = URI(embedUrl)
            val origin = "${uri.scheme}://${uri.host}"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to refererUrl,
                "Origin" to origin,
                "Content-Type" to "application/json"
            )

            val embedResp = app.get(embedUrl, headers = headers)
            var apiUrl = embedUrl
            val apiRegex = Regex("api\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']")
            apiRegex.find(embedResp.text)?.groupValues?.get(1)?.let {
                apiUrl = it
            }

            val bootPayload = """{"action":"bootstrap","mode":"json","frame_origins":["$mainUrl"]}"""
            val bootResp = app.post(
                apiUrl,
                headers = headers,
                requestBody = bootPayload.toRequestBody(mediaType)
            )

            val bootJson = jsonMapper.readTree(bootResp.text)
            val bootstrapToken = bootJson.get("bootstrap")?.asText() 
                ?: bootJson.get("token")?.asText() 
                ?: throw Exception("Không tìm thấy bootstrap token. Response: ${bootResp.text}")

            val issuePayload = """{"action":"issue","bootstrap":"$bootstrapToken","turnstile_response":null,"playlist_format":"hls","pretty_url":true,"path_chunks":true,"frame_origins":["$mainUrl"]}"""
            val issueResp = app.post(
                apiUrl,
                headers = headers,
                requestBody = issuePayload.toRequestBody(mediaType)
            )

            val issueJson = jsonMapper.readTree(issueResp.text)
            val playlist = issueJson.get("playlist")?.asText() 
                ?: issueJson.get("url")?.asText()
                ?: throw Exception("Không tìm thấy playlist trong issue response: ${issueResp.text}")

            playlist
        }.onFailure { e ->
            val errorMsg = "LỖI BẮT TAY EMBED: ${e.localizedMessage} | URL: $embedUrl"
            Log.e(TAG, errorMsg, e)
            copyLogToClipboard(errorMsg)
        }.getOrNull()
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

        if (m3u8.isNotBlank() && m3u8.startsWith("http") && !m3u8.contains("embed")) {
            success = runCatching {
                M3u8Helper.generateM3u8(name, m3u8, ref).forEach { link ->
                    callback(link)
                }
                true
            }.getOrDefault(false)

            if (!success) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} (M3U8)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = Qualities.P1080.value
                    }
                )
                success = true
            }
        }

        if (success) return true

        if (embed.isNotBlank() && embed.startsWith("http")) {
            val playlistUrl = resolveEmbedPlaylist(embed, ref)
            if (!playlistUrl.isNullOrBlank()) {
                val uri = URI(embed)
                val origin = "${uri.scheme}://${uri.host}"

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} (HLS)",
                        url = playlistUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embed
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to embed,
                            "Origin" to origin,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        )
                    }
                )
                return true
            }

            success = runCatching {
                loadExtractor(embed, ref, subtitleCallback, callback)
            }.onFailure { e ->
                val errorMsg = "LỖI LOAD EXTRACTOR: ${e.localizedMessage} | Embed: $embed"
                Log.e(TAG, errorMsg, e)
                copyLogToClipboard(errorMsg)
            }.getOrDefault(false)
        }

        if (!success) {
            copyLogToClipboard("KHÔNG TÌM THẤY LIÊN KẾT | Data payload: $data")
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
