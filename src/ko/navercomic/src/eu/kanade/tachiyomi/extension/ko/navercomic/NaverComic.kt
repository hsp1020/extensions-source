package eu.kanade.tachiyomi.extension.ko.navercomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

// ==========================================
// 1. 네이버 웹툰 (정식 연재) 클래스
// ==========================================
class NaverWebtoon : NaverComicBase("webtoon") {
    override val name = "Naver Webtoon"

    private val json = Json { ignoreUnknownKeys = true }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/webtoon/titlelist/weekday".toHttpUrl().newBuilder()
            .addQueryParameter("order", "USER")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseWebtoonList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/webtoon/titlelist/weekday".toHttpUrl().newBuilder()
            .addQueryParameter("order", "UPDATE")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseWebtoonList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/api/search/$mType".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val dayFilter = filters.firstInstanceOrNull<DayFilter>()

        val sortParam = sortFilter?.let { sortList[it.state].value } ?: sortList[0].value
        val genreParam = genreFilter?.let { genreList[it.state].value } ?: ""
        val dayParam = dayFilter?.let { dayList[it.state].value } ?: ""

        // [해결] 브랜드웹툰(BRAND)을 대분류 장르로 포함
        val isStandardGenre = listOf("DAILY", "COMIC", "FANTASY", "ACTION", "DRAMA", "PURE", "SENSIBILITY", "THRILL", "HISTORICAL", "SPORTS", "BRAND").contains(genreParam)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (dayParam == "finished") {
                addPathSegments("api/webtoon/titlelist/finished")
                addQueryParameter("order", sortParam)
                addQueryParameter("page", page.toString())
            } else if (genreParam.isNotEmpty() && dayParam.isEmpty()) {
                if (isStandardGenre) {
                    addPathSegments("api/webtoon/titlelist/genre")
                    addQueryParameter("genre", genreParam)
                    val finalSortParam = if (sortParam == "STAR_SCORE") "STAR" else sortParam
                    addQueryParameter("order", finalSortParam)
                    addQueryParameter("page", page.toString())
                } else {
                    addPathSegments("api/search/webtoon")
                    addQueryParameter("keyword", genreParam)
                    addQueryParameter("page", page.toString())
                    addQueryParameter("isTagSearch", "true") 
                }
            } else {
                addPathSegments("api/webtoon/titlelist/weekday")
                if (dayParam.isNotEmpty()) {
                    addQueryParameter("week", dayParam)
                }
                addQueryParameter("order", sortParam)
                addQueryParameter("page", page.toString())
            }
        }.build()

        val reqHeaders = headers.newBuilder().add("X-Sort-Param", sortParam).build()
        return GET(url, reqHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject

        if (jsonObject.containsKey("searchList")) {
            val result = json.decodeFromJsonElement<ApiMangaSearchResponse>(jsonObject)
            val mangas = result.searchList
            val hasNextPage = result.hasNextPage
            
            val sortParam = response.request.header("X-Sort-Param")
            val isTagSearch = response.request.url.queryParameter("isTagSearch") == "true"
            
            // 태그 검색 시 전체 페이지를 로드하여 타치요미 내부에서 로컬 정렬 수행
            if (isTagSearch && sortParam != null && sortParam != "USER") {
                val allMangas = mutableListOf<Manga>()
                allMangas.addAll(mangas)
                
                var currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
                var next = hasNextPage
                
                while (next) {
                    currentPage++
                    val nextUrl = response.request.url.newBuilder().setQueryParameter("page", currentPage.toString()).build()
                    val nextReq = GET(nextUrl, response.request.headers)
                    val nextRes = client.newCall(nextReq).execute()
                    val nextJson = json.parseToJsonElement(nextRes.body.string()).jsonObject
                    
                    if (nextJson.containsKey("searchList")) {
                        val nextResult = json.decodeFromJsonElement<ApiMangaSearchResponse>(nextJson)
                        allMangas.addAll(nextResult.searchList)
                        next = nextResult.hasNextPage
                    } else {
                        break
                    }
                }
                
                val sortedMangas = when (sortParam) {
                    "STAR_SCORE", "STAR" -> allMangas.sortedByDescending { it.starScore }
                    "VIEW" -> allMangas.sortedByDescending { it.viewCount }
                    else -> allMangas
                }
                
                return MangasPage(sortedMangas.map { it.toSManga(mType) }.distinctBy { it.url }, false)
            }
            
            return MangasPage(mangas.map { it.toSManga(mType) }.distinctBy { it.url }, hasNextPage)
        }

        return parseWebtoonListJson(response, jsonObject)
    }

    private fun parseWebtoonList(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        return parseWebtoonListJson(response, jsonObject)
    }

    private fun parseWebtoonListJson(response: Response, jsonObject: kotlinx.serialization.json.JsonObject): MangasPage {
        val allMangas = mutableListOf<Manga>()
        
        if (jsonObject.containsKey("titleList")) {
            allMangas.addAll(json.decodeFromJsonElement<List<Manga>>(jsonObject["titleList"]!!))
        } else if (jsonObject.containsKey("titleListMap")) {
            val map = json.decodeFromJsonElement<Map<String, List<Manga>>>(jsonObject["titleListMap"]!!)
            val sortParam = response.request.url.queryParameter("order") ?: "USER"
            
            if (sortParam == "STAR_SCORE" || sortParam == "STAR") {
                val flatList = map.values.flatten()
                allMangas.addAll(flatList.sortedByDescending { it.starScore })
            } else if (sortParam == "VIEW") {
                val flatList = map.values.flatten()
                allMangas.addAll(flatList.sortedByDescending { it.viewCount })
            } else {
                val daysOrder = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "DAILY_PLUS")
                val maxLen = map.values.maxOfOrNull { it.size } ?: 0
                
                for (i in 0 until maxLen) {
                    for (day in daysOrder) {
                        val list = map[day]
                        if (list != null && i < list.size) {
                            allMangas.add(list[i])
                        }
                    }
                }
            }
        }

        val mangas = allMangas.map { it.toSManga(mType) }.distinctBy { it.url }

        val pageInfo = jsonObject["pageInfo"]?.let { json.decodeFromJsonElement<PageInfo>(it) }
        val hasNextPage = pageInfo?.nextPage != 0 && pageInfo?.nextPage != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("키워드 검색 시 아래 필터는 무시됩니다."),
        SortFilter(),
        GenreFilter(),
        DayFilter(),
    )
}

