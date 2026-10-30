package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            // Registration has its own protocol; it is not a search/rank/detail business request.
            val body=if(request.url.encodedPath=="/service/2/device_register/") "{}" else respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun rankLoader(board: RankBoard,page: Int) = "<script>_ROUTER_DATA="+
        JSONObject().put("loaderData",JSONObject().put(board.route,JSONObject().put("rankKey",board.rankKey).put("pageNum",page)))+
        ";</script>"

    private fun currentRank(payload: JSONObject) =
        rankLoader(RankBoard.COMIC,payload.getJSONObject("pagination").getInt("pageNum"))+
        script("mergeLoaderData",JSONArray()
            .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
                .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsDerivedFromOrderAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(5,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedCategoryAndRealPagination() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            assertEquals("/category/comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            categoryHtml(JSONArray().put(JSONObject().put("series_id","123").put("series_name","测试漫剧")
                .put("series_cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
                .put("episode_cnt",12).put("tags",JSONArray().put("玄幻"))),totalPages=2)
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
        assertEquals("category success must not request the mixed rank board",1,requests.size)
    }

    @Test fun legacyRankIsStillSupported() {
        val html=rankLoader(RankBoard.COMIC,1)+
            script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload()))
        assertTrue(repository { html }.comicRanking(1).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { rankLoader(RankBoard.REAL,1)+script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    private fun Request.websiteSearchResult(body: String): String {
        assertEquals("hongguoduanju.com",url.host)
        assertEquals("/api/search/result/",url.encodedPath)
        assertTrue("search query must be carried to website fallback",!url.queryParameter("query").isNullOrBlank())
        return body
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
            else request.websiteSearchResult("""{"searchList":[{"video_data":{"series_id":"333","series_name":"漫画网站结果","genre":"205"}}],"totalPages":2,"hasMore":true}""")
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        repo.search("重生",2,ContentType.COMIC)
        assertEquals("2",requests[2].url.queryParameter("page"))
        assertEquals(3,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
            else request.websiteSearchResult("""{"searchList":[{"video_data":{"series_id":"444","series_name":"漫画兜底","genre":"205"}}],"totalPages":2,"hasMore":true}""")
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertEquals("444",repo.search("修仙",2,ContentType.COMIC).items.single().id)
        assertEquals(2,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(4,requests)
    }

    @Test fun comicSearchFallsBackToWebsiteAndKeepsOnlyComicGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else request.websiteSearchResult("""{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}],"totalPages":2,"hasMore":true}""")
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("222"),result.items.map { it.id })
        assertTrue(result.hasMore)
    }

    @Test fun shortSearchFallsBackToWebsiteAndKeepsOnlyShortGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else request.websiteSearchResult("""{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}],"totalPages":2,"hasMore":true}""")
        }
        val result=repo.search("总裁",1,ContentType.SHORT)
        assertEquals(listOf("111"),result.items.map { it.id })
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabFallsBackToWebsiteWithoutShortResults() {
        var requests=0
        val repo=repository { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
            else request.websiteSearchResult("""{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}}],"totalPages":1,"hasMore":false}""")
        }
        assertTrue(repo.search("修仙",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            request.websiteSearchResult("""{"searchList":[{"series_id":"456","series_name":"测试短剧","genre":"203"}],"totalPages":1,"hasMore":false}""")
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
        assertEquals(listOf("/reading/bookapi/search/tab/v","/api/search/result/"),paths)
    }

    private fun relatedItem(id: String,name: String)=JSONObject().put("series_id",id).put("series_name",name)
        .put("series_cover","https://example.com/$id.jpg").put("episode_cnt",10).put("episode_right_text","全 10 集")
    private fun detailHtml(series: JSONObject,recommended: JSONArray): String {
        val recommendations=JSONObject().put("videoList",JSONArray())
            .put("recommendationGroups",JSONObject().put("related_list",recommended))
        val args=JSONArray().put("detail_page").put(JSONArray().put(JSONObject().put("key","recommendations")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(recommendations.toString()))))
        val attribute=args.toString().replace("&","&amp;").replace("\"","&quot;")
        return """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":$series}}};</script><script data-fn-args="$attribute"></script>"""
    }

    @Test fun detailRelatedHoistsSameNameSeasonsAndSkipsCurrentSeries() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧").put("vid_list",JSONArray().put("900").put("901"))
        val html=detailHtml(series,JSONArray()
            .put(relatedItem("111","别人的剧"))
            .put(relatedItem("222","测试剧"))
            .put(relatedItem("333","测试剧 第二季"))
            .put(relatedItem("123","测试剧")))
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertEquals(listOf("222","333","111"),detail.related.map { it.id })
    }

    @Test fun detailWithoutRecommendationPayloadStillLoadsEpisodes() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"测试剧","vid_list":["900","901"]}}}};</script>"""
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertTrue(detail.related.isEmpty())
    }

    @Test fun detailCollectsSameNameSeasonsFromWebsiteSearchOrderedBySeason() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧 第二季").put("vid_list",JSONArray().put("900"))
        val repo=repository { request -> when(request.url.encodedPath) {
            "/detail" -> detailHtml(series,JSONArray())
            "/api/search/result/" -> """{"searchList":[
                {"video_data":{"series_id":"222","series_name":"测试剧 第三季","genre":"203"}},
                {"video_data":{"series_id":"111","series_name":"别人的剧","genre":"203"}},
                {"video_data":{"series_id":"333","series_name":"测试剧 第一季","genre":"203"}},
                {"video_data":{"series_id":"444","series_name":"测试剧 第十季","genre":"203"}},
                {"video_data":{"series_id":"123","series_name":"测试剧 第二季","genre":"203"}}],"hasMore":false}"""
            else -> """{"code":-1}"""
        } }
        val detail=repo.detail("123")
        assertEquals(listOf("333","222","444"),detail.sameName.map { it.id })
    }

    private fun currentRealRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-real-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun realRankingParsesTheShortDramaBoard() {
        val payload=rankPayload(1,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",1).put("heatText","9999万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-drama",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRealRank(payload)
        }
        val result=repo.realRanking(1)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(1,"9999万热度"),result.ranking!!.positions["123"])
        assertEquals("今日已更新",result.ranking!!.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun realRankingRejectsTheComicBoardPayload() {
        val repo=repository { currentRank(rankPayload()) }
        assertThrows(IOException::class.java) { repo.realRanking(1) }
    }

    @Test fun renderedRealRankingWorksWithoutStreamedPayload() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">22</span>
                <h2 id="rank-title-456">短剧</h2><p class="pc-metrics-example"><span>100万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-drama?page=5">5</a></nav>"""
        }
        val result=repo.realRanking(2)
        assertEquals(RankPosition(22,"100万热度"),result.ranking!!.positions["456"])
        assertEquals(5,result.ranking!!.totalPages)
        assertEquals("今日已更新",result.ranking!!.updatedText)
    }

    @Test fun unifiedRenderedRankWorksForBothBoardSelectorsWithoutRenumbering() {
        val repo=repository { request ->
            assertEquals("/rank/hot-drama",request.url.encodedPath)
            rankLoader(RankBoard.HOT,1)+"""
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">1</span>
                <h2 id="rank-title-123">测试漫剧</h2><p class="pc-metrics-example">9000万热度</p></article>
                <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">2</span>
                <h2 id="rank-title-456">测试短剧</h2><p class="pc-metrics-example">8000万热度</p></article>
                <nav aria-label="榜单分页"><span aria-current="page">1</span><a href="/rank/hot-drama?page=2">2</a></nav>"""
        }
        for(board in listOf(RankBoard.COMIC,RankBoard.REAL)) {
            val result=repo.ranking(board)
            assertEquals(listOf("123","456"),result.items.map { it.id })
            assertEquals(RankPosition(1,"9000万热度"),result.ranking!!.positions["123"])
            assertEquals(RankPosition(2,"8000万热度"),result.ranking!!.positions["456"])
            assertEquals(2,result.ranking!!.totalPages); assertTrue(result.hasMore)
        }
    }

    private fun selector(slug: String,name: String,vararg ids: String)=JSONObject().put("selector_item_id",slug).put("show_name",name)
        .put("category_json_ids",JSONArray().apply { ids.forEach { put(it) } })

    private fun categoryHtml(recommendList: JSONArray = JSONArray(),selectorList: JSONArray? = null,apiIds: JSONArray? = null,totalPages: Int = 1): String {
        val section=JSONObject().put("recommendList",recommendList).put("pagination",JSONObject().put("totalPages",totalPages))
        if(selectorList!=null) section.put("selectorList",selectorList)
        if(apiIds!=null) section.put("apiQuery",JSONObject().put("category_json_ids",apiIds))
        return """<script>_ROUTER_DATA={"loaderData":{"category_$":${section}}};</script>"""
    }

    @Test fun genresComeFromTheCategorySelectorAndAreCached() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("/category/real-drama",request.url.encodedPath)
            categoryHtml(selectorList=JSONArray().put(JSONObject().put("row_name","全部分类").put("items",JSONArray()
                .put(selector("romance","爱情","5000")).put(selector("costume","古装","5007")))))
        }
        val genres=repo.genres(ContentType.SHORT)
        assertEquals(listOf("romance","costume"),genres.map { it.slug })
        assertEquals(listOf("爱情","古装"),genres.map { it.name })
        assertEquals(listOf("5000"),genres.first().ids)
        assertEquals(genres,repo.genres(ContentType.SHORT))
        assertEquals(1,requests)
    }

    @Test fun genreHomeUsesTheSlugPathAndValidatesAppliedIds() {
        val rows=JSONArray().put(JSONObject().put("series_id","456").put("series_name","爱情短剧"))
        val repo=repository { request ->
            assertEquals("/category/real-drama/romance",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            categoryHtml(rows,apiIds=JSONArray().put("5000"),totalPages=9)
        }
        val page=repo.home(2,ContentType.SHORT,Genre("romance","爱情",listOf("5000")))
        assertEquals("456",page.items.single().id)
        assertTrue(page.hasMore)
    }

    @Test fun genreHomeFailsWhenTheSiteIgnoresTheFilter() {
        val repo=repository { categoryHtml(apiIds=JSONArray()) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.SHORT,Genre("romance","爱情",listOf("5000"))) }
    }

    @Test fun comicGenresUseTheComicCategoryPath() {
        val rows=JSONArray().put(JSONObject().put("series_id","789").put("series_name","脑洞漫剧"))
        val repo=repository { request ->
            assertEquals("/category/comic-drama/creative",request.url.encodedPath)
            categoryHtml(rows,apiIds=JSONArray().put("755"))
        }
        assertEquals("789",repo.home(1,ContentType.COMIC,Genre("creative","脑洞",listOf("755"))).items.single().id)
    }

    @Test fun suggestParsesQueryResultWordsAndDeduplicates() {
        val repo=repository { request ->
            assertEquals("/reading/bookapi/search/suggest/v",request.url.encodedPath)
            assertEquals("jywn",request.url.queryParameter("query"))
            """{"code":0,"data":{"query_key":"jywn","query_result":["家有五女"," 今夜为你无眠 ","家有五女",""]}}"""
        }
        assertEquals(listOf("家有五女","今夜为你无眠"),repo.suggest(" JYWN "))
    }

    @Test fun suggestFallsBackToEmptyOnBlankBodyErrorCodeOrNetworkFailure() {
        var requests=0
        val blank=repository { requests++; "" }
        assertTrue(blank.suggest("jywn").isEmpty())
        val rejected=repository { requests++; """{"code":-1}""" }
        assertTrue(rejected.suggest("jywn").isEmpty())
        val offline=repository { requests++; throw IOException("offline") }
        assertTrue(offline.suggest("jywn").isEmpty())
        assertEquals(3,requests)
    }

    @Test fun suggestSkipsNonAlphabetInputWithoutARequest() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"query_result":["a"]}}""" }
        assertTrue(repo.suggest("").isEmpty())
        assertTrue(repo.suggest("家有").isEmpty())
        assertTrue(repo.suggest("a".repeat(21)).isEmpty())
        assertEquals(0,requests)
    }
}

/** Isolated current-API fixtures; run with :core:test --tests '*DetailLoadingTest'. */
class DetailLoadingTest {
    private val detailPath="/novel/player/video_detail/v1/"
    private val planPath="/reading/bookapi/plan/v:version/"
    private val base=Detail(Series("123","测试剧 第二季",description="简介"),listOf("900","901"))

    private fun response(request: Request,body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()

    private fun repository(requests: MutableList<Request>,respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            // Keep registration out of business-request assertions; never mutate the global test identity.
            if(request.url.encodedPath=="/service/2/device_register/") response(request,"{}")
            else { requests+=request; response(request,respond(request)) }
        }.build()
    )

    private fun apiDetail() = """{"code":0,"data":{"video_data":{
        "series_title":"测试剧 第二季","series_cover":"https://example.com/cover.jpg","series_intro":"简介",
        "episode_cnt":2,"category_schema":[{"name":"玄幻"},{"name":"冒险"}],
        "video_list":[{"vid":"901","vid_index":2},{"vid":"900","vid_index":1},{"vid":"901","vid_index":3}]
    }}}"""

    private fun plan(vararg rows: Pair<String,String>) = JSONObject().put("code",0).put("data",JSONArray()
        .put(JSONObject().put("show_type",547).put("video_data",JSONArray().apply {
            rows.forEach { (id,title) -> put(JSONObject().put("series_id",id).put("title",title).put("episode_cnt",2)) }
        }))).toString()

    private fun search(vararg rows: Pair<String,String>) = JSONObject().put("searchList",JSONArray().apply {
        rows.forEach { (id,title) -> put(JSONObject().put("video_data",JSONObject().put("series_id",id).put("series_name",title))) }
    }).put("hasMore",false).toString()

    private fun webDetail(episodes: JSONArray = JSONArray(base.episodes)): String {
        val series=JSONObject().put("series_id","123").put("series_name",base.series.title).put("vid_list",episodes)
        val groups=JSONObject().put("recommendationGroups",JSONObject().put("related_list",JSONArray()
            .put(JSONObject().put("series_id","111").put("series_name","别人的剧"))
            .put(JSONObject().put("series_id","333").put("series_name","测试剧 第三季"))))
        val args=JSONArray().put("detail_page").put(JSONArray().put(JSONObject()
            .put("routerDataFnArgs",JSONArray().put(groups.toString())))).toString().replace("&","&amp;").replace("\"","&quot;")
        return """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":$series}}};</script><script data-fn-args="$args"></script>"""
    }

    @Test fun fastDetailUsesOneBusinessRequestAndReturnsOrderedEpisodes() {
        val requests=mutableListOf<Request>()
        val repo=repository(requests) { request ->
            assertEquals(detailPath,request.url.encodedPath)
            assertEquals("POST",request.method)
            assertEquals("123",request.url.queryParameter("series_id"))
            assertNotNull(request.header("x-medusa"))
            apiDetail()
        }
        val detail=repo.detailFast("123")
        assertEquals(base.episodes,detail.episodes)
        assertEquals(base.series.title,detail.series.title)
        assertEquals("简介",detail.series.description)
        assertEquals("玄幻 · 冒险",detail.series.tags)
        assertEquals("全 2 集",detail.series.badge)
        assertTrue(detail.related.isEmpty()); assertTrue(detail.sameName.isEmpty())
        assertEquals(1,requests.size)
    }

    @Test fun fastDetailWebsiteFallbackNeverRequestsRecommendationsOrSearch() {
        val requests=mutableListOf<Request>()
        val repo=repository(requests) { request -> when(request.url.encodedPath) {
            detailPath -> """{"code":-1}"""
            "/detail" -> webDetail()
            else -> throw AssertionError("unexpected optional request: ${request.url}")
        } }
        val detail=repo.detailFast("123")
        assertEquals(base.episodes,detail.episodes)
        assertEquals(listOf("111","333"),detail.related.map { it.id })
        assertTrue(detail.sameName.isEmpty())
        assertEquals(listOf(detailPath,"/detail"),requests.map { it.url.encodedPath })
    }

    @Test fun fastDetailRejectsCorruptWebsiteEpisodeLists() {
        for(episodes in listOf(JSONArray().put("bad"),JSONArray().put("900").put("900"),JSONArray())) {
            val requests=mutableListOf<Request>()
            val repo=repository(requests) { request ->
                if(request.url.encodedPath==detailPath) """{"code":-1}""" else webDetail(episodes)
            }
            assertThrows(IOException::class.java) { repo.detailFast("123") }
            assertEquals(listOf(detailPath,"/detail"),requests.map { it.url.encodedPath })
        }
    }

    @Test fun recommendationEnrichmentReusesBasicDetailAndAvoidsUnneededSearch() {
        val requests=mutableListOf<Request>()
        val repo=repository(requests) { request ->
            assertEquals(planPath,request.url.encodedPath)
            assertEquals("123",request.url.queryParameter("book_id"))
            plan("444" to "测试剧 第四季","111" to "别人的剧","222" to "测试剧 第一季","123" to base.series.title)
        }
        val detail=repo.detailRecommendations(base)
        assertSame(base.series,detail.series); assertSame(base.episodes,detail.episodes)
        assertEquals(listOf("222","444","111"),detail.related.map { it.id })
        assertEquals(listOf("222","444"),detail.sameName.map { it.id })
        assertEquals("444",detail.nextSeason!!.id)
        assertEquals(1,requests.size)
    }

    @Test fun recommendationEnrichmentSearchesForMissingSeasonsWithoutReloadingEpisodes() {
        val requests=mutableListOf<Request>()
        val repo=repository(requests) { request -> when(request.url.encodedPath) {
            planPath -> plan("333" to "测试剧 第三季","111" to "别人的剧")
            "/api/search/result/" -> {
                assertEquals("测试剧",request.url.queryParameter("query"))
                search("333" to "测试剧 第三季","111" to "别人的剧","222" to "测试剧 第一季","123" to base.series.title)
            }
            else -> throw AssertionError("basic detail must not be reloaded")
        } }
        val detail=repo.detailRecommendations(base)
        assertSame(base.episodes,detail.episodes)
        assertEquals(listOf("222","333"),detail.sameName.map { it.id })
        assertEquals(listOf(planPath,"/api/search/result/"),requests.map { it.url.encodedPath })
    }

    @Test fun knownNextSeasonSurvivesFailedOrPartialWebsiteFallback() {
        for(searchResponse in listOf("""{"code":-1}""",search("222" to "测试剧 第一季"))) {
            val requests=mutableListOf<Request>()
            val repo=repository(requests) { request -> when(request.url.encodedPath) {
                planPath -> plan("333" to "测试剧 第三季")
                "/api/search/result/" -> searchResponse
                else -> throw AssertionError("unexpected basic-detail reload")
            } }
            val detail=repo.detailRecommendations(base)
            assertEquals("333",detail.nextSeason!!.id)
            assertTrue(detail.sameName.any { it.id=="333" })
            if(searchResponse.contains("222")) assertEquals(listOf("222","333"),detail.sameName.map { it.id })
            assertSame(base.episodes,detail.episodes)
            assertEquals(listOf(planPath,"/api/search/result/"),requests.map { it.url.encodedPath })
        }
    }

    @Test fun optionalRecommendationFailuresPreserveAlreadyLoadedLists() {
        val requests=mutableListOf<Request>()
        val existing=base.copy(related=listOf(Series("111","官网推荐")),sameName=listOf(Series("333","测试剧 第三季")))
        val repo=repository(requests) { request -> when(request.url.encodedPath) {
            planPath -> """{"code":-1}"""
            "/api/search/result/" -> search()
            else -> throw AssertionError("unexpected detail reload")
        } }
        assertEquals(existing,repo.detailRecommendations(existing))
        assertEquals(2,requests.count { it.url.encodedPath==planPath })
        assertEquals(1,requests.count { it.url.encodedPath=="/api/search/result/" })
    }

    @Test fun legacyDetailStillCombinesBasicsAndRecommendations() {
        val requests=mutableListOf<Request>()
        val repo=repository(requests) { request -> when(request.url.encodedPath) {
            detailPath -> apiDetail()
            planPath -> plan("222" to "测试剧 第一季","333" to "测试剧 第三季","111" to "别人的剧")
            else -> throw AssertionError("unexpected fallback")
        } }
        val detail=repo.detail("123")
        assertEquals(base.episodes,detail.episodes)
        assertEquals(listOf("222","333"),detail.sameName.map { it.id })
        assertEquals(listOf(detailPath,planPath),requests.map { it.url.encodedPath })
    }

    @Test fun cancelledDetailCannotStartNetworkWork() {
        val scope=RequestScope().apply { cancel() }; val requests=mutableListOf<Request>()
        val repo=repository(requests) { throw AssertionError("cancelled work started") }
        assertThrows(IOException::class.java) { scope.run { repo.detailFast("123") } }
        assertTrue(requests.isEmpty())
    }

    @Test fun detailCancellationDoesNotFallBackToWebsite() {
        val scope=RequestScope(); val requests=mutableListOf<Request>()
        val repo=repository(requests) { scope.cancel(); """{"code":-1}""" }
        assertThrows(IOException::class.java) { scope.run { repo.detailFast("123") } }
        assertEquals(listOf(detailPath),requests.map { it.url.encodedPath })
    }

    @Test fun recommendationCancellationCannotRotateHostsOrStartSeasonSearch() {
        val scope=RequestScope(); val requests=mutableListOf<Request>()
        val repo=repository(requests) { scope.cancel(); """{"code":-1}""" }
        assertThrows(IOException::class.java) { scope.run { repo.detailRecommendations(base) } }
        assertEquals(listOf(planPath),requests.map { it.url.encodedPath })
    }

    @Test fun inFlightRecommendationCallIsCancelledAndDoesNotRestart() {
        val scope=RequestScope()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); entered.countDown()
            while(!chain.call().isCanceled() && !release.await(10,java.util.concurrent.TimeUnit.MILLISECONDS)) { }
            if(chain.call().isCanceled()) throw IOException("cancelled")
            response(chain.request(),plan("333" to "测试剧 第三季"))
        }.build()
        val worker=Thread {
            runCatching { scope.run { ContentRepository(http).detailRecommendations(base) } }.exceptionOrNull()?.let(failure::set)
        }
        worker.start()
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            scope.cancel(); worker.join(2000)
            assertFalse("cancelled request kept running",worker.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(1,calls.get())
        } finally { scope.cancel(); release.countDown(); worker.join(2000) }
    }

    @Test fun interruptedSeasonSearchBackoffDoesNotRetryOrLoseInterruptFlag() {
        val requests=mutableListOf<Request>()
        val interrupted=java.util.concurrent.atomic.AtomicBoolean()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val repo=repository(requests) { request -> when(request.url.encodedPath) {
            planPath -> plan("333" to "测试剧 第三季")
            "/api/search/result/" -> { Thread.currentThread().interrupt(); throw IOException("offline") }
            else -> throw AssertionError("unexpected basic request")
        } }
        val worker=Thread {
            failure.set(runCatching { RequestScope().run { repo.detailRecommendations(base) } }.exceptionOrNull())
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.start(); worker.join(5000)
        assertFalse(worker.isAlive); assertTrue(failure.get() is IOException); assertTrue(interrupted.get())
        assertEquals(listOf(planPath,"/api/search/result/"),requests.map { it.url.encodedPath })
    }

    @Test fun recommendationBudgetUsesTwoShortCallsAndOneSearchWithoutChangingDetailTimeouts() {
        val limits=mutableListOf<Pair<String,Triple<Int,Int,Long>>>()
        val http=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).callTimeout(45,java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request=chain.request(); val path=request.url.encodedPath
                if(path=="/service/2/device_register/") response(request,"{}")
                else {
                    limits+=path to Triple(chain.connectTimeoutMillis(),chain.readTimeoutMillis(),chain.call().timeout().timeoutNanos())
                    when(path) {
                        detailPath -> response(request,apiDetail())
                        planPath -> response(request,"""{"code":-1}""")
                        "/api/search/result/" -> throw IOException("offline")
                        else -> throw AssertionError("unexpected request: $path")
                    }
                }
            }.build()
        val repo=ContentRepository(http)
        val initial=repo.detailFast("123")
        assertEquals(initial,repo.detailRecommendations(initial))
        assertEquals(listOf(detailPath,planPath,planPath,"/api/search/result/"),limits.map { it.first })
        assertEquals(Triple(15000,30000,45000000000L),limits.first().second)
        limits.drop(1).forEach { assertEquals(Triple(2000,3000,4000000000L),it.second) }
        assertEquals(45000000000L,repo.http.callTimeoutMillis.toLong()*1000000L)
    }

    @Test(timeout=10000) fun slowBackgroundRecommendationsCannotBlockAnotherFastDetailLoad() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val scope=RequestScope()
        val detailCalls=java.util.concurrent.atomic.AtomicInteger()
        val http=OkHttpClient.Builder().addInterceptor { chain -> when(chain.request().url.encodedPath) {
            "/service/2/device_register/" -> response(chain.request(),"{}")
            detailPath -> { detailCalls.incrementAndGet(); response(chain.request(),apiDetail()) }
            planPath -> {
                entered.countDown(); release.await(5,java.util.concurrent.TimeUnit.SECONDS)
                response(chain.request(),plan("222" to "测试剧 第一季","333" to "测试剧 第三季","111" to "别人的剧"))
            }
            else -> throw AssertionError("unexpected fallback")
        } }.build()
        val repo=ContentRepository(http)
        val worker=Thread { runCatching { scope.run { repo.detailRecommendations(base) } } }
        worker.start()
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(base.episodes,repo.detailFast("123").episodes)
            assertEquals(1,detailCalls.get())
            assertTrue("background request should still be blocked independently",worker.isAlive)
        } finally { scope.cancel(); release.countDown(); worker.join(2000) }
    }

    private fun preservingDeviceIdentity(action: () -> Unit) {
        val device=VendorConstants.installDeviceId; val install=VendorConstants.installIid
        val registered=VendorConstants.registered
        try { action() }
        finally {
            if(registered) VendorConstants.adopt(device,install) else VendorConstants.resetIdentity()
            VendorConstants.installDeviceId=device; VendorConstants.installIid=install
        }
    }

    @Test fun withTransportInheritsFreshRegistrationAndCookiesWithoutRegisteringAgain() = preservingDeviceIdentity {
        val registrations=java.util.concurrent.atomic.AtomicInteger()
        val sourceHttp=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/service/2/device_register/",chain.request().url.encodedPath)
            registrations.incrementAndGet()
            response(chain.request(),"""{"device_id":"111111","install_id":"222222"}""").newBuilder()
                .addHeader("Set-Cookie","install_id=222222; Path=/")
                .addHeader("Set-Cookie","store-region=cn-gd; Path=/").build()
        }.build()
        val source=ContentRepository(sourceHttp)
        assertTrue(source.registerDeviceIfNeeded()); assertEquals(1,registrations.get())
        val before=source.deviceSnapshot()
        val businessCalls=java.util.concurrent.atomic.AtomicInteger()
        val transport=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("inherited session must skip registration",detailPath,chain.request().url.encodedPath)
            assertEquals("install_id=222222; store-region=cn-gd",chain.request().header("Cookie"))
            businessCalls.incrementAndGet(); response(chain.request(),apiDetail())
        }.build()
        val inherited=source.withTransport(transport)
        assertSame(transport,inherited.http)
        assertEquals(before,source.deviceSnapshot()) // deriving a transport must never adopt a new device
        assertEquals(before,inherited.deviceSnapshot())
        assertNotSame(before.third,inherited.deviceSnapshot().third)
        assertTrue(inherited.registerDeviceIfNeeded())
        assertEquals(base.episodes,inherited.detailFast("123").episodes)
        assertEquals(1,businessCalls.get()); assertEquals(1,registrations.get())
    }

    @Test fun withTransportDoesNotTreatColdRestoredIdentityAsVerifiedRegistration() = preservingDeviceIdentity {
        val source=ContentRepository(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("wrong transport used")
        }.build())
        source.restoreDevice("111111","222222",mapOf("install_id" to "222222"))
        val before=source.deviceSnapshot()
        val registrations=java.util.concurrent.atomic.AtomicInteger()
        val transport=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/service/2/device_register/",chain.request().url.encodedPath)
            registrations.incrementAndGet()
            response(chain.request(),"""{"device_id":"111111","install_id":"222222"}""")
        }.build()
        val inherited=source.withTransport(transport)
        assertEquals(before,source.deviceSnapshot())
        assertTrue(inherited.registerDeviceIfNeeded())
        assertEquals("cold restore still requires a renewal check",1,registrations.get())
    }

    @Test fun cancellationStopsRetryBackoffWithoutInterruptingWorker() {
        val scope=RequestScope(); val paused=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker=Thread {
            failure.set(runCatching { scope.run { paused.countDown(); RequestScope.pause(10000) } }.exceptionOrNull())
        }
        worker.start()
        try {
            assertTrue(paused.await(2,java.util.concurrent.TimeUnit.SECONDS))
            scope.cancel(); worker.join(2000)
            assertFalse("retry delay ignored scope cancellation",worker.isAlive)
            assertTrue(failure.get() is IOException)
        } finally { scope.cancel(); worker.interrupt(); worker.join(2000) }
    }
}
