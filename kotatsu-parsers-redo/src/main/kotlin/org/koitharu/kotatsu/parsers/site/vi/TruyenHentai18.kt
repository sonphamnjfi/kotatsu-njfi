package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRUYENHENTAI18", "TruyenHentai18", "vi", ContentType.HENTAI)
internal class TruyenHentai18(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TRUYENHENTAI18, 18) {

	override val configKeyDomain = ConfigKey.Domain("truyenhentai18.net", "truyenhentai18.app")

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrBlank() -> {
				buildPagedUrl("", page).toHttpUrl().newBuilder()
					.addQueryParameter("s", filter.query.trim())
					.build()
			}

			filter.tags.isNotEmpty() -> {
				val slug = filter.tags.first().key
				buildPagedUrl("/category/$slug", page).toHttpUrl()
			}

			order == SortOrder.POPULARITY -> buildPagedUrl("/xem-nhieu-nhat", page).toHttpUrl()
			else -> buildPagedUrl("/moi-cap-nhat", page).toHttpUrl()
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.col-6.col-md-4.col-lg-2.mb-3").mapNotNull { element ->
			val link = element.selectFirst("a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = element.selectFirst("h2")?.textOrNull()
				?: link.attrOrNull("title")
				?: return@mapNotNull null
			val coverUrl = element.selectFirst("img")?.src().orEmpty()

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

	private fun buildPagedUrl(path: String, page: Int): String = if (page > 1) {
		"https://$domain$path/page/$page"
	} else {
		"https://$domain$path"
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val resolvedUrl = if (!manga.url.startsWith("/") && "://" !in manga.url && !manga.url.endsWith(".html")) {
			"/${manga.url}.html".toAbsoluteUrl(domain)
		} else {
			manga.url.toAbsoluteUrl(domain)
		}
		val doc = webClient.httpGet(resolvedUrl).parseHtml()

		val title = doc.selectFirst("h1")?.textOrNull() ?: manga.title
		val coverUrl = doc.selectFirst("img.manga-cover")?.src()
			?: doc.selectFirst(".card img.img-fluid")?.src()
			?: manga.coverUrl

		val cachedTags = availableTags.getOrNull().orEmpty()
		val tags = doc.select("a.badge.bg-primary").mapNotNullToSet { a ->
			val tagTitle = a.textOrNull() ?: return@mapNotNullToSet null
			val pathSegments = a.absUrl("href").toHttpUrlOrNull()?.pathSegments
			val categoryIndex = pathSegments?.indexOf("category") ?: -1
			val slugFromHref = if (categoryIndex >= 0) {
				pathSegments?.getOrNull(categoryIndex + 1)?.takeIf { it.isNotEmpty() }
			} else {
				null
			}
			cachedTags.find { it.title.equals(tagTitle, ignoreCase = true) }
				?: MangaTag(
					key = slugFromHref ?: tagTitle.lowercase(Locale.ROOT).trim().replace(WHITESPACE_REGEX, "-"),
					title = tagTitle,
					source = source,
				)
		}

		val infoElements = doc.select(".list-group-item, div")
		var state: MangaState? = null
		val statusEl = infoElements.lastOrNull { el ->
			val lower = el.text().lowercase(Locale.ROOT)
			"trạng thái:" in lower &&
				el.select(".list-group-item, div").none { child ->
					child !== el && "trạng thái:" in child.text().lowercase(Locale.ROOT)
				}
		}
		if (statusEl != null) {
			val lower = statusEl.text().lowercase(Locale.ROOT)
			state = when {
				"hoàn thành" in lower -> MangaState.FINISHED
				"đang tiến hành" in lower -> MangaState.ONGOING
				else -> null
			}
		}

		var author: String? = null
		val authorEl = infoElements.lastOrNull { el ->
			val lower = el.text().lowercase(Locale.ROOT)
			"tác giả:" in lower &&
				el.select(".list-group-item, div").none { child ->
					child !== el && "tác giả:" in child.text().lowercase(Locale.ROOT)
				}
		}
		if (authorEl != null) {
			author = authorEl.text().substringAfter(":").trim().nullIfEmpty()
		}

		val description = doc.select(".description")
			.mapNotNull { it.wholeText().trim().nullIfEmpty() }
			.joinToString("\n")
			.nullIfEmpty()

		val chapters = doc.select("div.chapter-item").mapChapters(reversed = true) { i, element ->
			val link = element.selectFirst("a.fw-bold") ?: element.selectFirst("a") ?: return@mapChapters null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val name = link.textOrNull()
			val dateText = element.selectFirst("div.chapter-date")?.text()
			MangaChapter(
				id = generateUid(href),
				title = name,
				url = href,
				number = i + 1f,
				volume = 0,
				uploadDate = parseRelativeDate(dateText),
				scanlator = null,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val images = doc.select("div#viewer.chapter-container img")
			.ifEmpty { doc.select("div#viewer img, div.chapter-container img") }

		return images.mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseRelativeDate(value: String?): Long {
		val raw = value?.substringAfterLast("•")?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
		val lower = raw.lowercase(Locale.ROOT)
		if ("trước" in lower) {
			val amount = DATE_NUMBER_REGEX.find(lower)?.value?.toLongOrNull() ?: return 0L
			val offsetMs = when {
				"giây" in lower -> amount * 1000L
				"phút" in lower -> amount * 60_000L
				"giờ" in lower || "tiếng" in lower -> amount * 3_600_000L
				"ngày" in lower -> amount * 86_400_000L
				"tuần" in lower -> amount * 7L * 86_400_000L
				"tháng" in lower -> amount * 30L * 86_400_000L
				"năm" in lower -> amount * 365L * 86_400_000L
				else -> null
			}
			if (offsetMs != null) {
				return System.currentTimeMillis() - offsetMs
			}
		}

		return runCatching {
			SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
				timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
			}.parse(raw)?.time
		}.getOrNull() ?: 0L
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		val links = doc.selectFirst("#categoryDropdown")
			?.nextElementSibling()
			?.select("a[href*=/category/]")
			?.takeIf { it.isNotEmpty() }
			?: doc.select("a[href*=/category/]")

		return links.mapNotNullToSet { link ->
			val title = link.textOrNull() ?: return@mapNotNullToSet null
			val pathSegments = link.absUrl("href").toHttpUrlOrNull()?.pathSegments ?: return@mapNotNullToSet null
			val categoryIndex = pathSegments.indexOf("category")
			val slug = pathSegments.getOrNull(categoryIndex + 1)?.takeIf { it.isNotEmpty() }
				?: return@mapNotNullToSet null
			MangaTag(
				key = slug,
				title = title,
				source = source,
			)
		}
	}

	companion object {
		private val DATE_NUMBER_REGEX = Regex("""\d+""")
		private val WHITESPACE_REGEX = Regex("""\s+""")
	}
}
