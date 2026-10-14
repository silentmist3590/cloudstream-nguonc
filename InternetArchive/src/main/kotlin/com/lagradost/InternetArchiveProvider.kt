package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class InternetArchivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(InternetArchiveProvider())
    }
}

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    // Chỉ dùng các bộ sưu tập phim thuộc phạm vi công cộng
    override val mainPage = mainPageOf(
        "collection:feature_films" to "Phim dài (Public Domain)",
        "collection:prelinger" to "Phim tư liệu Prelinger"
    )

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun searchUrl(query: String, page: Int): String =
        "$mainUrl/advancedsearch.php?q=${enc(query)}" +
            "&fl[]=identifier&fl[]=title&fl[]=year" +
            "&sort[]=${enc("downloads desc")}&rows=40&page=$page&output=json"

    private fun idOf(url: String) =
        url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    private fun IaDoc.toSearchResponse(): SearchResponse? {
        val id = identifier ?: return null
        val title = title ?: id
        val yearInt = year?.toString()?.take(4)?.toIntOrNull()
        return newMovieSearchResponse(title, "$mainUrl/details/$id", TvType.Movie) {
            this.posterUrl = "$mainUrl/services/img/$id"
            this.year = yearInt
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val q = "${request.data} AND mediatype:movies"
        val res = app.get(searchUrl(q, page)).parsedSafe<IaSearchResponse>()
        val docs = res?.response?.docs ?: emptyList()
        val items = docs.mapNotNull { it.toSearchResponse() }
        val total = res?.response?.numFound ?: 0
        val hasNext = page * 40 < total
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = "($query) AND mediatype:movies AND collection:(feature_films OR prelinger)"
        val res = app.get(searchUrl(q, 1)).parsedSafe<IaSearchResponse>()
        return res?.response?.docs?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun anyToText(v: Any?): String? = when (v) {
        null -> null
        is List<*> -> v.joinToString(" ")
        else -> v.toString()
    }?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()

    override suspend fun load(url: String): LoadResponse? {
        val id = idOf(url)
        if (id.isBlank()) return null
        val meta = app.get("$mainUrl/metadata/$id").parsedSafe<IaMetadataResponse>() ?: return null
        val title = anyToText(meta.metadata?.title) ?: id
        val plot = anyToText(meta.metadata?.description)
        val yearInt = anyToText(meta.metadata?.year)?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, "$mainUrl/details/$id", TvType.Movie, id) {
            this.posterUrl = "$mainUrl/services/img/$id"
            this.plot = plot
            this.year = yearInt
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data
        if (id.isBlank()) return false
        val meta = app.get("$mainUrl/metadata/$id").parsedSafe<IaMetadataResponse>() ?: return false
        val mp4s = meta.files?.filter { it.name?.endsWith(".mp4", ignoreCase = true) == true }
            ?: return false
        if (mp4s.isEmpty()) return false

        mp4s.forEach { f ->
            val fileName = f.name ?: return@forEach
            val fileUrl = "$mainUrl/download/$id/" + fileName.split("/").joinToString("/") { enc(it).replace("+", "%20") }
            callback(
                newExtractorLink(name, "$name - $fileName", fileUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    data class IaDoc(
        val identifier: String?,
        val title: String?,
        val year: Any?
    )

    data class IaResponseBody(
        val numFound: Int?,
        val docs: List<IaDoc>?
    )

    data class IaSearchResponse(
        val response: IaResponseBody?
    )

    data class IaMeta(
        val title: Any?,
        val description: Any?,
        val year: Any?
    )

    data class IaFile(
        val name: String?,
        val format: String?,
        @JsonProperty("size") val size: String?
    )

    data class IaMetadataResponse(
        val metadata: IaMeta?,
        val files: List<IaFile>?
    )
}