// ==========================================
// 정식 연재 전용 필터 데이터
// ==========================================
data class FilterOption(val name: String, val value: String)

internal val sortList = listOf(
    FilterOption("인기순", "USER"),
    FilterOption("업데이트순", "UPDATE"),
    FilterOption("조회순", "VIEW"),
    FilterOption("별점순", "STAR_SCORE"), 
)

internal val genreList = listOf(
    FilterOption("전체", ""),
    
    // 기본 대분류 장르
    FilterOption("로맨스", "PURE"),
    FilterOption("판타지", "FANTASY"),
    FilterOption("액션", "ACTION"),
    FilterOption("일상", "DAILY"),
    FilterOption("스릴러", "THRILL"),
    FilterOption("개그", "COMIC"),
    FilterOption("무협/사극", "HISTORICAL"),
    FilterOption("드라마", "DRAMA"),
    FilterOption("감성", "SENSIBILITY"),
    FilterOption("스포츠", "SPORTS"),
    FilterOption("브랜드웹툰", "BRAND"),
    
    // [해결] 사용자가 지정한 세부 태그만 깔끔하게 노출
    FilterOption("드라마&영화 원작웹툰", "드라마&영화 원작웹툰"),
    FilterOption("먼치킨", "먼치킨"),
    FilterOption("학원로맨스", "학원로맨스"),
    FilterOption("로판", "로판"),
    FilterOption("게임판타지", "게임판타지"),
    FilterOption("2015 최강자전", "2015 최강자전"),
    FilterOption("유혹남", "유혹남"),
    FilterOption("인외존재", "인외존재"),
    FilterOption("세계관", "세계관"),
    FilterOption("다크히어로", "다크히어로"),
    FilterOption("학원물", "학원물"),
    FilterOption("참교육", "참교육"),
    FilterOption("드라마 (태그)", "드라마"), // 대분류 드라마와 겹침 방지
    FilterOption("짝사랑남", "짝사랑남"),
    FilterOption("계약연애", "계약연애"),
)

