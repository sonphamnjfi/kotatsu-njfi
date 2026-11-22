package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.io.InterruptedIOException
import java.util.*
import java.util.concurrent.TimeUnit

@MangaSourceParser("KURONEKO", "Kuro Neko / vi-Hentai", "vi", type = ContentType.HENTAI)
internal class KuroNeko(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.KURONEKO, 30) {

	override val configKeyDomain = ConfigKey.Domain("vi-hentai.moe", "vi-hentai.org")

	private val pagesRequestMutex = Mutex()
	private var lastPagesRequestTime = 0L

	private val rateLimitLock = Any()
	private var nextDomainRequestAt = 0L
	private var nextImageRequestAt = 0L

	@Volatile
	private var cooldownUntilMs = 0L

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val isDomainDocument = request.url.host.equals(domain, ignoreCase = true) &&
			!IMAGE_EXT_REGEX.containsMatchIn(request.url.encodedPath)

		for (attempt in 0..MAX_RATE_LIMIT_RETRIES) {
			acquireRateLimitSlot(isDomainDocument)
			val response = chain.proceed(request)
			val isRateLimited = response.code == 429 || (
				isDomainDocument &&
					response.code in 200..599 &&
					response.headersContentLength().let { it < 0L || it <= 16384L } &&
					response.mimeType?.startsWith("text/") == true &&
					runCatching { response.peekBody(8192).string() }.getOrDefault("").let { body ->
						body.contains("quá nhanh", ignoreCase = true) ||
							body.contains("Too Many Requests", ignoreCase = true)
					}
				)
			if (!isRateLimited || attempt == MAX_RATE_LIMIT_RETRIES) {
				return response
			}
			val retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
			val retryDelayMs = if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
				TimeUnit.SECONDS.toMillis(retryAfterSeconds).coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
			} else {
				(BASE_RETRY_DELAY_MS shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
			}
			synchronized(rateLimitLock) {
				cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + retryDelayMs)
			}
			runCatching { response.close() }
		}
		return chain.proceed(request)
	}

	private fun acquireRateLimitSlot(isDomainDocument: Boolean) {
		val waitUntilCooldown = cooldownUntilMs - System.currentTimeMillis()
		sleepSafely(waitUntilCooldown)
		val waitMs = synchronized(rateLimitLock) {
			val now = System.currentTimeMillis()
			val minInterval = if (isDomainDocument) DOMAIN_MIN_INTERVAL_MS else IMAGE_MIN_INTERVAL_MS
			val lastScheduled = if (isDomainDocument) nextDomainRequestAt else nextImageRequestAt
			val scheduled = maxOf(now, cooldownUntilMs, lastScheduled)
			if (isDomainDocument) {
				nextDomainRequestAt = scheduled + minInterval
			} else {
				nextImageRequestAt = scheduled + minInterval
			}
			scheduled - now
		}
		sleepSafely(waitMs)
	}

	private fun sleepSafely(ms: Long) {
		if (ms <= 0L) return
		try {
			Thread.sleep(ms)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw InterruptedIOException("Rate-limit wait interrupted").apply { initCause(e) }
		}
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
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			if (!filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)

				append("/tac-gia/")
				append(filter.author.lowercase().replace(" ", "-"))

				append("?sort=")
				append(
					when (order) {
						SortOrder.POPULARITY -> "-views"
						SortOrder.UPDATED -> "-updated_at"
						SortOrder.NEWEST -> "-created_at"
						SortOrder.ALPHABETICAL -> "name"
						SortOrder.ALPHABETICAL_DESC -> "-name"
						else -> "-updated_at"
					},
				)

				append("&page=")
				append(page)

				append("&filter[status]=")
				filter.states.forEach {
					append(
						when (it) {
							MangaState.ONGOING -> "2,"
							MangaState.FINISHED -> "1,"
							else -> "2,1"
						},
					)
				}

				return@buildString // end of buildString
			}

			append("https://")
			append(domain)

			append("/tim-kiem")
			append("?sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "-views"
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at"
				},
			)

			if (!filter.query.isNullOrEmpty()) {
				append("&keyword=")
				append(filter.query.urlEncoded())
			}

			if (page > 1) {
				append("&page=")
				append(page)
			}

			append("&filter[status]=")
			filter.states.forEach {
				append(
					when (it) {
						MangaState.ONGOING -> "2,"
						MangaState.FINISHED -> "1,"
						else -> "2,1"
					},
				)
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				filter.tags.joinTo(this, separator = ",") { it.key }
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				filter.tagsExclude.joinTo(this, separator = ",") { it.key }
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		val cards = doc.select("div.grid div.relative").ifEmpty { doc.select("div.manga-vertical") }
		return cards.map { div ->
			val href = div.selectFirst("a[href^=/truyen/]")?.attrOrNull("href")
				?: div.parseFailed("Không thể tìm thấy nguồn ảnh của Manga này!")
			val style = div.selectFirst("div.cover")?.attr("style").orEmpty()
			val coverUrl = BACKGROUND_IMAGE_REGEX.find(style)?.groupValues?.getOrNull(1)
				?.ifBlank { null }
				?: style.substringAfter("url('", "").substringBefore("')", "").ifBlank { null }
			val title = div.select("div.p-2 a.text-ellipsis").text()
				.ifBlank { div.select("div.p-2 a").text() }

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl.orEmpty(),
				tags = setOf(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = root.selectFirst("div.mt-2:contains(Tác giả) span a")?.textOrNull()
		val scanlator = root.selectFirst("div.mt-2:contains(Nhóm dịch) span a")?.textOrNull()
		val chapterElements = root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a")
			.ifEmpty { root.select("ul.overflow-y-auto > li a[href*=/truyen/]") }

		return manga.copy(
			altTitles = setOfNotNull(root.selectLast("div.grow div:contains(Tên khác) span")?.textOrNull()),
			state = when (root.selectFirst("div.mt-2:contains(Tình trạng) span.text-blue-500")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Đã hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			tags = root.select("div.mt-2:contains(Thể loại) a.bg-gray-500").mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = root.selectFirst("meta[name=description]")?.attrOrNull("content"),
			chapters = chapterElements.mapChapters(reversed = true) { i, a ->
				val href = a.attrAsRelativeUrl("href")
				val name = a.selectFirst("span.text-ellipsis, span.truncate")?.textOrNull() ?: a.text()
				val dateText = a.parent()?.selectFirst("span.timeago")?.attr("datetime").orEmpty()
				MangaChapter(
					id = generateUid(href),
					title = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = scanlator,
					uploadDate = parseDateTime(dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		pagesRequestMutex.withLock {
			val currentTime = System.currentTimeMillis()
			val timeSinceLastRequest = currentTime - lastPagesRequestTime
			if (timeSinceLastRequest < PAGES_REQUEST_DELAY_MS) {
				delay(PAGES_REQUEST_DELAY_MS - timeSinceLastRequest)
			}
			lastPagesRequestTime = System.currentTimeMillis()
		}

		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val packedScript = doc.select("script")
			.firstOrNull { it.data().contains("eval(function(h,u,n,t,e,r)") }
			?.data()
		val imageUrls = if (packedScript != null) {
			extractPackedImageUrls(packedScript)
		} else {
			doc.select("div#chapter-content img, div#chapter-content div.lazy")
				.mapNotNull { it.src() }
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

	/**
	 * Decodes packed Hunter JS (`KuroReader('#chapter-content', [...], 0)`) to extract chapter image URLs.
	 */
	private fun extractPackedImageUrls(scriptData: String): List<String> {
		val decoded = unpackHunter(scriptData)
		return PACKED_IMAGE_URL_REGEX.findAll(decoded)
			.map { it.groupValues[1].replace("\\/", "/") }
			.toList()
	}

	private fun unpackHunter(script: String): String {
		val args = PACKED_ARGS_REGEX.find(script)
			?: throw IllegalStateException("Could not parse packed script arguments")

		val h = args.groupValues[1]
		val n = args.groupValues[3]
		val t = args.groupValues[4].toInt()
		val e = args.groupValues[5].toInt()
		if (e !in n.indices) {
			throw IllegalStateException("Invalid delimiter index $e for charset length ${n.length}")
		}
		val delimiter = n[e]

		val result = StringBuilder()
		var i = 0
		while (i < h.length) {
			val s = StringBuilder()
			while (i < h.length && h[i] != delimiter) {
				s.append(h[i])
				i++
			}
			i++
			var segment = s.toString()
			if (segment.isEmpty()) continue
			for (j in n.indices) {
				segment = segment.replace(n[j].toString(), j.toString())
			}
			result.append((baseConvert(segment, e) - t).toChar())
		}
		return result.toString()
	}

	private fun baseConvert(d: String, fromBase: Int): Int {
		var result = 0
		for (c in d) {
			val pos = BASE_CHARSET.indexOf(c)
			if (pos in 0 until fromBase) {
				result = result * fromBase + pos
			}
		}
		return result
	}

	private suspend fun availableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		val regex = Regex("toggleGenre\\('([0-9]+)'\\)")
		return doc.select("div.grid.grid-cols-3 label").mapNotNullToSet { label ->
			val attr = label.attr("@click")
			val number = attr.findGroupValue(regex) ?: return@mapNotNullToSet null
			MangaTag(
				key = number,
				title = label.text(),
				source = source,
			)
		}.toSet()
	}

	private fun parseDateTime(dateStr: String): Long = runCatching {
		val parts = dateStr.split(' ')
		val dateParts = parts[0].split('-')
		val timeParts = parts[1].split(':')

		val calendar = Calendar.getInstance()
		calendar.set(
			dateParts[0].toInt(),
			dateParts[1].toInt() - 1,
			dateParts[2].toInt(),
			timeParts[0].toInt(),
			timeParts[1].toInt(),
			timeParts[2].toInt(),
		)
		calendar.timeInMillis
	}.getOrDefault(0L)

	companion object {
		private const val PAGES_REQUEST_DELAY_MS = 1500L
		private const val DOMAIN_MIN_INTERVAL_MS = 1200L
		private const val IMAGE_MIN_INTERVAL_MS = 250L
		private const val BASE_RETRY_DELAY_MS = 3000L
		private const val MIN_RETRY_DELAY_MS = 2000L
		private const val MAX_RETRY_DELAY_MS = 30000L
		private const val MAX_RATE_LIMIT_RETRIES = 3
		private const val BASE_CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
		private val IMAGE_EXT_REGEX = Regex("""\.(?:jpe?g|png|webp|gif|avif|bmp)$""", RegexOption.IGNORE_CASE)
		private val BACKGROUND_IMAGE_REGEX = Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""", RegexOption.IGNORE_CASE)
		private val PACKED_IMAGE_URL_REGEX = Regex("""["'](https?:\\?/\\?/[^"']+\.\w{3,5}(?:\?[^"']*)?)["']""")
		private val PACKED_ARGS_REGEX = Regex("""\}\("([^"]+)",\s*(\d+),\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+)\)""")
	}
}
