package com.areacalc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Одна запись истории. */
data class HistoryItem(
    val ts: Long,
    val count: Int,
    val total: Double,
    val areas: List<Double>,
    val thumbPath: String
)

/** История расчётов: JSON-индекс + миниатюры в filesDir/history. */
object HistoryStore {
    private const val INDEX = "history.json"
    private const val DIR = "history"
    private const val MAX = 50

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun add(ctx: Context, ts: Long, bitmap: Bitmap, areas: List<Double>) {
        // Миниатюра ~320px по большей стороне
        val maxDim = 320
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val thumb = if (scale < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
        val thumbFile = File(dir(ctx), "thumb_$ts.png")
        FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }

        val arr = readArray(ctx)
        val obj = JSONObject()
        obj.put("ts", ts)
        obj.put("count", areas.size)
        obj.put("total", areas.sum())
        obj.put("areas", JSONArray(areas))
        obj.put("thumb", thumbFile.absolutePath)
        arr.put(obj)

        // Ограничиваем размер истории
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        File(ctx.filesDir, INDEX).writeText(trimmed.toString())
    }

    fun list(ctx: Context): List<HistoryItem> {
        val arr = readArray(ctx)
        val out = ArrayList<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val areasArr = o.getJSONArray("areas")
            val areas = ArrayList<Double>()
            for (j in 0 until areasArr.length()) areas.add(areasArr.getDouble(j))
            out.add(
                HistoryItem(
                    o.getLong("ts"), o.getInt("count"), o.getDouble("total"),
                    areas, o.optString("thumb", "")
                )
            )
        }
        return out.reversed() // новые сверху
    }

    fun loadThumb(path: String): Bitmap? =
        if (path.isNotEmpty() && File(path).exists()) BitmapFactory.decodeFile(path) else null

    fun clear(ctx: Context) {
        File(ctx.filesDir, INDEX).delete()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun readArray(ctx: Context): JSONArray {
        val f = File(ctx.filesDir, INDEX)
        return if (f.exists()) try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        else JSONArray()
    }
}
