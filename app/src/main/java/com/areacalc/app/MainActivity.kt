package com.areacalc.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Стандартный размер листа (см). */
data class PaperSize(val title: String, val wCm: Double, val hCm: Double)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var btnPick: Button
    private lateinit var btnCamera: Button
    private lateinit var btnSave: Button
    private lateinit var btnLog: Button
    private lateinit var btnHistory: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtAreas: TextView
    private lateinit var txtTotal: TextView
    private lateinit var imgResult: ImageView
    private lateinit var autoPaper: AutoCompleteTextView
    private lateinit var switchThin: MaterialSwitch
    private lateinit var switchDebug: MaterialSwitch
    private lateinit var mathSpinner: MathSpinnerView
    private lateinit var txtTilt: TextView
    private lateinit var tiltBar: android.view.View

    private var results: List<AreaResult> = emptyList()
    private var isPdf = false
    private var suggestedName = "result"
    private var cameraUri: Uri? = null
    private var selectedPaperIndex = 0

    // Акселерометр —ввв индикатор наклона (помогает снять ровно = точнее площадь)
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private val gravity = FloatArray(3)
    private var tiltDeg = 0.0

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
        private const val REQ_CAMERA = 3
        private const val MAX_PHOTO_DIM = 2400
        private const val DPI = 200.0

        val PAPERS = listOf(
            PaperSize("A4 (21×29.7 см)", 21.0, 29.7),
            PaperSize("A3 (29.7×42 см)", 29.7, 42.0),
            PaperSize("A5 (14.8×21 см)", 14.8, 21.0),
            PaperSize("Letter (21.6×27.9 см)", 21.59, 27.94)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPick = findViewById(R.id.btnPick)
        btnCamera = findViewById(R.id.btnCamera)
        btnSave = findViewById(R.id.btnSave)
        btnLog = findViewById(R.id.btnLog)
        btnHistory = findViewById(R.id.btnHistory)
        txtStatus = findViewById(R.id.txtStatus)
        txtAreas = findViewById(R.id.txtAreas)
        txtTotal = findViewById(R.id.txtTotal)
        imgResult = findViewById(R.id.imgResult)
        autoPaper = findViewById(R.id.autoPaper)
        switchThin = findViewById(R.id.switchThin)
        switchDebug = findViewById(R.id.switchDebug)
        mathSpinner = findViewById(R.id.mathSpinner)
        txtTilt = findViewById(R.id.txtTilt)
        tiltBar = findViewById(R.id.tiltBar)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        autoPaper.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, PAPERS.map { it.title })
        )
        autoPaper.setText(PAPERS[0].title, false)
        autoPaper.setOnItemClickListener { _, _, pos, _ -> selectedPaperIndex = pos }

        if (!OpenCVLoader.initLocal()) {
            txtStatus.text = "Ошибка: не удалось загрузить OpenCV"
            Toast.makeText(this, "OpenCV не загрузился", Toast.LENGTH_LONG).show()
        }

        btnPick.setOnClickListener { pickFile() }
        btnCamera.setOnClickListener { takePhoto() }
        btnSave.setOnClickListener { saveResult() }
        btnLog.setOnClickListener { shareLog() }
        btnHistory.setOnClickListener { showHistory() }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Низкочастотный фильтр (сглаживание)
        val a = 0.2f
        for (i in 0..2) gravity[i] = gravity[i] * (1 - a) + event.values[i] * a
        val (gx, gy, gz) = gravity
        val norm = sqrt((gx * gx + gy * gy + gz * gz).toDouble())
        if (norm < 1e-3) return
        // Угол между осью экрана (z) и вертикалью: 0° = телефон плашмя (камера вниз)
        tiltDeg = Math.toDegrees(acos(Math.abs(gz) / norm))
        updateTiltUi()
    }

    private fun updateTiltUi() {
        val d = tiltDeg.roundToInt()
        val (msg, color) = when {
            tiltDeg <= 8 -> "Наклон: $d° — ровно ✓" to Color.parseColor("#22E0C8")
            tiltDeg <= 20 -> "Наклон: $d° — почти ровно" to Color.parseColor("#E0C022")
            else -> "Наклон: $d° — держите ровнее" to Color.parseColor("#FF5C7A")
        }
        txtTilt.text = msg
        txtTilt.setTextColor(color)
        tiltBar.setBackgroundColor(color)
    }

    private fun takePhoto() {
        try {
            val file = File(cacheDir, "capture.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQ_CAMERA)
            } else {
                Toast.makeText(this, "Камера не найдена", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            txtStatus.text = "Ошибка камеры: ${e.message}"
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK -> data?.data?.let { processUri(it) }
            REQ_SAVE -> data?.data?.let { writeResult(it) }
            REQ_CAMERA -> cameraUri?.let { uri ->
                isPdf = false
                suggestedName = "photo_calculate"
                startProcessing { processImage(uri) }
            }
        }
    }

    private fun processUri(uri: Uri) {
        val name = queryName(uri)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        isPdf = ext == "pdf"
        suggestedName = base + "_calculate"
        startProcessing { if (isPdf) processPdf(uri) else processImage(uri) }
    }

    private fun startProcessing(work: () -> List<AreaResult>) {
        setBusy(true)
        txtAreas.text = ""
        txtTotal.text = "…"
        imgResult.setImageBitmap(null)
        Logger.clear()
        AreaProcessor.debug = switchDebug.isChecked
        AreaProcessor.thinLines = switchThin.isChecked
        Logger.log("=== Новый расчёт ===")
        Logger.log("thinLines=${switchThin.isChecked}, debug=${switchDebug.isChecked}")

        Thread {
            try {
                val out = work()
                Logger.writeToFile(cacheDir)
                val allAreas = out.flatMap { it.areas }
                runOnUiThread {
                    results = out
                    if (out.isNotEmpty()) imgResult.setImageBitmap(out[0].bitmap)
                    if (allAreas.isNotEmpty()) {
                        txtTotal.text = "${allAreas.size} фигур · ${"%.1f".format(allAreas.sum())} см²"
                        val sb = StringBuilder()
                        out.forEachIndexed { i, r ->
                            if (out.size > 1) sb.append("Стр. ${i + 1}: ")
                            sb.append(r.areas.joinToString(", ") { "%.1f".format(it) })
                            sb.append(" см²\n")
                        }
                        txtAreas.text = sb.toString().trim()
                        txtStatus.text = "Готово"
                        btnSave.isEnabled = true
                        // Сохраняем в историю
                        if (out.isNotEmpty()) {
                            try {
                                HistoryStore.add(this, System.currentTimeMillis(), out[0].bitmap, allAreas)
                            } catch (e: Exception) { /* не критично */ }
                        }
                    } else {
                        txtTotal.text = "0 фигур"
                        txtStatus.text = "Фигуры не найдены — проверьте фон и освещение"
                    }
                    setBusy(false)
                }
            } catch (e: Exception) {
                Logger.log("ОШИБКА: ${e.message}")
                Logger.writeToFile(cacheDir)
                runOnUiThread {
                    txtTotal.text = "—"
                    txtStatus.text = "Ошибка: ${e.message}"
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun processImage(uri: Uri): List<AreaResult> {
        val bmp = decodeSampled(uri, MAX_PHOTO_DIM)
        val paper = PAPERS[selectedPaperIndex.coerceIn(0, PAPERS.size - 1)]

        Logger.log("Наклон устройства: ${tiltDeg.roundToInt()}° (0° = ровно)")
        updateProgress("Поиск листа...")
        val sheet = try {
            SheetDetector.detectAndWarp(bmp, paper.wCm, paper.hCm, DPI)
        } catch (e: Exception) { null }

        val imgToProcess: Bitmap
        val dpiEff: Double
        Logger.log("Картинка: ${bmp.width}x${bmp.height}, лист=${paper.title}")
        if (sheet != null) {
            imgToProcess = sheet
            dpiEff = DPI
            Logger.log("Лист НАЙДЕН и выпрямлен -> ${sheet.width}x${sheet.height}")
            updateProgress("Лист найден и выпрямлен")
        } else {
            imgToProcess = bmp
            val landscape = bmp.width >= bmp.height
            val wcm = if (landscape) maxOf(paper.wCm, paper.hCm) else minOf(paper.wCm, paper.hCm)
            dpiEff = bmp.width / wcm * 2.54
            Logger.log("Лист НЕ найден -> весь кадр как ${paper.title}, dpiEff=${"%.1f".format(dpiEff)}")
            updateProgress("Лист не найден — считаю весь кадр")
        }

        val res = AreaProcessor.process(imgToProcess, dpiEff) { f ->
            updateProgress("Обработка — ${(f * 100).roundToInt()}%")
        }
        return listOf(res)
    }

    private fun processPdf(uri: Uri): List<AreaResult> {
        val out = ArrayList<AreaResult>()
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Не удалось открыть PDF")
        pfd.use {
            val renderer = PdfRenderer(it)
            val n = renderer.pageCount
            val scale = DPI / 72.0
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                val w = (page.width * scale).roundToInt()
                val h = (page.height * scale).roundToInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                val pageIndex = i
                val res = AreaProcessor.process(bmp, DPI) { f ->
                    updateProgress("Страница ${pageIndex + 1}/$n — ${(f * 100).roundToInt()}%")
                }
                out.add(res)
                bmp.recycle()
            }
            renderer.close()
        }
        return out
    }

    private fun saveResult() {
        if (results.isEmpty()) return
        val mime = if (isPdf) "application/pdf" else "image/png"
        val fname = suggestedName + if (isPdf) ".pdf" else ".png"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fname)
        }
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun writeResult(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)!!.use { os ->
                if (isPdf) {
                    val doc = PdfDocument()
                    results.forEachIndexed { i, r ->
                        val b = r.bitmap
                        val info = PdfDocument.PageInfo.Builder(b.width, b.height, i + 1).create()
                        val page = doc.startPage(info)
                        page.canvas.drawBitmap(b, 0f, 0f, null)
                        doc.finishPage(page)
                    }
                    doc.writeTo(os); doc.close()
                } else {
                    results[0].bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            }
            txtStatus.text = "Сохранено"
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            txtStatus.text = "Ошибка сохранения: ${e.message}"
        }
    }

    private fun shareLog() {
        try {
            val f = Logger.writeToFile(cacheDir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, Logger.text())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Отправить лог"))
        } catch (e: Exception) {
            txtStatus.text = "Ошибка лога: ${e.message}"
        }
    }

    private fun showHistory() {
        val items = HistoryStore.list(this)
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "История · ${items.size}"
            textSize = 20f
            setTextColor(Color.parseColor("#ECECF3"))
            typeface = Typeface.DEFAULT_BOLD
        })

        if (items.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Пусто — сделайте первый расчёт"
                setTextColor(Color.parseColor("#8A8A9C"))
                setPadding(0, dp(14), 0, 0)
            })
        } else {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault())
            for (it in items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                HistoryStore.loadThumb(it.thumbPath)?.let { b -> iv.setImageBitmap(b) }
                row.addView(iv)
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                col.addView(TextView(this).apply {
                    text = "${it.count} фигур · ${"%.1f".format(it.total)} см²"
                    setTextColor(Color.parseColor("#ECECF3"))
                    textSize = 15f
                })
                col.addView(TextView(this).apply {
                    text = fmt.format(Date(it.ts))
                    setTextColor(Color.parseColor("#8A8A9C"))
                    textSize = 12f
                })
                row.addView(col)
                list.addView(row)
            }
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
            }
            scroll.addView(list)
            root.addView(scroll)
            root.addView(Button(this).apply {
                text = "Очистить историю"
                setOnClickListener { HistoryStore.clear(this@MainActivity); sheet.dismiss() }
            })
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun decodeSampled(uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        while (big / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Не удалось прочитать изображение")
    }

    private fun queryName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun updateProgress(msg: String) {
        runOnUiThread { txtStatus.text = msg }
    }

    private fun setBusy(busy: Boolean) {
        if (busy) mathSpinner.start() else mathSpinner.stop()
        btnPick.isEnabled = !busy
        btnCamera.isEnabled = !busy
        if (busy) btnSave.isEnabled = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
