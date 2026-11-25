package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LXMANGA, 60) {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.top", "lxmanga.my")

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	@Volatile
	private var latestActionToken: String = ""

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val originalUrl = unwrapImageProxy(request.url)
		val fragment = originalUrl.fragment
		if (fragment != null && fragment.startsWith(TOKEN_FRAGMENT_PREFIX)) {
			val token = fragment.removePrefix(TOKEN_FRAGMENT_PREFIX).ifEmpty {
				latestActionToken.ifEmpty { "x" }
			}
			val cleanUrl = originalUrl.newBuilder().fragment(null).build()
			val newRequest = request.newBuilder()
				.url(cleanUrl)
				.header("Referer", "https://$domain/")
				.header("Origin", "https://$domain")
				.header("Token", token)
				.build()
			return chain.proceed(newRequest)
		}
		if (!originalUrl.host.equals(domain, ignoreCase = true) && request.header("Token") == null && latestActionToken.isNotEmpty()) {
			val newRequest = request.newBuilder()
				.url(originalUrl)
				.header("Referer", "https://$domain/")
				.header("Origin", "https://$domain")
				.header("Token", latestActionToken)
				.build()
			return chain.proceed(newRequest)
		}
		if (originalUrl != request.url) {
			return chain.proceed(request.newBuilder().url(originalUrl).build())
		}
		return chain.proceed(request)
	}

	private fun unwrapImageProxy(url: HttpUrl): HttpUrl = when (url.host) {
		"wsrv.nl" -> url.queryParameter("url")?.toHttpUrlOrNull() ?: url
		"v.recipes" -> url.toString()
			.substringAfter("https://v.recipes/i/", "")
			.takeIf(String::isNotEmpty)
			?.toHttpUrlOrNull()
			?: url
		else -> url
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sort = when (order) {
			SortOrder.POPULARITY -> "-views"
			SortOrder.UPDATED -> "-updated_at"
			SortOrder.NEWEST -> "-created_at"
			SortOrder.ALPHABETICAL -> "name"
			SortOrder.ALPHABETICAL_DESC -> "-name"
			else -> "-updated_at"
		}

		val url = "https://$domain/tim-kiem".toHttpUrl().newBuilder()
			.addQueryParameter("sort", sort)
			.addQueryParameter("page", page.toString())
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
					addQueryParameter("filter[name]", query)
				}
				val statusParam = if (filter.states.isNotEmpty()) {
					filter.states.mapNotNull { state ->
						when (state) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "paused"
							else -> null
						}
					}.joinToString(",")
				} else if (filter.query.isNullOrBlank() && filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) {
					"ongoing,completed,paused"
				} else {
					""
				}
				if (statusParam.isNotEmpty()) {
					addQueryParameter("filter[status]", statusParam)
				}
				if (filter.tags.isNotEmpty()) {
					addQueryParameter("filter[accept_genres]", filter.tags.joinToString(",") { it.key })
				}
				if (filter.tagsExclude.isNotEmpty()) {
					addQueryParameter("filter[reject_genres]", filter.tagsExclude.joinToString(",") { it.key })
				}
			}
			.build()

		val doc = webClient.httpGet(url).parseHtml()
		val cards = doc.select("div.manga-vertical").ifEmpty { doc.select("div.grid div.relative") }
		return cards.map { div ->
			val link = div.selectFirst("a.text-ellipsis[href^=/truyen/]")
				?: div.selectFirst("a[href^=/truyen/]")
				?: div.parseFailed("Không thể tìm thấy link truyện")
			val href = link.attrAsRelativeUrl("href")
			val title = link.textOrNull() ?: div.select("div.p-2 a.text-ellipsis").text()
			val coverUrl = div.selectFirst("div.cover")?.let { cover ->
				cover.attrAsAbsoluteUrlOrNull("data-bg") ?: parseBackgroundUrl(cover.attr("style"))
			}.orEmpty()

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = root.selectFirst("div.flex.flex-row.truncate.mb-4 span.grow.text-lg.ml-1.text-ellipsis.font-semibold")
			?.textOrNull()
			?: manga.title
		val coverUrl = root.selectFirst("div.md\\:col-span-2 div.cover-frame > div.cover")
			?.let { cover ->
				cover.attrAsAbsoluteUrlOrNull("data-bg") ?: parseBackgroundUrl(cover.attr("style"))
			}
			?: manga.coverUrl
		val authors = root.infoRow("Tác giả:")
			?.select("a[href*=/tac-gia/]")
			?.mapNotNullToSet { it.textOrNull() }
			?.ifEmpty { null }
			?: root.selectFirst("div.mt-2:contains(Tác giả) span a")?.textOrNull()?.let { setOf(it) }
			?: emptySet()
		val altTitles = root.infoRow("Tên khác:")
			?.select("a, span:not(.font-semibold)")
			?.mapNotNullToSet { it.textOrNull() }
			?.ifEmpty { null }
			?: root.selectFirst("div.grow div:contains(Tên khác)")
				?.select("span a")?.mapNotNullToSet { it.textOrNull() }
			?: emptySet()
		val tags = root.infoRow("Thể loại:")
			?.select("a[href*=/the-loai/]")
			?.mapNotNullToSet { a ->
				val key = a.attr("href").removeSuffix('/').substringAfterLast('/')
				if (key.isEmpty()) null else MangaTag(key = key, title = a.text(), source = source)
			}
			?.ifEmpty { null }
			?: root.selectFirst("div.mt-2:contains(Thể loại)")?.select("a.bg-gray-500")?.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			}
			?: emptySet()
		val statusText = (
			root.infoRow("Tình trạng:")?.text()
				?: root.selectFirst("div.mt-2:contains(Tình trạng) span.text-blue-500")?.text()
			)?.lowercase()
		val state = when {
			statusText == null -> null
			"đang tiến hành" in statusText -> MangaState.ONGOING
			"hoàn thành" in statusText || "completed" in statusText -> MangaState.FINISHED
			"tạm ngưng" in statusText || "paused" in statusText -> MangaState.PAUSED
			else -> null
		}
		val description = root.select("p:contains(Tóm tắt) ~ p")
			.mapNotNull { it.wholeText().trim().nullIfEmpty() }
			.joinToString("\n")
			.nullIfEmpty()
			?: root.selectFirst("meta[name=description]")?.attrOrNull("content")
		val scanlator = root.infoRow("Nhóm dịch:")
			?.select("a, span:not(.font-semibold)")
			?.textOrNull()
			?: root.selectFirst("div.mt-2:has(span:first-child:contains(Thực hiện:)) span:last-child")?.textOrNull()
		val chapterElements = root.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
			.ifEmpty { root.select("a[href^=/truyen/]:has(span.timeago)") }
			.ifEmpty { root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a") }

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			altTitles = altTitles,
			state = state,
			tags = tags,
			authors = authors,
			description = description,
			chapters = chapterElements.mapChapters(reversed = true) { i, a ->
				val href = a.attrAsRelativeUrl("href")
				val name = a.selectFirst("span.text-ellipsis")?.textOrNull() ?: a.text()
				val dateText = (a.selectFirst("span.timeago") ?: a.parent()?.selectFirst("span.timeago"))
					?.attr("datetime").orEmpty()
				MangaChapter(
					id = generateUid(href),
					title = name,
					number = (i + 1).toFloat(),
					volume = 0,
					url = href,
					scanlator = scanlator,
					uploadDate = parseChapterDate(dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		// /get_token can rotate the XSRF cookie, invalidating a stale CSRF (HTTP 419). Retry once cleanly.
		for (attempt in 0 until 2) {
			val chapterHtml = webClient.httpGet(fullUrl).parseRaw()
			val mfKey = extractMfKey(chapterHtml)
				?: throw ParseException("Không giải mã được viewer", fullUrl)
			val component = findViewerComponent(chapterHtml)
				?: throw ParseException("Không tìm thấy component ảnh", fullUrl)
			val mfKid = component.optJSONObject("serverMemo")
				?.optJSONObject("data")
				?.optString("manifestKid")
				?.takeIf { it.isNotEmpty() }
			val csrfToken = csrfTokenRegex.find(chapterHtml)?.groupValues?.get(1).orEmpty()

			val tokenResult = fetchActionToken(fullUrl, csrfToken, mfKey, mfKid)
			val actionToken = when (tokenResult) {
				is TokenFetchResult.Success -> tokenResult.token
				TokenFetchResult.CsrfRetry -> {
					if (attempt == 0) continue
					throw ParseException("CSRF token expired when fetching action token", fullUrl)
				}
				TokenFetchResult.BrowserRequired -> context.requestBrowserAction(this, fullUrl)
			}

			val manifestResult = fetchImageManifest(fullUrl, component, csrfToken, actionToken, mfKey)
			val imageUrls = when (manifestResult) {
				is ManifestFetchResult.Success -> manifestResult.urls
				ManifestFetchResult.CsrfRetry -> {
					if (attempt == 0) continue
					throw ParseException("CSRF token expired when unlocking images", fullUrl)
				}
				ManifestFetchResult.BrowserRequired -> context.requestBrowserAction(this, fullUrl)
			}

			if (imageUrls.isNotEmpty()) {
				return imageUrls.map { imageUrl ->
					val absoluteUrl = imageUrl.toAbsoluteUrl(domain)
					val pageUrl = absoluteUrl.toHttpUrlOrNull()
						?.newBuilder()
						?.fragment("$TOKEN_FRAGMENT_PREFIX$actionToken")
						?.build()
						?.toString()
						?: "$absoluteUrl#$TOKEN_FRAGMENT_PREFIX$actionToken"
					MangaPage(
						id = generateUid(imageUrl),
						url = pageUrl,
						preview = null,
						source = source,
					)
				}
			}
		}

		throw ParseException("Không tải được danh sách trang", fullUrl)
	}

	private sealed interface TokenFetchResult {
		data class Success(val token: String) : TokenFetchResult
		data object CsrfRetry : TokenFetchResult
		data object BrowserRequired : TokenFetchResult
	}

	private sealed interface ManifestFetchResult {
		data class Success(val urls: List<String>) : ManifestFetchResult
		data object CsrfRetry : ManifestFetchResult
		data object BrowserRequired : ManifestFetchResult
	}

	private suspend fun fetchActionToken(
		chapterUrl: String,
		csrfToken: String,
		mfKey: String,
		mfKid: String?,
	): TokenFetchResult {
		val tokenUrl = buildString {
			append("https://")
			append(domain)
			append("/get_token")
			if (!mfKid.isNullOrEmpty()) {
				append("?kid=")
				append(mfKid.urlEncoded())
			}
		}.toHttpUrl()

		val tokenHeaders = getRequestHeaders().newBuilder()
			.set("Accept", "application/json")
			.set("Content-Type", "application/json")
			.set("X-CSRF-TOKEN", csrfToken)
			.set("Referer", chapterUrl)
			.set("Origin", "https://$domain")
			.build()

		val request = Request.Builder()
			.get()
			.url(tokenUrl)
			.headers(tokenHeaders)
			.tag(MangaSource::class.java, source)
			.build()

		val response = context.httpClient.newCall(request).await()
		if (response.code == 419) {
			runCatching { response.close() }
			return TokenFetchResult.CsrfRetry
		}
		if (response.code == 401 || response.code == 403) {
			runCatching { response.close() }
			return TokenFetchResult.BrowserRequired
		}
		if (!response.isSuccessful) {
			val code = response.code
			val message = response.message
			runCatching { response.close() }
			throw HttpStatusException(message, code, tokenUrl.toString())
		}

		val data = response.parseJson()
		val actionToken = data.optString("action_token").takeIf { it.isNotEmpty() }
			?: data.optString("e").takeIf { it.isNotEmpty() }?.let { encrypted ->
				xorB64Decode(encrypted, mfKey)
					?.let { decoded -> runCatching { JSONObject(decoded).optString("at") }.getOrNull() }
					?.takeIf { it.isNotEmpty() }
			}
		if (actionToken != null) {
			latestActionToken = actionToken
			return TokenFetchResult.Success(actionToken)
		}
		return TokenFetchResult.BrowserRequired
	}

	private suspend fun fetchImageManifest(
		chapterUrl: String,
		component: JSONObject,
		csrfToken: String,
		actionToken: String,
		mfKey: String,
	): ManifestFetchResult {
		val randomId = buildString(7) {
			repeat(7) { append(RANDOM_ID_CHARS[Random.nextInt(RANDOM_ID_CHARS.length)]) }
		}
		val messageJson = JSONObject()
			.put("fingerprint", component.optJSONObject("fingerprint") ?: JSONObject())
			.put("serverMemo", component.optJSONObject("serverMemo") ?: JSONObject())
			.put(
				"updates",
				JSONArray().put(
					JSONObject()
						.put("type", "fireEvent")
						.put(
							"payload",
							JSONObject()
								.put("id", randomId)
								.put("event", "lxViewerImagesUnlock")
								.put("params", JSONArray().put(actionToken)),
						),
				),
			)

		val manifestHeaders = getRequestHeaders().newBuilder()
			.set("Accept", "text/html, application/xhtml+xml")
			.set("X-Livewire", "true")
			.set("X-CSRF-TOKEN", csrfToken)
			.set("Referer", chapterUrl)
			.set("Origin", "https://$domain")
			.build()

		val endpoint = "https://$domain/livewire/message/chapter-viewer-images".toHttpUrl()
		val request = Request.Builder()
			.post(messageJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
			.url(endpoint)
			.headers(manifestHeaders)
			.tag(MangaSource::class.java, source)
			.build()

		val response = context.httpClient.newCall(request).await()
		if (response.code == 419) {
			runCatching { response.close() }
			return ManifestFetchResult.CsrfRetry
		}
		if (response.code == 401 || response.code == 403) {
			runCatching { response.close() }
			return ManifestFetchResult.BrowserRequired
		}
		if (!response.isSuccessful) {
			val code = response.code
			val message = response.message
			runCatching { response.close() }
			throw HttpStatusException(message, code, endpoint.toString())
		}

		val responseJson = response.parseJson()
		val dispatches = responseJson.optJSONObject("effects")?.optJSONArray("dispatches")
			?: return ManifestFetchResult.Success(emptyList())
		var dispatchData: JSONObject? = null
		for (i in 0 until dispatches.length()) {
			val dispatch = dispatches.optJSONObject(i) ?: continue
			if (dispatch.optString("event") == "lx:srcs") {
				dispatchData = dispatch.optJSONObject("data")
				if (dispatchData != null) break
			}
		}
		if (dispatchData == null) {
			return ManifestFetchResult.Success(emptyList())
		}

		val encodedData = dispatchData.optString("data").takeIf { it.isNotEmpty() }
		val u: JSONArray
		val f: JSONArray?
		if (encodedData != null) {
			val decodedManifest = xorB64Decode(encodedData, mfKey)
				?: return ManifestFetchResult.Success(emptyList())
			val manifestJson = runCatching { JSONObject(decodedManifest) }.getOrNull()
				?: return ManifestFetchResult.Success(emptyList())
			u = manifestJson.optJSONArray("u") ?: return ManifestFetchResult.Success(emptyList())
			f = manifestJson.optJSONArray("f") ?: dispatchData.optJSONArray("flags")
		} else {
			u = dispatchData.optJSONArray("urls") ?: return ManifestFetchResult.Success(emptyList())
			f = dispatchData.optJSONArray("flags")
		}

		val urls = ArrayList<String>(u.length())
		for (i in 0 until u.length()) {
			val flag = f?.optInt(i, 0) ?: 0
			if ((flag and 1) == 0) {
				val url = u.optString(i).trim()
				if (url.isNotEmpty() && !url.contains("/imgs/gifs/", ignoreCase = true)) {
					urls.add(url)
				}
			}
		}
		return ManifestFetchResult.Success(urls)
	}

	private fun Document.infoRow(label: String): Element? = select("div")
		.firstOrNull { row -> row.selectFirst("> span.font-semibold")?.text() == label }

	private fun findViewerComponent(html: String): JSONObject? = wireInitialDataRegex.findAll(html)
		.mapNotNull { match ->
			runCatching {
				val unescaped = Parser.unescapeEntities(match.groupValues[1], false)
				JSONObject(unescaped)
			}.getOrNull()
		}
		.firstOrNull { obj ->
			obj.optJSONObject("fingerprint")?.optString("name") == "chapter-viewer-images"
		}

	private fun extractMfKey(html: String): String? {
		var candidates = listOf(html)
		repeat(4) {
			val next = ArrayList<String>()
			for (text in candidates) {
				mfKeyRegex.find(text)?.let { return it.groupValues[1].replace("\\/", "/") }
				next.addAll(decodeHexPayloads(text))
			}
			if (next.isEmpty()) return null
			candidates = next
		}
		return null
	}

	private fun decodeHexPayloads(text: String): List<String> {
		val layers = ArrayList<String>()
		var offset = 0
		while (true) {
			val start = arrayStartRegex.find(text, offset)?.let { it.range.last + 1 } ?: break
			val end = text.indexOf(']', start)
			if (end < 0) break
			offset = end + 1
			val hex = extractHex(text, start, end) ?: continue
			decodeHexPayload(hex)?.let(layers::add)
		}
		return layers
	}

	private fun extractHex(text: String, start: Int, end: Int): String? {
		val firstQuote = text.indexOf('"', start)
		if (firstQuote < 0 || firstQuote >= end) return null
		val secondQuote = text.indexOf('"', firstQuote + 1)
		if (secondQuote < 0 || secondQuote >= end) return null
		if (secondQuote - firstQuote - 1 > 512) return null

		val hex = StringBuilder(end - start)
		for (i in start until end) {
			when (text[i]) {
				in '0'..'9', in 'a'..'f', in 'A'..'F' -> hex.append(text[i])
				'"', ',', ' ', '\t', '\n', '\r' -> Unit
				else -> return null
			}
		}
		return hex.toString().takeIf { it.length >= 66 }
	}

	private fun decodeHexPayload(hex: String): String? {
		if (hex.length < 66 || hex.length % 2 != 0) return null
		val bytes = decodeHex(hex) ?: return null
		val out = ByteArray(bytes.size - 32)
		for (i in out.indices) {
			out[i] = (bytes[i + 32].toInt() xor bytes[i % 32].toInt()).toByte()
		}
		return String(out, Charsets.UTF_8).takeIf { it.isNotBlank() }
	}

	private fun decodeHex(hex: String): ByteArray? {
		if (hex.length % 2 != 0) return null
		val out = ByteArray(hex.length / 2)
		for (i in out.indices) {
			val hi = Character.digit(hex[i * 2], 16)
			val lo = Character.digit(hex[i * 2 + 1], 16)
			if (hi < 0 || lo < 0) return null
			out[i] = ((hi shl 4) or lo).toByte()
		}
		return out
	}

	private fun xorB64Decode(payload: String, key: String): String? = runCatching {
		if (key.isEmpty()) return null
		val bytes = context.decodeBase64(payload.filterNot { it.isWhitespace() })
		val chars = CharArray(bytes.size) { i ->
			((bytes[i].toInt() and 0xff) xor key[i % key.length].code).toChar()
		}
		String(chars)
	}.getOrNull()

	private fun parseBackgroundUrl(styleValue: String?): String? {
		if (styleValue.isNullOrBlank()) return null
		val rawUrl = backgroundUrlRegex.find(styleValue)?.groupValues?.getOrNull(1)?.trim() ?: return null
		return rawUrl.toAbsoluteUrl(domain)
	}

	private fun parseChapterDate(dateStr: String): Long {
		val cleaned = dateStr.trim()
		if (cleaned.isEmpty()) return 0L
		val tz = TimeZone.getTimeZone("GMT+7")
		for (pattern in DATE_PATTERNS) {
			val parsed = runCatching {
				SimpleDateFormat(pattern, Locale.ROOT).apply {
					timeZone = tz
				}.parse(cleaned)?.time
			}.getOrNull()
			if (parsed != null) return parsed
		}
		return 0L
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		val fromSearch = doc.select("label").mapNotNullToSet { label ->
			val click = label.attr("@click").ifEmpty { label.attr("x-on:click") }
			val slug = click.findGroupValue(genreSlugRegex) ?: return@mapNotNullToSet null
			val title = label.textOrNull() ?: return@mapNotNullToSet null
			MangaTag(
				key = slug,
				title = title,
				source = source,
			)
		}
		if (fromSearch.isNotEmpty()) return fromSearch

		val fallbackDoc = webClient.httpGet("https://$domain/the-loai").parseHtml()
		return fallbackDoc.select("nav.grid button").mapNotNullToSet { button ->
			val key = button.attr("wire:click")
				.substringAfterLast(", '", "")
				.substringBeforeLast("')", "")
				.ifEmpty { return@mapNotNullToSet null }
			val title = button.select("span.text-ellipsis").textOrNull()
				?: button.textOrNull()
				?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	companion object {
		private const val TOKEN_FRAGMENT_PREFIX = "lxtoken="
		private const val RANDOM_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
		private val DATE_PATTERNS = arrayOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
			"yyyy-MM-dd'T'HH:mm:ssXXX",
			"yyyy-MM-dd HH:mm:ss",
		)
		private val csrfTokenRegex = Regex("""var\s+csrf_token\s*=\s*'([^']+)'""")
		private val wireInitialDataRegex = Regex("""wire:initial-data="((?:[^"\\]|\\.)*)"""")
		private val mfKeyRegex = Regex("""__lxMfkey\s*=\s*"([^"]+)"""")
		private val arrayStartRegex = Regex("""var\s+_\w+\s*=\s*\[""")
		private val backgroundUrlRegex = Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
		private val genreSlugRegex = Regex("""toggleGenre\('([^']+)'\)""")
	}
}
