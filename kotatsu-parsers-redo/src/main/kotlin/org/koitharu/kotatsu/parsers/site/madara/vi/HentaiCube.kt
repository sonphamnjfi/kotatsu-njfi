package org.koitharu.kotatsu.parsers.site.madara.vi

import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.security.SecureRandom
import java.text.SimpleDateFormat

// Do not use "hentaicb.sbs" domain, may cause duplicate tags!
@MangaSourceParser("HENTAICUBE", "CBHentai", "vi", ContentType.HENTAI)
internal class HentaiCube(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAICUBE, "2tencb.pro") {

	override val configKeyDomain = ConfigKey.Domain("2tencb.pro", "hentaicube.xyz", "2tencb.net")

	override val datePattern = "dd/MM/yyyy"
	override val authorSearchSupported = true
	override val stylePage = ""
	override val selectAlt = ".post-content_item:contains(Alt) .summary-content, .post-content_item:contains(Tên khác) .summary-content"

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1

		val url = buildString {
			if (!filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				append("/tacgia/")
				append(filter.author.lowercase().replace(" ", "-"))

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				append("/?m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> {}
					SortOrder.RATING -> append("trending")
					SortOrder.RELEVANCE -> {}
					else -> append("latest") // default
				}
				return@buildString
			}

			append("https://")
			append(domain)

			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}

			append("/?s=")

			filter.query?.let { rawQuery ->
				val queryFixed = rawQuery
					.replace("–", "-")
					.replace("’", "'")
					.replace("“", "\"")
					.replace("”", "\"")
					.replace("…", "...")
				append(queryFixed.urlEncoded())
			}

