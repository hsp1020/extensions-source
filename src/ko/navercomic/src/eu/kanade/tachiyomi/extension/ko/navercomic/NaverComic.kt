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
        // 일반 키워드 검색일 경우 기존 API 호출
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
            // 필터 우선순위 분기 (요일/완결 > 장르)
            if (dayParam == "finished") {
                addPathSegments("api/webtoon/titlelist/finished")
            } else if (genreParam.isNotEmpty() && dayParam.isEmpty()) {
                addPathSegments("api/webtoon/titlelist/genre")
                addQueryParameter("genre", genreParam)
            } else {
                addPathSegments("api/webtoon/titlelist/weekday")
                if (dayParam.isNotEmpty()) {
                    addQueryParameter("week", dayParam)
                }
            }
            
            addQueryParameter("order", sortParam)
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject

        // 일반 키워드 검색 응답 처리 (searchList 포함)
        if (jsonObject.containsKey("searchList")) {
            val result = json.decodeFromJsonElement<ApiMangaSearchResponse>(jsonObject)
            return MangasPage(result.toSMangas(mType), result.hasNextPage)
        }

        // 요일/장르/완결 필터 API 응답 처리
        return parseWebtoonListJson(jsonObject)
    }

    private fun parseWebtoonList(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        return parseWebtoonListJson(jsonObject)
    }

    private fun parseWebtoonListJson(jsonObject: kotlinx.serialization.json.JsonObject): MangasPage {
        val titleList = mutableListOf<Manga>()
        
        // 단일 리스트 형태 (장르, 완결, 특정 요일 선택 시)
        if (jsonObject.containsKey("titleList")) {
            titleList.addAll(json.decodeFromJsonElement<List<Manga>>(jsonObject["titleList"]!!))
        } 
        // Map 형태 (요일 전체 선택 시 MONDAY, TUESDAY 등으로 나뉘어 옴)
        else if (jsonObject.containsKey("titleListMap")) {
            val map = json.decodeFromJsonElement<Map<String, List<Manga>>>(jsonObject["titleListMap"]!!)
            map.values.forEach { titleList.addAll(it) }
        }

        val pageInfo = jsonObject["pageInfo"]?.let { json.decodeFromJsonElement<PageInfo>(it) }
        val hasNextPage = pageInfo?.nextPage != 0 && pageInfo?.nextPage != null

        // 요일 '전체' 검색 시 여러 요일에 연재하는 작품이 중복되므로 URL 기준으로 distinct 처리
        val mangas = titleList.map { it.toSManga(mType) }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("키워드 검색 시 아래 필터는 무시됩니다."),
        SortFilter(),
        GenreFilter(),
        DayFilter(),
    )
}

// 필터 데이터 정의
data class FilterOption(val name: String, val value: String)

internal val sortList = listOf(
    FilterOption("인기순", "USER"),
    FilterOption("업데이트순", "UPDATE"),
    FilterOption("조회순", "VIEW"),
    FilterOption("별점순", "STAR_SCORE"),
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
    
    // 추가 요청하신 세부 태그 필터 목록
    FilterOption("드라마&영화 원작웹툰", "드라마&영화 원작웹툰"),
    FilterOption("먼치킨", "먼치킨"),
    FilterOption("학원로맨스", "학원로맨스"),
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
