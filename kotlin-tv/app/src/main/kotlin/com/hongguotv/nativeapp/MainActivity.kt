// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

private const val TYPE_CARD=0
private const val TYPE_FOOTER=1
private const val FOOTER_NONE=0
private const val FOOTER_LOADING=1
private const val FOOTER_END=2
private const val FOOTER_ERROR=3
private const val FOOTER_MORE_HOT=4

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private companion object {
        /** Survives the `recreate()` a theme switch triggers, so the user lands back on the same page. */
        var restoreAfterRecreate: Int?=null
    }
    private val bg by lazy { TvStyle.background }; private val surface by lazy { TvStyle.surface }
    private val accent by lazy { TvStyle.accent }; private val white by lazy { TvStyle.text }; private val muted by lazy { TvStyle.muted }
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    // Lazily built so TlsSupport can read assets: Activity properties initialise before attach().
    private val httpClient by lazy {
        TlsSupport.client(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30,java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(45,java.util.concurrent.TimeUnit.SECONDS),
            this
        ).build()
    }
    private val repository by lazy { ContentRepository(httpClient) }
    private val danmakuRepo by lazy { DanmakuRepository(repository.http) }
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val gridColumns=6
    private val waterfallMaxPages=10
    private var catalogRecycler: RecyclerView?=null
    private var catalogAdapter: CatalogAdapter?=null
    private var loadingMore=false
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var pageHeader: LinearLayout?=null
    private var headerCollapsed=false
    private var headerHeight=0
    private val headerDownBefore=mutableMapOf<Int,Int>()
    // 热门发现 pool spans both content types and is sampled 12 at a time (2 rows of 6).
    private var hotPool: List<Series> = emptyList()
    private var hotPick: List<Series> = emptyList()
    private var hotPoolLoading=false
    private val hotDeck=mutableListOf<Series>()
    // Everything already shown from the current pool, so a re-deal never repeats on screen.
    private val hotShownIds=mutableSetOf<String>()
    // The shelf is served from cache instantly but re-dealt once it is older than a day.
    private val hotTtlMillis=24*60*60*1000L
    private var hotRefreshDue=false
    // Set when the platform decoder rejects the stream; the player is rebuilt with FFmpeg software decoding.
    private var softDecodeForced=false
    private var softRetryUsed=false
    private var videoCodec=""
    /** Codecs this device actually decodes, used to skip variants it can never play. */
    private val deviceCodecs: Set<String> by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info -> info.supportedTypes.toList() }
                .mapNotNull { mime ->
                    when(mime) {
                        "video/avc" -> "avc"
                        "video/hevc" -> "hevc"
                        "video/av01" -> "av1"
                        "video/x-vnd.on2.vp9" -> "vp9"
                        else -> null
                    }
                }.toSet()
        }.getOrDefault(emptySet())
    }
    private var hotDraws=0
    // Shuffled copy of the pool: batches are dealt without replacement, so a pool never repeats.
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var danmakuView: DanmakuView?=null
    private var danmakuLoader: DanmakuLoader?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var favoriteGroupFilter: String?=null
    private var favoriteFilterFirst: TextView?=null
    private var favoriteFilterButtons: List<TextView> =emptyList()
    private var favoriteFilterFocus: Int=-1
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var outroSkipSeconds=0
    private var outroTriggered=false
    private var ffActive=false
    private var ffSpeedBefore=1f
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var backdropView: ImageView?=null
    private var backdropBitmap: Bitmap?=null
    private var generation=0
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val board: RankBoard?=null,val genre: Genre?=null)
    private var ranking: ComicRanking?=null
    private var rankBoard=RankBoard.COMIC
    /** Genre view of the ranking tab; null means the official heat board. */
    private var rankGenre: Genre?=null
    /** 剧场页筛选：站方题材（24/8 个）加客户端排序，只作用于剧场页。 */
    private var theaterGenres: List<Genre> =emptyList()
    private var theaterGenre: Genre?=null
    private var theaterGenresLoading=false
    private val theaterChips=mutableListOf<View>()
    private var theaterFocusKey=""
    private var rankSwitch: View?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private var genre: Genre?=null
    private var genreButton: View?=null
    private var genreClear: View?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private val searchQuickViews=mutableListOf<View>()
    /** Keys stay open while the query is empty; an explicit search folds them away to give results the full frame. */
    private var searchKeysOpen=false
    private var azFirst: View?=null
    private val azLastRow=mutableListOf<View>()
    private var suggestRow: LinearLayout?=null
    private var suggestStrip: View?=null
    private var suggestFirst: View?=null
    private var suggestTicket=0
    private val pendingSuggest=Runnable { fetchSuggest() }
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                danmakuLoader?.let { loader -> if(player?.isPlaying==true) { val position=currentPosition(); io.execute { runCatching { loader.loadUpTo(position) } } } }
                maybePrefetch()
                val p=player; val data=detail
                if(p!=null && data!=null && playbackReady && !playError && outroSkipSeconds>0 && !outroTriggered && p.isPlaying) {
                    val remaining=p.duration-p.currentPosition
                    if(p.duration>0 && remaining in 0..outroSkipSeconds*1000L) {
                        outroTriggered=true
                        if(library.autoNext && episodeIndex<data.episodes.lastIndex) {
                            Toast.makeText(this@MainActivity,"已跳过片尾，播放下一集",Toast.LENGTH_SHORT).show()
                            playEpisode(episodeIndex+1)
                        } else if(continueToNextSeason()) { /* season jump in progress */ }
                    }
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    /**
     * Applies the saved text scale through Configuration.fontScale, so every sp-based
     * TextView (settings rows, lists, dialogs, HUD) follows one setting without per-site edits.
     */
    override fun attachBaseContext(newBase: Context) {
        val saved=newBase.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE).getFloat("fontScale",1f)
        if(saved<=0f || saved==1f) { super.attachBaseContext(newBase); return }
        val config=android.content.res.Configuration(newBase.resources.configuration)
        config.fontScale=FontScale.scaleOf(FontScale.indexOf(saved))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(state: Bundle?) {
        TvStyle.apply(getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).getString("theme", null))
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        backdropView=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        loadBackdrop()
        connectivity=getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            if(android.os.Build.VERSION.SDK_INT>=24) {
                connectivity.registerDefaultNetworkCallback(networkCallback)
            } else {
                // API 21~23 没有 registerDefaultNetworkCallback，用普通 request 回调代替
                connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)
            }
            networkRegistered=true
        }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        android.util.Log.i("HongguoTV","build variant="+BuildConfig.VARIANT_ID+" version="+BuildConfig.VERSION_NAME+"("+BuildConfig.VERSION_CODE+")")
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) {
                restoreAfterRecreate?.let { saved -> restoreAfterRecreate=null; tab=saved.coerceIn(0,6) }
                showCatalog(load=tab<=2); favoriteMonitor.check()
            }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        backdropView?.takeIf { backdropBitmap!=null }?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) {
            // A blank title renders the back button alone, so the detail page does not print the
            // series name twice (breadcrumb + hero).
            val heading=row(); addButton(heading,"‹ 返回") { goBack() }
            if(title.isNotEmpty()) heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f })
            container.addView(heading)
        }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { scrollPositions[catalogPageKey]=it.findFirstVisibleItemPosition() }
        homeScreen=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false; pageHeader=null; headerCollapsed=false; headerHeight=0; headerDownBefore.clear()
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear(); searchQuickViews.clear(); theaterChips.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestStrip=null; suggestFirst=null; suggestTicket++; main.removeCallbacks(pendingSuggest)
        historyManage=null; rankSwitch=null; rankRefresh=null; rankSubtitle=null; genreButton=null; genreClear=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=when {
            TlsSupport.isCertificateProblem(problem) -> "证书校验失败：这台设备的系统证书库太旧，或系统时间不对（也可能是所连 Wi-Fi 需要网页登录）。本版已内置常用根证书，若仍失败请检查系统时间后重试。"
            problem is java.net.UnknownHostException -> "无法连接内容网站"
            problem is java.net.SocketTimeoutException -> "连接超时"
            else -> problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        }
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        rankBoard=saved?.board ?: rankBoard; genre=saved?.genre ?: genre
        showCatalog(load=((next<=2 || next==5) && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null; genre=null; rankGenre=null
        theaterGenre=null; theaterGenres=emptyList(); tabState.remove(5); tabFocus.remove(5)
        showCatalog(load=(tab==0 || tab==5 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun inlineHomeFilters()=tab==0 && resources.configuration.fontScale<=1.2f && widthDp()>=900
    private fun localCatalog()=tab==3 || tab==4 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==3) library.favorites().filter { favoriteGroupFilter==null || library.groupOf(it.id)==favoriteGroupFilter } else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusFilter: Boolean=false,focusSearch: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1 && genre==null) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        top.addView(brand,lp(dp(120),dp(44)))
        listOf("首页","搜索","排行榜","收藏","最近观看","剧场","设置").forEachIndexed { index,label ->
            val item=addButton(top,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                // Same rule as keys, chips and the primary button: the remote's position is always the accent fill.
                item.setTextColor(if(focused) bg else if(index==tab) accent else muted)
                item.background=rounded(if(focused) accent else if(index==tab) TvStyle.selectedSurface else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false); item.setOnFocusChangeListener { _,focused -> paint(focused) }; nav+=item
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        // Everything below the tab bar (filters, page title, board controls) lives in one
        // collapsible block: it scrolls away while the tab bar stays pinned.
        val header=column()
        pageHeader=header; container.addView(header,lp(-1,-2))
        if(tab==6) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab==0 || tab==5) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(tab==5) types.addView(text("剧场",12f,muted),lp(dp(42),-2))
            if(!inlineHomeFilters() && tab!=5) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            if(tab==0) {
                genreButton=addButton(types,genre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }.apply { nextFocusUpId=nav[tab].id }
            }
            if(inlineHomeFilters()) {
                top.addView(Space(this),lp(0,1).apply { weight=1f })
                typeButtons.values.forEach { view -> (view as TextView).apply { textSize=12f; minHeight=dp(34); setPadding(dp(12),dp(7),dp(12),dp(7)) } }
                types.setPadding(0,0,0,0); top.addView(types)
            } else {
                header.addView(types)
            }
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==5) {
            if(theaterGenres.isEmpty()) loadTheaterGenres()
            theaterChips.clear()
            val subjectScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val subjectLine=row().apply { setPadding(0,dp(2),0,dp(2)) }
            subjectLine.addView(text("题材",12f,muted).apply { setPadding(dp(2),dp(12),dp(10),0) })
            fun subject(label: String,key: String,selected: Boolean,onClick: ()->Unit) {
                val chip=theaterChip(label,selected,onClick); chip.tag=key
                subjectLine.addView(chip,lp(-2,dp(38)).apply { rightMargin=dp(8) }); theaterChips+=chip
            }
            subject("全部","all",theaterGenre==null) { selectTheaterGenre(null) }
            theaterGenres.forEach { item -> subject(item.name,"genre:${item.slug}",theaterGenre?.slug==item.slug) { selectTheaterGenre(item) } }
            subjectScroll.addView(subjectLine)
            header.addView(subjectScroll,lp(-1,-2))
            if(theaterFocusKey.isNotEmpty()) {
                val target=theaterChips.firstOrNull { it.tag==theaterFocusKey }
                theaterFocusKey=""
                if(target!=null) target.post { if(target.isAttachedToWindow) target.requestFocus() }
            }
        }
        if(tab==1) {
            // Empty query keeps the keys open (nothing else to look at); a real search folds them away
            // so the poster grid owns the whole frame, and 展开键盘 brings them back on demand.
            val keysOpen=searchKeysOpen || query.isBlank()
            val searchRow=row().apply { setPadding(0,dp(6),0,dp(2)) }
            val input=EditText(this).apply {
                id=View.generateViewId(); hint="输入${library.contentType.label}名称，或用字母键拼首拼"; setText(query)
                textSize=17f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1
                filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                includeFontPadding=false; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(18),0,dp(16),0)
                background=TvStyle.field(this@MainActivity,false)
            }
            input.setOnFocusChangeListener { _,focused -> input.background=TvStyle.field(this,focused) }
            searchInput=input; input.nextFocusUpId=nav[tab].id
            searchRow.addView(input,lp(0,dp(52)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            val go=primaryButton("搜索") { search() }.apply { nextFocusUpId=input.nextFocusUpId }
            searchButton=go
            searchRow.addView(go,lp(-2,dp(52)).apply { rightMargin=dp(10) })
            lateinit var phone: TextView
            phone=ghostButton("手机输入") {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) }
            }.apply { nextFocusUpId=input.nextFocusUpId }
            searchRow.addView(phone,lp(-2,dp(52)).apply { rightMargin=dp(10) })
            val keysToggle=ghostButton(if(keysOpen) "收起键盘" else "展开键盘") {
                searchKeysOpen=!keysOpen; showCatalog(focusSearch=true)
            }.apply { nextFocusUpId=input.nextFocusUpId }
            searchRow.addView(keysToggle,lp(-2,dp(52)))
            searchActions.addAll(listOf(phone,keysToggle))
            nav.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }
            container.addView(searchRow,container.indexOfChild(header))
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { scheduleSuggest() }
            })
            // Suggestions sit directly under the field, where a dropdown belongs, not below the keyboard.
            val suggestScroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false; isFocusable=false; visibility=View.GONE; clipToPadding=false
            }
            val suggestLine=row()
            suggestScroll.addView(suggestLine)
            suggestRow=suggestLine; suggestStrip=suggestScroll
            header.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(6) })
            if(keysOpen) {
                val zone=row().apply { setPadding(0,dp(6),0,0) }
                zone.addView(searchKeypad(input),lp(0,-2).apply { weight=.68f; rightMargin=dp(20) })
                zone.addView(searchQuickPanel(),lp(0,-2).apply { weight=.32f })
                header.addView(zone,lp(-1,-2))
            }
        } else if(tab==2) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            val boardTitle=rankGenre?.let { "${it.name} · ${library.contentType.label}热播" } ?: "${rankBoard.label}热播榜"
            heading.addView(text(boardTitle,25f).apply { setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=TextUtils.TruncateAt.END },lp(0,-2).apply { weight=1f })
            lateinit var genrePick: TextView
            genrePick=addButton(heading,rankGenre?.let { "题材 · ${it.name}" } ?: "题材") { showRankGenrePicker(genrePick) }
                .apply { nextFocusUpId=nav[tab].id }
            rankSwitch=addButton(heading,if(rankBoard==RankBoard.COMIC) "换到短剧榜" else "换到漫剧榜") {
                rankBoard=if(rankBoard==RankBoard.COMIC) RankBoard.REAL else RankBoard.COMIC
                rankGenre=null
                page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
            }.apply { nextFocusUpId=nav[tab].id }
            rankRefresh=addButton(heading,if(rankGenre!=null) "换一批" else "刷新榜单") { page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            header.addView(heading)
            rankSubtitle=text(rankSubtitleText(load),13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            header.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=genrePick.id }
        } else if(tab>2) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==3) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD); maxLines=1 })
            // Update state sits on the title line: it is status, not a second heading.
            if(tab==3) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply {
                    setPadding(dp(14),dp(10),dp(10),0); maxLines=1; ellipsize=TextUtils.TruncateAt.END
                }
                heading.addView(favoriteStatus,lp(0,-2).apply { weight=1f })
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            } else heading.addView(Space(this),lp(0,1).apply { weight=1f })
            if(tab==4 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            header.addView(heading)
        }
        val body=column(); container.addView(body,lp(-1,0).apply { weight=1f })
        if(tab==3) {
            val groups=library.groups()
            if(favoriteGroupFilter!=null && favoriteGroupFilter !in groups) favoriteGroupFilter=null
            if(groups.isNotEmpty()) {
                val filterRow=row().apply { setPadding(0,0,0,dp(6)) }
                val options=listOf<String?>(null)+groups
                favoriteFilterButtons=options.mapIndexed { index,group ->
                    addButton(filterRow,group ?: "全部",favoriteGroupFilter==group) {
                        favoriteGroupFilter=group; favoriteFilterFocus=index; showCatalog(focusFilter=true)
                    }.apply { nextFocusUpId=favoriteCheck?.id ?: nav[tab].id }
                }
                favoriteFilterFirst=favoriteFilterButtons.first()
                container.addView(filterRow,container.indexOfChild(body))
            }
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=1; hasMore=false
        }
        if(load) {
            if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            else if(tab==1) { searchEmptyState(body,true); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
            else { message(body,if(tab==5) "正在加载剧场…" else "正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
            val requestTheater=if(requestTab==5) theaterGenre else null
            work({
                val result=when(requestTab) { 0 -> repository.home(requestPage,requestType,requestGenre); 2 -> rankGenre?.let { repository.home(requestPage,requestType,it) } ?: repository.ranking(requestBoard,requestPage); 5 -> repository.home(requestPage,requestType,requestTheater); 1 -> repository.searchMerged(requestQuery,requestPage); else -> repository.search(requestQuery,requestPage,requestType) }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==0 && requestPage==1 && requestGenre==null && (homeFromCache || tvTools.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    val genreMode=requestTab==2 && rankGenre!=null
                    catalog=result.items
                    hasMore=if(genreMode) false else result.hasMore
                    ranking=if(genreMode) genreRanking(result.items) else result.ranking
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    rankSubtitle?.text=rankSubtitleText(false)
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType,focusSearch=focusSearch)
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType,focusFilter=focusFilter,focusSearch=focusSearch)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusFilter: Boolean=false,focusSearch: Boolean=false) {
        if(tab==0 && page==1 && collection==null && genre==null) { renderHome(body,focusNav,focusType,loading); return }
        collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            (pageHeader ?: body).addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        catalogPageKey="$tab:${collection.orEmpty()}:${genre?.slug.orEmpty()}:$page:$query"
        if(tab==0 && genre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${genre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") { genre=null; page=1; catalogFocus=""; tabState.remove(0); tabFocus.remove(0); showCatalog(true) }
                .apply { nextFocusUpId=genreButton?.id ?: typeButtons[library.contentType]?.id ?: nav[tab].id }
            (pageHeader ?: body).addView(bar)
            typeButtons.values.forEach { it.nextFocusDownId=genreClear!!.id }
            genreButton?.nextFocusDownId=genreClear!!.id
        }
        val displayed=mutableListOf<Series>()
        displayed+=if(localCatalog()) catalog else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog
        // Search and 剧场 list every season as its own id; fold them into one card per show.
        if(tab==1 || tab==5) { val folded=Seasons.collapse(displayed); displayed.clear(); displayed.addAll(folded) }
        if(displayed.isEmpty()) {
            if(tab==1) {
                val action=searchEmptyState(body,loading)
                if(focusType) typeButtons[library.contentType]?.requestFocus()
                else if(action!=null) action.requestFocus()
                else if(query.isBlank()) searchInput?.requestFocus()
                else nav[tab].requestFocus()
                return
            }
            message(body,if(loading) "正在加载剧场内容…" else when(tab) { 3 -> if(favoriteGroupFilter!=null) "「$favoriteGroupFilter」分组暂无收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; 5 -> if(theaterGenre!=null) "「${theaterGenre?.name}」暂无${library.contentType.label}，换个题材或切到全部" else "剧场暂时没有内容，稍后再试"; else -> "本页没有更多内容" })
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[tab].requestFocus()
            return
        }
        val adapter=CatalogAdapter(displayed)
        if(tab==1 && query.isNotBlank()) body.addView(text(resultSummary(),12f,muted).apply { setPadding(0,0,0,dp(6)) })
        val grid=GridLayoutManager(this,gridColumns)
        grid.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int)=if(adapter.isFooter(position)) gridColumns else 1 }
        val recycler=RecyclerView(this).apply {
            id=View.generateViewId(); clipToPadding=false; isVerticalScrollBarEnabled=false; itemAnimator=null
            layoutManager=grid; this.adapter=adapter
            // Focus entering the grid lands on the first visible card, keeping the user's place.
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused -> if(focused) { val first=grid.findFirstVisibleItemPosition().coerceAtLeast(0); post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() } } }
        }
        catalogRecycler=recycler; catalogAdapter=adapter
        recycler.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView,dx: Int,dy: Int) {
                if(rv!==catalogRecycler) return
                setHeaderCollapsed(grid.findFirstVisibleItemPosition()>0)
            }
        })
        adapter.footer=when {
            localCatalog() -> if(collection=="hot" && collectionReturn?.hasMore==true) FOOTER_MORE_HOT else FOOTER_NONE
            hasMore -> FOOTER_NONE
            else -> FOOTER_END
        }
        body.addView(recycler,lp(-1,0).apply { weight=1f })
        nav.forEach { it.nextFocusDownId=typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: recycler.id }
        rankSwitch?.nextFocusDownId=recycler.id; rankRefresh?.nextFocusDownId=recycler.id; historyManage?.nextFocusDownId=recycler.id; favoriteCheck?.nextFocusDownId=favoriteFilterFirst?.id ?: recycler.id
        favoriteFilterButtons.forEach { it.nextFocusDownId=recycler.id }
        collectionBack?.nextFocusDownId=recycler.id; genreClear?.nextFocusDownId=recycler.id
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: theaterChips.firstOrNull()?.id ?: collectionBack?.id ?: genreClear?.id ?: recycler.id }
        theaterChips.forEach { it.nextFocusDownId=recycler.id }
        genreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        chainSearchFocus()
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusSearch) searchInput?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
        else {
            val found=displayed.indexOfFirst { it.id==catalogFocus }
            val target=if(found>=0) found else scrollPositions[catalogPageKey]?.coerceIn(0,displayed.size-1) ?: 0
            grid.scrollToPositionWithOffset(target,0)
            recycler.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }
    private fun maybeLoadMore() {
        val adapter=catalogAdapter ?: return
        if(loadingMore || !hasMore || localCatalog() || screen!="catalog") return
        if(page>=waterfallMaxPages || catalog.size>=waterfallMaxPages*20) { hasMore=false; adapter.updateFooter(FOOTER_END); return }
        val requestTab=tab; val requestPage=page+1; val requestQuery=query; val requestType=library.contentType
        val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
        val requestTheater=if(requestTab==5) theaterGenre else null
        loadingMore=true; adapter.updateFooter(FOOTER_LOADING)
        work({
            when(requestTab) { 0 -> repository.home(requestPage,requestType,requestGenre); 2 -> rankGenre?.let { repository.home(requestPage,requestType,it) } ?: repository.ranking(requestBoard,requestPage); 5 -> repository.home(requestPage,requestType,requestTheater); 1 -> repository.searchMerged(requestQuery,requestPage); else -> repository.search(requestQuery,requestPage,requestType) }
        }, { result ->
            loadingMore=false
            page=requestPage
            hasMore=result.hasMore
            result.ranking?.let { r -> ranking=ComicRanking((ranking?.positions.orEmpty())+r.positions,r.totalPages,r.updatedText.ifBlank { ranking?.updatedText.orEmpty() }) }
            rankSubtitle?.text=rankSubtitleText(false)
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            catalog=catalog+result.items
            val current=catalogAdapter
            if(current!=null) {
                // A later page can repeat a show whose seasons were already folded into one card.
                val visible=if(tab==1 || tab==5) {
                    val existing=current.displayed.mapTo(HashSet()) { Seasons.base(it.title).ifBlank { it.id } }
                    Seasons.collapse(fresh).filter { Seasons.base(it.title).ifBlank { it.id } !in existing }
                } else fresh.filter { tab!=0 || it.id !in library.hidden() }
                val insertAt=current.displayed.size
                current.displayed.addAll(visible)
                if(visible.isNotEmpty()) current.notifyItemRangeInserted(insertAt,visible.size)
                current.updateFooter(if(hasMore && page<waterfallMaxPages && catalog.size<waterfallMaxPages*20) FOOTER_NONE else FOOTER_END)
            }
        }, { problem ->
            loadingMore=false
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                Toast.makeText(this,"加载失败，继续浏览会自动重试",Toast.LENGTH_SHORT).show()
                catalogAdapter?.updateFooter(FOOTER_ERROR)
            }
        })
    }
    private inner class CardHolder(val card: LinearLayout,val image: ImageView,val rank: TextView,val title: TextView,val badge: TextView): RecyclerView.ViewHolder(card) {
        var boundId=""
    }
    private inner class CatalogAdapter(val displayed: MutableList<Series>): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var footer=FOOTER_NONE
        fun isFooter(position: Int)=footer!=FOOTER_NONE && position==displayed.size
        override fun getItemCount()=displayed.size+if(footer!=FOOTER_NONE) 1 else 0
        override fun getItemViewType(position: Int)=if(isFooter(position)) TYPE_FOOTER else TYPE_CARD
        fun updateFooter(next: Int) {
            if(footer==next) { if(next!=FOOTER_NONE) notifyItemChanged(displayed.size); return }
            val had=footer!=FOOTER_NONE; footer=next
            if(next==FOOTER_NONE) notifyItemRemoved(displayed.size)
            else if(had) notifyItemChanged(displayed.size)
            else notifyItemInserted(displayed.size)
        }
        private var posterHeightFixed=0
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
            if(viewType==TYPE_FOOTER) {
                val frame=FrameLayout(this@MainActivity)
                frame.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
                return object: RecyclerView.ViewHolder(frame) {}
            }
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
            val artwork=FrameLayout(this@MainActivity)
            val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            artwork.addView(image,FrameLayout.LayoutParams(-1,-1))
            val rank=TextView(this@MainActivity).apply { textSize=11f; setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(4),dp(8),dp(4)) }
            artwork.addView(rank,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
            val title=text("",14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) }
            val badge=text("",12f).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            val gridWidth=parent.width.takeIf { it>0 } ?: (resources.displayMetrics.widthPixels*.9f).toInt()
            val natural=((gridWidth/gridColumns-dp(14)-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val chrome=dp(13)+dp(7)+title.lineHeight*2+maxOf(dp(19),badge.lineHeight)
            // Solved once per page from screen metrics, never from the live list height: that height
            // changes when the page header collapses, and mixing poster sizes across holders makes
            // covers look cropped or misaligned after scrolling.
            val posterHeight=if(posterHeightFixed>0) posterHeightFixed else {
                val cap=(resources.displayMetrics.heightPixels*.74f-chrome-dp(52)-dp(4)).toInt().coerceAtLeast(dp(48))
                minOf(natural,cap).also { posterHeightFixed=it }
            }
            card.addView(artwork,lp(-1,posterHeight))
            card.addView(title,lp(-1,-2))
            card.addView(badge,lp(-1,-2))
            card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
            return CardHolder(card,image,rank,title,badge)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
            if(getItemViewType(position)==TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
            holder as CardHolder
            val item=displayed[position]; holder.boundId=item.id
            val card=holder.card
            card.tag=item.id; card.contentDescription=item.title
            loadCover(holder.image,item.cover)
            val rankPosition=if(tab==2) ranking?.positions?.get(item.id) else null
            if(tab==2) {
                val rank=rankPosition?.rank
                val rankLabel=if(rank!=null) "第 $rank 名" else "名次暂无"
                val styled=android.text.SpannableString(rankLabel)
                if(rank!=null) {
                    val start=rankLabel.indexOf(' ')+1; val end=rankLabel.lastIndexOf(' ')
                    styled.setSpan(android.text.style.RelativeSizeSpan(1.45f),start,end,0)
                    styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD),start,end,0)
                }
                holder.rank.text=styled
                holder.rank.setTextColor(if(rank==1) white else Color.rgb(24,26,30))
                holder.rank.background=rounded(if(rank==1) accent else Color.argb(235,255,255,255),6)
                holder.rank.visibility=View.VISIBLE
                card.contentDescription="$rankLabel，${item.title}，${rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
            } else holder.rank.visibility=View.GONE
            holder.title.text=item.title
            val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
            holder.badge.text=if(tab==3) library.favoriteLabel(item.id) else if(tab==2) rankBadge(rankPosition) else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
            holder.badge.setTextColor(if(tab==2 || tab==3) accent else muted)
            if(tab==3) favoriteBadges[item.id]=holder.badge
            card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==4 || collection=="resume" || collection=="updates") }
            card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
            card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
            card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover) } }
            card.nextFocusUpId=if(position<gridColumns) azLastRow.firstOrNull()?.id ?: searchInput?.id ?: theaterChips.firstOrNull()?.id ?: collectionBack?.id ?: genreClear?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historyManage?.id ?: nav[tab].id else View.NO_ID
            if(position>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? CardHolder)?.let { h -> if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId) }
        }
        private fun bindFooter(frame: FrameLayout) {
            frame.removeAllViews()
            if(footer==FOOTER_MORE_HOT) {
                frame.addView(button("下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                return
            }
            val label=when(footer) {
                FOOTER_LOADING -> "正在加载…"
                FOOTER_ERROR -> "加载失败，继续浏览会自动重试"
                else -> if(tab==2) "已展示全部 ${displayed.size} 部" else "已加载全部，试试搜索"
            }
            val hint=text(label,14f,muted).apply {
                gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(8)); isFocusable=true; isFocusableInTouchMode=true
                setOnFocusChangeListener { _,focused -> setTextColor(if(focused) white else muted) }
            }
            frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        if(hotPick.isEmpty()) {
            // Replay the last shelf immediately: the shelf must never wait for the network again.
            val cached=library.cachedHotPicks()
            if(cached.isNotEmpty()) {
                hotPick=cached
                val savedAt=library.hotPicksSavedAt()
                val age=System.currentTimeMillis()-savedAt
                hotRefreshDue=savedAt<=0L || age<0L || age>=hotTtlMillis
                cached.forEach { hotShownIds+=it.id }
            } else hotRefreshDue=true
        }
        val top=if(inlineHomeFilters()) nav[0] else typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,::loadCover,
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        when {
            // Nothing cached yet: one background build, after the home page has painted.
            hotPick.isEmpty() -> loadHotPool(force=true,dealAfter=true,deferMillis=400,parallel=false)
            // Stale shelf: refresh quietly in the background instead of blocking entry.
            hotRefreshDue -> loadHotPool(force=true,dealAfter=true,deferMillis=1500,parallel=false)
        }
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,null,homePosition)
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) nav[0].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        body.post { maybeAutoUpdate() }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    /**
     * 热门发现 samples the 剧场 library (全部题材, 短剧 + 漫剧). The pool is built on demand only —
     * home entry never pays for six page loads unless the shelf is empty or older than a day.
     * Background builds run one page at a time so they never compete with the home feed or covers.
     */
    private fun loadHotPool(force: Boolean,dealAfter: Boolean,deferMillis: Long,parallel: Boolean) {
        if(hotPoolLoading || !library.isLoaded) return
        if(!force && hotPool.isNotEmpty()) { if(dealAfter) dealHotPicks(); return }
        hotPoolLoading=true
        val pages=(1..8).shuffled().take(3)
        val build=Runnable {
            io.execute {
                val collected=mutableListOf<Series>()
                if(parallel) {
                    // User is waiting on 换一批: fetch the pages concurrently.
                    val jobs=ContentType.entries.flatMap { type -> pages.map { page -> type to page } }
                    val futures=jobs.map { (type,page) ->
                        // 用 ExecutorService.submit 而不是 CompletableFuture：后者 API 24 才有
                        io.submit<List<Series>> { runCatching { repository.home(page,type,null).items }.getOrDefault(emptyList()) }
                    }
                    futures.forEach { future -> runCatching { collected+=future.get() } }
                } else {
                    for(type in ContentType.entries) for(page in pages) {
                        collected+=runCatching { repository.home(page,type,null).items }.getOrDefault(emptyList())
                    }
                }
                val fallback=if(collected.size<24) ContentType.entries.flatMap { type -> runCatching { repository.home(1,type,null).items }.getOrDefault(emptyList()) } else emptyList()
                main.post { finishHotPool(collected+fallback,dealAfter) }
            }
        }
        if(deferMillis>0) main.postDelayed(build,deferMillis) else build.run()
    }
    private fun finishHotPool(items: List<Series>,dealAfter: Boolean) {
        hotPoolLoading=false
        if(isDestroyed) return
        val hidden=library.hidden()
        val seen=mutableSetOf<String>()
        val pool=Seasons.collapse(items.filterNot { it.id in hidden }).filter { seen.add(it.id) }
        if(pool.isNotEmpty()) {
            hotPool=pool; hotDraws=0
            hotDeck.clear(); hotDeck.addAll(pool.shuffled().filterNot { it.id in hotShownIds })
        }
        if(hotPool.isNotEmpty() && (dealAfter || hotPick.isEmpty())) dealHotPicks()
        if(screen=="catalog" && tab==0) homeScreen?.updateShelves(homeShelves())
    }
    private fun dealHotPicks() {
        hotPick=pickHot()
        hotRefreshDue=false
        library.cacheHotPicks(hotPick)
    }
    /**
     * Deals the next 12 from a shuffled deck (no replacement). Only when fewer than 12 cards are
     * left does it reshuffle, and then it pushes the just-shown items to the end so the new batch
     * does not repeat what is still on screen.
     */
    private fun pickHot(): List<Series> {
        if(hotPool.isEmpty()) return hotPick
        if(hotDeck.size<12) {
            val recent=hotPick.map { it.id }.toSet()
            hotDeck.clear()
            hotDeck.addAll(hotPool.shuffled().sortedBy { if(it.id in recent) 1 else 0 })
        }
        val count=minOf(12,hotDeck.size)
        val picked=hotDeck.take(count)
        repeat(count) { hotDeck.removeAt(0) }
        picked.forEach { hotShownIds+=it.id }
        return picked
    }
    private fun refreshHotPicks() {
        when {
            hotPool.isEmpty() -> {
                Toast.makeText(this,"正在准备热门…",Toast.LENGTH_SHORT).show()
                loadHotPool(force=true,dealAfter=true,deferMillis=0,parallel=true)
            }
            hotDraws>=5 -> {
                Toast.makeText(this,"正在换一批热门…",Toast.LENGTH_SHORT).show()
                loadHotPool(force=true,dealAfter=true,deferMillis=0,parallel=true)
            }
            else -> {
                hotDraws++
                dealHotPicks()
                homeScreen?.updateShelves(homeShelves())
            }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later=library.later(); val hidden=library.hidden()
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot","热门发现 · 剧场随机 12 部",hotPick.map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                columns=6,refresh={ refreshHotPicks() }))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); return }
        val adapter=catalogAdapter
        if(removed && (localCatalog() || tab==0) && adapter!=null) {
            val index=adapter.displayed.indexOfFirst { it.id==changedId }
            if(localCatalog()) catalog=localItems()
            if(index>=0) {
                adapter.displayed.removeAt(index)
                adapter.notifyItemRemoved(index)
                if(adapter.displayed.isEmpty()) { catalogFocus=""; showCatalog(); return }
                val next=minOf(index,adapter.displayed.size-1)
                catalogFocus=adapter.displayed[next].id
                val recycler=catalogRecycler
                recycler?.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
            }
        } else {
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val grouped=library.favorite(series.id)
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(grouped) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看")
        if(grouped) labels+="设置分组 · ${library.groupOf(series.id) ?: "未分组"}"
        labels+="查看剧集详情"
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4 || collection=="resume") labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            val detailIndex=if(grouped) 4 else 3
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> if(grouped) showGroupPicker(series,anchor) else openDetail(series)
                else -> if(choice==detailIndex) openDetail(series) else if(tab==4 || collection=="resume") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun showGroupPicker(series: Series,anchor: View) {
        val options=listOf("未分组")+library.groups()+listOf("＋ 新建分组…")
        fun apply() { if(tab==3) showCatalog() else refreshLibraryCards(series.id) }
        tvTools.choose("选择分组 · ${series.title}",options,anchor,{ index ->
            when {
                index==0 -> { library.setGroup(series.id,null); apply() }
                index==options.lastIndex -> tvTools.phoneInput(anchor) { name ->
                    if(!library.setGroup(series.id,name)) Toast.makeText(this,"分组数量已达上限或名称无效",Toast.LENGTH_SHORT).show()
                    apply()
                }
                else -> { library.setGroup(series.id,options[index]); apply() }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout) {
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        // Three even columns: the danmaku block used to be buried at the bottom of the playback column,
        // which made the left column twice the height of the right one.
        val playback=column(); val danmaku=column(); val device=column()
        listOf(playback,danmaku,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i<2) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(danmaku,"弹幕","平台官方弹幕，可随时关闭")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var codecInfo: TextView
        codecInfo=setting(playback,"解码信息  ·  查看本机解码能力") { showCodecInfo(codecInfo) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var autoSeasonButton: TextView
        autoSeasonButton=setting(playback,"系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}") {
            library.autoSeason=!library.autoSeason
            autoSeasonButton.text="系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}"
        }
        val danmakuPrefRows=mutableListOf<TextView>()
        fun syncDanmakuPrefRows()=danmakuPrefRows.forEach { it.visibility=if(library.danmaku) View.VISIBLE else View.GONE }
        var rechainSettings: ()->Unit={}
        lateinit var danmakuButton: TextView
        danmakuButton=setting(danmaku,"官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}") {
            library.danmaku=!library.danmaku
            danmakuButton.text="官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}"
            syncDanmakuPrefRows(); rechainSettings()
        }
        lateinit var danmakuOpacity: TextView
        danmakuOpacity=setting(danmaku,"弹幕不透明度  ·  ${library.danmakuOpacity}%") { showDanmakuOpacityPicker(danmakuOpacity) }
        lateinit var danmakuFont: TextView
        danmakuFont=setting(danmaku,"弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}") { showDanmakuFontPicker(danmakuFont) }
        lateinit var danmakuSpeed: TextView
        danmakuSpeed=setting(danmaku,"弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}") { showDanmakuSpeedPicker(danmakuSpeed) }
        lateinit var danmakuRows: TextView
        danmakuRows=setting(danmaku,"弹幕展示行数  ·  ${library.danmakuRows} 行") { showDanmakuRowsPicker(danmakuRows) }
        lateinit var danmakuReset: TextView
        danmakuReset=setting(danmaku,"恢复弹幕默认设置") {
            resetDanmakuPrefs()
            danmakuOpacity.text="弹幕不透明度  ·  ${library.danmakuOpacity}%"
            danmakuFont.text="弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            danmakuSpeed.text="弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            danmakuRows.text="弹幕展示行数  ·  ${library.danmakuRows} 行"
            Toast.makeText(this,"弹幕设置已恢复默认",Toast.LENGTH_SHORT).show()
        }
        danmakuPrefRows.addAll(listOf(danmakuOpacity,danmakuFont,danmakuSpeed,danmakuRows,danmakuReset))
        syncDanmakuPrefRows()

        lateinit var themeButton: TextView
        themeButton=setting(device,"系统风格  ·  ${TvStyle.theme.label}") {
            restoreAfterRecreate=tab; library.theme=TvStyle.toggle().name; recreate()
        }
        lateinit var fontSizeButton: TextView
        fontSizeButton=setting(device,"文字大小  ·  ${FontScale.label(FontScale.indexOf(library.fontScale))}") { showFontScalePicker(fontSizeButton) }
        lateinit var updateButton: TextView
        updateButton=setting(device,"检查更新  ·  当前 ${BuildConfig.VERSION_NAME}") { checkForUpdates(manual=true) }
        lateinit var autoUpdateButton: TextView
        autoUpdateButton=setting(device,"自动检查更新  ·  ${if(library.autoUpdate) "开启" else "关闭"}") {
            library.autoUpdate=!library.autoUpdate
            autoUpdateButton.text="自动检查更新  ·  ${if(library.autoUpdate) "开启" else "关闭"}"
        }
        lateinit var backdropButton: TextView
        fun backdropLabel()="自定义背景  ·  ${if(backdropFile()!=null) "已启用" else if(backdropStyle()=="forced") "强制欣赏" else "默认风格"}"
        backdropButton=setting(device,backdropLabel()) {
            tvTools.choose("自定义背景",listOf("手机上传图片","恢复默认背景"),backdropButton,{ choice ->
                when(choice) {
                    0 -> tvTools.backdropTransfer(backdropButton) { bytes,ext -> saveBackdrop(bytes,ext); backdropButton.post { backdropButton.text=backdropLabel() } }
                    1 -> tvTools.choose("恢复默认背景",listOf("强制欣赏","默认风格"),backdropButton,{ style ->
                        resetBackdrop(if(style==0) "forced" else "plain")
                        backdropButton.text=backdropLabel()
                    })
                }
            })
        }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        fun cacheBytes()=listOfNotNull(cacheDir,externalCacheDir).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        fun cacheLabel()="清理缓存  ·  ${when(val b=cacheBytes()) {
            in 0..1023 -> "$b B"; in 1024..1048575 -> "${b/1024} KB"; else -> "%.1f MB".format(b/1048576f)
        }}"
        lateinit var clearCache: TextView
        clearCache=setting(device,cacheLabel()) {
            AlertDialog.Builder(this).setTitle("清理缓存").setMessage("清除封面图片和已下载的安装包等缓存数据，不影响收藏和观看记录。")
                .setNegativeButton("取消",null).setPositiveButton("清理") { _,_ ->
                    io.execute {
                        listOfNotNull(cacheDir,externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                        runOnUiThread { clearCache.text=cacheLabel(); Toast.makeText(this,"缓存已清理",Toast.LENGTH_SHORT).show() }
                    }
                }.show()
        }
        val license=setting(device,"开源许可") {
            AlertDialog.Builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：jycao2/hongguoTV。").setPositiveButton("关闭",null).show()
        }
        // The page footer must be focusable and chained: without it the D-pad stalls on the
        // last setting row and the ScrollView never reveals anything below the two columns.
        val settingsFooter=text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply {
            id=View.generateViewId(); isFocusable=true; setPadding(dp(2),dp(18),0,dp(10))
            setOnFocusChangeListener { _,focused -> setTextColor(if(focused) accent else muted) }
        }
        rechainSettings={
            val left=listOf(qualityButton,defaultSpeed,frame,codecInfo,autoNextButton,autoSeasonButton)
            val middle=listOf(danmakuButton)+(if(library.danmaku) danmakuPrefRows else emptyList())
            val right=listOf(themeButton,fontSizeButton,updateButton,autoUpdateButton,backdropButton,backup,hidden,clearCache,license)
            val columns=listOf(left,middle,right)
            columns.forEach { items -> items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: settingsFooter.id
            } }
            columns.forEachIndexed { columnIndex,items ->
                items.forEachIndexed { index,view ->
                    view.nextFocusLeftId=columns.getOrNull(columnIndex-1)?.getOrNull(index)?.id ?: view.id
                    view.nextFocusRightId=columns.getOrNull(columnIndex+1)?.getOrNull(index)?.id ?: view.id
                }
            }
            settingsFooter.nextFocusUpId=left.last().id
            nav.forEach { it.nextFocusDownId=qualityButton.id }
        }
        rechainSettings()
        parent.addView(settingsFooter)
        nav[tab].requestFocus()
    }
    /** Filled call to action. Focus flips it to the inverse so the remote always has one obvious target. */
    private fun primaryButton(label: String,onClick: ()->Unit): TextView = text(label,16f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; includeFontPadding=false
        setTypeface(null,Typeface.BOLD); setPadding(dp(26),0,dp(26),0); minWidth=dp(108)
        isFocusable=true; isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            background=TvStyle.shape(this@MainActivity,if(focused) white else accent,Color.TRANSPARENT,12)
            setTextColor(bg)
        }
        paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }; setOnClickListener { onClick() }
    }
    /** Secondary action: outline only, so it never competes with the field or the primary button. */
    private fun ghostButton(label: String,onClick: ()->Unit): TextView = text(label,14f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; includeFontPadding=false; setPadding(dp(18),0,dp(18),0)
        isFocusable=true; isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            background=TvStyle.shape(this@MainActivity,if(focused) TvStyle.raised else Color.TRANSPARENT,if(focused) accent else TvStyle.outline,12)
            setTextColor(if(focused) white else muted); setTypeface(null,if(focused) Typeface.BOLD else Typeface.NORMAL)
        }
        paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }; setOnClickListener { onClick() }
    }
    /** Rounded chip for history and suggestions; one line, ellipsized, accent filled when focused. */
    private fun chipView(label: String,size: Float=14f,onClick: ()->Unit): TextView = text(label,size).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; includeFontPadding=false
        maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(14),0,dp(14),0)
        isFocusable=true; isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            background=TvStyle.pill(this@MainActivity,focused)
            setTextColor(if(focused) bg else white); setTypeface(null,if(focused) Typeface.BOLD else Typeface.NORMAL)
        }
        paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }; setOnClickListener { onClick() }
    }
    /** Left half of the idle search page: a compact keycap pad instead of four full-width letter rows. */
    private fun searchKeypad(anchor: View): LinearLayout {
        val panel=column().apply {
            background=TvStyle.shape(this@MainActivity,TvStyle.surface,TvStyle.outline,14)
            setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        val keys=mutableListOf<TextView>()
        (('A'..'Z').map(Char::toString)+listOf("删除","清空")).chunked(7).forEachIndexed { rowIndex,rowKeys ->
            val line=row()
            rowKeys.forEachIndexed { index,label ->
                val wide=label.length>1
                val key=TextView(this).apply {
                    id=View.generateViewId(); text=label; textSize=if(wide) 13f else 17f
                    gravity=Gravity.CENTER; includeFontPadding=false; setTextColor(white); setTypeface(null,Typeface.BOLD)
                    isFocusable=true; isFocusableInTouchMode=true; background=TvStyle.key(this@MainActivity,false)
                }
                key.setOnFocusChangeListener { _,focused -> key.background=TvStyle.key(this,focused); key.setTextColor(if(focused) bg else white) }
                key.setOnClickListener { onAzKey(label) }
                keys+=key
                line.addView(key,lp(0,dp(46)).apply { weight=1f; if(index<rowKeys.lastIndex) rightMargin=dp(8) })
            }
            panel.addView(line,lp(-1,-2).apply { if(rowIndex>0) topMargin=dp(8) })
        }
        keys.forEachIndexed { index,key ->
            val lastRow=index>=keys.size-7
            key.nextFocusLeftId=keys[if(index%7==0) index else index-1].id
            key.nextFocusRightId=keys[if(index%7==6) index else index+1].id
            key.nextFocusUpId=if(index<7) anchor.id else keys[index-7].id
            key.nextFocusDownId=if(lastRow) catalogRecycler?.id ?: key.id else keys[index+7].id
        }
        azFirst=keys.first(); azLastRow.addAll(keys.takeLast(7))
        return panel
    }
    /** Right half of the idle search page: real history chips instead of a dialog behind one more key press. */
    private fun searchQuickPanel(): LinearLayout {
        val panel=column()
        val head=row().apply { setPadding(0,dp(3),0,dp(9)) }
        head.addView(text("最近搜索",13f).apply { setTypeface(null,Typeface.BOLD); setTextColor(muted) },lp(0,-2).apply { weight=1f })
        val history=if(library.isLoaded) library.searches() else emptyList()
        if(history.isNotEmpty()) {
            head.addView(text("长按删除",11f,muted).apply { setPadding(0,0,dp(10),0) })
            lateinit var more: TextView
            more=ghostButton("全部") { showSearchHistory(more) }.apply { textSize=12f; setPadding(dp(14),0,dp(14),0) }
            head.addView(more,lp(-2,dp(30)))
        }
        panel.addView(head)
        if(history.isEmpty()) {
            panel.addView(text("搜过的剧名会留在这里，最多 20 条。\n也可以用「手机输入」在手机上打字，电视自动搜索。",12f,muted)
                .apply { setLineSpacing(dp(8).toFloat(),1f) })
            return panel
        }
        history.take(6).chunked(3).forEachIndexed { lineIndex,line ->
            val chipRow=row().apply { if(lineIndex>0) setPadding(0,dp(7),0,0) }
            line.forEachIndexed { index,entry ->
                val label=if(entry.type==library.contentType) entry.query else "${entry.query} · ${entry.type.label}"
                val chip=chipView(label,13f) { runSearch(entry.query,entry.type) }
                chip.setOnLongClickListener {
                    library.forgetSearch(entry.query,entry.type)
                    Toast.makeText(this,"已删除这条搜索记录",Toast.LENGTH_SHORT).show()
                    showCatalog()
                    true
                }
                searchQuickViews+=chip
                chipRow.addView(chip,lp(0,dp(36)).apply { weight=1f; if(index<line.lastIndex) rightMargin=dp(7) })
            }
            repeat(3-line.size) { slot -> chipRow.addView(Space(this),lp(0,1).apply { weight=1f; if(slot<2) rightMargin=dp(7) }) }
            panel.addView(chipRow)
        }
        return panel
    }
    /** One place for every D-pad link on the search page; re-run after late suggestion updates. */
    private fun chainSearchFocus() {
        val input=searchInput ?: return
        val grid=catalogRecycler?.id ?: View.NO_ID
        val keys=azFirst?.id ?: grid
        val fromField=suggestFirst?.id ?: keys
        input.nextFocusDownId=fromField
        searchButton?.nextFocusDownId=fromField
        searchActions.forEach { it.nextFocusDownId=fromField }
        suggestRow?.let { line -> for(index in 0 until line.childCount) {
            val chip=line.getChildAt(index); chip.nextFocusUpId=input.id; chip.nextFocusDownId=keys
        } }
        azLastRow.forEach { it.nextFocusDownId=searchQuickViews.firstOrNull()?.id ?: grid }
        searchQuickViews.forEach { chip -> chip.nextFocusUpId=suggestFirst?.id ?: input.id; chip.nextFocusDownId=grid }
    }
    /** Empty and loading states for the search page; returns the view that should take focus. */
    /** 剧场筛选件：选中态用珊瑚描边，焦点态用珊瑚实心，和导航、键帽同一套规则。 */
    private fun theaterChip(label: String,selected: Boolean,onClick: ()->Unit): TextView = text(label,14f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; includeFontPadding=false
        maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(18),0,dp(18),0)
        isFocusable=true; isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            background=TvStyle.shape(this@MainActivity,if(focused) accent else if(selected) TvStyle.selectedSurface else Color.TRANSPARENT,if(focused || selected) accent else TvStyle.outline,18)
            setTextColor(if(focused) bg else if(selected) accent else muted)
            setTypeface(null,if(focused || selected) Typeface.BOLD else Typeface.NORMAL)
        }
        paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }; setOnClickListener { onClick() }
    }
    private fun selectTheaterGenre(next: Genre?) {
        theaterGenre=next; page=1; catalogFocus=""; tabState.remove(5); tabFocus.remove(5)
        theaterFocusKey=if(next==null) "all" else "genre:${next.slug}"
        showCatalog(load=true)
    }
    /** 站方题材列表按片库类型取一次，失败时剧场页只显示「全部」。 */
    private fun loadTheaterGenres() {
        if(theaterGenresLoading) return
        theaterGenresLoading=true
        val type=library.contentType
        io.execute {
            val result=runCatching { repository.genres(type) }
            main.post {
                theaterGenresLoading=false
                if(isDestroyed || !foreground || screen!="catalog") return@post
                result.onSuccess { list ->
                    if(library.contentType!=type) return@onSuccess
                    theaterGenres=list
                    if(tab==5) showCatalog()
                }
            }
        }
    }
    /** Ranking heat, or the position inside a genre view (the site has no per-genre board). */
    private fun rankBadge(position: RankPosition?): String {
        val genre=rankGenre
        if(genre!=null) return "${genre.name} · 站内第 ${position?.rank ?: "-"} 位"
        return position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"
    }
    private fun rankSubtitleText(load: Boolean): String {
        val genre=rankGenre
        if(genre!=null) return if(load) "正在读取「${genre.name}」…" else "题材榜 · ${genre.name} · 按站内推荐顺序，仅前 20 部"
        return if(load) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
    }
    /** The site has no per-genre board, so positions come from the genre listing's own order. */
    private fun genreRanking(items: List<Series>)=
        ComicRanking(items.mapIndexed { index,series -> series.id to RankPosition(index+1,"") }.toMap(),1,"")
    private fun showRankGenrePicker(anchor: View) {
        val type=library.contentType
        io.execute {
            val result=runCatching { repository.genres(type) }
            main.post {
                if(isDestroyed || !foreground || screen!="catalog" || tab!=2) return@post
                result.onSuccess { genres ->
                    tvTools.choose("题材榜 · 选择题材",listOf("全部题材（官方热播榜）")+genres.map { it.name },anchor,{ index ->
                        rankGenre=if(index==0) null else genres[index-1]
                        page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    })
                }.onFailure { tvTools.info("题材暂不可用","题材列表加载失败，请稍后重试。",anchor) }
            }
        }
    }
    private fun searchEmptyState(parent: LinearLayout,loading: Boolean): View? {
        val box=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(18),0,0) }
        var first: View?=null
        if(loading) {
            box.gravity=Gravity.CENTER
            box.addView(ProgressBar(this).apply { indeterminateTintList=android.content.res.ColorStateList.valueOf(accent) },lp(dp(40),dp(40)))
            box.addView(text("正在搜索「$query」…",15f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(14),0,0) })
        } else if(query.isBlank()) {
            box.addView(text("想看什么？",24f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER })
            box.addView(text("按字母键拼首拼，例如「霸道总裁」→ B D Z C\n也可以选上面的「手机输入」，在手机上打字，电视自动搜索。",14f,muted)
                .apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,0); setLineSpacing(dp(8).toFloat(),1f) })
        } else {
            box.addView(text("没有找到「$query」",22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER })
            box.addView(text("换个关键词再试，例如剧名或主角名。",14f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(10),0,0) })
            val actions=row().apply { setPadding(0,dp(20),0,0) }
            val refine=ghostButton("改关键词") { searchKeysOpen=true; showCatalog(focusSearch=true) }
            actions.addView(refine,lp(-2,dp(40)))
            first=refine
            box.addView(actions)
        }
        parent.addView(box,lp(-1,0).apply { weight=1f })
        return first
    }
    private fun resultSummary(): String {
        val shown=Seasons.collapse(catalog).size
        val extra=if(catalog.size>shown) "（已合并 ${catalog.size-shown} 个同剧集条目）" else ""
        return if(shown==0) "漫剧 + 短剧 · 暂无结果"
        else "共 $shown 部（漫剧 + 短剧）$extra · 靠近末尾自动加载更多"
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; searchKeysOpen=false; showCatalog(true)
    }
    private fun onAzKey(key: String) {
        val input=searchInput as? EditText ?: return
        when(key) {
            "删除" -> { val length=input.text.length; if(length>0) input.text.delete(length-1,length) }
            "清空" -> input.setText("")
            else -> if(input.text.length<20) input.append(key.lowercase())
        }
        input.setSelection(input.text.length)
        scheduleSuggest()
    }
    private fun scheduleSuggest() {
        main.removeCallbacks(pendingSuggest)
        main.postDelayed(pendingSuggest,300)
    }
    private fun fetchSuggest() {
        if(isDestroyed || tab!=1 || screen!="catalog") return
        val key=(searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
        if(key.isEmpty() || !key.matches(Regex("[a-zA-Z0-9]+"))) { suggestTicket++; renderSuggest(emptyList()); return }
        val ticket=++suggestTicket
        io.execute {
            val words=repository.suggest(key)
            main.post { if(!isDestroyed && ticket==suggestTicket && tab==1 && screen=="catalog") renderSuggest(words) }
        }
    }
    private fun renderSuggest(words: List<String>) {
        val container=suggestRow ?: return
        val refocus=container.hasFocus()
        container.removeAllViews(); suggestFirst=null
        val views=words.take(8).map { word -> chipView(word,13f) { runSearch(word) } }
        views.forEachIndexed { index,view ->
            container.addView(view,lp(LinearLayout.LayoutParams.WRAP_CONTENT,dp(38)).apply { rightMargin=dp(8) })
            view.nextFocusLeftId=views[if(index==0) 0 else index-1].id
            view.nextFocusRightId=views[if(index==views.size-1) index else index+1].id
        }
        suggestFirst=views.firstOrNull()
        suggestStrip?.visibility=if(views.isEmpty()) View.GONE else View.VISIBLE
        chainSearchFocus()
        if(refocus) (suggestFirst ?: azLastRow.firstOrNull() ?: searchInput)?.requestFocus()
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { it.query },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun showGenrePicker() {
        val anchor=genreButton ?: return
        val type=library.contentType
        io.execute {
            val result=runCatching { repository.genres(type) }
            main.post {
                if(isDestroyed || !foreground || screen!="catalog" || tab!=0) return@post
                result.onSuccess { genres ->
                    tvTools.choose("选择${type.label}题材",listOf("全部题材")+genres.map { it.name },anchor,{ index ->
                        val next=if(index==0) null else genres[index-1]
                        if(next!=genre) { genre=next; page=1; catalogFocus=""; tabState.remove(0); tabFocus.remove(0); showCatalog(true) }
                    })
                }.onFailure { tvTools.info("题材暂不可用","题材列表加载失败，请稍后重试。",anchor) }
            }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=primaryButton(if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        actions.addView(play,lp(-2,dp(44)).apply { rightMargin=dp(10) })
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        // Folding seasons in the grids needs a way back into the other seasons; this is it.
        val seasons=Seasons.siblings(data.series,data.sameName)
        if(seasons.size>1) {
            val seasonScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val seasonRow=row().apply { setPadding(0,dp(2),0,dp(6)) }
            seasonRow.addView(text("本系列共 ${seasons.size} 季",12f,muted).apply { setPadding(dp(2),dp(10),dp(12),0) })
            seasons.forEach { season ->
                addButton(seasonRow,"第 ${Seasons.number(season.title)} 季",season.id==data.series.id) {
                    if(season.id!=data.series.id) openDetail(season)
                }
            }
            seasonScroll.addView(seasonRow)
            content.addView(seasonScroll,lp(-1,-2))
        }
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index ->
                // The episode you are on stays marked in the accent colour, not only by focus.
                val item=button("${index+1}",index==episodeIndex) {
                    playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0)
                }.apply { if(index==episodeIndex) { setTextColor(accent); setTypeface(null,Typeface.BOLD) } }
                item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item
            }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        val extraRows=mutableListOf<MutableList<View>>()
        fun recommendationRow(title: String,items: List<Series>) {
            if(items.isEmpty()) return
            val section=column()
            val heading=row(); heading.setPadding(0,dp(20),0,dp(9))
            heading.addView(View(this).apply { background=TvStyle.shape(this@MainActivity,TvStyle.accent,radius=2) },lp(dp(3),dp(14)).apply { rightMargin=dp(8) })
            heading.addView(text(title,16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            heading.addView(text("左右查看更多",11f,muted))
            section.addView(heading)
            val horizontal=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
            val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
            val posterHeight=((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val cards=mutableListOf<View>()
            items.take(12).forEach { series ->
                val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=TvStyle.card(this@MainActivity,false); contentDescription="$title，${series.title}" }
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                card.addView(image,lp(-1,posterHeight))
                card.addView(text(series.title,14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) })
                card.addView(text(series.badge.ifBlank { series.tags },12f,muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) })
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) loadCover(image,series.cover) }
                card.setOnClickListener { openDetail(series) }
                if(cards.size<6) loadCover(image,series.cover)
                line.addView(card,lp(width,-2).apply { rightMargin=dp(14) })
                cards+=card
            }
            horizontal.addView(line); section.addView(horizontal)
            content.addView(section,lp(-1,-2).apply { topMargin=dp(4) })
            extraRows+=cards
        }
        recommendationRow("系列剧推荐",data.sameName)
        recommendationRow("相关推荐",data.related)
        val lastRowStart=((episodes.size-1)/10)*10
        val firstRow=extraRows.firstOrNull()
        episodes.forEachIndexed { i,v ->
            v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id
            v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id
            if(i>=10) v.nextFocusUpId=episodes[i-10].id
            v.nextFocusDownId=when {
                i+10<episodes.size -> episodes[i+10].id
                firstRow!=null && i>=lastRowStart -> firstRow[(i-lastRowStart).coerceAtMost(firstRow.lastIndex)].id
                else -> v.id
            }
        }
        extraRows.forEachIndexed { rowIndex,cards ->
            cards.forEachIndexed { i,v ->
                v.nextFocusLeftId=cards[(i-1).coerceAtLeast(0)].id
                v.nextFocusRightId=cards[(i+1).coerceAtMost(cards.lastIndex)].id
                v.nextFocusUpId=if(rowIndex>0) extraRows[rowIndex-1].getOrNull(i.coerceAtMost(extraRows[rowIndex-1].lastIndex))?.id ?: play.id
                    else episodes.getOrNull((lastRowStart+i).coerceAtMost(episodes.lastIndex))?.id ?: play.id
                v.nextFocusDownId=if(rowIndex<extraRows.lastIndex) extraRows[rowIndex+1].getOrNull(i.coerceAtMost(extraRows[rowIndex+1].lastIndex))?.id ?: v.id else v.id
            }
        }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        // Auto-next / episode switch while the player is already up: swap the source in place
        // and keep the last rendered frame instead of tearing the player down.
        val seamless=reuse && position==0L
        val staleVideo=if(reuse) video else null
        val staleHttp=if(reuse) playbackHttp else null
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews(); stopDanmaku()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            // Deliberately no stop()/clearMediaItems(): the old source keeps the last frame on
            // screen and the decoder is reconfigured in place. Old resources are released once
            // the new source has taken over (see below).
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        val skipMark=library.skipMark(data.series.id)
        outroSkipSeconds=skipMark?.outroSeconds ?: 0; outroTriggered=false; ffActive=false
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val danmakuLayer=DanmakuView(this)
            danmakuView=danmakuLayer
            danmakuLayer.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
            root.addView(danmakuLayer,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.38f).toInt(),Gravity.TOP).apply { topMargin=dp(12) })
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    长按右键 3× 快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playerView?.setShowBuffering(if(seamless) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_ALWAYS)
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality; videoCodec=remote.info.codec
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,500,1000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=DecodeSupport.renderers(this,softDecodeForced)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        applyVideoFrameRateHint(p)
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}"); startDanmaku() }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && continueToNextSeason()) { /* season jump in progress */ }
                        else { requestedAutoplay=false; p.pause(); showEndedPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) { syncMediaSession(); danmakuView?.seekTo(newPosition.positionMs) } }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            val introSkip=if(position<=0 && (skipMark?.introSeconds ?: 0)>0) skipMark!!.introSeconds*1000L else position.coerceAtLeast(0)
            p.setMediaSource(source); p.seekTo(introSkip); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            if(staleVideo!=null || staleHttp!=null) main.postDelayed({ staleVideo?.close(); closeTransport(staleHttp) },1500)
            if(introSkip>0 && position<=0) Toast.makeText(this,"已跳过片头 ${skipMark!!.introSeconds} 秒",Toast.LENGTH_SHORT).show()
        }, { problem -> playerFailure(problem) })
    }
    /**
     * Hint the display pipeline about the content frame rate (API 29+/31+).
     * On MTK TV SoCs (MT9655/MT9681 class) this lets MEMC and panel timing match the
     * fixed-rate source instead of doing 3:2/2:2 pulldown against a mismatched refresh,
     * reducing judder on 24/25/30fps content. Best-effort: silently ignored when the
     * surface is gone, the frame rate is unknown, or the platform rejects the hint.
     */
    private fun applyVideoFrameRateHint(p: Player) {
        val group=p.currentTracks.groups.firstOrNull { it.type==C.TRACK_TYPE_VIDEO && it.isSelected }
        val fps=group?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate } }
            ?.takeIf { it>0f && it.isFinite() } ?: return
        val surfaceView=playerView?.videoSurfaceView as? android.view.SurfaceView ?: return
        val surface=surfaceView.holder?.surface ?: return
        runCatching {
            if(android.os.Build.VERSION.SDK_INT>=31) {
                surface.setFrameRate(fps,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else if(android.os.Build.VERSION.SDK_INT>=30) {
                // 两参数版 setFrameRate 自 API 30 起才有；API 29 上原本会静默失效
                surface.setFrameRate(fps,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=TlsSupport.client(
            okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS),
            this
        ).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean {
        if(android.os.Build.VERSION.SDK_INT>=23) return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
        @Suppress("DEPRECATION")
        return connectivity.activeNetworkInfo?.isConnected==true
    }
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        val failedCodec=problem is PlaybackException && problem.errorCode in 3000..4999
        if(failedCodec && !softRetryUsed) {
            // The platform decoder cannot handle this stream: rebuild with FFmpeg software decoding.
            softRetryUsed=true; softDecodeForced=true
            val position=currentPosition().coerceAtLeast(0)
            android.util.Log.i("HongguoTV","Platform decode failed codec=$videoCodec, switching to software decoder")
            player?.stop()
            playEpisode(episodeIndex,position,requestedAutoplay,recovering=true)
            return
        }
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        playbackText.text=if(TlsSupport.isCertificateProblem(problem)) {
            "证书校验失败，无法取流。请检查设备系统时间是否正确；本版已内置常用根证书，仍失败可换网络再试。"
        } else if(decoding) {
            val codec=when(videoCodec) { "hevc" -> "HEVC/H.265"; "av1" -> "AV1"; "avc" -> "H.264"; "vp9" -> "VP9"; "" -> "未知"; else -> videoCodec }
            "本机硬件解码器不支持 $codec，已尝试切换 FFmpeg 软件解码。"
        } else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        // Failure screen gets one primary action instead of three look-alike buttons.
        val retryNow=primaryButton("立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        actions.addView(retryNow,lp(-2,dp(44)).apply { rightMargin=dp(10) })
        retryNow.requestFocus()
        if(decoding) addButton(actions,"软解重试") {
            softDecodeForced=true; softRetryUsed=true
            playEpisode(episodeIndex,retryPosition,retryAutoplay)
        } else addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private var updateChecking=false

    /** Manual or scheduled check against the newest CNB release for this build's variant. */
    private fun checkForUpdates(manual: Boolean) {
        if(updateChecking || isDestroyed) return
        updateChecking=true
        if(manual) Toast.makeText(this,"正在检查更新…",Toast.LENGTH_SHORT).show()
        io.execute {
            val info=runCatching { UpdateCenter.check(httpClient,BuildConfig.VARIANT_ID,BuildConfig.VERSION_CODE) }.getOrNull()
            main.post {
                updateChecking=false
                if(isDestroyed) return@post
                library.lastUpdateCheck=System.currentTimeMillis()
                when {
                    info!=null -> showUpdateDialog(info)
                    manual -> Toast.makeText(this,"已是最新版本（${BuildConfig.VERSION_NAME}）",Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    /** Runs once a day, and never while a video is on screen. */
    private fun maybeAutoUpdate() {
        if(!library.autoUpdate || updateChecking || screen=="player" || player!=null) return
        if(System.currentTimeMillis()-library.lastUpdateCheck<24*60*60*1000L) return
        checkForUpdates(manual=false)
    }
    private fun showUpdateDialog(info: UpdateCenter.Info) {
        val notes=info.notes.ifBlank { "包含问题修复与稳定性改进。" }
        AlertDialog.Builder(this).setTitle("发现新版本 ${info.versionName}")
            .setMessage("当前 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n$notes")
            .setPositiveButton("立即更新") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("稍后",null)
            .show()
    }
    /** Streams the APK with a progress dialog, then hands it to the system installer. */
    private fun startUpdateDownload(info: UpdateCenter.Info) {
        val panel=column().apply { setPadding(dp(24),dp(18),dp(24),dp(6)) }
        val label=text("正在下载 ${info.versionName}…",14f)
        val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0 }
        panel.addView(label)
        panel.addView(bar,lp(-1,dp(26)).apply { topMargin=dp(12) })
        val dialog=AlertDialog.Builder(this).setTitle("应用内更新").setView(panel).setCancelable(false).setNegativeButton("取消",null).create()
        var cancelled=false
        dialog.setOnDismissListener { cancelled=true }
        dialog.show()
        val target=java.io.File(cacheDir,"updates/${info.file}")
        io.execute {
            val file=UpdateCenter.download(httpClient,info,target) { percent ->
                main.post { if(dialog.isShowing) { bar.progress=percent; label.text="正在下载 ${info.versionName}… $percent%" } }
            }
            main.post {
                if(cancelled || isDestroyed) return@post
                dialog.dismiss()
                if(file==null) { Toast.makeText(this,"下载校验失败，请稍后重试",Toast.LENGTH_LONG).show(); return@post }
                if(!UpdateCenter.install(this,file)) Toast.makeText(this,"请在系统设置里允许本应用安装未知应用，然后再点一次检查更新",Toast.LENGTH_LONG).show()
            }
        }
    }
    /** On-device answer to "why can't this play": what the SoC decodes and what the stream uses. */
    private fun showCodecInfo(anchor: TextView) {
        val report=codecReport()
        AlertDialog.Builder(this).setTitle("解码信息").setMessage(report)
            .setPositiveButton("关闭",null)
            .setNeutralButton("写入日志") { _, _ ->
                android.util.Log.i("HongguoTV","CODEC_REPORT\n$report")
                Toast.makeText(this,"已写入日志（标签 HongguoTV）",Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun codecReport(): String {
        val sb=StringBuilder()
        sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
        sb.append("系统：Android ").append(android.os.Build.VERSION.RELEASE).append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n")
        sb.append("平台：").append(android.os.Build.HARDWARE).append('\n')
        val infos=runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        fun describe(mime: String): String {
            val matching=infos.filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime,true) } }
            if(matching.isEmpty()) return "无"
            return matching.joinToString("；") { info ->
                val hw=if(android.os.Build.VERSION.SDK_INT>=29) (if(info.isHardwareAccelerated) "硬件" else "软件") else "未知"
                val level=runCatching { info.getCapabilitiesForType(mime).profileLevels.maxOfOrNull { it.level } ?: 0 }.getOrDefault(0)
                "${info.name}（$hw"+(if(level>0) " · level $level" else "")+"）"
            }
        }
        val hevc=describe("video/hevc")
        sb.append("\nH.265 / HEVC：").append(hevc).append('\n')
        sb.append("H.264 / AVC：").append(describe("video/avc")).append('\n')
        sb.append("AV1：").append(describe("video/av01")).append('\n')
        sb.append("VP9：").append(describe("video/x-vnd.on2.vp9")).append('\n')
        sb.append("\n当前片源：").append(if(videoCodec.isBlank()) "未播放" else "$quality · $videoCodec")
        sb.append("\n软件解码：").append(if(DecodeSupport.softwareAvailable) "FFmpeg 可用" else "不含（仅硬件）")
        if(hevc=="无") sb.append("\n\n本机没有 H.265 解码器，而片源只有 H.265（1080P~360P 全是），因此无法播放；降低清晰度也没有用。")
        return sb.toString()
    }
    /** Seven-level text scale as a seek bar: three steps smaller and three larger than 100%. */
    private fun showFontScalePicker(anchor: TextView) {
        val panel=column().apply { setPadding(dp(22),dp(14),dp(22),0) }
        var index=FontScale.indexOf(library.fontScale)
        val value=text(FontScale.label(index),16f)
        val sample=text("红果短剧 · 第 12 集  示例文字",16f)
        val bar=SeekBar(this).apply {
            max=FontScale.steps.lastIndex; progress=index
            isFocusable=true; isFocusableInTouchMode=true
            setPadding(dp(2),0,dp(2),0)
        }
        fun apply(value0: Int) {
            index=value0
            value.text=FontScale.label(index)
            sample.textSize=16f*FontScale.scaleOf(index)
        }
        bar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar,progress: Int,fromUser: Boolean) { apply(progress) }
            override fun onStartTrackingTouch(seek: SeekBar) {}
            override fun onStopTrackingTouch(seek: SeekBar) {}
        })
        panel.addView(value)
        panel.addView(sample,lp(-1,-2).apply { topMargin=dp(10) })
        panel.addView(bar,lp(-1,dp(52)).apply { topMargin=dp(8) })
        panel.addView(text("左右方向键调节 · 共 7 档（-3 ~ +3）· 应用后整机生效",12f,muted).apply { setPadding(0,dp(6),0,0) })
        val dialog=AlertDialog.Builder(this).setTitle("文字大小").setView(panel)
            .setPositiveButton("应用",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            bar.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                library.fontScale=FontScale.scaleOf(index)
                dialog.dismiss()
                anchor.text="文字大小  ·  ${FontScale.label(index)}"
                restoreAfterRecreate=tab; recreate()
            }
        }
        dialog.show()
    }
    /** Collapses the secondary page header while the grid is scrolled; the tab bar never moves. */
    private fun setHeaderCollapsed(collapsed: Boolean) {
        val header=pageHeader ?: return
        if(headerCollapsed==collapsed || header.childCount==0) return
        if(header.hasFocus()) return
        if(header.height>0) headerHeight=header.height
        if(headerHeight<=0) headerHeight=header.measuredHeight.takeIf { it>0 } ?: dp(96)
        headerCollapsed=collapsed
        if(collapsed) { headerDownBefore.clear(); nav.forEach { headerDownBefore[it.id]=it.nextFocusDownId } }
        val from=header.height.coerceAtLeast(0)
        val to=if(collapsed) 0 else headerHeight
        header.visibility=View.VISIBLE
        val animator=android.animation.ValueAnimator.ofInt(from,to)
        animator.duration=if(collapsed) 140L else 180L
        animator.interpolator=android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val height=animation.animatedValue as Int
            header.layoutParams=header.layoutParams.apply { this.height=height }
            header.requestLayout()
            header.alpha=if(headerHeight>0) (height.toFloat()/headerHeight).coerceIn(0f,1f) else if(collapsed) 0f else 1f
        }
        animator.addListener(object: android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if(headerCollapsed!=collapsed) return
                header.visibility=if(collapsed) View.GONE else View.VISIBLE
                header.layoutParams=header.layoutParams.apply { height=if(collapsed) 0 else -2 }
                header.alpha=1f; header.requestLayout()
                if(!collapsed) header.post { if(!headerCollapsed) headerHeight=header.height }
                if(collapsed) nav.forEach { it.nextFocusDownId=catalogRecycler?.id ?: it.id }
                else nav.forEach { view -> headerDownBefore[view.id]?.let { view.nextFocusDownId=it } }
            }
        })
        animator.start()
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && !ffActive && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { ffActive -> "3× 快进中"; p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun applyDanmakuPrefs()=danmakuView?.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
    private fun resetDanmakuPrefs() {
        library.danmakuOpacity=DanmakuPrefs.OPACITY_DEFAULT; library.danmakuFont=DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed=DanmakuPrefs.SPEED_DEFAULT; library.danmakuRows=DanmakuPrefs.ROWS_DEFAULT
        applyDanmakuPrefs()
    }
    private fun showDanmakuOpacityPicker(anchor: TextView,done: (()->Unit)?=null) {
        val options=listOf(100,90,80,70,60,50,40,30,21)
        tvTools.choose("弹幕不透明度 · ${library.danmakuOpacity}%",options.map { "$it%" },anchor,{ choice ->
            library.danmakuOpacity=options[choice]; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕不透明度  ·  ${library.danmakuOpacity}%"
            done?.invoke()
        })
    }
    private fun showDanmakuFontPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕字体大小 · ${DanmakuPrefs.fontLabel(library.danmakuFont)}",DanmakuPrefs.FONT_LABELS,anchor,{ choice ->
            library.danmakuFont=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            done?.invoke()
        })
    }
    private fun showDanmakuSpeedPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕移动速度 · ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}",DanmakuPrefs.SPEED_LABELS,anchor,{ choice ->
            library.danmakuSpeed=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            done?.invoke()
        })
    }
    private fun showDanmakuRowsPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕展示行数 · ${library.danmakuRows} 行",(DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).map { "$it 行" },anchor,{ choice ->
            library.danmakuRows=choice+DanmakuPrefs.ROWS_MIN; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕展示行数  ·  ${library.danmakuRows} 行"
            done?.invoke()
        })
    }
    /** In-player danmaku preferences; reopens itself after each change so several can be tuned in one visit. */
    private fun showDanmakuPrefsDialog(anchor: TextView) {
        fun labels()=listOf("不透明度 ${library.danmakuOpacity}%","字体大小 ${DanmakuPrefs.fontLabel(library.danmakuFont)}","移动速度 ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}","展示行数 ${library.danmakuRows} 行","恢复默认设置")
        tvTools.choose("弹幕设置",labels(),anchor,{ choice ->
            main.post {
                if(screen!="player" || !anchor.isAttachedToWindow) return@post
                when(choice) {
                    0 -> showDanmakuOpacityPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    1 -> showDanmakuFontPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    2 -> showDanmakuSpeedPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    3 -> showDanmakuRowsPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    4 -> { resetDanmakuPrefs(); showDanmakuPrefsDialog(anchor) }
                }
            }
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun startFastForward() {
        val p=player ?: return
        if(ffActive || !playbackReady || p.duration<=0) return
        ffActive=true; ffSpeedBefore=p.playbackParameters.speed
        p.setPlaybackSpeed(3f); showHud(); updatePlaybackText()
    }
    private fun stopFastForward() {
        if(!ffActive) return
        ffActive=false; player?.setPlaybackSpeed(ffSpeedBefore)
        updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        lateinit var episodes: TextView
        episodes=addButton(transport,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(transport,"播放设置") {
            val seriesId=detail?.series?.id.orEmpty()
            val mark=library.skipMark(seriesId)
            fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
            tvTools.choose("播放设置",buildList {
                add("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}"); add("清晰度 ${library.maxQuality}P"); add("画面 ${library.frameMode.label}"); add("定时 ${sleepTimer.label()}")
                add("跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}"); add("跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}"); add("从头播放"); add("弹幕 ${if(library.danmaku) "开启" else "关闭"}")
                if(library.danmaku) add("弹幕设置")
            },more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> showSkipPicker(more,true)
                        5 -> showSkipPicker(more,false)
                        6 -> { seekTo(0); hidePanel() }
                        7 -> { library.danmaku=!library.danmaku; if(library.danmaku) startDanmaku() else stopDanmaku() }
                        8 -> showDanmakuPrefsDialog(more)
                    }
                }
            })
        }.apply { tag="playback-settings" }
        // Series actions were only reachable by leaving playback; favoriting mid-episode is the
        // single most requested thing to do while watching, so it lives here now.
        val series=detail?.series
        if(series!=null) {
            val seriesRow=row().apply { setPadding(0,dp(8),0,0) }
            controls.addView(seriesRow)
            lateinit var favorite: TextView
            favorite=addButton(seriesRow,if(library.favorite(series.id)) "已收藏" else "收藏这部剧") {
                val saved=library.toggle(series)
                favorite.text=if(saved) "已收藏" else "收藏这部剧"
                Toast.makeText(this,if(saved) "已收藏，更新会出现在收藏页" else "已取消收藏",Toast.LENGTH_SHORT).show()
            }
            addButton(seriesRow,"查看详情") { returnToDetail(true) }
        }
        play.requestFocus()
    }
    /** Season finale auto-continues into the next season only when both the master autoplay and the season switch are on. */
    private fun continueToNextSeason(): Boolean {
        val data=detail ?: return false
        if(!library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) return false
        val next=data.nextSeason ?: return false
        Toast.makeText(this,"即将播放《${next.title}》",Toast.LENGTH_SHORT).show()
        openDetail(next,resume=true,autoplay=true)
        return true
    }
    private fun showEndedPanel() {
        val data=detail
        if(playError || data==null || !library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) {
            playbackText.text="本集已结束"; showPanel(); return
        }
        val cached=library.cachedHome(library.contentType)?.items.orEmpty().filter { it.id!=data.series.id }
        if(cached.isNotEmpty()) { showRecommendationPanel(cached); return }
        playbackText.text="本集已结束"; showPanel()
        // The home cache can be empty on a cold start that went straight into playback; fetch once and upgrade the panel.
        work({ repository.home(1,library.contentType) }, { page ->
            if(screen=="player" && player?.playbackState==Player.STATE_ENDED && detail?.series?.id==data.series.id) {
                val items=page.items.filter { it.id!=data.series.id }
                if(items.isNotEmpty()) showRecommendationPanel(items)
            }
        }, { /* keep the plain ended panel */ })
    }
    private fun showRecommendationPanel(items: List<Series>) {
        if(playError || screen!="player") return
        panel=true; controls.visibility=View.VISIBLE; controls.removeAllViews()
        controls.addView(text("《${detail?.series?.title}》已播完 · 为你推荐热门${library.contentType.label}",14f,muted).apply { setPadding(dp(4),0,0,dp(8)) })
        val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
        val line=row(); strip.addView(line); controls.addView(strip)
        items.take(10).forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(8)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(dp(96),(dp(96)/TvStyle.POSTER_ASPECT).toInt()))
            loadCover(image,item.cover)
            card.addView(text(item.title,12f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(4),dp(2),0) },lp(dp(96),-2))
            card.contentDescription=item.title
            card.setOnClickListener { openDetail(item) }
            line.addView(card,lp(-2,-2).apply { rightMargin=dp(10) })
        }
        val actions=row().apply { setPadding(0,dp(10),0,0) }; controls.addView(actions)
        addButton(actions,"返回详情") { returnToDetail(true) }
        line.getChildAt(0)?.requestFocus()
        showHud()
    }
    private fun showSkipPicker(anchor: TextView,intro: Boolean) {
        val data=detail ?: return
        val options=listOf(0,15,30,45,60,90,120)
        val current=library.skipMark(data.series.id)?.let { if(intro) it.introSeconds else it.outroSeconds } ?: 0
        tvTools.choose((if(intro) "跳过片头" else "跳过片尾")+" · 仅对本剧生效",options.map { if(it==0) "关闭" else "$it 秒" },anchor,{ index ->
            val old=library.skipMark(data.series.id) ?: SkipMark(0,0)
            val next=if(intro) old.copy(introSeconds=options[index]) else old.copy(outroSeconds=options[index])
            library.setSkipMark(data.series.id,next)
            if(!intro) { outroSkipSeconds=next.outroSeconds; outroTriggered=false }
            Toast.makeText(this,"已保存，仅对《${data.series.title}》生效",Toast.LENGTH_SHORT).show()
            showHud()
        },"当前 ${if(current>0) "$current 秒" else "关闭"}") {}
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=AlertDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=AlertDialog.Builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target
                library.maxQuality=target; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun backdropFile()=filesDir.listFiles()?.firstOrNull { it.name.startsWith("backdrop.") }
    /** Scale [raw] to fill the screen and lay a theme-colored scrim over it so text stays readable. */
    private fun frameBackdrop(raw: Bitmap): Bitmap? {
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        if(width<=0 || height<=0) return null
        val scale=maxOf(width.toFloat()/raw.width,height.toFloat()/raw.height)
        val drawWidth=(raw.width*scale).toInt(); val drawHeight=(raw.height*scale).toInt()
        val scaled=if(drawWidth==raw.width && drawHeight==raw.height) raw else Bitmap.createScaledBitmap(raw,drawWidth,drawHeight,true)
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(scaled,(width-drawWidth)/2f,(height-drawHeight)/2f,null)
            drawColor(Color.argb(205,Color.red(bg),Color.green(bg),Color.blue(bg)))
        }
        if(scaled!==raw) raw.recycle()
        return output
    }
    private fun decodeBackdrop(bytes: ByteArray): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        var sample=1
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        while(bounds.outWidth/(sample*2)>=width && bounds.outHeight/(sample*2)>=height) sample*=2
        val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        return frameBackdrop(raw)
    }
    /** The bundled "强制欣赏" backdrop: first-launch default, selectable from 恢复默认背景. */
    private fun decodeForcedBackdrop(): Bitmap? =
        BitmapFactory.decodeResource(resources,R.drawable.backdrop_forced)?.let { frameBackdrop(it) }
    /** Built-in backdrop style: "forced" (bundled picture, default) or "plain" (theme color only). */
    private fun backdropStyle()=getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).getString("backdropStyle","forced") ?: "forced"
    private fun loadBackdrop() {
        io.execute {
            val bitmap=backdropFile()?.let { runCatching { decodeBackdrop(it.readBytes()) }.getOrNull() }
                ?: if(backdropStyle()=="forced") runCatching { decodeForcedBackdrop() }.getOrNull() else null
            main.post {
                if(isDestroyed) return@post
                backdropBitmap=bitmap
                backdropView?.let {
                    if(bitmap==null) { (it.parent as? android.view.ViewGroup)?.removeView(it); it.setImageDrawable(null) }
                    else { if(it.parent==null && screen!="player") root.addView(it,0,FrameLayout.LayoutParams(-1,-1)); it.setImageBitmap(bitmap) }
                }
            }
        }
    }
    private fun saveBackdrop(bytes: ByteArray,extension: String) {
        io.execute {
            val bitmap=runCatching {
                filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() }
                java.io.File(filesDir,"backdrop.$extension").writeBytes(bytes)
                decodeBackdrop(bytes)
            }.getOrNull()
            main.post {
                if(isDestroyed) return@post
                if(bitmap==null) { Toast.makeText(this@MainActivity,"图片无法使用，请换一张试试",Toast.LENGTH_LONG).show(); return@post }
                backdropBitmap=bitmap
                backdropView?.let {
                    if(it.parent==null && screen!="player") root.addView(it,0,FrameLayout.LayoutParams(-1,-1))
                    it.setImageBitmap(bitmap)
                }
                Toast.makeText(this@MainActivity,"背景已更新",Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** Drop any uploaded picture and switch back to one of the built-in styles. */
    private fun resetBackdrop(style: String) {
        io.execute { filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() } }
        getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).edit().putString("backdropStyle",style).apply()
        loadBackdrop()
        Toast.makeText(this,if(style=="forced") "已恢复为强制欣赏" else "已恢复默认背景",Toast.LENGTH_SHORT).show()
    }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    /** Starts the official-comment overlay for the current episode; safe to call repeatedly. */
    private fun startDanmaku() {
        val layer=danmakuView ?: return
        val data=detail ?: return
        val p=player
        if(!library.danmaku || p==null || !playbackReady || playError || p.duration<=0) return
        danmakuLoader=null
        val loader=DanmakuLoader(danmakuRepo,data.series.id,data.episodes[episodeIndex],p.duration)
        danmakuLoader=loader
        layer.bind(loader,{ currentPosition() })
        val startPosition=currentPosition()
        io.execute { runCatching { loader.loadUpTo(startPosition) } }
    }
    private fun stopDanmaku() {
        danmakuLoader=null
        danmakuView?.unbind()
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        ffActive=false; outroSkipSeconds=0; outroTriggered=false
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        stopDanmaku(); danmakuView=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(player?.playbackState==Player.STATE_ENDED) returnToDetail() else if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_UP) { stopFastForward(); return true }
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(event.repeatCount==0) seek(1,0) else startFastForward()
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
