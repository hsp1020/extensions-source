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

        val actualGenre = if (genreParam == "드라마 (태그)") "드라마" else genreParam
        val isStandardGenre = listOf("DAILY", "COMIC", "FANTASY", "ACTION", "DRAMA", "PURE", "SENSIBILITY", "THRILL", "HISTORICAL", "SPORTS", "PERIOD", "BRAND").contains(actualGenre)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (dayParam == "finished") {
                addPathSegments("api/webtoon/titlelist/finished")
                addQueryParameter("order", sortParam)
                addQueryParameter("page", page.toString())
            } else if (actualGenre.isNotEmpty() && dayParam.isEmpty()) {
                if (isStandardGenre) {
                    addPathSegments("api/webtoon/titlelist/genre")
                    addQueryParameter("genre", actualGenre)
                    val finalSortParam = if (sortParam == "STAR_SCORE") "STAR" else sortParam
                    addQueryParameter("order", finalSortParam)
                    addQueryParameter("page", page.toString())
                } else {
                    addPathSegments("api/search/webtoon")
                    addQueryParameter("keyword", actualGenre)
                    addQueryParameter("page", page.toString())
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

        val reqHeaders = headers.newBuilder()
            .add("X-Sort-Param", sortParam)
            .add("X-Is-Tag-Search", if (!isStandardGenre && actualGenre.isNotEmpty() && dayParam.isEmpty()) "true" else "false")
            .build()

        return GET(url, reqHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val bodyString = response.body.string()
        val jsonObject = json.parseToJsonElement(bodyString).jsonObject

        if (jsonObject.containsKey("searchList")) {
            val result = json.decodeFromJsonElement<ApiMangaSearchResponse>(jsonObject)
            val mangas = result.searchList
            val hasNextPage = result.hasNextPage
            
            val sortParam = response.request.header("X-Sort-Param")
            val isTagSearch = response.request.header("X-Is-Tag-Search") == "true"
            
            if (isTagSearch && sortParam != null && sortParam != "USER") {
                val allMangas = mutableListOf<Manga>()
                allMangas.addAll(mangas)
                
                var currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
                var next = hasNextPage
                
                while (next && currentPage < 50) {
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
    FilterOption("연도별웹툰", "PERIOD"), 
    FilterOption("브랜드웹툰", "BRAND"),   
    FilterOption("드라마 (태그)", "드라마 (태그)"), 
    FilterOption("드라마&영화 원작웹툰", "드라마&영화 원작웹툰"),
    FilterOption("먼치킨", "먼치킨"),
    FilterOption("학원로맨스", "학원로맨스"),
    FilterOption("학원액션", "학원액션"),
    FilterOption("로판", "로판"),
    FilterOption("게임판타지", "게임판타지"),
    FilterOption("타임슬립", "타임슬립"),
    FilterOption("짐승남", "짐승남"),
    FilterOption("대형견남", "대형견남"),
    FilterOption("감염", "감염"),
    FilterOption("중세", "중세"),
    FilterOption("할리퀸", "할리퀸"),
    FilterOption("2014 최강자전", "2014 최강자전"),
    FilterOption("바디 호러", "바디 호러"),
    FilterOption("집착남", "집착남"),
    FilterOption("비밀연애", "비밀연애"),
    FilterOption("액션판타지", "액션판타지"),
    FilterOption("햇살남", "햇살남"),
    FilterOption("참교육", "참교육"),
    FilterOption("2025 지상최대공모전", "2025 지상최대공모전"),
    FilterOption("연상연하", "연상연하"),
    FilterOption("공감성수치", "공감성수치"),
    FilterOption("상태창", "상태창"),
    FilterOption("인생역전", "인생역전"),
    FilterOption("소심녀", "소심녀"),
    FilterOption("삼각관계", "삼각관계"),
    FilterOption("흑막녀", "흑막녀"),
    FilterOption("지금추천작", "지금추천작"),
    FilterOption("SF액션", "SF액션"),
    FilterOption("피카레스크", "피카레스크"),
    FilterOption("로맨스릴러", "로맨스릴러"),
    FilterOption("의학드라마", "의학드라마"),
    FilterOption("4컷만화", "4컷만화"),
    FilterOption("로맨스코미디", "로맨스코미디"),
    FilterOption("힘숨찐", "힘숨찐"),
    FilterOption("성장로판", "성장로판"),
    FilterOption("순진녀", "순진녀"),
    FilterOption("아이돌", "아이돌"),
    FilterOption("이능력", "이능력"),
    FilterOption("직업드라마", "직업드라마"),
    FilterOption("고자극스릴러", "고자극스릴러"),
    FilterOption("2013 최강자전", "2013 최강자전"),
    FilterOption("짝사랑", "짝사랑"),
    FilterOption("혐관", "혐관"),
    FilterOption("사내연애", "사내연애"),
    FilterOption("캠퍼스", "캠퍼스"),
    FilterOption("2015 최강자전", "2015 최강자전"),
    FilterOption("현대판타지", "현대판타지"),
    FilterOption("성장드라마", "성장드라마"),
    FilterOption("고자극로맨스", "고자극로맨스"),
    FilterOption("동물", "동물"),
    FilterOption("낮져밤이", "낮져밤이"),
    FilterOption("악녀", "악녀"),
    FilterOption("흑백", "흑백"),
    FilterOption("소년물", "소년물"),
    FilterOption("후회남", "후회남"),
    FilterOption("미래", "미래"),
    FilterOption("2024 연재직행열차", "2024 연재직행열차"),
    FilterOption("러블리", "러블리"),
    FilterOption("능력녀", "능력녀"),
    FilterOption("2019 지상최대공모전", "2019 지상최대공모전"),
    FilterOption("슈퍼스트링", "슈퍼스트링"),
    FilterOption("2023 지상최대공모전", "2023 지상최대공모전"),
    FilterOption("중세판타지액션", "중세판타지액션"),
    FilterOption("사연남", "사연남"),
    FilterOption("연예계로맨스", "연예계로맨스"),
    FilterOption("공식미녀", "공식미녀"),
    FilterOption("하렘", "하렘"),
    FilterOption("아이돌연애", "아이돌연애"),
    FilterOption("히어로", "히어로"),
    FilterOption("학원물", "학원물"),
    FilterOption("세계관", "세계관"),
    FilterOption("예술", "예술"),
    FilterOption("미스터리", "미스터리"),
    FilterOption("성별반전", "성별반전"),
    FilterOption("명작", "명작"),
    FilterOption("시리어스", "시리어스"),
    FilterOption("직진녀", "직진녀"),
    FilterOption("사천당가", "사천당가"),
    FilterOption("회귀", "회귀"),
    FilterOption("블루스트링", "블루스트링"),
    FilterOption("인플루언서", "인플루언서"),
    FilterOption("모험", "모험"),
    FilterOption("청춘로맨스", "청춘로맨스"),
    FilterOption("컷툰", "컷툰"),
    FilterOption("공감", "공감"),
    FilterOption("헌신남", "헌신남"),
    FilterOption("재회", "재회"),
    FilterOption("걸크러시", "걸크러시"),
    FilterOption("군림녀", "군림녀"),
    FilterOption("프리퀄", "프리퀄"),
    FilterOption("까칠남", "까칠남"),
    FilterOption("자본주의", "자본주의"),
    FilterOption("2020 지상최대공모전", "2020 지상최대공모전"),
    FilterOption("일상개그힐링", "일상개그힐링"),
    FilterOption("스팀펑크", "스팀펑크"),
    FilterOption("사회고발", "사회고발"),
    FilterOption("2021 지상최대공모전", "2021 지상최대공모전"),
    FilterOption("계략녀", "계략녀"),
    FilterOption("영화원작웹툰", "영화원작웹툰"),
    FilterOption("2024 지상최대공모전", "2024 지상최대공모전"),
    FilterOption("리얼로맨스", "리얼로맨스"),
    FilterOption("데스게임", "데스게임"),
    FilterOption("사이비종교", "사이비종교"),
    FilterOption("재벌", "재벌"),
    FilterOption("2021 최강자전", "2021 최강자전"),
    FilterOption("다크히어로", "다크히어로"),
    FilterOption("평범남", "평범남"),
    FilterOption("러브코미디", "러브코미디"),
    FilterOption("무심녀", "무심녀"),
    FilterOption("2024 최강자전", "2024 최강자전"),
    FilterOption("복수극", "복수극"),
    FilterOption("던전물", "던전물"),
    FilterOption("격투기", "격투기"),
    FilterOption("순정녀", "순정녀"),
    FilterOption("농구", "농구"),
    FilterOption("왕족/귀족", "왕족/귀족"),
    FilterOption("농사", "농사"),
    FilterOption("절망적인", "절망적인"),
    FilterOption("다정녀", "다정녀"),
    FilterOption("최강자전", "최강자전"),
    FilterOption("인소감성", "인소감성"),
    FilterOption("법정드라마", "법정드라마"),
    FilterOption("무심남", "무심남"),
    FilterOption("액션아포칼립스", "액션아포칼립스"),
    FilterOption("배틀", "배틀"),
    FilterOption("2017 최강자전", "2017 최강자전"),
    FilterOption("마법", "마법"),
    FilterOption("순애남", "순애남"),
    FilterOption("공포", "공포"),
    FilterOption("애니메이션", "애니메이션"),
    FilterOption("판타지개그", "판타지개그"),
    FilterOption("직진남", "직진남"),
    FilterOption("공식미남", "공식미남"),
    FilterOption("치명적인", "치명적인"),
    FilterOption("가벼운", "가벼운"),
    FilterOption("사이버펑크", "사이버펑크"),
    FilterOption("디스토피아", "디스토피아"),
    FilterOption("야구", "야구"),
    FilterOption("이능력배틀물", "이능력배틀물"),
    FilterOption("판무", "판무"),
    FilterOption("순진남", "순진남"),
    FilterOption("서바이벌", "서바이벌"),
    FilterOption("폭스남", "폭스남"),
    FilterOption("동양", "동양"),
    FilterOption("혐관로맨스", "혐관로맨스"),
    FilterOption("오컬트", "오컬트"),
    FilterOption("궁중로맨스", "궁중로맨스"),
    FilterOption("다크판타지", "다크판타지"),
    FilterOption("음식&요리", "음식&요리"),
    FilterOption("용사", "용사"),
    FilterOption("차원이동", "차원이동"),
    FilterOption("크리처", "크리처"),
    FilterOption("재벌남", "재벌남"),
    FilterOption("열혈병맛개그", "열혈병맛개그"),
    FilterOption("미친작화", "미친작화"),
    FilterOption("짝사랑남", "짝사랑남"),
    FilterOption("햇살캐", "햇살캐"),
    FilterOption("첫사랑", "첫사랑"),
    FilterOption("2020 최강자전", "2020 최강자전"),
    FilterOption("감성적인", "감성적인"),
    FilterOption("음악", "음악"),
    FilterOption("헌터물", "헌터물"),
    FilterOption("2025 고등 최강자전", "2025 고등 최강자전"),
    FilterOption("힐링", "힐링"),
    FilterOption("환골탈태", "환골탈태"),
    FilterOption("청춘", "청춘"),
    FilterOption("밀리터리", "밀리터리"),
    FilterOption("입시", "입시"),
    FilterOption("연예계", "연예계"),
    FilterOption("햇살녀", "햇살녀"),
    FilterOption("짝사랑녀", "짝사랑녀"),
    FilterOption("얼빠녀", "얼빠녀"),
    FilterOption("사이다", "사이다"),
    FilterOption("게임", "게임"),
    FilterOption("레트로", "레트로"),
    FilterOption("까칠녀", "까칠녀"),
    FilterOption("느와르", "느와르"),
    FilterOption("현대", "현대"),
    FilterOption("퓨전사극", "퓨전사극"),
    FilterOption("친구>연인", "친구>연인"),
    FilterOption("정통무협", "정통무협"),
    FilterOption("괴담", "괴담"),
    FilterOption("시대물", "시대물"),
    FilterOption("정치", "정치"),
    FilterOption("하이틴", "하이틴"),
    FilterOption("전남친", "전남친"),
    FilterOption("우정", "우정"),
    FilterOption("고자극드라마", "고자극드라마"),
    FilterOption("열혈", "열혈"),
    FilterOption("동양풍판타지", "동양풍판타지"),
    FilterOption("오피스로맨스", "오피스로맨스"),
    FilterOption("외유내강녀", "외유내강녀"),
    FilterOption("2022 지상최대공모전", "2022 지상최대공모전"),
    FilterOption("가족", "가족"),
    FilterOption("오피스", "오피스"),
    FilterOption("역사판타지", "역사판타지"),
    FilterOption("집착물", "집착물"),
    FilterOption("중년", "중년"),
    FilterOption("스포츠성장", "스포츠성장"),
    FilterOption("집착녀", "집착녀"),
    FilterOption("자극적인", "자극적인"),
    FilterOption("라이벌", "라이벌"),
    FilterOption("SF", "SF"),
    FilterOption("육아물", "육아물"),
    FilterOption("캠퍼스로맨스", "캠퍼스로맨스"),
    FilterOption("인외존재", "인외존재"),
    FilterOption("비쥬얼쇼크", "비쥬얼쇼크"),
    FilterOption("재벌녀", "재벌녀"),
    FilterOption("신화", "신화"),
    FilterOption("결혼생활", "결혼생활"),
    FilterOption("4차원", "4차원"),
    FilterOption("동아리", "동아리"),
    FilterOption("2016 최강자전", "2016 최강자전"),
    FilterOption("하이퍼리얼리즘", "하이퍼리얼리즘"),
    FilterOption("레드아이스 스튜디오", "레드아이스 스튜디오"),
    FilterOption("전쟁", "전쟁"),
    FilterOption("이세계", "이세계"),
    FilterOption("아방녀", "아방녀"),
    FilterOption("독자PICK", "독자PICK"),
    FilterOption("환생", "환생"),
    FilterOption("2025 연재직행열차", "2025 연재직행열차"),
    FilterOption("유혹녀", "유혹녀"),
    FilterOption("무해한", "무해한"),
    FilterOption("주식", "주식"),
    FilterOption("사연녀", "사연녀"),
    FilterOption("오컬트판타지", "오컬트판타지"),
    FilterOption("요즘핫한추천작", "요즘핫한추천작"),
    FilterOption("축구", "축구"),
    FilterOption("순애녀", "순애녀"),
    FilterOption("좀비", "좀비"),
    FilterOption("동양풍로맨스", "동양풍로맨스"),
    FilterOption("두뇌싸움", "두뇌싸움"),
    FilterOption("생존", "생존"),
    FilterOption("서양", "서양"),
    FilterOption("레드스트링", "레드스트링"),
    FilterOption("2022 최강자전", "2022 최강자전"),
    FilterOption("역하렘", "역하렘"),
    FilterOption("소년왕도물", "소년왕도물"),
    FilterOption("명랑녀", "명랑녀"),
    FilterOption("소꿉친구", "소꿉친구"),
    FilterOption("블랙코미디", "블랙코미디"),
    FilterOption("눈물샘자극", "눈물샘자극"),
    FilterOption("해외작품", "해외작품"),
    FilterOption("빙의", "빙의"),
    FilterOption("2018 최강자전", "2018 최강자전"),
    FilterOption("범죄", "범죄"),
    FilterOption("이세계요리", "이세계요리"),
    FilterOption("설렘폭발", "설렘폭발"),
    FilterOption("과거", "과거"),
    FilterOption("연애/결혼공감", "연애/결혼공감"),
    FilterOption("서스펜스", "서스펜스"),
    FilterOption("투병", "투병"),
    FilterOption("병맛", "병맛"),
    FilterOption("고인물", "고인물"),
    FilterOption("감성드라마", "감성드라마"),
    FilterOption("선결혼후연애", "선결혼후연애"),
    FilterOption("계약연애", "계약연애"),
    FilterOption("철벽녀", "철벽녀"),
    FilterOption("역사물", "역사물"),
    FilterOption("2019 최강자전", "2019 최강자전"),
    FilterOption("뱀파이어", "뱀파이어"),
    FilterOption("머니게임", "머니게임"),
    FilterOption("구원서사", "구원서사"),
    FilterOption("아카데미물", "아카데미물"),
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
    FilterOption("별점순", "STAR"), 
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
    
    // [추가] JSON 파서 선언
    private val json = Json { ignoreUnknownKeys = true }

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
        
        val apiOrder = if (sortParam == "STAR" || sortParam == "VIEW") "VIEW" else sortParam

        val url = "$baseUrl/api/$mType/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("order", apiOrder)
            addQueryParameter("page", page.toString())
            if (genreParam.isNotEmpty()) {
                addQueryParameter("genre", genreParam)
            }
        }.build()
        
        val reqHeaders = headers.newBuilder()
            .add("X-Sort-Param", sortParam)
            .build()
            
        return GET(url, reqHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.contains("/search/")) {
            return super.searchMangaParse(response)
        }
        
        val bodyString = response.body.string()
        val jsonObject = json.parseToJsonElement(bodyString).jsonObject
        val result = json.decodeFromJsonElement<ApiMangaChallengeResponse>(jsonObject)
        
        val sortParam = response.request.header("X-Sort-Param")
        
        if (sortParam == "STAR") {
            val allMangas = mutableListOf<MangaChallenge>()
            allMangas.addAll(result.list)
            
            var currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
            var next = result.pageInfo?.nextPage != null && result.pageInfo.nextPage != 0
            
            while (next && currentPage < 50) {
                currentPage++
                val nextUrl = response.request.url.newBuilder().setQueryParameter("page", currentPage.toString()).build()
                val nextReq = GET(nextUrl, response.request.headers)
                val nextRes = client.newCall(nextReq).execute()
                val nextJson = json.parseToJsonElement(nextRes.body.string()).jsonObject
                
                val nextResult = json.decodeFromJsonElement<ApiMangaChallengeResponse>(nextJson)
                allMangas.addAll(nextResult.list)
                next = nextResult.pageInfo?.nextPage != null && nextResult.pageInfo.nextPage != 0
            }
            
            val sortedMangas = allMangas.sortedByDescending { it.starScore }
            return MangasPage(sortedMangas.map { it.toSManga(mType) }.distinctBy { it.url }, false)
        }
        
        return MangasPage(result.toSMangas(mType), result.pageInfo?.nextPage != null && result.pageInfo.nextPage != 0)
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
    
    // [추가] JSON 파서 선언
    private val json = Json { ignoreUnknownKeys = true }

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
        
        val apiOrder = if (sortParam == "STAR" || sortParam == "VIEW") "VIEW" else sortParam

        val url = "$baseUrl/api/$mType/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("order", apiOrder)
            addQueryParameter("page", page.toString())
            if (genreParam.isNotEmpty()) {
                addQueryParameter("genre", genreParam)
            }
        }.build()
        
        val reqHeaders = headers.newBuilder()
            .add("X-Sort-Param", sortParam)
            .build()
            
        return GET(url, reqHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.contains("/search/")) {
            return super.searchMangaParse(response)
        }
        
        val bodyString = response.body.string()
        val jsonObject = json.parseToJsonElement(bodyString).jsonObject
        val result = json.decodeFromJsonElement<ApiMangaChallengeResponse>(jsonObject)
        
        val sortParam = response.request.header("X-Sort-Param")
        
        if (sortParam == "STAR") {
            val allMangas = mutableListOf<MangaChallenge>()
            allMangas.addAll(result.list)
            
            var currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
            var next = result.pageInfo?.nextPage != null && result.pageInfo.nextPage != 0
            
            while (next && currentPage < 50) {
                currentPage++
                val nextUrl = response.request.url.newBuilder().setQueryParameter("page", currentPage.toString()).build()
                val nextReq = GET(nextUrl, response.request.headers)
                val nextRes = client.newCall(nextReq).execute()
                val nextJson = json.parseToJsonElement(nextRes.body.string()).jsonObject
                
                val nextResult = json.decodeFromJsonElement<ApiMangaChallengeResponse>(nextJson)
                allMangas.addAll(nextResult.list)
                next = nextResult.pageInfo?.nextPage != null && nextResult.pageInfo.nextPage != 0
            }
            
            val sortedMangas = allMangas.sortedByDescending { it.starScore }
            return MangasPage(sortedMangas.map { it.toSManga(mType) }.distinctBy { it.url }, false)
        }
        
        return MangasPage(result.toSMangas(mType), result.pageInfo?.nextPage != null && result.pageInfo.nextPage != 0)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("키워드 검색 시 아래 필터는 무시됩니다."),
        ChallengeSortFilter(),
        ChallengeGenreFilter(),
    )
}
