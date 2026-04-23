package com.begugla.snipinger

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object WhitelistCache {

    const val URL =
        "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/whitelist.txt"

    private const val FILE_NAME = "whitelist.txt"
    private const val META_PREFS = "whitelist_meta"
    private const val KEY_LAST_UPDATED = "last_updated"
    private const val KEY_LAST_SIZE = "last_size"

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    fun hasCache(context: Context): Boolean = file(context).exists() && file(context).length() > 0

    fun read(context: Context): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readText()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    fun lastUpdatedMillis(context: Context): Long =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATED, 0L)

    fun lastSize(context: Context): Int =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_SIZE, 0)

    sealed class DownloadResult {
        data class Success(val entries: List<String>, val changed: Boolean) : DownloadResult()
        data class Failure(val message: String) : DownloadResult()
    }

    fun download(context: Context): DownloadResult {
        return try {
            val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            conn.inputStream.use { input ->
                val text = input.bufferedReader().readText()
                val f = file(context)
                val previous = if (f.exists()) f.readText() else ""
                val changed = previous != text
                if (changed) f.writeText(text)
                context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                    .putInt(
                        KEY_LAST_SIZE,
                        text.split("\n").count { it.isNotBlank() && !it.trim().startsWith("#") }
                    )
                    .apply()
                val entries = text.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                DownloadResult.Success(entries, changed)
            }
        } catch (e: Exception) {
            DownloadResult.Failure(e.message ?: "unknown error")
        }
    }
}
