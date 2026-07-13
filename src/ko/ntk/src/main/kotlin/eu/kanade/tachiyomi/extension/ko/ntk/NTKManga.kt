package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NTKManga : NTKBase("NTK Manga", "manhwa") {

    override fun popularMangaParse(response: Response): MangasPage = htmlCardParse(response)
    override fun latestUpdatesParse(response: Response): MangasPage = htmlCardParse(response)

    override fun popularMangaRequest(page: Int): Request {
        val url = "$rootUrl/manhwa".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$rootUrl/manhwa/updates".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$rootUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("kind", "manhwa")
            }.build()
            return GET(url, headers)
        }

        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        val sortParam = sortFilter?.let { sortList[it.state].value } ?: sortList[0].value
        val statusParam = statusFilter?.let { statusList[it.state].value } ?: statusList[0].value
        val genreParam = buildGenreParam(genreFilter)

        // 기존 Filters.kt의 "-end" 값을 그대로 활용해 분기합니다.
        val path = if (statusParam == "-end") "manhwa-end" else "manhwa"

        val url = "$rootUrl/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (sortParam != "new") addQueryParameter("sort", sortParam)
            genreParam?.let { addQueryParameter("g", it) }
        }.build()
        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
        Filter.Header(""),
    )
}
