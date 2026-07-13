package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NTKWebtoon : NTKBase("NTK Webtoon", "webtoon") {
    override val webViewPath = "ing"

    override fun popularMangaParse(response: Response): MangasPage = htmlCardParse(response)
    override fun latestUpdatesParse(response: Response): MangasPage = htmlCardParse(response)

    override fun popularMangaRequest(page: Int): Request {
        val url = "$rootUrl/ing".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$rootUrl/ing".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "new")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$rootUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("kind", "webtoon")
            }.build()
            return GET(url, headers)
        }

        val sortFilter = filters.firstInstanceOrNull<WtSortFilter>()
        val statusFilter = filters.firstInstanceOrNull<WtStatusFilter>()
        val catFilter = filters.firstInstanceOrNull<WtCategoryFilter>()
        val dayFilter = filters.firstInstanceOrNull<WtDayFilter>()
        val genreFilter = filters.firstInstanceOrNull<WtGenreFilter>()

        val sortParam = sortFilter?.let { sortList[it.state].value } ?: sortList[0].value
        val statusParam = statusFilter?.let { wtStatusList[it.state].value } ?: wtStatusList[0].value
        val catParam = catFilter?.let { wtCatList[it.state].value } ?: ""
        val dayParam = dayFilter?.let { wtDayList[it.state].value } ?: ""
        val tagParam = buildWtGenreParam(genreFilter)

        // 기존 Filters.kt의 "end" 값을 그대로 활용해 분기합니다.
        val path = if (statusParam == "end") "end" else "ing"

        val url = "$rootUrl/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (sortParam != "new") addQueryParameter("sort", sortParam)
            
            // 완결("end") 상태가 아닐 때만 카테고리와 요일 파라미터를 추가합니다.
            if (statusParam != "end") {
                if (catParam.isNotEmpty()) addQueryParameter("cat", catParam)
                if (dayParam.isNotEmpty()) addQueryParameter("day", dayParam)
            }
            if (!tagParam.isNullOrEmpty()) addQueryParameter("tag", tagParam)
        }.build()
        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        WtSortFilter(),
        WtStatusFilter(),
        WtCategoryFilter(),
        WtDayFilter(),
        WtGenreFilter(),
        Filter.Header(""),
    )
}
