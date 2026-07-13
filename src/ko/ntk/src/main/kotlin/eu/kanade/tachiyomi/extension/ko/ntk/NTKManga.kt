package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class NTKManga : NTKBase("NTK Manga", "manhwa") {

    override fun popularMangaRequest(page: Int): Request {
        val url = "$rootUrl/api/manhwa-list".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "ongoing")
            addQueryParameter("sort", "views")
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", PAGE_SIZE.toString())
            addQueryParameter("withTotal", "1")
        }.build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$rootUrl/manhwa/updates", headers)

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

        // 👈 [해결 핵심] 기존 "-end" 상태일 때, 서버에 보낼 API 상태 파라미터 값을 "completed"로 지정합니다.
        val apiStatus = if (statusParam == "-end") "completed" else "ongoing"

        val url = "$rootUrl/api/manhwa-list".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", apiStatus)
            if (sortParam != "new") addQueryParameter("sort", sortParam)
            genreParam?.let { addQueryParameter("g", it) }
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", PAGE_SIZE.toString())
            addQueryParameter("withTotal", "1")
        }.build()
        return GET(url, apiHeaders)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
        Filter.Header(""),
    )
}