			append("&post_type=wp-manga")

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					append("&genre[]=")
					append(it.key)
				}
			}

			filter.states.forEach {
				append("&status[]=")
				when (it) {
					MangaState.ONGOING -> append("on-going")
					MangaState.FINISHED -> append("end")
					MangaState.ABANDONED -> append("canceled")
					MangaState.PAUSED -> append("on-hold")
					MangaState.UPCOMING -> append("upcoming")
					else -> throw IllegalArgumentException("$it not supported")
				}
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(
					when (it) {
						ContentRating.SAFE -> "0"
						ContentRating.ADULT -> "1"
						else -> ""
					},
				)
			}

			if (filter.year != 0) {
				append("&release=")
				append(filter.year.toString())
			}

			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				SortOrder.RELEVANCE -> {}
				else -> {}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun createMangaTag(a: Element): MangaTag? {
		val allTags = availableTags.getOrNull().orEmpty()
		val title = a.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim to remove space
		// compare to avoid duplicate tags with the same title
		return allTags.find {
			it.title.trim().equals(title, ignoreCase = true) // try to search with trim
		}
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return fetchAllChapters(manga.url, doc)
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		return fetchAllChapters(mangaUrl, document)
	}

	/**
	 * Fetches paginated Madara `/ajax/chapters/?t=N` chapters directly against the resolved host
	 * (avoiding 301/302 POST->GET redirects), with fallback to `admin-ajax.php` and inline HTML.
	 */
	private suspend fun fetchAllChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val pageHttpUrl = document.location().toHttpUrlOrNull()
		val baseOrigin = pageHttpUrl?.let { "${it.scheme}://${it.host}" } ?: "https://$domain"
		val resolvedMangaPath = pageHttpUrl?.encodedPath?.takeIf { it.length > 1 }
			?: mangaUrl.toHttpUrlOrNull()?.encodedPath
			?: if (mangaUrl.startsWith('/')) mangaUrl else "/$mangaUrl"
		val mangaPageUrl = "$baseOrigin${resolvedMangaPath.removeSuffix('/')}/"
		val chaptersAjaxUrl = "${mangaPageUrl}ajax/chapters/"

		val xhrHeaders = getRequestHeaders().newBuilder()
			.set("X-Requested-With", "XMLHttpRequest")
			.set("Referer", mangaPageUrl)
			.build()

		val collected = ArrayList<Element>()
		val seenHrefs = HashSet<String>()
		var page = 1

		while (true) {
			if (page > 1) {
				delay(PAGINATION_DELAY_MS)
			}
			val pageDoc = try {
				webClient.httpPost("$chaptersAjaxUrl?t=$page".toHttpUrl(), emptyMap<String, String>(), xhrHeaders).parseHtml()
			} catch (_: NotFoundException) {
				break
			} catch (e: HttpStatusException) {
				if (page == 1 && e.statusCode == 400) {
					val mangaId = document.selectFirst("[id^=manga-chapters-holder]")?.attr("data-id")?.takeIf { it.isNotBlank() }
						?: document.selectFirst("input.rating-post-id")?.attr("value")?.takeIf { it.isNotBlank() }
						?: document.selectFirst("a[data-post]")?.attr("data-post")?.takeIf { it.isNotBlank() }
					if (!mangaId.isNullOrBlank()) {
						val legacyDoc = webClient.httpPost(
							"$baseOrigin/wp-admin/admin-ajax.php".toHttpUrl(),
							"action=manga_get_chapters&manga=$mangaId",
							xhrHeaders,
						).parseHtml()
						collected.addAll(legacyDoc.select(selectChapter))
					}
					break
				}
				if (collected.isNotEmpty()) break else throw e
			}

			val pageElements = pageDoc.select(selectChapter)
			if (pageElements.isEmpty()) break

			var added = 0
			for (li in pageElements) {
				val href = li.selectFirst("a")?.attrOrNull("href") ?: continue
				if (seenHrefs.add(href)) {
					collected.add(li)
					added++
				}
			}
			if (added == 0) break
			page++
		}

		val finalElements = if (collected.isNotEmpty()) collected else document.select(selectChapter)
		return parseChaptersFromElements(finalElements)
	}

	private fun parseChaptersFromElements(elements: List<Element>): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return elements.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst(selectDate)?.text()
				?: li.selectFirst("span.chapter-release-date")?.text()
			val name = a.selectFirst("p")?.textOrNull()
				?: a.ownText().ifBlank { a.text() }
			MangaChapter(
				id = generateUid(href),
				url = href + stylePage,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(dateFormat, dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val resolvedChapterUrl = doc.location().ifBlank { fullUrl }
		val resolvedOrigin = resolvedChapterUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://$domain"

		val msr3Element = doc.selectFirst(".msr-reader[data-msr-api]")
		if (msr3Element != null) {
			val mangaId = msr3Element.attr("data-msr-manga").toIntOrNull()
				?: throw ParseException("Manga ID not found in reader element", resolvedChapterUrl)
			val chapterId = msr3Element.attr("data-msr-chapter").toIntOrNull()
				?: throw ParseException("Chapter ID not found in reader element", resolvedChapterUrl)
			val apiEndpoint = msr3Element.attr("data-msr-api").ifEmpty { "/wp-json/manga-reader/v3/" }
			val baseApiUrl = if (apiEndpoint.startsWith("http")) {
				apiEndpoint.removeSuffix("/")
			} else {
				"$resolvedOrigin${if (apiEndpoint.startsWith("/")) "" else "/"}${apiEndpoint.removeSuffix("/")}"
			}

			val apiHeaders = getRequestHeaders().newBuilder()
				.set("Accept", "application/json")
				.set("X-MSR-Request", "1")
				.set("Referer", resolvedChapterUrl)
				.set("Origin", resolvedOrigin)
				.build()
			val payload = JSONObject()
				.put("manga", mangaId)
				.put("chapter", chapterId)
			val pagesUrl = "$baseApiUrl/pages".toHttpUrl()
			val request = Request.Builder()
				.url(pagesUrl)
				.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
				.headers(apiHeaders)
				.tag(MangaSource::class.java, source)
				.build()
			val response = context.httpClient.newCall(request).await()
			if (response.code == 401 || response.code == 403) {
				runCatching { response.close() }
				context.requestBrowserAction(this, resolvedChapterUrl)
			}
			if (!response.isSuccessful) {
				val code = response.code
				val message = response.message
				runCatching { response.close() }
				throw HttpStatusException(message, code, pagesUrl.toString())
			}
			val json = response.parseJson()
			val items = json.getJSONArray("items")
			return (0 until items.length()).map { i ->
				val url = items.getString(i)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		val masr2Element = doc.selectFirst(".masr2-reader[data-masr2-token]")
		if (masr2Element != null) {
			val token = masr2Element.attr("data-masr2-token")
			if (token.isEmpty()) {
				throw ParseException("Token not found in reader element", resolvedChapterUrl)
			}
			val clientId = generateClientId()
			val apiHeaders = getRequestHeaders().newBuilder()
				.set("Accept", "application/json")
				.set("Referer", resolvedChapterUrl)
				.build()
			val imageUrls = ArrayList<String>()
			var currentToken = token
			val seenTokens = HashSet<String>()

			while (seenTokens.add(currentToken)) {
				val apiUrl = "$resolvedOrigin$PAGES_V2_URL".toHttpUrl().newBuilder()
					.addQueryParameter("token", currentToken)
					.addQueryParameter("cid", clientId)
					.build()
				val request = Request.Builder()
					.get()
					.url(apiUrl)
					.headers(apiHeaders)
					.tag(MangaSource::class.java, source)
					.build()
				val response = context.httpClient.newCall(request).await()
				if (response.code == 401 || response.code == 403) {
					runCatching { response.close() }
					context.requestBrowserAction(this, resolvedChapterUrl)
				}
				if (!response.isSuccessful) {
					val code = response.code
					val message = response.message
					runCatching { response.close() }
					throw HttpStatusException(message, code, apiUrl.toString())
				}
				val data = response.parseJson()
				val items = data.optJSONArray("items")
				if (items != null) {
					for (i in 0 until items.length()) {
						imageUrls.add(items.getString(i))
					}
				}
				val done = data.optBoolean("done", true)
				val nextToken = if (!data.isNull("next_token")) {
					data.optString("next_token").takeIf { it.isNotEmpty() }
				} else {
					null
				}
				if (done || nextToken == null) break
				currentToken = nextToken
			}

			return imageUrls.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		if (doc.getElementById("chapter-protector-data") != null) {
			return super.getPages(chapter)
		}

		val root = doc.body().selectFirst("div.main-col-inner div.reading-content, div.reading-content")
			?: throw ParseException("Root not found", resolvedChapterUrl)
		return root.select("img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun generateClientId(): String {
		val bytes = ByteArray(16)
		SecureRandom().nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/the-loai-genres").parseHtml()
		val elements = doc.select("ul.list-unstyled li a")
		return elements.mapToSet { element ->
			val href = element.attr("href")
			val key = href.substringAfter("/theloai/").removeSuffix("/")
			val title = element.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}.toSet()
	}

	companion object {
		private const val PAGINATION_DELAY_MS = 250L
		private const val PAGES_V2_URL = "/wp-json/manga-reader/v2/pages"
	}
}
