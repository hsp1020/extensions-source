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
            return super.searchMangaRequest(page, query, filters)
        }

        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val dayFilter = filters.firstInstanceOrNull<DayFilter>()

        val sortParam = sortFilter?.let { sortList[it.state].value } ?: sortList[0].value
        val genreParam = genreFilter?.let { genreList[it.state].value } ?: ""
        val dayParam = dayFilter?.let { dayList[it.state].value } ?: ""

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (dayParam == "finished") {
                addPathSegments("api/webtoon/titlelist/finished")
                addQueryParameter("order", sortParam)
                addQueryParameter("page", page.toString())
            } else if (genreParam.isNotEmpty() && dayParam.isEmpty()) {
                addPathSegments("api/webtoon/titlelist/genre")
                addQueryParameter("genre", genreParam)
                
                // [해결] 장르 탭 전용 별점순 파라미터(STAR) 예외 처리
                val finalSortParam = if (sortParam == "STAR_SCORE") "STAR" else sortParam
                addQueryParameter("order", finalSortParam)
                addQueryParameter("page", page.toString())
            } else {
                addPathSegments("api/webtoon/titlelist/weekday")
                if (dayParam.isNotEmpty()) {
                    addQueryParameter("week", dayParam)
                }
                addQueryParameter("order", sortParam)
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject

        // 일반 키워드 검색 시 searchList 처리
        if (jsonObject.containsKey("searchList")) {
            val result = json.decodeFromJsonElement<ApiMangaSearchResponse>(jsonObject)
            return MangasPage(result.toSMangas(mType), result.hasNextPage)
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
                // 월~일 순서를 명시적으로 배열로 선언하여 강제 순회 (무작위 배치 방지)
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
    FilterOption("별점순", "STAR_SCORE"), // 내부적으로 장르 탭일 경우 STAR로 자동 변환됨
)

internal val genreList = listOf(
    FilterOption("전체", ""),
    FilterOption("일상", "DAILY"),
    FilterOption("개그", "COMIC"),
    FilterOption("판타지", "FANTASY"),
    FilterOption("액션", "ACTION"),
    FilterOption("드라마", "DRAMA"),
    FilterOption("순정", "PURE"),
    FilterOption("감성", "SENSIBILITY"),
    FilterOption("스릴러", "THRILL"),
    FilterOption("무협/사극", "HISTORICAL"),
    FilterOption("스포츠", "SPORTS"),
    FilterOption("드라마&영화 원작웹툰", "드라마&영화 원작웹툰"),
    FilterOption("먼치킨", "먼치킨"),
    FilterOption("학원로맨스", "학원로맨스"),
    FilterOption("학원액션", "학원액션"), 
    FilterOption("로판", "로판"),
    FilterOption("게임판타지", "게임판타지"),
    FilterOption("소년물", "소년물"),
    FilterOption("삼각관계", "삼각관계"),
    FilterOption("빌런", "빌런"),
    FilterOption("악역이주인공", "악역이주인공"),
    FilterOption("후회물", "후회물"),
    FilterOption("슈퍼스트링", "슈퍼스트링"),
    FilterOption("로맨스릴러", "로맨스릴러"),
    FilterOption("스핀오프", "스핀오프"),
    FilterOption("후회남", "후회남"),
    FilterOption("능력녀", "능력녀"),
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
    FilterOption("별점순", "starScore"), // 백엔드 API 규칙에 맞춘 소문자
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
            return super.searchMangaRequest(page, query, filters)
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
            return super.searchMangaRequest(page, query, filters)
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
