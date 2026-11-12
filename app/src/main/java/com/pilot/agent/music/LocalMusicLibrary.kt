package com.pilot.agent.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.Manifest
import android.content.pm.PackageManager

/** Lightweight MediaStore lookup. It holds no audio data in memory and never starts another app. */
object LocalMusicLibrary {
    data class Track(val uri: Uri, val title: String, val artist: String, val durationMs: Long) {
        val display: String get() = if (artist.isBlank()) title else "$title - $artist"
    }

    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT <= 32 &&
                Environment.isExternalStorageManager())
    }

    fun find(context: Context, query: String): Track? {
        if (!hasPermission(context)) return null
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > ?"
        val args = arrayOf("30000")
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val needle = normalize(query)
        val terms = query.split(Regex("[\\s的、，,。]+"))
            .map(::normalize).filter { it.length >= 2 }
        if (needle.isBlank()) return null
        return runCatching {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                var best: Track? = null
                var bestScore = 0
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleCol).orEmpty()
                    val artist = cursor.getString(artistCol).orEmpty()
                    val normalizedTitle = normalize(title)
                    val normalized = normalize(title + artist)
                    val score = when {
                        normalizedTitle == needle -> 3
                        normalizedTitle.contains(needle) || (terms.size > 1 && terms.all(normalized::contains)) -> 2
                        normalized.contains(needle) -> 2
                        else -> 0
                    }
                    if (score > bestScore) {
                        val id = cursor.getLong(idCol)
                        best = Track(
                            ContentUris.withAppendedId(collection, id), title, artist,
                            cursor.getLong(durationCol),
                        )
                        bestScore = score
                        if (score == 3) break
                    }
                }
                best
            }
        }.getOrNull()
    }

    /** 本地音乐列表：最近加入的歌曲优先，供“播放本地音乐”直接建立播放队列。 */
    fun list(context: Context, limit: Int = 30): List<Track> {
        if (!hasPermission(context) || limit <= 0) return emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > ?"
        val args = arrayOf("30000")
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        return runCatching {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val out = ArrayList<Track>(limit)
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext() && out.size < limit) {
                    val title = cursor.getString(titleCol).orEmpty().trim()
                    if (title.isBlank()) continue
                    val id = cursor.getLong(idCol)
                    out += Track(
                        ContentUris.withAppendedId(collection, id),
                        title,
                        cursor.getString(artistCol).orEmpty(),
                        cursor.getLong(durationCol),
                    )
                }
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun normalize(value: String): String = value.lowercase()
        .filterNot { it.isWhitespace() || it in "《》【】“”\"'()（）-_" }
}
