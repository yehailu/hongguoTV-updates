package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
    args.firstOrNull { it.startsWith("--theater=") }?.substringAfter("=")?.let { raw ->
        val type=ContentType.entries.firstOrNull { it.storedValue==raw.lowercase() } ?: ContentType.SHORT
        val genres=runCatching { repository.genres(type) }
        println("genres=${genres.getOrNull()?.size} err=${genres.exceptionOrNull()?.message}")
        val genre=genres.getOrNull()?.firstOrNull()
        if(genre!=null) {
            val page=runCatching { repository.home(1,type,genre) }
            println("theater genre=${genre.name} items=${page.getOrNull()?.items?.size} hasMore=${page.getOrNull()?.hasMore} err=${page.exceptionOrNull()?.message}")
        }
        val all=runCatching { repository.home(1,type) }
        println("theater-all items=${all.getOrNull()?.items?.size} err=${all.exceptionOrNull()?.message}")
        return
    }
    if(args.contains("--register")) {
        val ok=repository.registerDeviceIfNeeded()
        val snap=repository.deviceSnapshot()
        println("registered=$ok device=${snap.first} iid=${snap.second} cookies=${snap.third.keys}")
        return
    }
    args.firstOrNull { it.startsWith("--search=") }?.substringAfter("=")?.let { keyword ->
        for(type in ContentType.entries) {
            val page=runCatching { repository.search(keyword,1,type) }
            println("type=${type.label} count=${page.getOrNull()?.items?.size} hasMore=${page.getOrNull()?.hasMore} err=${page.exceptionOrNull()?.message}")
            page.getOrNull()?.items?.take(3)?.forEach { println("  - ${it.title} (${it.id}) genre=${it.badge}") }
        }
        val merged=runCatching { repository.searchMerged(keyword,1) }
        println("merged count=${merged.getOrNull()?.items?.size} hasMore=${merged.getOrNull()?.hasMore} err=${merged.exceptionOrNull()?.message}")
        merged.getOrNull()?.items?.take(4)?.forEach { println("  * ${it.title} (${it.id})") }
        return
    }
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            println("ids="+first.items.take(5).map { it.id })
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // Stream-level inspection: what the first megabyte actually contains (profile, bit depth).
    args.firstOrNull { it.startsWith("--stream=") }?.substringAfter("=")?.let { id ->
        val detail=repository.detail(id)
        val episode=detail.episodes.first()
        val cap=args.firstOrNull { it.startsWith("--cap=") }?.substringAfter("=")?.toIntOrNull() ?: 1080
        val stream=repository.stream(episode,cap)
        println("stream quality=${stream.quality} codec=${stream.codec}")
        RemoteVideo(repository.http,stream).use { remote ->
            remote.prepare()
            val head=remote.read(0,minOf(1024*1024L,remote.total).toInt())
            val text=String(head,Charsets.ISO_8859_1)
            val hvc=text.indexOf("hvcC")
            val sampleEntry=listOf("hvc1","hev1","avc1","av01").firstOrNull { text.contains(it) } ?: "?"
            if(hvc>=0) {
                val b=head.copyOfRange(hvc+4,minOf(hvc+4+23,head.size))
                val profileIdc=b[1].toInt() and 0x1F
                val levelIdc=b[12].toInt() and 0xFF
                val chroma=b[16].toInt() and 0x03
                val bitDepthLuma=(b[17].toInt() and 0x07)+8
                println("mp4 sampleEntry=$sampleEntry hvcC profile=$profileIdc level=$levelIdc bitDepth=$bitDepthLuma chromaFormat=$chroma")
            } else println("mp4 sampleEntry=$sampleEntry no-hvcC (avc/other)")
            println("total=${remote.total}")
        }
        return
    }
    args.firstOrNull { it.startsWith("--home=") }?.substringAfter("=")?.let { raw ->
        val homeType=ContentType.entries.firstOrNull { it.storedValue==raw.lowercase() } ?: ContentType.SHORT
        val page=repository.home(1,homeType)
        println("home=${homeType.label}; items=${page.items.size}; ids="+page.items.take(8).map { it.id })
        return
    }
    // Direct series id: skips search/home so the probe still works when those endpoints change.
    args.firstOrNull { it.startsWith("--id=") }?.substringAfter("=")?.let { id ->
        val detail=repository.detail(id)
        println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
        val sample=listOf(detail.episodes.first(),detail.episodes[detail.episodes.size/2],detail.episodes.last()).distinct()
        for(episode in sample) {
            val report=runCatching { repository.variantReport(episode) }.getOrElse { listOf("error=${it.message}") }
            println("episode=$episode variants=$report")
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2,type)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    if(args.contains("--variants")) {
        for(episode in detail.episodes.take(3)) {
            val report=runCatching { repository.variantReport(episode) }.getOrElse { listOf("error=${it.message}") }
            println("episode=$episode variants=$report")
        }
    }
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
