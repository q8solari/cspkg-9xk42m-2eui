package com.cimanow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class CimaNowProvider : MainAPI() {
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiBase = "$mainUrl/api/cloudstream"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "CimaNow",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "Series",
        "$mainUrl/category/movies/" to "Movies",
        "$mainUrl/category/series/" to "Series",
        "$mainUrl/episodes/" to "Episodes",
        "$mainUrl/watch/" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        runCatching {
            val home = app.get("$apiBase/home", headers = defaultHeaders).parsed<ApiHome>()
            val sections = home.sections.mapNotNull { section ->
                val items = section.items.mapNotNull { it.toSearchResponse() }
                if (items.isEmpty()) null else HomePageList(section.name, items)
            }
            if (sections.isNotEmpty()) return newHomePageResponse(sections)
        }.onFailure { log("API home failed: ${it.message}") }

        val doc = getDocument(if (page > 1) appendPage(request.data, page) else request.data)
        val sections = extractHomeSections(doc, request.name)
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        runCatching {
            val url = "$apiBase/search?q=${query.encodeUrl()}"
            return app.get(url, headers = defaultHeaders).parsed<ApiSearch>().results.mapNotNull { it.toSearchResponse() }
        }.onFailure { log("API search failed: ${it.message}") }

        val candidates = listOf(
            "$mainUrl/?s=${query.encodeUrl()}",
            "$mainUrl/search/${query.encodeUrl()}/",
            "$mainUrl/search?keyword=${query.encodeUrl()}"
        )
        return candidates.firstNotNullOfOrNull { url ->
            runCatching {
                parseCards(getDocument(url)).ifEmpty { null }
            }.getOrNull()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.startsWith(apiBase)) {
            runCatching { return loadFromApi(url) }.onFailure { log("API title failed: ${it.message}") }
        }

        val doc = getDocument(url)
        val title = cleanTitle(doc.selectFirst("h1, .Title, .title, .entry-title")?.text() ?: doc.title())
        val poster = getPoster(doc)
        val description = doc.selectFirst(".story, .description, .post-content, .entry-content, [itemprop=description]")
            ?.text()?.trim()
        val tags = doc.select("a[href*=/genre/], a[href*=/category/], .genres a, .terms a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = parseYear(doc.text())
        val watchUrl = findWatchUrl(doc, url)
        val episodes = extractEpisodes(doc)
        val isSeries = episodes.isNotEmpty() || doc.text().contains("حلقة") || doc.text().contains("مسلسل")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.ifEmpty {
                listOf(newEpisode(watchUrl ?: url) {
                    name = title
                    season = 1
                    episode = 1
                    posterUrl = poster
                })
            }) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("loadLinks page: $data")
        runCatching {
            val links = app.get("$apiBase/links?id=${data.encodeUrl()}", headers = defaultHeaders).parsed<ApiLinks>()
            links.subtitles.forEach { sub ->
                if (!sub.url.isNullOrBlank()) subtitleCallback(SubtitleFile(sub.lang ?: "Arabic", fixUrl(sub.url)))
            }
            links.links.forEach { addApiLink(it, data, subtitleCallback, callback) }
            if (links.links.isNotEmpty()) return true
        }.onFailure { log("API links failed: ${it.message}") }

        val doc = getDocument(data)
        val servers = dedupeServers(
            extractServerButtons(doc, data) + extractServerFromScripts(doc, data) + extractServersFromAjax(doc, data)
        )
        log("server buttons/scripts found: ${servers.size}")
        log("server names: ${servers.joinToString { it.name }}")

        var added = 0
        var failed = 0
        servers.forEach { server ->
            runCatching {
                val ok = addServer(server, data, subtitleCallback, callback)
                if (ok) added++ else failed++
                log("${server.name}: ${server.kind}${if (ok) " added" else " unsupported"}")
            }.onFailure {
                failed++
                log("${server.name}: failed ${it.message}")
            }
        }
        log("servers added: $added, failed/unsupported: $failed")
        return added > 0
    }

    private suspend fun loadFromApi(url: String): LoadResponse {
        val item = app.get(url, headers = defaultHeaders).parsed<ApiTitle>()
        val title = item.title ?: "CimaNow"
        val type = item.type?.toTvType() ?: TvType.Movie
        return if (type == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, item.episodes.mapNotNull { ep ->
                val data = ep.data ?: ep.url ?: return@mapNotNull null
                newEpisode(data) {
                    name = ep.name
                    season = ep.season ?: 1
                    episode = ep.episode
                    posterUrl = item.poster
                }
            }) {
                posterUrl = item.poster?.let { fixUrl(it) }
                plot = item.description
                year = item.year?.toIntOrNull()
                tags = item.genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, item.data ?: url) {
                posterUrl = item.poster?.let { fixUrl(it) }
                plot = item.description
                year = item.year?.toIntOrNull()
                tags = item.genres
            }
        }
    }

    private suspend fun getDocument(url: String): Document =
        app.get(url, headers = defaultHeaders).document

    private fun extractHomeSections(doc: Document, fallbackName: String = "CimaNow"): List<HomePageList> {
        val sections = doc.select("section, .section, .Block, .block, .widget, .MovieList, .Grid--WecimaPosts, .row, .container")
            .mapNotNull { section ->
                val name = section.selectFirst("h1, h2, h3, h4, .title, .Title, .BlockTitle, .section-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val items = parseCards(Jsoup.parse(section.outerHtml())).take(30)
                if (items.isEmpty()) null else HomePageList(name, items)
            }
        return sections.ifEmpty {
            parseCards(doc).take(40).let { if (it.isEmpty()) emptyList() else listOf(HomePageList(fallbackName, it)) }
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val selectors = listOf(
            ".movie a[href], .post a[href], .item a[href], .GridItem a[href], .Thumb--GridItem a[href]",
            ".MovieBlock a[href], .MovieItem a[href], .SeriesBlock a[href], .EpisodeBlock a[href]",
            ".poster a[href], .thumb a[href], .thumbnail a[href], .content-box a[href]",
            "article a[href], li a[href], div[class*=movie] a[href], div[class*=post] a[href], div[class*=item] a[href]",
            "a[href][title]",
            "a[href]"
        )
        return selectors.flatMap { doc.select(it) }
            .mapNotNull { cardToSearch(it) }
            .filterNot { isBadListingTitle(it.name) }
            .distinctBy { it.url }
    }

    private fun cardToSearch(element: Element): SearchResponse? {
        val href = getHref(element) ?: return null
        if (href.contains("#") || href.contains("javascript:")) return null
        if (!looksLikeTitleUrl(href)) return null
        val text = element.attr("title")
            .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { element.selectFirst(".title, .Title, h2, h3, h4")?.text().orEmpty() }
            .ifBlank { element.text() }
        val title = cleanTitle(text).takeIf { it.length > 1 } ?: return null
        val poster = element.selectFirst("img")?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("data-image") }
                .ifBlank { img.attr("src") }
        }?.let { fixUrlNull(it) }
        val type = if (title.contains("حلقة") || title.contains("مسلسل") || href.contains("series", true) || href.contains("episode", true)) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href) {
                posterUrl = poster
            }
        }
    }

    private fun extractEpisodes(doc: Document): List<Episode> =
        doc.select("a[href*='episode'], a[href*='حلقة'], .episodes a[href], .Episodes a[href], .ep a[href]")
            .mapNotNull { ep ->
                val href = getHref(ep) ?: return@mapNotNull null
                val text = ep.text().ifBlank { ep.attr("title") }
                newEpisode(href) {
                    name = cleanTitle(text)
                    season = parseSeasonNumber(text) ?: 1
                    episode = parseEpisodeNumber(text)
                    posterUrl = ep.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }, { it.name }))

    private fun findWatchUrl(doc: Document, fallback: String): String? =
        doc.select("a[href*='watch'], a[href*='مشاهدة'], a[href*='play'], a.btn, .watch a[href]")
            .firstNotNullOfOrNull { getHref(it) } ?: fallback

    private fun getPoster(doc: Document): String? =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: doc.selectFirst(".poster img, .Poster img, [itemprop=image], img")?.let {
                fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
            }

    private fun extractServerButtons(document: Document, pageUrl: String): List<Server> {
        val selectors = listOf(
            "iframe[src]",
            "a[href*='embed'], a[href*='player'], a[href*='watch'], a[href*='.m3u8'], a[href*='.mp4']",
            "[data-src], [data-url], [data-link], [data-embed], [onclick]"
        )
        return selectors.flatMap { document.select(it) }.mapNotNull { element ->
            val raw = listOf("src", "href", "data-src", "data-url", "data-link", "data-embed")
                .firstNotNullOfOrNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                ?: onclickUrl(element.attr("onclick")) ?: return@mapNotNull null
            val fixed = fixAgainst(raw, pageUrl) ?: return@mapNotNull null
            Server(normalizeServerName(element.text().ifBlank { hostName(fixed) }), fixed, detectKind(fixed))
        }
    }

    private fun extractServerFromScripts(document: Document, pageUrl: String): List<Server> =
        document.select("script").flatMap { script ->
            val data = script.data()
            Regex("""https?:\\/\\/[^"'\\\s]+|https?://[^"'\s]+""").findAll(data).mapNotNull { match ->
                val url = match.value.replace("\\/", "/")
                if (!looksLikeVideoServer(url)) null else Server(normalizeServerName(hostName(url)), fixAgainst(url, pageUrl) ?: url, detectKind(url))
            }.toList()
        }

    private suspend fun extractServersFromAjax(document: Document, pageUrl: String): List<Server> {
        val endpoints = document.select("[data-id], [data-post], [data-episode], [data-server]")
            .mapNotNull { it.attr("data-url").ifBlank { null } ?: it.attr("href").ifBlank { null } }
            .distinct()
        return endpoints.flatMap { endpoint ->
            runCatching {
                val url = fixAgainst(endpoint, pageUrl) ?: return@flatMap emptyList()
                val text = app.get(url, headers = defaultHeaders + ("X-Requested-With" to "XMLHttpRequest")).text
                val ajaxDoc = Jsoup.parse(text)
                extractServerButtons(ajaxDoc, pageUrl) + extractServerFromScripts(ajaxDoc, pageUrl)
            }.getOrElse {
                log("ajax endpoint failed $endpoint: ${it.message}")
                emptyList()
            }
        }
    }

    private suspend fun addServer(
        server: Server,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = when (server.kind) {
        LinkKind.M3U8, LinkKind.MP4 -> {
            addDirectVideoLink(server.url, server.name, detectQuality(server.url), mapOf("Referer" to referer), callback)
            true
        }
        LinkKind.IFRAME -> addExtractorLink(server.url, server.name, referer, subtitleCallback, callback)
        LinkKind.UNSUPPORTED -> false
    }

    private suspend fun addApiLink(
        link: ApiLink,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = link.url ?: return
        val quality = link.quality ?: detectQuality(url)
        val headers = link.headers ?: mapOf("Referer" to referer)
        when (link.type?.lowercase()) {
            "m3u8", "mp4" -> addDirectVideoLink(url, link.name ?: hostName(url), quality, headers, callback)
            else -> addExtractorLink(url, link.name ?: hostName(url), referer, subtitleCallback, callback)
        }
    }

    private suspend fun addDirectVideoLink(
        url: String,
        name: String,
        quality: Int,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixUrl(url),
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = headers["Referer"] ?: mainUrl
                this.quality = quality
            }
        )
    }

    private suspend fun addExtractorLink(
        url: String,
        name: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = loadExtractor(fixUrl(url), referer, subtitleCallback, callback)

    private fun dedupeServers(servers: List<Server>): List<Server> = servers.distinctBy { it.url }

    private fun cleanTitle(value: String): String =
        value.replace(Regex("""(?i)\b(watch|download|مشاهدة|تحميل)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')

    private fun parseQuality(value: String?): Int? =
        Regex("""(?i)(2160|1080|720|480|360|240)p?""").find(value.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private fun detectQuality(value: String?): Int = parseQuality(value) ?: Qualities.Unknown.value

    private fun parseYear(value: String?): Int? =
        Regex("""\b(19\d{2}|20\d{2})\b""").find(value.orEmpty())?.value?.toIntOrNull()

    private fun parseEpisodeNumber(value: String?): Int? =
        Regex("""(?i)(?:episode|ep|الحلقة|حلقة)\s*(\d+)""").find(value.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,3})\b""").find(value.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private fun parseSeasonNumber(value: String?): Int? =
        Regex("""(?i)(?:season|s|الموسم|موسم)\s*(\d+)""").find(value.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private fun getHref(element: Element): String? =
        element.attr("href").ifBlank { element.selectFirst("a[href]")?.attr("href").orEmpty() }
            .takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }

    private fun normalizeServerName(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }

    private fun detectKind(url: String): LinkKind = when {
        url.contains(".m3u8", true) -> LinkKind.M3U8
        url.contains(".mp4", true) -> LinkKind.MP4
        url.startsWith("http", true) -> LinkKind.IFRAME
        else -> LinkKind.UNSUPPORTED
    }

    private fun looksLikeVideoServer(url: String): Boolean =
        listOf(".m3u8", ".mp4", "embed", "player", "dood", "filemoon", "streamtape", "uqload", "mixdrop", "voe", "vid", "streamwish")
            .any { url.contains(it, true) }

    private fun onclickUrl(value: String): String? =
        Regex("""['"](https?://[^'"]+|/[^'"]+)['"]""").find(value)?.groupValues?.get(1)

    private fun fixAgainst(url: String, pageUrl: String): String? = runCatching {
        when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> URI(mainUrl).resolve(url).toString()
            else -> URI(pageUrl).resolve(url).toString()
        }
    }.getOrNull()

    private fun hostName(url: String): String = runCatching { URI(url).host?.removePrefix("www.") ?: "Server" }.getOrDefault("Server")

    private fun appendPage(url: String, page: Int): String =
        when {
            page <= 1 -> url
            url.contains("?") -> "$url&page=$page"
            url.endsWith("/") -> "${url}page/$page/"
            else -> "$url/page/$page/"
        }

    private fun looksLikeTitleUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(mainUrl) && !lower.startsWith("/")) return false
        if (isCategoryOrNavigationUrl(lower)) return false
        return listOf(
            "/watch", "/movie/", "/film/", "/series/", "/serie/", "/episode/",
            "مشاهدة", "فيلم", "مسلسل", "حلقة"
        ).any { lower.contains(it) }
    }

    private fun isCategoryOrNavigationUrl(url: String): Boolean {
        val path = runCatching { URI(url).path ?: url }.getOrDefault(url).trimEnd('/')
        val blockedExact = setOf(
            "",
            "/",
            "/movies",
            "/movie",
            "/films",
            "/film",
            "/series",
            "/serie",
            "/episodes",
            "/episode",
            "/watch",
            "/category",
            "/genre",
            "/quality",
            "/year"
        )
        if (path in blockedExact) return true
        return listOf(
            "/category/",
            "/genre/",
            "/quality/",
            "/year/",
            "/tag/",
            "/page/",
            "/search"
        ).any { path.startsWith(it) }
    }

    private fun isBadListingTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        if (normalized.length < 2 || normalized == "cimanow") return true
        return normalized in setOf(
            "المسلسلات",
            "مسلسلات",
            "الأفلام",
            "افلام",
            "أفلام",
            "movies",
            "series",
            "episodes",
            "latest",
            "cimanow"
        )
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun String.toTvType(): TvType = if (contains("series", true) || contains("tv", true)) TvType.TvSeries else TvType.Movie

    private fun ApiItem.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val fixed = url ?: return null
        val type = type?.toTvType() ?: TvType.Movie
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fixed) {
                posterUrl = poster?.let { fixUrl(it) }
            }
        } else {
            newMovieSearchResponse(title, fixed) {
                posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    private fun log(message: String) = println("[CimaNow] $message")

    private data class Server(val name: String, val url: String, val kind: LinkKind)
    private enum class LinkKind { M3U8, MP4, IFRAME, UNSUPPORTED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiHome(val sections: List<ApiSection> = emptyList())
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiSection(val name: String = "CimaNow", val items: List<ApiItem> = emptyList())
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiSearch(val results: List<ApiItem> = emptyList())
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiItem(
        val title: String? = null,
        val url: String? = null,
        val poster: String? = null,
        val type: String? = null,
        val year: String? = null,
        val quality: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiTitle(
        val title: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val year: String? = null,
        val genres: List<String> = emptyList(),
        val type: String? = null,
        val data: String? = null,
        val episodes: List<ApiEpisode> = emptyList()
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiEpisode(
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val data: String? = null,
        val url: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiLinks(val links: List<ApiLink> = emptyList(), val subtitles: List<ApiSubtitle> = emptyList())
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiLink(
        val name: String? = null,
        val url: String? = null,
        val quality: Int? = null,
        val type: String? = null,
        val headers: Map<String, String>? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiSubtitle(val lang: String? = null, val url: String? = null)
}
