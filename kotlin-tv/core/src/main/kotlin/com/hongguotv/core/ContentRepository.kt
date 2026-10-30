// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList(), val sameName: List<Series> = emptyList()) {
    /** Same-name entries with a finite season number beyond the current one (untitled season counts as 1). */
    val newerSeasons: List<Series> get() {
        val current=seasonNumber(series.title).let { if(it==Int.MAX_VALUE) 1 else it }
        return sameName.filter { val n=seasonNumber(it.title); n!=Int.MAX_VALUE && n>current }
    }
    /** The immediately following season, so auto-play continues in order instead of jumping to the latest. */
    val nextSeason: Series? get() = newerSeasons.minByOrNull { seasonNumber(it.title) }
}
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
/** One website genre selector entry; the slug selects the category path, ids echo back for validation. */
data class Genre(val slug: String, val name: String, val ids: List<String>)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val codec: String = "")
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/** One candidate stream: height, api key, raw json and normalised codec name. */
private data class VideoVariant(val height: Int,val key: String,val json: JSONObject,val codec: String)

/** Normalises the vendor's codec labels to the names Android's MediaCodecList uses. */
private fun codecName(raw: String): String = when(raw.trim().lowercase()) {
    "h264","avc","avc1" -> "avc"
    "h265","hevc","hvc1","hev1","bytevc1","bvc1" -> "hevc"
    "av1","av01" -> "av1"
    "vp9","vp09" -> "vp9"
    "bytevc2","bvc2" -> "bytevc2"
    "" -> "unknown"
    else -> "unknown"
}

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    // Optional recommendations must not occupy a browsing worker for minutes on bad routes.
    private val recommendationHttp=http.newBuilder().connectTimeout(2,TimeUnit.SECONDS)
        .readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).build()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private fun categoryPath(type: ContentType)=if(type==ContentType.COMIC) "comic-drama" else "real-drama"
    @Volatile private var registrationCookies: Map<String,String> = emptyMap()
    @Volatile private var registerInFlight = false
    @Volatile private var registeredAt = 0L
    @Volatile private var deviceRejected = false

    /**
     * 平台要求「注册过的设备」才给数据；身份有时效——实测看 2 小时后取流开始被拒（空响应 / 101000），
     * 所以这里除了首次注册，还按 [REGISTER_TTL_MS] 到期自动重新注册续期，失败也不阻断回退路径。
     */
    fun registerDeviceIfNeeded(): Boolean {
        if(VendorConstants.registered && System.currentTimeMillis()-registeredAt < REGISTER_TTL_MS) return true
        synchronized(this) {
            if(VendorConstants.registered && System.currentTimeMillis()-registeredAt < REGISTER_TTL_MS) return true
            if(registerInFlight) return false
            registerInFlight = true
        }
        return try { registerDevice() } finally { registerInFlight = false }
    }
    private fun registerDevice(): Boolean {
        val profile=VendorConstants.device
        val body=DeviceRegister.payload(profile)
        val headers=DeviceRegister.headers(body,profile)
        val registered=runCatching {
            val builder=Request.Builder().url(DeviceRegister.url())
            headers.forEach { (key,value) -> builder.header(key,value) }
            builder.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
            RequestScope.execute(http.newCall(builder.build())) { response ->
                if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
                val bytes=response.body?.bytes() ?: throw IOException("设备注册响应为空")
                DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
            }
        }.getOrNull() ?: return false
        VendorConstants.adopt(registered.deviceId,registered.installId)
        registrationCookies=registered.cookies
        registeredAt=System.currentTimeMillis()
        return true
    }
    /** 启动时把上次注册的身份与 Cookie 放回来，避免每次冷启动都重新注册。 */
    fun restoreDevice(deviceId: String, installId: String, cookies: Map<String,String>) {
        if(deviceId.isBlank() || installId.isBlank()) return
        VendorConstants.adopt(deviceId,installId)
        registrationCookies=cookies
    }
    fun deviceSnapshot(): Triple<String,String,Map<String,String>> =
        Triple(VendorConstants.installDeviceId,VendorConstants.installIid,registrationCookies)

    /**
     * A same-session repository using a different transport (for example, bounded speculative work).
     * Preserve verified registration freshness and cookies without adopting/resetting global device
     * identity. A cold [restoreDevice] remains unverified and still needs its normal renewal check;
     * in-flight registration and browsing caches are deliberately not shared.
     */
    fun withTransport(http: OkHttpClient): ContentRepository {
        // Read the timestamp first: concurrent renewal can only make this snapshot conservative.
        val verifiedAt=registeredAt
        val cookies=registrationCookies.toMap()
        val rejected=deviceRejected
        return ContentRepository(http,clock).also {
            it.registeredAt=verifiedAt
            it.registrationCookies=cookies
            it.deviceRejected=rejected
        }
    }

    private fun cookieHeader(): String? =
        registrationCookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }.takeIf { it.isNotBlank() }

    /** 站点页面与官网 JSON 会偶发「200 + 0 字节」或超时；短退避重试几次再放弃。 */
    private fun textRetry(url: String, signed: Signer.Request? = null, attempts: Int = 5, client: OkHttpClient = http): String {
        var last: IOException? = null
        for (attempt in 1..attempts) {
            try {
                // 第二次起换一条新连接：站点偶发把 keep-alive 连接挂住，重发到同一条连接上只会继续超时。
                return text(url, signed, freshConnection = attempt > 1, client = client)
            } catch (problem: IOException) {
                RequestScope.ensureActive()
                last = problem
                if (attempt < attempts) RequestScope.pause(400L * attempt + (0..200).random())
            }
        }
        throw last ?: IOException("内容请求失败")
    }
    private fun text(url: String, signed: Signer.Request? = null, freshConnection: Boolean = false, client: OkHttpClient = http): String {
        RequestScope.ensureActive()
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        if (freshConnection) { request.header("Connection","close"); request.header("Cache-Control","no-cache") }
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        if(signed!=null) cookieHeader()?.let { request.header("Cookie",it) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(client.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { RequestScope.ensureActive(); val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            RequestScope.ensureActive()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            if(bytes.isEmpty()) throw IOException("接口响应为空，请稍后重试")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(textRetry(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        // 分类型榜已合并：统一读 /rank/hot-drama（一个列表混排漫剧与短剧）。
        return ComicRank.parse(textRetry("$site/rank/${RankBoard.HOT.path}?page=$page",attempts=7),page,board)
    }
    fun comicRanking(page: Int = 1)=ranking(RankBoard.COMIC,page)
    fun realRanking(page: Int = 1)=ranking(RankBoard.REAL,page)
    /** Genre selectors from the website's category page; cached briefly because they rarely change. */
    fun genres(type: ContentType): List<Genre> {
        synchronized(genreCache) { genreCache[type]?.let { if(clock()-it.first<600_000) return it.second } }
        val data=router("$site/category/${categoryPath(type)}")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("题材列表暂不可用")
        val rows=section.optJSONArray("selectorList") ?: throw IOException("题材列表暂不可用")
        val genres=linkedMapOf<String,Genre>()
        for(row in rows.objects()) for(item in row.optJSONArray("items")?.objects() ?: emptyList()) {
            val ids=item.optJSONArray("category_json_ids") ?: continue
            val list=(0 until ids.length()).map { ids.optString(it) }.filter { it.isNotBlank() }
            val slug=item.str("selector_item_id"); val name=item.str("show_name")
            if(slug.isNotBlank() && name.isNotBlank() && list.isNotEmpty()) genres.putIfAbsent(slug,Genre(slug,name,list))
        }
        if(genres.isEmpty()) throw IOException("题材列表暂不可用")
        return genres.values.toList().also { list -> synchronized(genreCache) { genreCache[type]=clock() to list } }
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT, genre: Genre? = null): CatalogPage {
        require(page in 1..100)
        if(genre!=null) return categoryListing("$site/category/${categoryPath(type)}/${genre.slug}"+if(page>1) "?page=$page" else "",page,"题材",genre)
        // 漫剧首页：统一热播榜是混排的，首页「漫剧」货架改用同类型分类目录，内容类型才对；
        // 分类目录失败时再退回榜单。
        if(type==ContentType.COMIC) return runCatching { categoryListing("$site/category/comic-drama"+if(page>1) "?page=$page" else "",page,"热门") }
            .getOrElse { comicRanking(page) }
        return categoryListing("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "",page,"首页")
    }
    private fun categoryListing(url: String,page: Int,label: String,genre: Genre? = null): CatalogPage {
        val data=router(url)
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("${label}数据结构已变化")
        if(genre!=null) {
            // The site silently ignores filters it does not recognize; only the echoed ids prove the genre applied.
            val applied=section.optJSONObject("apiQuery")?.optJSONArray("category_json_ids")
            val matched=applied!=null && genre.ids.any { id -> (0 until applied.length()).any { applied.optString(it)==id } }
            if(!matched) throw IOException("题材筛选暂不可用，请稍后重试")
        }
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("${label}数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        if(type==ContentType.COMIC) {
            try { return appSearch(keyword,page,type) }
            catch(e: Exception) { /* App search API rejected the request or the cursor expired; the website paginates independently. */ }
            return webSearch(keyword,page,COMIC_WEB_GENRE)
        }
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; /* Website fallback for short dramas only. */ }
        return webSearch(keyword,page,SHORT_WEB_GENRE)
    }
    /**
     * Search across both libraries and interleave the hits, so the grid mixes 短剧 and 漫剧
     * instead of letting one type fill the first page.
     */
    fun searchMerged(keyword: String, page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        var short=runCatching { search(keyword,page,ContentType.SHORT) }.getOrNull()
        var comic=runCatching { search(keyword,page,ContentType.COMIC) }.getOrNull()
        if(short==null && comic==null) {
            // 站点/接口偶发超时：稍等再试一轮，仍然全空才报错。
            runCatching { Thread.sleep(300) }
            short=runCatching { search(keyword,page,ContentType.SHORT) }.getOrNull()
            comic=runCatching { search(keyword,page,ContentType.COMIC) }.getOrNull()
        }
        if(short==null && comic==null) throw IOException("搜索暂不可用")
        val first=Seasons.collapse(short?.items.orEmpty())
        val second=Seasons.collapse(comic?.items.orEmpty())
        val merged=mutableListOf<Series>()
        for(index in 0 until maxOf(first.size,second.size)) {
            first.getOrNull(index)?.let { merged+=it }
            second.getOrNull(index)?.let { merged+=it }
        }
        return CatalogPage(merged,short?.hasMore==true || comic?.hasMore==true)
    }
    /**
     * The search page became a client-rendered React app, so the old _ROUTER_DATA scrape no longer
     * exists; the site now exposes a JSON endpoint at /api/search/result/ with the same searchList
     * rows (video_data.series_id / series_title / series_cover / genre).
     */
    private fun webSearch(keyword: String, page: Int, genre: String?, attempts: Int = 5, client: OkHttpClient = http): CatalogPage {
        val data=JSONObject(textRetry("$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page",attempts=attempts,client=client))
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据暂不可用")
        val mapped=rows.objects().let { list -> if(genre==null) list else list.filter { (it.optJSONObject("video_data") ?: it).optString("genre")==genre } }.map(::card)
        val items=valid(mapped)
        val lastPage=data.optInt("totalPages",100).coerceIn(1,100)
        val hasMore=data.optBoolean("hasMore",items.isNotEmpty() && page<lastPage)
        return CatalogPage(items,hasMore && page<lastPage)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        registerDeviceIfNeeded()
        VendorConstants.regenerate()
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    /** Pinyin-initial suggestions from the signed app API; every failure returns an empty list so the TV keyboard keeps working. */
    fun suggest(query: String): List<String> {
        val key=query.trim().lowercase()
        if(key.isEmpty() || key.length>20 || !key.matches(Regex("[a-z0-9]+"))) return emptyList()
        return runCatching {
            VendorConstants.regenerate()
            val values=VendorConstants.device.toMutableMap()
            for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
            values["version_name"]="7.2.2.32"
            values["query"]=key
            val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v",values)
            val data=JSONObject(text(signed.url,signed))
            if(data.optInt("code",-1)!=0) return emptyList()
            val rows=data.optJSONObject("data")?.optJSONArray("query_result") ?: return emptyList()
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        }.getOrElse { emptyList() }
    }
    /** Compatibility API: waits for optional recommendations as well as the playable episode list. */
    fun detail(id: String): Detail = detailRecommendations(detailFast(id))

    /**
     * Loads only the series metadata and playable episode ids, without waiting for related-work or
     * same-name searches. Show this result immediately, then call [detailRecommendations] on a
     * separate worker inside its own [RequestScope]. Website fallback may include recommendations
     * already embedded in that response, but never makes another request for them here.
     */
    fun detailFast(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        // 官网详情页已逐步下线，优先走 App 播放侧详情；失败才回退官网。
        val api=detailViaApi(id)
        RequestScope.ensureActive()
        if(api!=null) return api
        val page=textRetry("$site/detail?series_id=$id")
        val loader=extractRouter(page).getJSONObject("loaderData")
        val o=loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val series=card(o).copy(id=id)
        return Detail(series,episodes,related(page,series))
    }

    /**
     * Enriches an already loaded detail without re-fetching metadata or episodes. Ordinary optional
     * API failures leave existing recommendations intact; cancellation is propagated, not converted
     * to an empty result. Uses at most two 4-second related calls and one 4-second website search,
     * without retry backoff; normal browsing/playback HTTP timeouts are unchanged. The caller owns
     * scheduling, scope cancellation and stale-result checks.
     */
    fun detailRecommendations(detail: Detail): Detail {
        val series=detail.series; val id=series.id
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        // 详情页下方「相关作品」：App 接口给得更全（含同 IP、各季）。
        val appRelated=runCatching { relatedWorks(id,series.title,DETAIL_RELATED_ATTEMPTS,recommendationHttp) }.getOrDefault(emptyList())
        RequestScope.ensureActive()
        // 「其他季」优先从 plan 接口结果里挑，不够再退回网页搜索。
        val base=baseName(series.title)
        val planSeasons=appRelated.filter { base.isNotBlank() && baseName(it.title)==base && it.id!=id }
            .sortedBy { seasonNumber(it.title) }
        val sameNameList=if(planSeasons.size>=2) planSeasons else {
            val fallback=sameName(series).ifEmpty { detail.sameName }
            (planSeasons+fallback).distinctBy { it.id }.sortedBy { seasonNumber(it.title) }
        }
        // App 接口偶尔只回 1~2 条，保留基础详情中已有的官网推荐，避免推荐栏突然变空。
        val relatedAll=if(appRelated.size>=3 || detail.related.isEmpty()) appRelated else detail.related
        RequestScope.ensureActive()
        return detail.copy(related=relatedAll,sameName=sameNameList)
    }
    /** 剧集详情（简介 + 分集列表）走 App 播放侧接口：POST /novel/player/video_detail/v1/?series_id=<id>，body {}。 */
    private fun detailViaApi(id: String): Detail? = runCatching {
        registerDeviceIfNeeded()
        val values=VendorConstants.device.toMutableMap()
        values["series_id"]=id
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_detail/v1/",values,"{}".toByteArray())
        val raw=text(signed.url,signed)
        if(raw.isBlank()) throw IOException("剧集详情暂不可用")
        val root=JSONObject(raw)
        if(root.optInt("code",-1)!=0) throw IOException("剧集详情暂不可用（${root.optInt("code")}）")
        val o=root.optJSONObject("data")?.optJSONObject("video_data") ?: throw IOException("剧集详情结构已变化")
        val episodes=o.optJSONArray("video_list")?.objects()
            ?.sortedBy { it.optInt("vid_index",0) }
            ?.map { it.str("vid") }
            ?.filter { it.matches(Regex("[0-9]{1,30}")) }
            ?.distinct().orEmpty()
        if(episodes.isEmpty()) throw IOException("分集数据为空")
        val count=o.optInt("episode_cnt",episodes.size)
        val tags=(o.optJSONArray("category_schema") ?: JSONArray()).let { arr ->
            (0 until minOf(arr.length(),5)).mapNotNull { arr.optJSONObject(it)?.str("name") }.filter { it.isNotBlank() }.joinToString(" · ")
        }
        val series=Series(id,o.str("series_title"),cleanUrl(o.str("series_cover")),o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" },tags)
        Detail(series,episodes)
    }.getOrNull()
    // The detail page has no dedicated same-name group, so other seasons are collected by searching the
    // website for the season-independent base title and keeping every entry that shares that base name.
    private fun sameName(current: Series): List<Series> {
        val base=baseName(current.title); if(base.isBlank()) return emptyList()
        val page=runCatching { webSearch(base,1,null,attempts=1,client=recommendationHttp) }.getOrElse { RequestScope.ensureActive(); return emptyList() }
        RequestScope.ensureActive()
        return page.items.filter { it.id!=current.id && baseName(it.title)==base }
            .distinctBy { it.id }.sortedBy { seasonNumber(it.title) }.take(12)
    }
    private fun baseName(title: String)=title.replace(SeasonPattern,"").trim().trim('：',':','-','·',' ')
    // The detail page embeds its recommendation payload as an HTML-escaped JSON array inside the
    // `data-fn-args` attribute, separate from _ROUTER_DATA. Same-name entries (other seasons) sort first.
    private fun related(html: String,current: Series): List<Series> {
        val marker="data-fn-args=\""
        val start=html.indexOf(marker); if(start<0) return emptyList()
        val from=start+marker.length; val end=html.indexOf('"',from); if(end<0) return emptyList()
        val args=runCatching {
            JSONArray(decodeEntities(html.substring(from,end)))
                .optJSONArray(1)?.optJSONObject(0)?.optJSONArray("routerDataFnArgs")?.optString(0).orEmpty()
        }.getOrNull() ?: return emptyList()
        if(args.isEmpty()) return emptyList()
        val groups=runCatching { JSONObject(args).optJSONObject("recommendationGroups") }.getOrNull() ?: return emptyList()
        val rows=groups.optJSONArray("related_list") ?: groups.optJSONArray("hot_list") ?: return emptyList()
        val items=valid(rows.objects().map(::card)).filter { it.id!=current.id }
        val key=current.title.trim()
        if(key.isEmpty()) return items
        return items.sortedBy { item ->
            when {
                item.title.equals(key,ignoreCase=true) -> 0
                item.title.startsWith(key) || key.startsWith(item.title) -> 1
                else -> 2
            }
        }
    }
    private fun decodeEntities(value: String) = value.replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">").replace("&#39;","'").replace("&amp;","&")
    /** Resolves the playable variants once; the selection below stays a pure function of them. */
    private class StreamVariants(val info: JSONObject,val rows: List<VideoVariant>)
    private fun loadVariants(id: String): StreamVariants {
        require(id.matches(Regex("[0-9]{1,30}")))
        registerDeviceIfNeeded()
        val biz="""{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        val videoMap="""{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
        val model=videoModel(id,biz,videoMap)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(textRetry(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { key ->
            val o=videoList.optJSONObject(key) ?: return@mapNotNull null
            if(o.str("main_url").isEmpty()) return@mapNotNull null
            val codec=codecName(o.str("codec_type"))
            if(codec in setOf("bytevc2","bvc2")) return@mapNotNull null
            VideoVariant(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { key } } } }),key,o,codec)
        }.sortedByDescending { it.height }.toList()
        return StreamVariants(info,rows)
    }
    private fun modelOf(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    /**
     * 平台 2026-10-14 改过播放接口：带场景段的 `multi_video_model/:dr_scene/v1/` 回「200 + 空体」，
     * 旧的 `multi_video_model/v1/` 回 `110001`，目前可用的是 `player/video_model/v1/`（请求体是
     * `video_id` 单值）。按「新路由 → 带场景段 → 旧路径」依次试，空响应先原地重试再换下一条。
     */
    private fun videoModel(id: String,biz: String,videoMap: String): JSONObject {
        val candidates=listOf(
            "/novel/player/video_model/v1/" to """{"video_id":"$id","biz_param":$biz}""",
            "/novel/player/multi_video_model/preload/v1/" to videoMap,
            "/novel/player/multi_video_model/default/v1/" to videoMap,
            "/novel/player/multi_video_model/detail/v1/" to videoMap,
            "/novel/player/multi_video_model/v1/" to videoMap
        )
        var failure: IOException?=null
        for ((path,body) in candidates) {
            val attempt=runCatching {
                var response=""
                var blank=0
                while(true) {
                    VendorConstants.regenerate()
                    val payload=body.toByteArray()
                    val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com$path",VendorConstants.device,payload)
                    response=runCatching { text(signed.url,signed) }.getOrElse { "" }
                    // 网关拦截时是 200 + 0 字节：必须先判空，否则业务码默认 0 会被当成成功。
                    if(response.isNotBlank() || blank>=2) break
                    blank++
                    Thread.sleep(250L+blank*250L)
                }
                if(response.isBlank()) throw IOException("播放接口暂不可用（空响应）")
                val data=JSONObject(response)
                val code=data.optInt("Code",data.optInt("code",0))
                if(code==101000) deviceRejected=true
                if(code!=0) throw IOException("播放接口暂不可用（$code）")
                val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
                // 新路由 data.video_model；旧路由 data[<vid>].video_model，两者都可能是字符串化 JSON。
                modelOf(map.opt("video_model"))
                    ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull()?.let { modelOf(it.opt("video_model")) }
                    ?: throw IOException("播放信息为空")
            }
            attempt.getOrNull()?.let { return it }
            (attempt.exceptionOrNull() as? IOException)?.let { failure=it }
        }
        throw failure ?: IOException("播放接口暂不可用")
    }
    /**
     * 「相关作品」：App 详情页下方那一栏（`show_type=547`），一次请求拿全。
     * 接口对部分线路回空响应是常态，六条线路轮询、最多试 8 次。
     */
    fun relatedWorks(id: String, title: String): List<Series> = relatedWorks(id,title,RELATED_ATTEMPTS,http)

    private fun relatedWorks(id: String, title: String, attempts: Int, client: OkHttpClient): List<Series> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val rows=relatedRows(id,attempts,client)
        if(rows.isEmpty()) return emptyList()
        val current=baseName(title)
        val seasons=rows.filter { it.id!=id && (current.isBlank() || baseName(it.title)==current) }
            .sortedBy { seasonNumber(it.title) }
        val seasonIds=seasons.map { it.id }.toSet()
        return (seasons+rows.filter { it.id!=id && it.id !in seasonIds }).distinctBy { it.id }
    }
    private fun relatedRows(id: String, attempts: Int, client: OkHttpClient): List<Series> {
        var failure: IOException?=null
        for(attempt in 0 until attempts) {
            RequestScope.ensureActive()
            val host=RELATED_HOSTS[attempt % RELATED_HOSTS.size]
            val values=VendorConstants.device.toMutableMap()
            values.putAll(linkedMapOf(
                "scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "new_search_middle_page" to "false","bookstore_tab_type" to "0","query_history_removed" to "false",
                "user_is_login" to "0","bookstore_tab" to "0","search_middle_page_version" to "0",
                "pad_column_detail" to "0","hot_word_exchange" to "false","device_level" to "3",
                "limit" to "0","offset" to "0","is_horizontal_screen" to "false",
                "reader_single_col_from_ip_strategy" to "false"
            ))
            val result=runCatching {
                val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
                val raw=text(signed.url,signed,client=client)
                if(raw.isBlank()) throw IOException("相关作品接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("相关作品接口暂不可用（${data.optInt("code")}）")
                parseRelatedCells(data)
            }
            RequestScope.ensureActive()
            val rows=result.getOrNull().orEmpty()
            if(rows.isNotEmpty()) return rows
            failure=result.exceptionOrNull() as? IOException
        }
        throw failure ?: IOException("相关作品接口暂不可用")
    }
    private fun parseRelatedCells(data: JSONObject): List<Series> {
        val cells=data.optJSONArray("data") ?: return emptyList()
        val out=linkedMapOf<String,Series>()
        cells.objects().forEach { cell ->
            val isRelated=cell.optInt("show_type")==RELATED_CELL_SHOW_TYPE || cell.optString("cell_name")=="相关作品"
            if(isRelated) {
                (cell.optJSONArray("video_data") ?: JSONArray()).objects().forEach { v ->
                    val seriesId=v.optString("series_id")
                    val name=v.optString("title")
                    if(seriesId.matches(Regex("[0-9]{1,30}")) && name.isNotBlank()) {
                        val count=v.optInt("episode_cnt")
                        out.putIfAbsent(seriesId,Series(seriesId,name,cleanUrl(v.optString("cover")),v.optString("video_desc"),
                            if(count>0) "全 $count 集" else "",""))
                    }
                }
            }
        }
        return out.values.toList()
    }
    /** 诊断：把 plan 接口的原始 cell 结构打出来，用于排查「相关作品」只回少量条目的问题。 */
    internal fun relatedDebug(id: String): String {
        val sb=StringBuilder()
        for(attempt in 0 until RELATED_ATTEMPTS) {
            val host=RELATED_HOSTS[attempt % RELATED_HOSTS.size]
            val values=VendorConstants.device.toMutableMap()
            values.putAll(linkedMapOf("scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "user_is_login" to "0","bookstore_tab" to "0","search_middle_page_version" to "0",
                "pad_column_detail" to "0","device_level" to "3","limit" to "0","offset" to "0"))
            val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
            val raw=runCatching { text(signed.url,signed) }.getOrNull() ?: continue
            if(raw.isBlank()) { sb.append("$host blank\n"); continue }
            val data=JSONObject(raw)
            sb.append("$host code=${data.optInt("code",-1)}\n")
            val cells=data.optJSONArray("data") ?: JSONArray()
            sb.append("cells=${cells.length()}\n")
            for(i in 0 until cells.length()) {
                val c=cells.optJSONObject(i)
                val vd=c.optJSONArray("video_data")
                sb.append("  cell[$i] show_type=${c.optInt("show_type")} cell_name=${c.optString("cell_name")} video_data=${vd?.length() ?: 0}\n")
                if(vd!=null && vd.length()>0) {
                    val first=vd.optJSONObject(0)
                    sb.append("    first keys="+first.keySet().joinToString(",")+"\n")
                    sb.append("    first="+first.toString().take(300)+"\n")
                }
            }
            val parsed=parseRelatedCells(data)
            sb.append("parsed=${parsed.size}\n")
            parsed.take(20).forEach { sb.append("   - "+it.id+" "+it.title+"\n") }
            return sb.toString()
        }
        return "all hosts failed"
    }
    /** Diagnostics: every variant the API returns, ignoring device limits (used by the probe). */
    internal fun variantReport(id: String): List<String> = loadVariants(id).rows.map { "${it.height}P/${it.codec}" }
    fun stream(id: String, maxQuality: Int = 1080, supportedCodecs: Set<String> = emptySet()): StreamInfo {
        // 身份可能已过期（空响应 / 101000）：强制重新注册后重试一次，避免观众卡在续播。
        var variants=runCatching { loadVariants(id) }.getOrNull()
        if(variants==null) {
            registeredAt=0
            if(deviceRejected) { deviceRejected=false; VendorConstants.resetIdentity() }
            registerDeviceIfNeeded()
            variants=runCatching { loadVariants(id) }.getOrNull()
        }
        val loaded=variants ?: throw IOException("播放接口暂不可用，请稍后重试或重启应用")
        val info=loaded.info; val rows=loaded.rows
        // A head unit without HEVC cannot play a 1080P HEVC variant any better at 720P: pick a
        // variant whose codec the device actually decodes before falling back to quality alone.
        val usable=if(supportedCodecs.isEmpty()) rows else rows.filter { it.codec in supportedCodecs || it.codec=="unknown" }
        val pool=usable.ifEmpty { rows }
        val selected=pool.firstOrNull { it.height<=maxQuality } ?: pool.lastOrNull() ?: throw IOException("没有可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.json; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.height>0) "${selected.height}P"+(if(selected.height>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality,selected.codec)
    }
    companion object {
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧
        // （2026-10-13 用站点自带的漫剧热播榜与短剧分类列表逐条核对确认）。
        private const val COMIC_WEB_GENRE="205"
        private const val REGISTER_TTL_MS = 30L * 60 * 1000L
        private const val RELATED_CELL_SHOW_TYPE=547
        private const val DETAIL_RELATED_ATTEMPTS=2
        private const val RELATED_ATTEMPTS=8
        private val RELATED_HOSTS=listOf(
            "api5-normal-hl.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com",
            "api5-normal-sinfonlinea.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com"
        )
        private const val SHORT_WEB_GENRE="203"
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}

