package com.areacalc.app

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Находит лист бумаги (самый крупный четырёхугольник) на фото/скане,
 * выпрямляет перспективу (warpPerspective) и возвращает картинку листа
 * в размере pageWcm x pageHcm при заданном dpi.
 *
 * Так как лист известного размера, масштаб результата ТОЧНЫЙ: dpi/2.54 пикс/см.
 * Это и убирает искажение от угла съёмки, и даёт правильную площадь в см².
 */
object SheetDetector {

    /** @return выпрямленный лист или null, если четырёхугольник не найден. */
    fun detectAndWarp(src: Bitmap, pageWcm: Double, pageHcm: Double, dpi: Double): Bitmap? {
        val full = Mat()
        Utils.bitmapToMat(src, full) // RGBA
        val rgb = Mat()
        Imgproc.cvtColor(full, rgb, Imgproc.COLOR_RGBA2RGB)

        // Уменьшенная копия для быстрого поиска контура листа
        val maxDim = 1000.0
        val scale = min(1.0, maxDim / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(rgb.cols() * scale, rgb.rows() * scale))

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.dilate(
            edges, edges,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        )

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val imgArea = (small.cols() * small.rows()).toDouble()
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < 0.15 * imgArea) continue // лист должен занимать заметную часть кадра
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts)) && area > bestArea) {
                bestArea = area
                best = pts
            }
        }
        if (best == null) return null

        // Координаты обратно к полному разрешению
        val corners = best!!.map { Point(it.x / scale, it.y / scale) }
        var o = orderCorners(corners) // [tl, tr, br, bl]

        // СУБ-ПИКСЕЛЬНОЕ уточнение углов на полном разрешении -> точнее площадь.
        // Уточняем положение каждого угла по градиенту яркости (cornerSubPix).
        try {
            val fullGray = Mat()
            Imgproc.cvtColor(rgb, fullGray, Imgproc.COLOR_RGB2GRAY)
            val cm = MatOfPoint2f(o[0], o[1], o[2], o[3])
            val win = Size(11.0, 11.0)
            val crit = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.01)
            Imgproc.cornerSubPix(fullGray, cm, win, Size(-1.0, -1.0), crit)
            o = cm.toArray()
        } catch (e: Exception) {
            // если не удалось — используем углы как есть
        }

        // Длины сторон -> ориентация листа (портрет/альбом)
        val avgW = (dist(o[0], o[1]) + dist(o[3], o[2])) / 2.0
        val avgH = (dist(o[0], o[3]) + dist(o[1], o[2])) / 2.0
        val longCm = max(pageWcm, pageHcm)
        val shortCm = min(pageWcm, pageHcm)
        val wcm = if (avgW >= avgH) longCm else shortCm
        val hcm = if (avgW >= avgH) shortCm else longCm

        val wpx = (wcm / 2.54 * dpi).roundToInt()
        val hpx = (hcm / 2.54 * dpi).roundToInt()
        if (wpx < 10 || hpx < 10) return null

        val srcM = MatOfPoint2f(o[0], o[1], o[2], o[3])
        val dstM = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((wpx - 1).toDouble(), 0.0),
            Point((wpx - 1).toDouble(), (hpx - 1).toDouble()),
            Point(0.0, (hpx - 1).toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(srcM, dstM)
        val warped = Mat()
        Imgproc.warpPerspective(rgb, warped, transform, Size(wpx.toDouble(), hpx.toDouble()))

        val warpedRgba = Mat()
        Imgproc.cvtColor(warped, warpedRgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedRgba, out)
        return out
    }

    /** Упорядочить углы: tl=min(x+y), br=max(x+y), tr=max(x-y), bl=min(x-y). */
    private fun orderCorners(p: List<Point>): Array<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.maxByOrNull { it.x - it.y }!!
        val bl = p.minByOrNull { it.x - it.y }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