internal val dayList = listOf(
    FilterOption("전체", ""),
    FilterOption("월요일", "mon"),
    FilterOption("화요일", "tue"),
    FilterOption("수요일", "wed"),
    FilterOption("목요일", "thu"),
    FilterOption("금요일", "fri"),
    FilterOption("토요일", "sat"),
    FilterOption("일요일", "sun"),
    FilterOption("매일+", "dailyPlus"),
    FilterOption("완결", "finished"),
)

class SortFilter : Filter.Select<String>("정렬", sortList.map { it.name }.toTypedArray())
class GenreFilter : Filter.Select<String>("장르 (요일/완결 선택 시 무시됨)", genreList.map { it.name }.toTypedArray())
class DayFilter : Filter.Select<String>("요일 / 완결", dayList.map { it.name }.toTypedArray())


// ==========================================
// 베스트도전 / 도전만화 전용 필터 데이터
// ==========================================
internal val challengeSortList = listOf(
    FilterOption("조회순", "VIEW"),
    FilterOption("업데이트순", "UPDATE"),
    FilterOption("별점순", "starScore"),
)

internal val challengeGenreList = listOf(
    FilterOption("전체", ""),
    FilterOption("로맨스", "PURE"),
    FilterOption("액션", "ACTION"),
    FilterOption("스포츠", "SPORTS"),
    FilterOption("스릴러", "THRILL"),
    FilterOption("판타지", "FANTASY"),
    FilterOption("드라마", "DRAMA"),
    FilterOption("일상", "DAILY"),
    FilterOption("개그", "COMIC"),
    FilterOption("감성", "SENSIBILITY"),
    FilterOption("무협/사극", "HISTORICAL"),
    FilterOption("에피소드", "EPISODE"),
    FilterOption("옴니버스", "OMNIBUS"),
    FilterOption("스토리", "STORY"),
)

class ChallengeSortFilter : Filter.Select<String>("정렬", challengeSortList.map { it.name }.toTypedArray())
class ChallengeGenreFilter : Filter.Select<String>("장르", challengeGenreList.map { it.name }.toTypedArray())


// ==========================================
// 2. 베스트도전 클래스
// ==========================================
class NaverBestChallenge : NaverComicChallengeBase("bestChallenge") {
    override val name = "Naver Webtoon Best Challenge"

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/$mType/list?order=VIEW&page=$page", headers)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/$mType/list?order=UPDATE&page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/api/search/$mType".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        
        val sortFilter = filters.firstInstanceOrNull<ChallengeSortFilter>()
        val genreFilter = filters.firstInstanceOrNull<ChallengeGenreFilter>()
        
        val sortParam = sortFilter?.let { challengeSortList[it.state].value } ?: "VIEW"
        val genreParam = genreFilter?.let { challengeGenreList[it.state].value } ?: ""
        
        val url = "$baseUrl/api/$mType/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("order", sortParam)
            addQueryParameter("page", page.toString())
            if (genreParam.isNotEmpty()) {
                addQueryParameter("genre", genreParam)
            }
        }.build()
        
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.contains("/search/")) {
            return super.searchMangaParse(response)
        }
        return popularMangaParse(response)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("키워드 검색 시 아래 필터는 무시됩니다."),
        ChallengeSortFilter(),
        ChallengeGenreFilter(),
    )
}

// ==========================================
// 3. 도전만화 클래스
// ==========================================
class NaverChallenge : NaverComicChallengeBase("challenge") {
    override val name = "Naver Webtoon Challenge"
    override val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/$mType/list?order=VIEW&page=$page", headers)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/$mType/list?order=UPDATE&page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/api/search/$mType".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        
        val sortFilter = filters.firstInstanceOrNull<ChallengeSortFilter>()
        val genreFilter = filters.firstInstanceOrNull<ChallengeGenreFilter>()
        
        val sortParam = sortFilter?.let { challengeSortList[it.state].value } ?: "VIEW"
        val genreParam = genreFilter?.let { challengeGenreList[it.state].value } ?: ""
        
        val url = "$baseUrl/api/$mType/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("order", sortParam)
            addQueryParameter("page", page.toString())
            if (genreParam.isNotEmpty()) {
                addQueryParameter("genre", genreParam)
            }
        }.build()
        
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.contains("/search/")) {
            return super.searchMangaParse(response)
        }
        return popularMangaParse(response)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("키워드 검색 시 아래 필터는 무시됩니다."),
        ChallengeSortFilter(),
        ChallengeGenreFilter(),
    )
}
