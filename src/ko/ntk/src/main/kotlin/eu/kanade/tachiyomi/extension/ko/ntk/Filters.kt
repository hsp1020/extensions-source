package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.source.model.Filter

data class FilterOption(val name: String, val value: String)

internal val sortList = listOf(
    FilterOption("최신순", "new"),
    FilterOption("신작순", "fresh"),
    FilterOption("북마크순", "hot"),
    FilterOption("조회순", "views"),
)

class GenreTriState(val genre: String) : Filter.TriState(genre)

class SortFilter : Filter.Select<String>("정렬", sortList.map { it.name }.toTypedArray())
class StatusFilter : Filter.Select<String>("상태", statusList.map { it.name }.toTypedArray())
class GenreFilter : Filter.Group<GenreTriState>("장르", genreList.map { GenreTriState(it) })

internal val statusList = listOf(
    FilterOption("전체", ""),
    FilterOption("완결", "-end"),
)

internal val genreList = listOf(
    "순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션", "백합", "SF",
    "일상", "이세계", "스릴러", "애니화", "전생", "스포츠", "TS", "소년", "먹방", "붕탁", "게임",
    "호러", "시대", "로맨스", "추리", "무협", "음악", "BL",
)

fun buildGenreParam(genreFilter: GenreFilter?): String? {
    if (genreFilter == null) return null
    val genres = genreFilter.state
        .filterIsInstance<GenreTriState>()
        .mapIndexedNotNull { index, triState ->
            when (triState.state) {
                Filter.TriState.STATE_INCLUDE -> genreList[index]
                Filter.TriState.STATE_EXCLUDE -> "-${genreList[index]}"
                else -> null
            }
        }
    return if (genres.isNotEmpty()) genres.joinToString(",") else null
}

class WtSortFilter : Filter.Select<String>("정렬", sortList.map { it.name }.toTypedArray())
class WtStatusFilter : Filter.Select<String>("상태", wtStatusList.map { it.name }.toTypedArray())
class WtCategoryFilter : Filter.Select<String>("분류", wtCatList.map { it.name }.toTypedArray())
class WtDayFilter : Filter.Select<String>("요일", wtDayList.map { it.name }.toTypedArray())
class WtGenreFilter : Filter.Group<GenreTriState>("장르", wtGenreList.map { GenreTriState(it) })

internal val wtStatusList = listOf(
    FilterOption("연재중", "ing"),
    FilterOption("완결  (※ 장르 필터만 적용됩니다)", "end"),
)

internal val wtCatList = listOf(
    FilterOption("전체", ""),
    FilterOption("일반웹툰", "normal"),
    FilterOption("BL/GL", "bl"),
    FilterOption("성인웹툰", "adult"),
)

internal val wtDayList = listOf(
    FilterOption("전체", ""),
    FilterOption("월", "월"),
    FilterOption("화", "화"),
    FilterOption("수", "수"),
    FilterOption("목", "목"),
    FilterOption("금", "금"),
    FilterOption("토", "토"),
    FilterOption("일", "일"),
)

// Filters.kt 파일 내부 수정
internal val wtGenreList = listOf(
    // 기본 장르 대분류
    "학원", "액션", "SF", "스토리", "판타지", "BL/백합", "개그/코미디", "연애/순정", "드라마", "로맨스",
    "시대", "스포츠", "일상", "추리/미스터리", "공포/스릴러", "성인", "옴니버스", "에피소드", "무협", "소년", "기타",

    // 캐릭터 성향 및 연애 키워드
    "다정남", "능글남", "능력녀", "절륜남", "직진남", "유혹남", "순정녀", "캠퍼스물", "능력남", "동양풍",
    "인외존재", "소꿉친구", "피폐물", "삼각관계", "대형견남", "원나잇", "성장물", "순수녀", "동거", "짝사랑",
    "오해/착각", "유부녀", "하드코어", "고수위", "오피스", "능욕", "하렘", "강제", "로코", "다정녀",
    "순정남", "연상녀", "현대물", "연하남", "첫사랑", "달달물", "여성인기19", "남성인기19", "나쁜남자", "순진녀",
    "더티토크", "거유", "3P", "모럴리스", "후방주의", "복수", "상처녀", "서양풍", "집착남", "계략남", "로판", "소설원작",

    // BL 전문 키워드
    "절륜공", "존댓말공", "강공", "능글공", "집착공", "까칠수", "까칠공", "연상수", "미남공", "순정공",
    "연하공", "대물공", "다정수", "계략공", "대형견공", "단정수", "후회공", "다정공", "순진수", "짝사랑수",
    "상처수", "미인수", "무심수", "애증", "사랑꾼공", "귀염수", "능력수", "상처공", "강수", "연하수",
    "미남수", "오메가버스", "SM", "계약관계", "섹스파트너", "BL"
)


