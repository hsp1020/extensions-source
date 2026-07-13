package eu.kanade.tachiyomi.extension.ko.ntk

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
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

        val chapterUrl = request.url.toString()
        
        // 미온 앱 에러 다이얼로그 및 화면에 실시간으로 로그를 노출하기 위한 리스트
        val debugLogs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
        
        fun log(msg: String) {
            val time = timeFormat.format(java.util.Date())
            val formatted = "[$time] $msg"
            Log.d("NTK_DEBUG", formatted)
            debugLogs.add(formatted)
        }

        log("=== [시작] Trojan WebView Interceptor ===")
        log("Chapter URL: $chapterUrl")
        log("Root URL: $rootUrl")

        var finalHtml: String? = null
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            try {
                val context = Injekt.get<Application>()
                val webView = WebView(context)
                log("WebView 인스턴스 생성 완료")

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 1080, 1920)

                val userAgent = request.header("User-Agent")
                    ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                webView.settings.userAgentString = userAgent
                log("적용된 User-Agent: $userAgent")

                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                val currentCookies = cookieManager.getCookie(rootUrl)
                log("현재 수집된 쿠키 ($rootUrl): $currentCookies")

                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        @Suppress("unused")
                        fun exfiltrate(html: String) {
                            val snippet = if (html.length > 150) html.substring(0, 150) else html
                            log("=== [수신 성공] JavaScript에서 exfiltrate 수신완료! ===")
                            log("수신 데이터 길이: ${html.length}")
                            log("수신 데이터 앞부분: $snippet")
                            finalHtml = html
                            latch.countDown()
                        }
                    },
                    "TrojanTunnel",
                )

                val wiretapScript = """
                    (function() {
                        window.__ntkDevtoolsPreflight = 1;
                        const originalFetch = window.fetch;
                        window.fetch = async function() {
                            let reqUrl = arguments[0] && arguments[0].url ? arguments[0].url : arguments[0];
                            console.log("NTK_JS: fetch 요청 감지됨 -> " + reqUrl);
                            const response = await originalFetch.apply(this, arguments);
                            if (reqUrl && reqUrl.toString().match(/\/api\/(manhwa|webtoon)-images/)) {
                                console.log("NTK_JS: API 주소 일치 확인! 본문 데이터 추출 개시...");
                                response.clone().text().then(text => {
                                    window.TrojanTunnel.exfiltrate(text);
                                }).catch(err => {
                                    console.error("NTK_JS_ERR: 복사 실패: " + err);
                                });
                            }
                            return response;
                        };
                        console.log("NTK_JS: window.fetch 후킹 스크립트 주입 완료");
                    })();
                """.trimIndent()

                var preloadDone = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        log("onPageStarted: $url (preloadDone: $preloadDone)")
                        if (preloadDone) {
                            log("페이지 주입 개시: $url")
                            view.evaluateJavascript(wiretapScript) { result ->
                                log("스크립트 주입 반환값: $result")
                            }
                        }
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        log("onPageFinished: $url")
                        if (!preloadDone) {
                            preloadDone = true
                            log("홈 화면 로드 완료. 만화 본문 chapterUrl 로딩 시작: $chapterUrl")
                            view.loadUrl(chapterUrl)
                        } else {
                            log("만화 본문 chapterUrl 로드 완료 상태 도달")
                        }
                        super.onPageFinished(view, url)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        log("onReceivedError: ${request.url} - 에러코드: ${error.errorCode}, 설명: ${error.description}")
                        super.onReceivedError(view, request, error)
                    }

                    @Suppress("deprecation")
                    override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                        log("onReceivedError (구버전): $failingUrl - 코드: $errorCode, 설명: $description")
                        super.onReceivedError(view, errorCode, description, failingUrl)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        log("onReceivedHttpError: ${request.url} - HTTP 상태 코드: ${errorResponse.statusCode}")
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        log("onReceivedSslError: ${error.url} - SSL 에러 상태: $error")
                        handler.proceed()
                    }
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val msg = "CONSOLE [${consoleMessage.messageLevel()}]: ${consoleMessage.message()} (위치: ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        log(msg)
                        return true
                    }

                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        log("로딩 진행도: $newProgress% (현재 웹뷰 주소: ${view.url})")
                    }
                }

                log("최초 홈 주소 로딩 개시: $rootUrl")
                webView.loadUrl(rootUrl)

            } catch (e: Exception) {
                log("Handler 스레드 내부에서 심각한 예외 발생: ${e.message}")
                latch.countDown()
            }
        }

        log("exfiltrate 데이터 대기 시작 (최대 30초)...")
        val reachedZero = latch.await(30, TimeUnit.SECONDS)
        log("대기 정지 풀림. reachedZero: $reachedZero")

        if (!reachedZero) {
            log("=== [실패] 웹뷰 로딩 타임아웃 30초를 모두 초과했습니다. ===")
        }

        finalHtml?.let {
            val isJson = it.trim().startsWith("{")
            val mediaType = if (isJson) "application/json" else "text/html"
            log("데이터 수신 성공. 뷰어로 반환 진행.")
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(it.toResponseBody(mediaType.toMediaType()))
                .build()
        }

        // 미온 앱 화면에 통째로 보여주기 위해 로그 조립
        val logDump = debugLogs.joinToString("\n")
        log("finalHtml이 null이므로 오류 화면에 수집된 디버그 로그를 덤프합니다.")

        throw Exception("WebView timed out loading ${request.url}\n\n=== 디버그 로그 수집 결과 ===\n$logDump")
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

        chapters.addAll(parseChaptersFromDocument(document))

        val maxPage = document.select(".episode-pager a[href*=epage=]")
            .mapNotNull { it.attr("href").substringAfter("epage=").toIntOrNull() }
            .maxOrNull() ?: 1

        for (page in 2..maxPage) {
            val pageUrl = response.request.url.newBuilder()
                .setQueryParameter("epage", page.toString())
                .build()
            
            val pageRequest = GET(pageUrl, headers)
            val pageResponse = client.newCall(pageRequest).execute()
            
            if (pageResponse.isSuccessful) {
                val pageDocument = pageResponse.asJsoup()
                chapters.addAll(parseChaptersFromDocument(pageDocument))
            }
            pageResponse.close()
        }

        return chapters
    }

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
        val data = response.parseAs<PageImagesResponse>()
        return data.images.mapIndexed { i, image ->
            Page(i, imageUrl = image.src)
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
