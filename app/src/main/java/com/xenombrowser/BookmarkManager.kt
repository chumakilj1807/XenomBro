package com.xenombrowser

import android.content.Context

object BookmarkManager {

    private const val PREFS = "bookmarks"
    private const val KEY = "list"
    private const val SEP = "|||"

    data class Bookmark(val title: String, val url: String)

    fun getAll(ctx: Context): List<Bookmark> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).mapNotNull {
            val parts = it.split(":::", limit = 2)
            if (parts.size == 2) Bookmark(parts[0], parts[1]) else null
        }
    }

    fun add(ctx: Context, title: String, url: String) {
        val list = getAll(ctx).toMutableList()
        if (list.none { it.url == url }) {
            list.add(0, Bookmark(title, url))
            save(ctx, list)
        }
    }

    fun remove(ctx: Context, url: String) {
        val list = getAll(ctx).toMutableList()
        list.removeAll { it.url == url }
        save(ctx, list)
    }

    fun contains(ctx: Context, url: String) = getAll(ctx).any { it.url == url }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val raw = list.joinToString(SEP) { "${it.title}:::${it.url}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }
}