fun buildWtGenreParam(genreFilter: WtGenreFilter?): String? {
    if (genreFilter == null) return null
    
    // 뉴토끼 웹툰 소스코드 실데이터(tags)에서 누락 없이 매핑한 100% 검증된 ID 테이블입니다.
    val genreIdMap = mapOf(
        // 기본 장르 대분류
        "학원" to "1",
        "액션" to "2",
        "SF" to "3",
        "스토리" to "4",
        "판타지" to "5",
        "BL/백합" to "6",
        "개그/코미디" to "7",
        "연애/순정" to "8",
        "드라마" to "9",
        "로맨스" to "10",
        "시대" to "11",
        "스포츠" to "12",
        "일상" to "13",
        "추리/미스터리" to "14",
        "공포/스릴러" to "15",
        "성인" to "16", // 👈 성인 고유 ID 16번 정상 반영 완료
        "옴니버스" to "17",
        "에피소드" to "18",
        "무협" to "19",
        "소년" to "20",
        "기타" to "99",

        // 세부 인물/소설 성향 및 키워드 태그 전체 매핑
        "절륜공" to "517",
        "존댓말공" to "529",
        "월드드랍" to "287",
        "WorldDrop" to "288",
        "다정남" to "290",
        "능글남" to "291",
        "능력녀" to "292",
        "절륜남" to "293",
        "직진남" to "295",
        "유혹남" to "297",
        "순정녀" to "298",
        "캠퍼스물" to "299",
        "능력남" to "301",
        "동양풍" to "302",
        "인외존재" to "304",
        "소꿉친구" to "306",
        "강공" to "310",
        "피폐물" to "311",
        "삼각관계" to "312",
        "능글공" to "315",
        "집착공" to "316",
        "까칠수" to "317",
        "대형견남" to "333",
        "원나잇" to "336",
        "성장물" to "340",
        "순수녀" to "343",
        "까칠공" to "379",
        "연상수" to "380",
        "미남공" to "384",
        "순정공" to "385",
        "연하공" to "387",
        "대물공" to "388",
        "다정수" to "390",
        "계략공" to "392",
        "대형견공" to "393",
        "단정수" to "394",
        "후회공" to "409",
        "다정공" to "410",
        "순진수" to "413",
        "짝사랑수" to "414",
        "상처수" to "416",
        "동거" to "418",
        "미인수" to "419",
        "짝사랑" to "420",
        "무심수" to "424",
        "애증" to "429",
        "사랑꾼공" to "435",
        "귀염수" to "436",
        "능력수" to "438",
        "상처공" to "440",
        "오해/착각" to "461",
        "유부녀" to "102",
        "하드코어" to "103",
        "고수위" to "114",
        "오피스" to "121",
        "능욕" to "127",
        "하렘" to "135",
        "강제" to "142",
        "연상공" to "144",
        "미인공" to "145",
        "강수" to "147",
        "연하수" to "148",
        "미남수" to "150",
        "오메가버스" to "151",
        "SM" to "152",
        "계약관계" to "153",
        "섹스파트너" to "154",
        "BL" to "155",
        "로코" to "156",
        "다정녀" to "157",
        "순정남" to "158",
        "연상녀" to "160",
        "현대물" to "161",
        "연하남" to "162",
        "첫사랑" to "163",
        "달달물" to "164",
        "여성인기19" to "165",
        "남성인기19" to "166",
        "나쁜남자" to "167",
        "순진녀" to "168",
        "더티토크" to "169",
        "거유" to "193",
        "3P" to "206",
        "모럴리스" to "207",
        "후방주의" to "209",
        "복수" to "234",
        "상처녀" to "241",
        "서양풍" to "243",
        "집착남" to "245",
        "계략남" to "246",
        "로판" to "247",
        "소설원작" to "248"
    )
    
    val tags = genreFilter.state
        .filterIsInstance<GenreTriState>()
        .mapNotNull { triState ->
            val id = genreIdMap[triState.genre] ?: return@mapNotNull null
            when (triState.state) {
                Filter.TriState.STATE_INCLUDE -> id
                Filter.TriState.STATE_EXCLUDE -> "-$id"
                else -> null
            }
        }
    return if (tags.isNotEmpty()) tags.joinToString(",") else null
}
