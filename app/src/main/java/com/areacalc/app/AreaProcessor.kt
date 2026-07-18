package com.areacalc.app

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Результат обработки одной страницы/картинки. */
data class AreaResult(val bitmap: Bitmap, val areas: List<Double>)

/**
 * Надёжный подсчёт площади замкнутых (в т.ч. нарисованных от руки, с разрывом)
 * контуров на листе.
 *
 * Идея (проверена локально на реальном фото):
 *  1) порог (адаптивный для тонких линий / Otsu для залитых фигур);
 *  2) чистим края кадра (убирает ложную заливку всего листа);
 *  3) наращиваем морфологическое замыкание (kernel k), пока разрыв контура
 *     не закроется и не образуется ВНУТРЕННЯЯ область (дырка);
 *  4) площадь фигуры = среднее внешнего и внутреннего контура (по центральной
 *     линии нарисованной линии) — так точнее всего.
 */
object AreaProcessor {
    var minAreaCm = 5.5   // минимальная площадь фигур, см^2
    var debug = false     // отладка: показать маску порога вместо оригинала
    var thinLines = true  // true - тонкие контуры (адаптивный порог), false - Otsu

    private data class Found(val cx: Double, val cy: Double, val areaCm: Double, val outer: MatOfPoint)

    /**
     * @param dpi разрешение изображения (для перевода px -> см). Для листа/PDF
     *            известного размера это точное значение.
     */
    fun process(src: Bitmap, dpi: Double, progress: (Double) -> Unit): AreaResult {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val grayFull = Mat()
        Imgproc.cvtColor(rgba, grayFull, Imgproc.COLOR_RGBA2GRAY)
        val origW = grayFull.cols()
        val origH = grayFull.rows()

        // Уменьшаем для поиска (закрытие большим ядром на полном разрешении медленно).
        // Порог считаем на уменьшенной серой копии — тонкая линия сохраняется.
        val detW = min(origW, 900)
        val ds = detW.toDouble() / origW
        val grayDet = Mat()
        if (ds < 1.0) {
            Imgproc.resize(grayFull, grayDet, Size(origW * ds, origH * ds), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            grayFull.copyTo(grayDet)
        }

        val bin = Mat()
        if (thinLines) {
            var block = (grayDet.cols() / 40) or 1
            if (block < 15) block = 15
            Imgproc.adaptiveThreshold(
                grayDet, bin, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, block, 10.0
            )
            Logger.log("Порог: адаптивный, block=$block")
        } else {
            Imgproc.threshold(grayDet, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            Logger.log("Порог: Otsu (авто)")
        }

        // Убираем краевые артефакты порога (рамку по краю кадра)
        val m = 3
        Imgproc.rectangle(bin, Point(0.0, 0.0), Point((bin.cols() - 1).toDouble(), (m - 1).toDouble()), Scalar(0.0), -1)
        Imgproc.rectangle(bin, Point(0.0, (bin.rows() - m).toDouble()), Point((bin.cols() - 1).toDouble(), (bin.rows() - 1).toDouble()), Scalar(0.0), -1)
        Imgproc.rectangle(bin, Point(0.0, 0.0), Point((m - 1).toDouble(), (bin.rows() - 1).toDouble()), Scalar(0.0), -1)
        Imgproc.rectangle(bin, Point((bin.cols() - m).toDouble(), 0.0), Point((bin.cols() - 1).toDouble(), (bin.rows() - 1).toDouble()), Scalar(0.0), -1)

        val whitePx = Core.countNonZero(bin)
        Logger.log("Маска (детекция): ${bin.cols()}x${bin.rows()}, белых: $whitePx (${"%.2f".format(100.0 * whitePx / (bin.cols() * bin.rows()))}%)")

        // px/см на уменьшенной копии
        val pxPerCmDet = (dpi / 2.54) * ds
        val ppc2 = pxPerCmDet * pxPerCmDet

        val W = bin.cols()
        val H = bin.rows()
        val kmax = max(15, W / 6)
        val minPix = 50.0
        val found = ArrayList<Found>()

        var k = 3
        var lastFoundK = 0
        while (k <= kmax) {
            progress(min(1.0, k.toDouble() / kmax))
            // Ранняя остановка: если уже что-то нашли и давно (по k) нет нового —
            // дальше растить ядро смысла нет (только исказит и замедлит).
            if (found.isNotEmpty() && k - lastFoundK > 48) break
            val se = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
            val closed = Mat()
            Imgproc.morphologyEx(bin, closed, Imgproc.MORPH_CLOSE, se)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)

            for (i in contours.indices) {
                val h = hierarchy.get(0, i) ?: continue
                val parent = h[3]
                if (parent < 0) continue // не дырка
                val inner = Imgproc.contourArea(contours[i])
                if (inner < minPix) continue
                val pIdx = parent.toInt()
                if (pIdx < 0 || pIdx >= contours.size) continue
                val outerArea = Imgproc.contourArea(contours[pIdx])
                val area = (inner + outerArea) / 2.0
                val areaCm = area / ppc2
                if (areaCm < minAreaCm) continue
                // слишком большое (весь лист)?
                if (area > 0.7 * W * H) continue

                val mm = Imgproc.moments(contours[i])
                if (mm.m00 == 0.0) continue
                val cx = mm.m10 / mm.m00
                val cy = mm.m01 / mm.m00

                val dupR = (W * 0.05) * (W * 0.05)
                val dup = found.any { (cx - it.cx) * (cx - it.cx) + (cy - it.cy) * (cy - it.cy) < dupR }
                if (dup) continue

                found.add(Found(cx, cy, areaCm, contours[pIdx]))
                lastFoundK = k
                Logger.log("  [k=$k] фигура: ${"%.2f".format(areaCm)} см²")
            }
            k += 4
        }
        Logger.log("ИТОГО фигур: ${found.size}")

        // ---- Рисуем результат на полном разрешении ----
        val outMat = Mat()
        if (debug) {
            val binUp = Mat()
            Imgproc.resize(bin, binUp, Size(origW.toDouble(), origH.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.cvtColor(binUp, outMat, Imgproc.COLOR_GRAY2RGB)
        } else {
            Imgproc.cvtColor(rgba, outMat, Imgproc.COLOR_RGBA2RGB)
        }

        val inv = 1.0 / ds
        val fontScale = max(1.0, origW / 900.0)
        val thick = max(2, (fontScale * 2).toInt())
        val areas = ArrayList<Double>()
        for (f in found) {
            areas.add(f.areaCm)
            // Масштабируем контур к полному разрешению
            val pts = f.outer.toArray()
            for (p in pts) { p.x *= inv; p.y *= inv }
            val big = MatOfPoint(*pts)
            Imgproc.drawContours(outMat, listOf(big), -1, Scalar(255.0, 0.0, 0.0), thick)
            val txt = "%.1f".format(f.areaCm)
            drawLabel(outMat, txt, f.cx * inv, f.cy * inv, fontScale, thick)
        }

        val outRgba = Mat()
        Imgproc.cvtColor(outMat, outRgba, Imgproc.COLOR_RGB2RGBA)
        val outBmp = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, outBmp)
        return AreaResult(outBmp, areas)
    }

    private fun drawLabel(img: Mat, text: String, cx: Double, cy: Double, fontScale: Double, thickness: Int) {
        val font = Imgproc.FONT_HERSHEY_SIMPLEX
        val base = IntArray(1)
        val sz = Imgproc.getTextSize(text, font, fontScale, thickness, base)
        val x = cx - sz.width / 2
        val y = cy + sz.height / 2
        val pad = 6.0
        Imgproc.rectangle(
            img,
            Point(x - pad, y - sz.height - pad),
            Point(x + sz.width + pad, y + base[0] + pad),
            Scalar(255.0, 255.0, 255.0), -1
        )
        Imgproc.putText(img, text, Point(x, y), font, fontScale, Scalar(0.0, 0.0, 255.0), thickness)
    }
}
