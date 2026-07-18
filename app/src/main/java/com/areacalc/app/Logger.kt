package com.areacalc.app

import android.util.Log
import java.io.File

/** Простой накопительный лог: только по делу. Пишется в файл И в Logcat (тег AREACALC). */
object Logger {
    const val TAG = "AREACALC"
    private val sb = StringBuilder()

    fun clear() {
        sb.setLength(0)
    }

    fun log(msg: String) {
        sb.append(msg).append('\n')
        Log.i(TAG, msg) // видно в Android Studio Logcat при фильтре tag:AREACALC
    }

    fun text(): String = sb.toString()

    /** Пишет лог в файл area_log.txt в указанной папке и возвращает его. */
    fun writeToFile(dir: File): File {
        val f = File(dir, "area_log.txt")
        f.writeText(sb.toString())
        return f
    }
}
