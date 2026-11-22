package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("DAMCONUONG", "Dâm Cô Nương", "vi", type = ContentType.HENTAI)
internal class DamCoNuong(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAMCONUONG, 30) {

	override val configKeyDomain = ConfigKey.Domain("damconuong.tech", "damconuong.ceo", "damconuong.co")

	private val availableTags = suspendLazy(initializer = ::fetchTags)
	private val secretMutex = Mutex()

	@Volatile
	private var cachedSecret: ByteArray? = null

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val key = request.url.fragment
			?.takeIf { it.startsWith(SCRAMBLE_PREFIX) && it.length == SCRAMBLE_KEY_LENGTH }
			?: return chain.proceed(request)
		val seed = decodeBase64Url(key.removePrefix(SCRAMBLE_PREFIX))
			?.takeIf { it.size == SCRAMBLE_SEED_LENGTH }
			?: return chain.proceed(request)

		val cleanUrl = request.url.newBuilder().fragment(null).build()
		val response = chain.proceed(request.newBuilder().url(cleanUrl).build())
		if (!response.isSuccessful) {
			return response
		}
		return context.redrawImageResponse(response) { bitmap ->
			descrambleBitmap(bitmap, seed)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sort = when (order) {
			SortOrder.UPDATED -> "-updated_at"
			SortOrder.NEWEST -> "-created_at"
			SortOrder.POPULARITY -> "-views"
			SortOrder.ALPHABETICAL -> "name"
			SortOrder.ALPHABETICAL_DESC -> "-name"
			else -> "-updated_at"
		}

		val url = "https://$domain/tim-kiem".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("sort", sort)
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
					addQueryParameter("filter[name]", query)
				}
				if (filter.states.isNotEmpty()) {
					val statuses = filter.states.mapNotNull { state ->
						when (state) {
							MangaState.ONGOING -> "2"
							MangaState.FINISHED -> "1"
							else -> null
						}
					}.joinToString(",")
					if (statuses.isNotEmpty()) {
						addQueryParameter("filter[status]", statuses)
					}
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
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val cards = doc.select(".manga-vertical").ifEmpty {
			doc.select("div.border.rounded-xl.border-gray-300")
		}
		return cards.mapNotNull { card ->
			val link = card.selectFirst("h3 a") ?: card.selectFirst("a[href*=/truyen/]") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val coverUrl = card.selectFirst(".cover-frame img, img")?.src().orEmpty()
			val title = link.textOrNull()
				?: card.selectFirst(".cover-frame img, img")?.attrOrNull("alt")
				?: return@mapNotNull null

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

	private fun isLoginRequired(text: String): Boolean = text.contains("\"code\":\"login_required\"") ||
		text.contains("Login required", ignoreCase = true) ||
		text.contains("Yêu cầu đăng nhập", ignoreCase = true)

	private suspend fun fetchHtmlDocument(url: String): Document {
		val request = Request.Builder()
			.get()
			.url(url.toHttpUrl())
			.headers(getRequestHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		if (response.code == 403) {
			runCatching { response.close() }
			context.requestBrowserAction(this, url)
		}
		if (!response.isSuccessful) {
			val code = response.code
			val message = response.message
			runCatching { response.close() }
			if (code == 404) {
				throw NotFoundException(message, url)
			}
			throw HttpStatusException(message, code, url)
		}
		val doc = response.parseHtml()
		if (isLoginRequired(doc.text())) {
			context.requestBrowserAction(this, url)
		}
		return doc
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchHtmlDocument(fullUrl)

		val title = doc.selectFirst("h1.md-title, h1")?.textOrNull() ?: manga.title
		val coverUrl = doc.selectFirst(".md-cover img, .cover-frame img")?.src()
			?: doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val description = doc.selectFirst(".md-synopsis p[x-ref=text], .md-synopsis p")?.text()?.trim()?.nullIfEmpty()

		val authors = LinkedHashSet<String>()
		doc.select(".md-rail dt:contains(Tác giả) + dd a, .md-rail dt:contains(Tác giả) + dd span")
			.forEach { it.textOrNull()?.let(authors::add) }
		doc.select(".md-rail dt:contains(Họa sĩ) + dd a, .md-rail dt:contains(Họa sĩ) + dd span")
			.forEach { it.textOrNull()?.let(authors::add) }

		val altTitles = doc.select("div.mt-2:contains(Tên khác:) span, .md-rail dt:contains(Tên khác) + dd span")
			.mapNotNullToSet { it.textOrNull() }

		val allTags = availableTags.getOrNull().orEmpty()
		val tags = doc.select(".md-rail-genres a.md-chip, .md-rail dt:contains(Thể loại) + dd a.md-chip, div.mt-2:contains(Thể loại:) a")
			.mapNotNullToSet { a ->
				val tagTitle = a.textOrNull() ?: return@mapNotNullToSet null
				allTags.find { it.title.equals(tagTitle, ignoreCase = true) }
					?: MangaTag(
						key = a.attr("href").substringBefore('?').removeSuffix('/').substringAfterLast('/').ifEmpty { tagTitle },
						title = tagTitle,
						source = source,
					)
			}

		val legacyStatus = doc.selectFirst("div.mt-2:contains(Tình trạng:) span")?.text()
		val state = when {
			doc.selectFirst(".md-badge-done") != null -> MangaState.FINISHED
			doc.selectFirst(".md-badge")?.text()?.contains("hoàn thành", ignoreCase = true) == true -> MangaState.FINISHED
			doc.selectFirst(".md-badge-ongoing") != null -> MangaState.ONGOING
			doc.selectFirst(".md-badge")?.text()?.contains("đang tiến hành", ignoreCase = true) == true -> MangaState.ONGOING
			legacyStatus?.contains("đang tiến hành", ignoreCase = true) == true -> MangaState.ONGOING
			legacyStatus?.contains("hoàn thành", ignoreCase = true) == true -> MangaState.FINISHED
			else -> null
		}

		val chapterLinks = doc.select("ul#md-chapter-list li a.md-ch, a.md-ch")
			.ifEmpty { doc.select("ul#chapterList a.block") }
		val chapters = chapterLinks.mapChapters(reversed = true) { index, a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chTitle = a.selectFirst(".md-ch-title")?.textOrNull()
				?: a.selectFirst("span.text-ellipsis")?.textOrNull()
				?: a.textOrNull()
			val uploadDate = a.selectFirst(".md-ch-meta span")?.textOrNull()
				?: a.selectFirst("span.ml-2.whitespace-nowrap")?.textOrNull()

			MangaChapter(
				id = generateUid(href),
				title = chTitle,
				number = index + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(uploadDate),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			authors = authors,
			altTitles = altTitles,
			tags = tags,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val chapterPath = chapter.url.toHttpUrlOrNull()?.encodedPath ?: chapter.url.substringBefore('?')
		val segments = chapterPath.trimStart('/').split('/')
		val mangaSlug = segments.getOrNull(1)?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Invalid chapter url: ${chapter.url}", chapterUrl)
		val chapterSlug = segments.getOrNull(2)?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Invalid chapter url: ${chapter.url}", chapterUrl)
		val path = "$mangaSlug/$chapterSlug"

		val chapterPage = fetchHtmlDocument(chapterUrl)
		val payloadJson = fetchPagesPayload(mangaSlug, chapterSlug, path, chapterUrl, chapterPage)

		val pagesArray = payloadJson.optJSONArray("p")
			?: throw ParseException("Không tìm thấy danh sách trang", chapterUrl)
		val scrambleKeys = payloadJson.optJSONArray("s")

		val pages = ArrayList<MangaPage>(pagesArray.length())
		for (index in 0 until pagesArray.length()) {
			val src = pagesArray.optString(index).trim()
			if (src.isEmpty()) continue
			val absSrc = src.toAbsoluteUrl(domain)
			val scrambleKey = scrambleKeys?.optString(index)?.takeIf {
				it.startsWith(SCRAMBLE_PREFIX) && it.length == SCRAMBLE_KEY_LENGTH
			}
			val pageUrl = if (scrambleKey != null) "$absSrc#$scrambleKey" else absSrc
			pages.add(
				MangaPage(
					id = generateUid(src),
					url = pageUrl,
					preview = null,
					source = source,
				),
			)
		}
		if (pages.isEmpty()) {
			throw ParseException("Không tìm thấy bất kỳ nguồn ảnh nào.", chapterUrl)
		}
		return pages
	}

	private suspend fun fetchPagesPayload(
		mangaSlug: String,
		chapterSlug: String,
		path: String,
		chapterUrl: String,
		chapterPage: Document,
	): JSONObject {
		for (attempt in 0 until 2) {
			val secret = loadSecret(chapterPage, chapterUrl, knownStale = attempt > 0)
			val tokKey = hmacSha256("tok".toByteArray(Charsets.UTF_8), secret)
			val encKey = hmacSha256("enc".toByteArray(Charsets.UTF_8), secret)
			val token = computePagesToken(mangaSlug, chapterSlug, tokKey)

			val apiUrl = "https://$domain/_c/mangas/$mangaSlug/chapters/$chapterSlug/pages?_=$token"
			val apiHeaders = getRequestHeaders().newBuilder()
				.set("X-Requested-With", "XMLHttpRequest")
				.set("Accept", "application/json")
				.set("Referer", chapterUrl)
				.build()
			val request = Request.Builder()
				.get()
				.url(apiUrl.toHttpUrl())
				.headers(apiHeaders)
				.tag(MangaSource::class.java, source)
				.build()

			val response = context.httpClient.newCall(request).await()
			val statusCode = response.code
			val bodyText = response.use { it.body.string() }

			if (bodyText.contains("\"e\":")) {
				val encrypted = runCatching { JSONObject(bodyText).optString("e") }.getOrNull()
					?.takeIf { it.isNotEmpty() }
					?: throw ParseException("Invalid encrypted payload", apiUrl)
				return decryptPagesPayload(encrypted, token, path, encKey)
			}
			if (bodyText.contains("invalid_token") && attempt == 0) {
				continue
			}
			if (statusCode == 403 || isLoginRequired(bodyText)) {
				context.requestBrowserAction(this, chapterUrl)
			}
			throw ParseException("Failed to fetch encrypted pages (HTTP $statusCode)", apiUrl)
		}
		throw ParseException("Pages token rejected by the site", chapterUrl)
	}

	private suspend fun loadSecret(
		chapterPage: Document,
		chapterUrl: String,
		knownStale: Boolean = false,
	): ByteArray = secretMutex.withLock {
		if (!knownStale) {
			cachedSecret?.let { return@withLock it }
		} else {
			cachedSecret = null
		}

		val decoderEl = chapterPage.selectFirst("[data-cipher-decoder]")
			?: throw ParseException("Decoder script not found", chapterUrl)
		val rawDecoderAttr = decoderEl.attr("data-cipher-decoder").trim()
		val decoderUrl = decoderEl.absUrl("data-cipher-decoder").ifEmpty { rawDecoderAttr.toAbsoluteUrl(domain) }
		val pageHost = chapterUrl.toHttpUrlOrNull()?.host ?: domain
		val scriptText = webClient.httpGet(decoderUrl).parseRaw()
			.replace("export default", "globalThis.__decoder = ")
			.replace(NULL_IMPORT_RE, "globalThis.__noImport(")

		val secret = captureDecoderKey(scriptText, pageHost)
			?: throw ParseException("Could not read the page decoder key", chapterUrl)
		cachedSecret = secret
		secret
	}

	private suspend fun captureDecoderKey(script: String, host: String): ByteArray? {
		val syncResult = runCatching {
			context.evaluateJs("", buildSyncCaptureScript(script, host), 5000L)
		}.getOrNull()
		parseKeyBytes(syncResult)?.let { return it }

		val asyncResult = runCatching {
			context.evaluateJs("about:blank", buildAsyncPollCaptureScript(script, host), 12000L)
		}.getOrNull()
		return parseKeyBytes(asyncResult)
	}

	private fun parseKeyBytes(raw: String?): ByteArray? {
		val cleaned = raw?.trim()?.removeSurrounding("\"")?.takeIf {
			it.isNotEmpty() && it != "null" && it != "undefined"
		} ?: return null
		val parts = cleaned.split(',')
		if (parts.size != SCRAMBLE_SEED_LENGTH) return null
		val bytes = ByteArray(SCRAMBLE_SEED_LENGTH)
		for (i in parts.indices) {
			val v = parts[i].trim().toIntOrNull() ?: return null
			bytes[i] = v.toByte()
		}
		return bytes
	}

	private fun buildSyncCaptureScript(script: String, host: String): String {
		val escapedHost = host.replace("\\", "\\\\").replace("\"", "\\\"")
		return """
			(function () {
				var __secret = null;
				function __recordKey(k) {
					if (!k) return;
					var bytes = (k instanceof ArrayBuffer)
						? new Uint8Array(k)
						: (ArrayBuffer.isView(k) ? new Uint8Array(k.buffer, k.byteOffset, k.byteLength) : k);
					if (bytes && bytes.length === 32 && __secret === null) {
						__secret = Array.prototype.slice.call(bytes);
					}
				}
				function SyncPromise(executor) {
					var state = "pending";
					var value = undefined;
					var handlers = [];
					function flush() {
						var q = handlers;
						handlers = [];
						for (var i = 0; i < q.length; i++) handle(q[i]);
					}
					function handle(h) {
						if (state === "pending") { handlers.push(h); return; }
						try {
							if (state === "fulfilled") {
								h.res(h.onFulfilled ? h.onFulfilled(value) : value);
							} else {
								if (h.onRejected) h.res(h.onRejected(value)); else h.rej(value);
							}
						} catch (err) { h.rej(err); }
					}
					function resolve(v) {
						if (state !== "pending") return;
						if (v && typeof v.then === "function") { v.then(resolve, reject); return; }
						state = "fulfilled";
						value = v;
						flush();
					}
					function reject(e) {
						if (state !== "pending") return;
						state = "rejected";
						value = e;
						flush();
					}
					this.then = function (onFulfilled, onRejected) {
						return new SyncPromise(function (res, rej) {
							handle({ onFulfilled: onFulfilled, onRejected: onRejected, res: res, rej: rej });
						});
					};
					this.catch = function (onRejected) { return this.then(null, onRejected); };
					this.finally = function (cb) {
						return this.then(
							function (v) { cb(); return v; },
							function (e) { cb(); throw e; }
						);
					};
					try { executor(resolve, reject); } catch (e) { reject(e); }
				}
				SyncPromise.resolve = function (v) { return new SyncPromise(function (res) { res(v); }); };
				SyncPromise.reject = function (e) { return new SyncPromise(function (_, rej) { rej(e); }); };
				SyncPromise.all = function (arr) { return SyncPromise.resolve(arr || []); };
				var fakeLocation = {
					hostname: "$escapedHost",
					protocol: "https:",
					href: "https://$escapedHost/",
					origin: "https://$escapedHost"
				};
				var fakeDocument = {
					createElement: function () { return { getContext: function () { return {}; } }; },
					querySelector: function () { return null; },
					querySelectorAll: function () { return []; }
				};
				var fakeCrypto = {
					getRandomValues: function (arr) {
						if (window.crypto && window.crypto.getRandomValues) return window.crypto.getRandomValues(arr);
						for (var i = 0; i < arr.length; i++) arr[i] = 0;
						return arr;
					},
					subtle: {
						importKey: function (format, key) {
							__recordKey(key);
							return SyncPromise.resolve({ export: function () { return key; } });
						},
						sign: function () { return SyncPromise.resolve(new ArrayBuffer(32)); },
						decrypt: function () { return SyncPromise.resolve(new ArrayBuffer(0)); },
						digest: function () { return SyncPromise.resolve(new ArrayBuffer(32)); }
					}
				};
				var env = {
					location: fakeLocation,
					document: fakeDocument,
					crypto: fakeCrypto,
					Promise: SyncPromise,
					TextEncoder: TextEncoder,
					TextDecoder: TextDecoder,
					Uint8Array: Uint8Array,
					ArrayBuffer: ArrayBuffer,
					atob: window.atob.bind(window),
					btoa: window.btoa.bind(window),
					setTimeout: window.setTimeout.bind(window),
					clearTimeout: window.clearTimeout.bind(window),
					__noImport: function () { return SyncPromise.reject(new Error("offline")); },
					__decoder: null
				};
				var proxy = new Proxy(env, {
					get: function (t, p) {
						if (p === "globalThis" || p === "self" || p === "window") return proxy;
						if (p in t) return t[p];
						return window[p];
					},
					set: function (t, p, v) { t[p] = v; return true; }
				});
				try {
					(function (globalThis, self, window, location, crypto, document, Promise) {
						$script
					}).call(proxy, proxy, proxy, proxy, fakeLocation, fakeCrypto, fakeDocument, SyncPromise);
				} catch (e) {}
				return __secret ? __secret.join(",") : null;
			})()
		""".trimIndent()
	}

	private fun buildAsyncPollCaptureScript(script: String, host: String): String {
		val escapedHost = host.replace("\\", "\\\\").replace("\"", "\\\"")
		return """
			(function () {
				if (window.__dcnSecret) return window.__dcnSecret.join(",");
				if (window.__dcnStarted) return null;
				window.__dcnStarted = true;
				window.__dcnSecret = null;
				function __recordKey(k) {
					if (!k) return;
					var bytes = (k instanceof ArrayBuffer)
						? new Uint8Array(k)
						: (ArrayBuffer.isView(k) ? new Uint8Array(k.buffer, k.byteOffset, k.byteLength) : k);
					if (bytes && bytes.length === 32 && window.__dcnSecret === null) {
						window.__dcnSecret = Array.prototype.slice.call(bytes);
					}
				}
				var fakeLocation = {
					hostname: "$escapedHost",
					protocol: "https:",
					href: "https://$escapedHost/",
					origin: "https://$escapedHost"
				};
				var fakeDocument = {
					createElement: function () { return { getContext: function () { return {}; } }; },
					querySelector: function () { return null; },
					querySelectorAll: function () { return []; }
				};
				var fakeCrypto = {
					getRandomValues: function (arr) {
						if (window.crypto && window.crypto.getRandomValues) return window.crypto.getRandomValues(arr);
						for (var i = 0; i < arr.length; i++) arr[i] = 0;
						return arr;
					},
					subtle: {
						importKey: function (format, key) {
							__recordKey(key);
							return Promise.resolve({ export: function () { return key; } });
						},
						sign: function () { return Promise.resolve(new ArrayBuffer(32)); },
						decrypt: function () { return Promise.resolve(new ArrayBuffer(0)); },
						digest: function () { return Promise.resolve(new ArrayBuffer(32)); }
					}
				};
				var env = {
					location: fakeLocation,
					document: fakeDocument,
					crypto: fakeCrypto,
					TextEncoder: TextEncoder,
					TextDecoder: TextDecoder,
					Uint8Array: Uint8Array,
					ArrayBuffer: ArrayBuffer,
					atob: window.atob.bind(window),
					btoa: window.btoa.bind(window),
					setTimeout: window.setTimeout.bind(window),
					clearTimeout: window.clearTimeout.bind(window),
					__noImport: function () { return Promise.reject(new Error("offline")); },
					__decoder: null
				};
				var proxy = new Proxy(env, {
					get: function (t, p) {
						if (p === "globalThis" || p === "self" || p === "window") return proxy;
						if (p in t) return t[p];
						return window[p];
					},
					set: function (t, p, v) { t[p] = v; return true; }
				});
				try {
					(function (globalThis, self, window, location, crypto, document) {
						$script
					}).call(proxy, proxy, proxy, proxy, fakeLocation, fakeCrypto, fakeDocument);
				} catch (e) {}
				return window.__dcnSecret ? window.__dcnSecret.join(",") : null;
			})()
		""".trimIndent()
	}

	private fun computePagesToken(mangaSlug: String, chapterSlug: String, tokKey: ByteArray): String {
		val mac = hmacSha256("$mangaSlug/$chapterSlug".toByteArray(Charsets.UTF_8), tokKey).copyOf(16)
		val bytes = ByteArray(17)
		bytes[0] = 1
		mac.copyInto(bytes, 1)
		return encodeBase64Url(bytes)
	}

	private fun decryptPagesPayload(
		encrypted: String,
		token: String,
		path: String,
		encKey: ByteArray,
	): JSONObject {
		val raw = decodeBase64Url(encrypted)
			?: throw ParseException("Invalid encrypted pages encoding", path)
		if (raw.size < 12 + 16) {
			throw ParseException("Encrypted pages payload too short", path)
		}
		val iv = raw.copyOfRange(0, 12)
		val cipherBytes = raw.copyOfRange(12, raw.size)
		val aesKey = hmacSha256(token.toByteArray(Charsets.UTF_8), encKey)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
			updateAAD(path.toByteArray(Charsets.UTF_8))
		}
		val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
		return JSONObject(decrypted)
	}

	private fun descrambleBitmap(bitmap: Bitmap, seed: ByteArray): Bitmap {
		val width = bitmap.width
		val height = bitmap.height
		val out = context.createBitmap(width, height)
		if (width <= 0 || height <= 0) {
			return out
		}

		val rng = HmacPrng(seed)
		val rows = 8 + rng.nextInt(13)
		val tileH = 16 * (height / (16 * rows))
		val total = rows * tileH
		if (tileH < 16 || total > height) {
			out.drawBitmap(bitmap, Rect(0, 0, width, height), Rect(0, 0, width, height))
			return out
		}

		val perm = IntArray(rows) { it }
		for (i in rows - 1 downTo 1) {
			val j = rng.nextInt(i + 1)
			val tmp = perm[i]
			perm[i] = perm[j]
			perm[j] = tmp
		}

		for (i in 0 until rows) {
			val srcY = i * tileH
			val dstY = perm[i] * tileH
			out.drawBitmap(
				bitmap,
				Rect(0, srcY, width, srcY + tileH),
				Rect(0, dstY, width, dstY + tileH),
			)
		}
		if (height > total) {
			out.drawBitmap(
				bitmap,
				Rect(0, total, width, height),
				Rect(0, total, width, height),
			)
		}
		return out
	}

	private class HmacPrng(seed: ByteArray) {
		private val mac = Mac.getInstance("HmacSHA256").apply {
			init(SecretKeySpec(seed, "HmacSHA256"))
		}
		private var counter = 0
		private var buf = ByteArray(0)
		private var pos = 0

		private fun nextUint32(): Int {
			if (pos >= buf.size) {
				val c = counter++
				val msg = byteArrayOf(
					(c ushr 24).toByte(),
					(c ushr 16).toByte(),
					(c ushr 8).toByte(),
					c.toByte(),
				)
				buf = mac.doFinal(msg)
				pos = 0
			}
			val v = ((buf[pos].toInt() and 0xff) shl 24) or
				((buf[pos + 1].toInt() and 0xff) shl 16) or
				((buf[pos + 2].toInt() and 0xff) shl 8) or
				(buf[pos + 3].toInt() and 0xff)
			pos += 4
			return v
		}

		fun nextInt(n: Int): Int {
			val limit = 0x100000000L / n * n
			var r = nextUint32().toLong() and 0xffffffffL
			while (r >= limit) {
				r = nextUint32().toLong() and 0xffffffffL
			}
			return (r % n).toInt()
		}
	}

	private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(key, "HmacSHA256"))
		return mac.doFinal(data)
	}

	private fun encodeBase64Url(input: ByteArray): String = buildString {
		var i = 0
		while (i < input.size) {
			val b0 = input[i].toInt() and 0xff
			val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xff else 0
			val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xff else 0
			val n = (b0 shl 16) or (b1 shl 8) or b2
			val chars = minOf(4, (input.size - i) * 8 / 6 + 1)
			for (c in 0 until chars) append(BASE64URL_ALPHABET[(n shr (18 - 6 * c)) and 63])
			i += 3
		}
	}

	private fun decodeBase64Url(input: String): ByteArray? {
		val out = ByteArrayOutputStream(input.length)
		var bits = 0
		var value = 0
		for (ch in input) {
			if (ch == '=') break
			val d = BASE64URL_ALPHABET.indexOf(ch)
			if (d < 0) return null
			value = (value shl 6) or d
			bits += 6
			if (bits >= 8) {
				bits -= 8
				out.write((value shr bits) and 0xff)
			}
		}
		return out.toByteArray()
	}

	private fun parseChapterDate(date: String?): Long {
		val cleaned = date?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
		val lower = cleaned.lowercase()
		val now = System.currentTimeMillis()
		when {
			"vừa xong" in lower || "vừa đăng" in lower || "hôm nay" in lower -> return now
			"hôm qua" in lower -> return now - 24L * 60L * 60L * 1000L
		}

		val amount = DATE_NUMBER_REGEX.find(lower)?.value?.toLongOrNull()
		if (amount != null) {
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
			if (offsetMs != null) return now - offsetMs
		}

		return runCatching {
			SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
				timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
			}.parse(cleaned)?.time
		}.getOrNull() ?: 0L
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		return doc.select("button, label").mapNotNullToSet { element ->
			val click = element.attr("@click").ifEmpty { element.attr("x-on:click") }
			val id = GENRE_TOGGLE_REGEX.find(click)?.groupValues?.getOrNull(1) ?: return@mapNotNullToSet null
			val title = element.textOrNull() ?: return@mapNotNullToSet null
			MangaTag(
				key = id,
				title = title,
				source = source,
			)
		}
	}

	companion object {
		private const val SCRAMBLE_PREFIX = "x1."
		private const val SCRAMBLE_KEY_LENGTH = 46
		private const val SCRAMBLE_SEED_LENGTH = 32
		private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
		private val NULL_IMPORT_RE = Regex("""\bimport\s*\(""")
		private val DATE_NUMBER_REGEX = Regex("""\d+""")
		private val GENRE_TOGGLE_REGEX = Regex("""toggleGenre\('([^']+)'\)""")
	}
}
