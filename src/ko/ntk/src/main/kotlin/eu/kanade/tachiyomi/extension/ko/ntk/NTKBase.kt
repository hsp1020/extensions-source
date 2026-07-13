package eu.kanade.tachiyomi.extension.ko.ntk

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

abstract class NTKBase(
    override val name: String,
    protected val contentKind: String,
) : HttpSource(),
    ConfigurableSource {

    protected val apiHeaders
        get() = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

    override val lang = "ko"
    override val supportsLatest = true
    protected val preferences by getPreferencesLazy()

    protected val rootUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            val domainNumber = stored.trimStart('0').ifEmpty { "0" }
            if (domainNumber != stored) {
                preferences.edit().putString(PREF_DOMAIN_KEY, domainNumber).apply()
            }
            return "https://sbxh$domainNumber.com"
        }

    protected open val webViewPath: String get() = contentKind
    override val baseUrl: String get() = "$rootUrl/$webViewPath"

    override fun mangaDetailsRequest(manga: SManga) = GET(rootUrl + manga.url, headers)
    override fun chapterListRequest(manga: SManga) = GET(rootUrl + manga.url, headers)

    override fun pageListRequest(chapter: SChapter) = GET(
        url = rootUrl + chapter.url,
        headers = headers.newBuilder().add("X-WebView-Intercept", "true").build(),
    )

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private val trojanWebViewInterceptor = Interceptor { chain ->
        val request = chain.request()

        if (request.header("X-WebView-Intercept") == null) {
            return@Interceptor chain.proceed(request)
        }

        // 백그라운드 웹뷰로 30초 대기 타임아웃을 유발하는 무거운 동작들을 전부 스킵하고,
        // 이미 앱 내부에 확보된 광고 검증 우회 쿠키를 실어 다이렉트로 정적 HTML 문서를 서버에 요청합니다.
        val newRequest = request.newBuilder()
            .removeHeader("X-WebView-Intercept")
            .build()

        return@Interceptor chain.proceed(newRequest)
    }
    
    private val headerCleanerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        if (originalRequest.header("Accept") == null) {
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        }

        chain.proceed(requestBuilder.build())
    }

    private val domainUpdateInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        val finalUrl = response.request.url.toString()
        val matchResult = """sbxh(\d+)\.com""".toRegex().find(finalUrl)

        if (matchResult != null) {
            val newDomainNumber = matchResult.groupValues[1]
            val currentDomainNumber = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            if (newDomainNumber != currentDomainNumber) {
                preferences.edit().putString(PREF_DOMAIN_KEY, newDomainNumber).apply()
            }
        }
        response
    }

    private val imageRefererInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.host.matches(Regex("""sbxh\d+\.com"""))) {
            chain.proceed(
                request.newBuilder()
                    .header("Referer", "$rootUrl/")
                    .build(),
            )
        } else {
            chain.proceed(request)
        }
    }

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(headerCleanerInterceptor)
            .addInterceptor(domainUpdateInterceptor)
            .addInterceptor(imageRefererInterceptor)
            .addInterceptor(trojanWebViewInterceptor)
            .build()
    }

    @Serializable
    private data class WorksResponse(
        val works: List<Work>,
        val hasMore: Boolean,
    )

    @Serializable
    private data class Work(
        val sourceWorkId: String,
        val title: String? = null,
        val workTitle: String? = null,
        val thumbnailUrl: String? = null,
        val coverUrl: String? = null,
        val imageUrl: String? = null,
        val thumbnail: String? = null,
        val genre: String? = null,
        val author: String? = null,
    )

    @Serializable
    private data class PageImagesResponse(
        val images: List<PageImage>,
    )

    @Serializable
    private data class PageImage(
        val src: String,
    )

    protected fun htmlCardParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.card-grid > a.card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.select("p.subject").text()
                thumbnail_url = element.select("div.thumb img:not(.platform-icon)").attr("abs:src")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<WorksResponse>()
        val mangas = data.works.map { work ->
            SManga.create().apply {
                url = "/$contentKind/${work.sourceWorkId}"
                title = work.workTitle ?: work.title ?: ""
                thumbnail_url = work.thumbnailUrl ?: work.coverUrl ?: work.imageUrl ?: work.thumbnail
                genre = work.genre
            }
        }
        return MangasPage(mangas, data.hasMore)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val rscData = document.select("script")
            .map { it.data() }
            .firstOrNull { "allCards" in it }
            ?: return MangasPage(emptyList(), false)

        val rawContent = rscData
            .substringAfter("[1,\"")
            .substringBeforeLast("\"])")

        val unescaped = rawContent
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val marker = "\"allCards\":"
        val markerIdx = unescaped.indexOf(marker)
        if (markerIdx < 0) return MangasPage(emptyList(), false)

        val arrayStart = markerIdx + marker.length
        var depth = 0
        var arrayEnd = arrayStart
        for (i in arrayStart until unescaped.length) {
            when (unescaped[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i + 1
                        break
                    }
                }
            }
        }

        val jsonArrayStr = unescaped.substring(arrayStart, arrayEnd)
        val cards = json.decodeFromString<List<Work>>(jsonArrayStr)

        val seen = mutableSetOf<String>()
        val mangas = cards.mapNotNull { card ->
            if (seen.add(card.sourceWorkId)) {
                SManga.create().apply {
                    url = "/$contentKind/${card.sourceWorkId}"
                    title = card.workTitle ?: card.title ?: ""
                    thumbnail_url = card.thumbnailUrl ?: card.coverUrl ?: card.imageUrl ?: card.thumbnail
                    genre = card.genre
                    author = card.author
                }
            } else {
                null
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val contentType = response.header("Content-Type") ?: ""
        return if (contentType.contains("application/json")) {
            popularMangaParse(response)
        } else {
            htmlCardParse(response)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.hero-v2-title").text()
            author = document.select("div.hero-v2-author a").text()
            description = document.select("p.hero-v2-desc").text()
            thumbnail_url = document.select("div.hero-v2-thumb img").attr("abs:src")

            val statusText = document.select("span.pill-status").text()
            status = when {
                statusText.contains("연재중") -> SManga.ONGOING
                statusText.contains("완결") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            genre = document.select("a.hero-v2-tag").joinToString(", ") {
                it.text().replace("#", "").trim()
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // 1. 우선 첫 번째 페이지(현재 수신한 HTML)의 회차들을 파싱하여 리스트에 담습니다.
        chapters.addAll(parseChaptersFromDocument(document))

        // 2. 하단 페이징 영역에서 epage=가 들어간 링크들을 모두 찾아 그중 가장 높은 페이지 번호(maxPage)를 추출합니다.
        val maxPage = document.select(".episode-pager a[href*=epage=]")
            .mapNotNull { it.attr("href").substringAfter("epage=").toIntOrNull() }
            .maxOrNull() ?: 1

        // 3. 만약 2페이지 이상 존재한다면, 코루틴을 사용해 안전하게 병렬 처리합니다.
        if (maxPage > 1) {
            val pagesToFetch = (2..maxPage).toList()

            // runBlocking으로 IO 스레드 풀에서 병렬 작업을 조율합니다.
            val fetchedChapters = runBlocking(Dispatchers.IO) {
                // 한 번에 3개씩 병렬 묶음(Chunk)을 지어 요청을 보냄으로써 차단 탐지를 안전하게 회피합니다.
                pagesToFetch.chunked(3).flatMap { chunk ->
                    chunk.map { page ->
                        async {
                            val pageUrl = response.request.url.newBuilder()
                                .setQueryParameter("epage", page.toString())
                                .build()
                            val pageRequest = GET(pageUrl, headers)

                            try {
                                // .use { ... } 블록을 사용해 커넥션 풀 누수를 방지하고 안전하게 응답을 닫습니다.
                                client.newCall(pageRequest).execute().use { pageResponse ->
                                    if (pageResponse.isSuccessful) {
                                        val pageDocument = pageResponse.asJsoup()
                                        parseChaptersFromDocument(pageDocument)
                                    } else {
                                        emptyList()
                                    }
                                }
                            } catch (e: Exception) {
                                emptyList() // 개별 페이지 로드 실패 시 무중단 처리를 위해 빈 리스트 반환
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
            chapters.addAll(fetchedChapters)
        }

        return chapters
    }

    // 반복적인 회차 HTML 엘리먼트 파싱을 담당하는 전용 헬퍼 함수를 추가합니다.
    private fun parseChaptersFromDocument(document: org.jsoup.nodes.Document): List<SChapter> {
        return document.select("ul.ep-list-v2 > li.ep-row-v2").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.select("a.ep-row-v2-link").attr("href"))
                name = element.select("div.ep-row-v2-title strong").text()
                date_upload = dateFormat.tryParse(element.select("span.ep-row-v2-date").text())
                scanlator = if (element.selectFirst("span.ep-price-badge") != null) "🔒" else null
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        // 1. 만약 수신된 데이터가 JSON 형식일 경우의 예외 대응 (하위 호환)
        if (body.trim().startsWith("{")) {
            val data = json.decodeFromString<PageImagesResponse>(body)
            return data.images.mapIndexed { i, image ->
                Page(i, imageUrl = image.src)
            }
        }

        // 2. 수신된 순정 HTML 문서에서 Jsoup을 사용해 원본 이미지 태그 주소를 즉시 추출합니다.
        val document = org.jsoup.Jsoup.parse(body)
        val imgElements = document.select("div.vw-imgs img.viewer-lazy-img")

        // 수동 광고 검증 세션이 유실되어 이미지를 긁어오지 못했을 때만 명확한 에러 가이드를 던집니다.
        if (imgElements.isEmpty()) {
            throw Exception("만화 이미지를 찾을 수 없습니다. 미온 앱 내 '지구본(웹뷰로 열기)'으로 접속하셔서 광고 검증을 완료하고 세션을 갱신해 주세요.")
        }

        return imgElements.mapIndexed { i, element ->
            val url = element.attr("data-src").ifEmpty { element.attr("src") }
            Page(i, imageUrl = url)
        }
    }
    

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "도메인 번호 (sbxh#.com)"
            summary = "현재 도메인 번호: ${preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)}\n숫자만 입력하세요 (예: 1, 2, 300)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "9"
        const val PAGE_SIZE = 49
    }
}
