// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Season folding.
 *
 * The content site sells every season as its own series id: searching 持械入宋 returns
 * 第一季 … 第九季 as nine separate cards, plus a duplicate 第四季 with a different episode count.
 * [collapse] folds those into one card per show, and [siblings] feeds the season switcher on the
 * detail page so the seasons stay reachable after folding.
 */
object Seasons {
    /** Season number of a title; a title without a marker counts as season 1. */
    fun number(title: String): Int=seasonNumber(title).let { if(it==Int.MAX_VALUE) 1 else it }

    /** Title with the season marker removed, used to match the same show across seasons. */
    fun base(title: String): String=baseName(title)

    /** One entry per season of the same show, ordered by season, richest entry first for duplicates. */
    fun siblings(current: Series,others: List<Series>): List<Series> {
        val key=baseName(current.title)
        if(key.isBlank()) return listOf(current)
        val pool=(listOf(current)+others).filter { baseName(it.title)==key }
        return pool.sortedWith(compareBy({ seasonNumber(it.title) },{ -episodes(it) }))
            .distinctBy { seasonNumber(it.title) }
    }

    /**
     * One card per show. The representative is the earliest season, so "开始观看" starts at
     * season 1 instead of dropping the viewer into the newest one; its badge marks the fold.
     */
    fun collapse(items: List<Series>): List<Series> {
        // Always return a new list: callers reuse and clear the argument.
        if(items.size<2) return items.toList()
        val groups=LinkedHashMap<String,MutableList<Series>>()
        for(item in items) {
            val key=baseName(item.title).ifBlank { item.id }
            groups.getOrPut(key){ mutableListOf() }.add(item)
        }
        return groups.values.map { group ->
            if(group.size<2) group.first()
            else group.sortedWith(compareBy({ seasonNumber(it.title) },{ -episodes(it) }))
                .distinctBy { seasonNumber(it.title) }
                .first().copy(badge="多季合集 · 点开选季")
        }
    }

    private fun episodes(series: Series): Int=Regex("[0-9]+").find(series.badge)?.value?.toIntOrNull() ?: 0
}

internal val SeasonPattern=Regex("第([0-9零〇一二三四五六七八九十百千]+)(?:季|部)")
internal fun seasonNumber(title: String): Int {
    val matched=SeasonPattern.find(title)?.groupValues?.get(1) ?: return Int.MAX_VALUE
    return chineseNumber(matched)
}
internal fun baseName(title: String)=title.replace(SeasonPattern,"").trim().trim('：',':','-','·',' ')
private fun chineseNumber(text: String): Int {
    text.toIntOrNull()?.let { return it }
    val digits=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
    val ten=text.indexOf('十')
    if(ten<0) return text.singleOrNull()?.let { digits[it] } ?: Int.MAX_VALUE
    val high=if(ten==0) 1 else digits[text.first()] ?: 1
    val low=if(ten==text.lastIndex) 0 else digits[text.last()] ?: 0
    return high*10+low
}
