package eu.kanade.tachiyomi.extension.ko.navercomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

abstract class NaverComicBase(protected val mType: String) : HttpSource() {
    override val lang: String = "ko"
    override val baseUrl: String = "https://comic.naver.com"
    internal val mobileUrl = "https://m.comic.naver.com"
    override val supportsLatest = true

    protected open val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

    // 401(권한 없음), 403(접근 금지) 에러 발생 시 사용자에게 로그인/성인인증 안내
    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401 || response.code == 403) {
                response.close()
                throw IOException("웹뷰(WebView)에서 네이버 로그인 및 성인 인증을 진행해 주세요.")
            }
            response
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/api/search/$mType?keyword=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ApiMangaSearchResponse>()
        return MangasPage(result.toSMangas(mType), result.hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val titleId = (baseUrl + manga.url).toHttpUrl().queryParameter("titleId")
        return GET("$baseUrl/api/article/list/info?titleId=$titleId", headers)
    }

    // 지구본(웹뷰) 버튼 클릭 시 JSON API가 아닌 실제 웹페이지로 연결되도록 URL 강제 지정
    override fun getMangaUrl(manga: SManga): String {
        return baseUrl + manga.url
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<Manga>().toSManga(mType)

    override fun chapterListRequest(manga: SManga): Request = chapterListRequest(manga.url, 1)

    private fun chapterListRequest(mangaUrl: String, page: Int): Request {
        val titleId = (baseUrl + mangaUrl).toHttpUrl().queryParameter("titleId")
        // [핵심 해결] 완결 웹툰이 1화부터 나오는 현상을 막기 위해 무조건 최신화부터(DESC) 주도록 강제합니다.
        return GET("$baseUrl/api/article/list?titleId=$titleId&page=$page&sort=DESC", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        var result = response.parseAs<ApiMangaChapterListResponse>()
        val chapters = mutableListOf<SChapter>()

        while (true) {
            chapters.addAll(
                result.articleList.map { chapter ->
                    chapter.toSChapter(mType, result.titleId).apply {
                        date_upload = parseChapterDate(chapter.serviceDateDescription)
                    }
                },
            )

            if (!result.hasNextPage) break

            val nextRequest = chapterListRequest("/$mType/list?titleId=${result.titleId}", result.pageInfo.nextPage)
            result = client.newCall(nextRequest).execute().parseAs<ApiMangaChapterListResponse>()
        }

        return chapters
    }

    protected fun parseChapterDate(date: String): Long = if (date.contains(":")) {
        Calendar.getInstance().timeInMillis
    } else {
        dateFormat.tryParse(date)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        var urls = document.select(".wt_viewer img").map { it.attr("abs:src").ifEmpty { it.attr("src") } }
        if (urls.isEmpty()) {
            urls = document.select(".toon_view_lst img").map { it.attr("abs:data-src").ifEmpty { it.attr("data-src") } }
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()
}

abstract class NaverComicChallengeBase(mType: String) : NaverComicBase(mType) {

    override fun popularMangaParse(response: Response): MangasPage {
        val apiMangaResponse = response.parseAs<ApiMangaChallengeResponse>()
        val mangas = apiMangaResponse.toSMangas(mType)

        val hasNextPage = mangas.size >= 30

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
}
