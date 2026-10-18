// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Fixed against a live search for 持械入宋 (2026-10-17): the site returns nine season entries for
 * one show, one of them duplicated under a second id with a different episode count, and a
 * colon-less title. Season folding has to survive all three shapes.
 */
class SeasonsTest {
    private fun series(id: String,title: String,badge: String="全 100 集",cover: String="")=
        Series(id,title,cover,"","$badge","")

    private val liveSearch=listOf(
        series("1","持械入宋：第一季"),
        series("9","持械入宋：第九季"),
        series("8","持械入宋：第八季"),
        series("7","持械入宋：第七季"),
        series("3","持械入宋：第三季"),
        series("4","持械入宋：第四季"),
        series("2","持械入宋：第二季"),
        series("44","持械入宋第四季","全 219 集"),
        series("5","持械入宋：第五季"),
        series("99","缅北风云","全 86 集")
    )

    @Test fun foldsEverySeasonIntoOneEntry() {
        val folded=Seasons.collapse(liveSearch)
        assertEquals(2,folded.size)
        assertEquals("持械入宋：第一季",folded[0].title)
        assertEquals("缅北风云",folded[1].title)
        assertEquals("多季合集 · 点开选季",folded[0].badge)
        assertEquals("全 86 集",folded[1].badge)
    }

    @Test fun seasonNumbersCoverChineseNumerals() {
        assertEquals(1,Seasons.number("持械入宋：第一季"))
        assertEquals(1,Seasons.number("持械入宋"))
        assertEquals(2,Seasons.number("持械入宋：第二季"))
        assertEquals(10,Seasons.number("某剧第十季"))
        assertEquals(12,Seasons.number("某剧第十二季"))
        assertEquals(3,Seasons.number("某剧第3部"))
    }

    @Test fun baseNameDropsTheSeasonMarkerAndSeparator() {
        assertEquals("持械入宋",Seasons.base("持械入宋：第一季"))
        assertEquals("持械入宋",Seasons.base("持械入宋第四季"))
        assertEquals("持械入宋",Seasons.base("持械入宋 - 第二季"))
        assertEquals("缅北风云",Seasons.base("缅北风云"))
    }

    @Test fun siblingsDedupeBySeasonNumberAndPreferTheRicherEntry() {
        val current=liveSearch.first { it.id=="1" }
        val others=liveSearch.filter { it.id!="1" }
        val siblings=Seasons.siblings(current,others)
        assertEquals(listOf(1,2,3,4,5,7,8,9),siblings.map { Seasons.number(it.title) })
        // 第四季 exists twice; the 219 集 edition is the one worth keeping.
        assertEquals("44",siblings.first { Seasons.number(it.title)==4 }.id)
    }

    @Test fun singleSeasonListsAreReturnedUnchanged() {
        val single=listOf(series("99","缅北风云","全 86 集"))
        assertEquals(single,Seasons.collapse(single))
        assertEquals(single,Seasons.siblings(single.first(),emptyList()))
    }

    @Test fun differentShowsSharingAPrefixAreNotMerged() {
        val items=listOf(series("1","持械入宋第二季"),series("2","持械入宋外传"))
        assertEquals(2,Seasons.collapse(items).size)
    }
}
