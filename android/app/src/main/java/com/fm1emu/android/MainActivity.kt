package com.fm1emu.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private data class TouchState(
    val pointerId: Int,
    var lastX: Float,
    var lastY: Float,
    var accumulated: Float = 0f,
    var keyId: Int = -1,
    var keyIsNote: Boolean = false,
    var padIndex: Int = -1,
    var faderIndex: Int = -1,
    var wheelIndex: Int = -1,
    var encoderIndex: Int = -1,
    var isPot: Boolean = false,
)

class MainActivity : ComponentActivity() {
    companion object {
        private const val MAX_FIRMWARE_BYTES = 32 * 1024 * 1024
        private const val FLASH_BYTES = 1024 * 1024
        private val CLOCKS = intArrayOf(60, 80, 100, 120, 160, 200, 240)

        init {
            System.loadLibrary("fm1_android_app")
        }
    }

    private external fun nativeCreateEngine(packageBytes: ByteArray, savedBytes: ByteArray?, clockMhz: Int): Long
    private external fun nativeLastError(): String
    private external fun nativeDestroyEngine(engineHandle: Long)
    private external fun nativeStartAudio(engineHandle: Long): Long
    private external fun nativeStopAudio(audioHandle: Long)
    private external fun nativeAudioInfo(audioHandle: Long): String
    private external fun nativePanelSize(engineHandle: Long): IntArray
    private external fun nativeRenderPanel(engineHandle: Long, destination: ByteBuffer): Int
    private external fun nativePanelHit(engineHandle: Long, x: Float, y: Float): Int
    private external fun nativePanelHitY(engineHandle: Long): Float
    private external fun nativeIsSmk37(engineHandle: Long): Boolean
    private external fun nativePanelSlide(engineHandle: Long, index: Int, y: Float): Int
    private external fun nativeKey(engineHandle: Long, id: Int, down: Boolean, velocity: Int)
    private external fun nativeEncoder(engineHandle: Long, index: Int, clicks: Int)
    private external fun nativeAnalog(engineHandle: Long, index: Int, value: Int)
    private external fun nativePot(engineHandle: Long, value: Int)
    private external fun nativeThemeCount(): Int
    private external fun nativeThemeName(index: Int): String
    private external fun nativeSetTheme(engineHandle: Long, index: Int)
    private external fun nativeStatus(engineHandle: Long): String
    private external fun nativeExportFlash(engineHandle: Long, destination: ByteBuffer): Int

    private var engineHandle = 0L
    private var audioHandle = 0L
    private var firmwareBytes: ByteArray? = null
    private var firmwareName = "No firmware loaded"
    private var firmwareHash = ""
    private var clockMhz = 100
    private var themeIndex = 0
    private var loadGeneration = 0
    private var lastSavedAt = 0L
    private var panelView: InstrumentPanelView? = null
    private lateinit var statusView: TextView
    private lateinit var firmwareView: TextView
    private lateinit var clockSpinner: Spinner
    private lateinit var themeSpinner: Spinner
    private var clockSpinnerReady = false
    private var themeSpinnerReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val openFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFirmware(uri)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            if (!isFinishing && !isDestroyed) mainHandler.postDelayed(this, 1_000)
        }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (engineHandle != 0L && SystemClock.elapsedRealtime() - lastSavedAt >= 5_000) {
                persistFlash(showErrors = false)
            }
            if (!isFinishing && !isDestroyed) mainHandler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        buildInterface()
        loadStoredSettings()
        loadLastFirmware()
        mainHandler.post(statusTick)
        mainHandler.postDelayed(saveTick, 5_000)
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 21))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(30, 33, 38))
        }

        val openButton = Button(this).apply {
            text = "Open .FWSC"
            isAllCaps = false
            setOnClickListener { openFirmware.launch(arrayOf("*/*")) }
        }
        bar.addView(openButton, LinearLayout.LayoutParams(dp(124), dp(42)))

        firmwareView = TextView(this).apply {
            text = firmwareName
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(firmwareView, LinearLayout.LayoutParams(0, dp(42), 1f))

        clockSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                CLOCKS.map { "$it MHz" })
            setSelection(CLOCKS.indexOf(clockMhz).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = CLOCKS.getOrElse(position) { 100 }
                    if (clockSpinnerReady && selected != clockMhz) changeClock(selected)
                }
            }
        }
        bar.addView(clockSpinner, LinearLayout.LayoutParams(dp(112), dp(46)))

        val themeNames = (0 until nativeThemeCount()).map { nativeThemeName(it) }
        themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, themeNames)
            setSelection(themeIndex.coerceIn(themeNames.indices))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!themeSpinnerReady) return
                    themeIndex = position
                    if (engineHandle != 0L) nativeSetTheme(engineHandle, themeIndex)
                    getPreferences(MODE_PRIVATE).edit().putInt("theme", themeIndex).apply()
                    panelView?.invalidate()
                }
            }
        }
        bar.addView(themeSpinner, LinearLayout.LayoutParams(dp(158), dp(46)))

        val saveButton = Button(this).apply {
            text = "Save"
            isAllCaps = false
            setOnClickListener { persistFlash(showErrors = true) }
        }
        bar.addView(saveButton, LinearLayout.LayoutParams(dp(76), dp(42)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panelView = InstrumentPanelView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(panelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusView = TextView(this).apply {
            setTextColor(Color.rgb(215, 220, 228))
            textSize = 11f
            maxLines = 2
            setPadding(dp(10), dp(4), dp(10), dp(4))
            text = "Import an .fwsc firmware package to start."
            setBackgroundColor(Color.rgb(27, 30, 35))
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        setContentView(root)
        mainHandler.post { clockSpinnerReady = true; themeSpinnerReady = true }
    }

    private fun loadStoredSettings() {
        val prefs = getPreferences(MODE_PRIVATE)
        clockMhz = prefs.getInt("clock", 100).let { value -> if (CLOCKS.contains(value)) value else 100 }
        themeIndex = prefs.getInt("theme", 0).coerceIn(0, (nativeThemeCount() - 1).coerceAtLeast(0))
        clockSpinner.setSelection(CLOCKS.indexOf(clockMhz).coerceAtLeast(0))
        themeSpinner.setSelection(themeIndex)
    }

    private fun loadLastFirmware() {
        val file = File(filesDir, "active.fwsc")
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_FIRMWARE_BYTES) return
        Thread {
            runCatching { file.readBytes() }.onSuccess { bytes ->
                runOnUiThread { startEngine(bytes, getPreferences(MODE_PRIVATE).getString("firmwareName", file.name) ?: file.name) }
            }.onFailure { error ->
                runOnUiThread { showMessage("Could not restore firmware: ${error.message}") }
            }
        }.start()
    }

    private fun importFirmware(uri: Uri) {
        showMessage("Reading firmware package…")
        Thread {
            try {
                val name = queryDisplayName(uri) ?: "custom-firmware.fwsc"
                val bytes = readLimited(uri, MAX_FIRMWARE_BYTES)
                if (bytes.isEmpty()) throw IllegalArgumentException("The selected file is empty")
                // Do not replace the last known-good firmware until the native engine has
                // accepted and booted this package.
                runOnUiThread { startEngine(bytes, name) }
            } catch (error: Throwable) {
                runOnUiThread { showMessage("Firmware import failed: ${error.message}") }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun readLimited(uri: Uri, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalArgumentException("Firmware exceeds the ${limit / (1024 * 1024)} MiB limit")
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("Android could not open this file")
        return output.toByteArray()
    }

    private fun startEngine(bytes: ByteArray, name: String) {
        loadGeneration += 1
        val generation = loadGeneration
        val selectedClock = clockMhz
        val pendingFirmware = File(cacheDir, "active-$generation.fwsc.tmp")
        firmwareView.text = "$name · loading native emulator…"
        showMessage("Loading $name · ARM64 native emulator")

        Thread {
            val hash = sha256(bytes)
            val saved = runCatching {
                flashFile(hash).takeIf { it.isFile && it.length() == FLASH_BYTES }?.readBytes()
            }.getOrNull()
            val handle = try {
                nativeCreateEngine(bytes, saved, selectedClock)
            } catch (_: Throwable) {
                0L
            }
            val firmwareCopyReady = handle != 0L && runCatching {
                pendingFirmware.writeBytes(bytes)
                true
            }.getOrDefault(false)

            runOnUiThread {
                if (generation != loadGeneration || isFinishing || isDestroyed) {
                    if (handle != 0L) nativeDestroyEngine(handle)
                    pendingFirmware.delete()
                    return@runOnUiThread
                }
                if (handle == 0L) {
                    pendingFirmware.delete()
                    firmwareView.text = if (engineHandle != 0L) firmwareName else name
                    if (engineHandle != 0L) {
                        // A failed clock change or firmware import leaves the running synth intact.
                        runCatching {
                            clockMhz = JSONObject(nativeStatus(engineHandle)).optInt("clock", clockMhz)
                            clockSpinner.setSelection(CLOCKS.indexOf(clockMhz).coerceAtLeast(0))
                            getPreferences(MODE_PRIVATE).edit().putInt("clock", clockMhz).apply()
                        }
                        refreshStatus()
                    }
                    showMessage("Could not load firmware: ${nativeLastError()}")
                    return@runOnUiThread
                }

                // The candidate booted successfully. Save the old engine before swapping it out.
                persistFlash(showErrors = false)
                releaseAudioAndEngine()

                if (firmwareCopyReady) {
                    val active = File(filesDir, "active.fwsc")
                    if (!pendingFirmware.renameTo(active)) {
                        runCatching {
                            pendingFirmware.copyTo(active, overwrite = true)
                            pendingFirmware.delete()
                        }
                    }
                } else {
                    pendingFirmware.delete()
                }

                firmwareBytes = bytes
                firmwareName = name
                firmwareHash = hash
                getPreferences(MODE_PRIVATE).edit().putString("firmwareName", name).apply()
                engineHandle = handle
                nativeSetTheme(engineHandle, themeIndex)
                panelView?.bind(engineHandle)
                audioHandle = nativeStartAudio(engineHandle)
                firmwareView.text = name
                if (audioHandle == 0L) {
                    showMessage("Firmware loaded, but Android audio failed: ${nativeLastError()}")
                } else if (!firmwareCopyReady) {
                    showMessage("$name loaded; the last-firmware copy could not be stored")
                } else {
                    showMessage("$name · native engine started")
                }
                refreshStatus()
            }
        }.start()
    }

    private fun changeClock(selected: Int) {
        clockMhz = selected
        getPreferences(MODE_PRIVATE).edit().putInt("clock", selected).apply()
        val bytes = firmwareBytes ?: return
        startEngine(bytes, firmwareName)
    }

    private fun releaseAudioAndEngine() {
        if (engineHandle != 0L) panelView?.releaseAllPointers()
        if (audioHandle != 0L) {
            nativeStopAudio(audioHandle)
            audioHandle = 0L
        }
        if (engineHandle != 0L) {
            nativeDestroyEngine(engineHandle)
            engineHandle = 0L
        }
    }

    private fun persistFlash(showErrors: Boolean) {
        val handle = engineHandle
        val hash = firmwareHash
        if (handle == 0L || hash.isBlank()) return
        val direct = ByteBuffer.allocateDirect(FLASH_BYTES).order(ByteOrder.nativeOrder())
        val copied = nativeExportFlash(handle, direct)
        if (copied != FLASH_BYTES) {
            if (showErrors) showMessage("Flash save did not complete; try again")
            return
        }
        val bytes = ByteArray(FLASH_BYTES)
        direct.position(0)
        direct.get(bytes)
        val destination = flashFile(hash)
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
        }.onSuccess {
            lastSavedAt = SystemClock.elapsedRealtime()
            if (showErrors) showMessage("Flash and patches saved locally")
        }.onFailure { error ->
            if (showErrors) showMessage("Flash save failed: ${error.message}")
        }
    }

    private fun flashFile(hash: String) = File(File(filesDir, "saves"), "$hash.flash")

    private fun refreshStatus() {
        val handle = engineHandle
        if (handle == 0L) {
            if (firmwareBytes == null) statusView.text = "Import an .fwsc package · offline playback · ARM64 native JIT"
            return
        }
        runCatching {
            val state = JSONObject(nativeStatus(handle))
            val speed = state.optDouble("speed", 0.0)
            val load = state.optDouble("load", 0.0)
            val jit = when (state.optInt("jit", 0)) {
                1 -> "native JIT"
                2 -> "JIT unavailable (interpreter fallback)"
                else -> "JIT warming up"
            }
            val underruns = state.optLong("underruns", 0)
            val starved = state.optLong("starvedHalves", 0)
            val audio = if (audioHandle != 0L) nativeAudioInfo(audioHandle) else "Audio stopped"
            statusView.text = String.format(
                Locale.US,
                "%d MHz · %.2fx real time · %.0f%% CPU demand · %s · underruns %d · firmware-starved %d\n%s",
                state.optInt("clock", clockMhz), speed, load, jit, underruns, starved, audio
            )
        }.onFailure { statusView.text = "Native status unavailable: ${it.message}" }
    }

    private fun showMessage(message: String) {
        if (::statusView.isInitialized) statusView.text = message
        else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (engineHandle != 0L && audioHandle == 0L) {
            audioHandle = nativeStartAudio(engineHandle)
            if (audioHandle == 0L) showMessage("Audio restart failed: ${nativeLastError()}")
        }
        panelView?.active = true
        panelView?.invalidate()
    }

    override fun onPause() {
        panelView?.releaseAllPointers()
        persistFlash(showErrors = false)
        if (audioHandle != 0L) {
            nativeStopAudio(audioHandle)
            audioHandle = 0L
        }
        panelView?.active = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else panelView?.releaseAllPointers()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statusTick)
        mainHandler.removeCallbacks(saveTick)
        loadGeneration += 1
        persistFlash(showErrors = false)
        releaseAudioAndEngine()
        super.onDestroy()
    }

    private inner class InstrumentPanelView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destination = RectF()
        private val pointers = HashMap<Int, TouchState>()
        private var engine = 0L
        private var panelWidth = 0
        private var panelHeight = 0
        private var bitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private var lastRender = 0L
        private var potValue = 900
        var active = true

        fun bind(handle: Long) {
            releaseAllPointers()
            engine = handle
            bitmap?.recycle()
            bitmap = null
            pixels = null
            panelWidth = 0
            panelHeight = 0
            lastRender = 0L
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val handle = engine
            if (handle == 0L) {
                paint.color = Color.rgb(185, 190, 198)
                paint.textSize = dp(16).toFloat()
                canvas.drawText("Open a custom .fwsc firmware package", dp(24).toFloat(), height / 2f, paint)
                return
            }
            ensurePanelBuffer(handle)
            val image = bitmap ?: return
            val now = SystemClock.uptimeMillis()
            if (active && now - lastRender >= 80L) {
                val target = pixels
                if (target != null) {
                    target.clear()
                    val drawn = nativeRenderPanel(handle, target)
                    if (drawn > 0) {
                        target.position(0)
                        image.copyPixelsFromBuffer(target)
                        lastRender = now
                    }
                }
            }
            // Fill the phone's landscape panel area rather than letterboxing the original desktop
            // aspect ratio. This widens the key targets on a 20:9 display; touch mapping below uses
            // the same independent X/Y scales, so every visual control remains correctly hittable.
            destination.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(image, null, destination, paint)
            if (active) postInvalidateDelayed(80L)
        }

        private fun ensurePanelBuffer(handle: Long) {
            if (bitmap != null && engine == handle) return
            val size = nativePanelSize(handle)
            if (size.size < 2 || size[0] <= 0 || size[1] <= 0) return
            panelWidth = size[0]
            panelHeight = size[1]
            bitmap = Bitmap.createBitmap(panelWidth, panelHeight, Bitmap.Config.ARGB_8888)
            pixels = ByteBuffer.allocateDirect(panelWidth * panelHeight * 4).order(ByteOrder.nativeOrder())
        }

        private fun toPanel(x: Float, y: Float): Pair<Float, Float>? {
            if (engine == 0L || panelWidth == 0 || panelHeight == 0) return null
            if (width <= 0 || height <= 0) return null
            val px = x * panelWidth.toFloat() / width.toFloat()
            val py = y * panelHeight.toFloat() / height.toFloat()
            if (px < 0f || py < 0f || px >= panelWidth || py >= panelHeight) return null
            return Pair(px, py)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (engine == 0L || !active) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> beginPointer(event, event.actionIndex)
                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) movePointer(event, index)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> finishPointer(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL -> releaseAllPointers()
            }
            return true
        }

        private fun beginPointer(event: MotionEvent, index: Int) {
            val pointerId = event.getPointerId(index)
            val point = toPanel(event.getX(index), event.getY(index)) ?: return
            val x = point.first
            val y = point.second
            val hit = nativePanelHit(engine, x, y)
            val state = TouchState(pointerId, x, y)
            pointers[pointerId] = state
            val smk = nativeIsSmk37(engine)
            when {
                hit in 100..199 -> {
                    val keyId = hit - 100
                    state.keyId = keyId
                    state.keyIsNote = if (smk) keyId in 0..36 else keyId in 14..40
                    pressKey(keyId, smk)
                }
                smk && hit in 200..215 -> {
                    state.padIndex = hit - 200
                    nativeAnalog(engine, state.padIndex, 700)
                }
                smk && hit in 300..303 -> {
                    state.faderIndex = hit - 300
                    updateFader(state, y)
                }
                smk && hit == 400 -> {
                    state.wheelIndex = 20
                    updateWheel(state, y)
                }
                smk && hit == 401 -> {
                    state.wheelIndex = 21
                    updateWheel(state, y)
                }
                !smk && hit == 7 -> state.isPot = true
                hit >= 0 && hit < (if (smk) 8 else 7) -> state.encoderIndex = hit
            }
            performControlMove(state, y)
            invalidate()
        }

        private fun pressKey(id: Int, smk: Boolean) {
            val fraction = nativePanelHitY(engine).coerceIn(0f, 1f)
            val velocity = if (smk) {
                (80_000f - 78_000f * fraction).toInt().coerceIn(2_000, 80_000)
            } else {
                (1f + 126f * fraction).toInt().coerceIn(1, 127)
            }
            nativeKey(engine, id, true, velocity)
        }

        private fun releaseKey(id: Int) {
            nativeKey(engine, id, false, 1)
        }

        private fun movePointer(event: MotionEvent, index: Int) {
            val pointerId = event.getPointerId(index)
            val state = pointers[pointerId] ?: return
            val point = toPanel(event.getX(index), event.getY(index)) ?: return
            val x = point.first
            val y = point.second
            if (state.keyId >= 0 && state.keyIsNote) transferGlissando(state, x, y)
            performControlMove(state, y)
            state.lastX = x
            state.lastY = y
        }

        private fun performControlMove(state: TouchState, y: Float) {
            val deltaUp = state.lastY - y
            when {
                state.keyId >= 0 -> Unit
                state.encoderIndex >= 0 -> {
                    state.accumulated += deltaUp
                    val clicks = if (state.accumulated >= 12f) floor(state.accumulated / 12f).toInt()
                        else if (state.accumulated <= -12f) -floor(abs(state.accumulated) / 12f).toInt() else 0
                    if (clicks != 0) {
                        nativeEncoder(engine, state.encoderIndex, clicks)
                        state.accumulated -= clicks * 12f
                    }
                }
                state.isPot -> {
                    potValue = (potValue + deltaUp.toInt() * 4).coerceIn(0, 1023)
                    nativePot(engine, potValue)
                }
                state.faderIndex >= 0 -> updateFader(state, y)
                state.wheelIndex >= 0 -> updateWheel(state, y)
            }
        }

        private fun transferGlissando(state: TouchState, x: Float, y: Float) {
            val hit = nativePanelHit(engine, x, y)
            if (hit !in 100..199) return
            val next = hit - 100
            val smk = nativeIsSmk37(engine)
            val nextIsNote = if (smk) next in 0..36 else next in 14..40
            if (!nextIsNote || next == state.keyId) return
            releaseKey(state.keyId)
            state.keyId = next
            pressKey(next, smk)
        }

        private fun updateFader(state: TouchState, y: Float) {
            val value = nativePanelSlide(engine, state.faderIndex, y)
            nativeAnalog(engine, 16 + state.faderIndex, value)
        }

        private fun updateWheel(state: TouchState, y: Float) {
            val index = if (state.wheelIndex == 20) 4 else 5
            val value = nativePanelSlide(engine, index, y)
            nativeAnalog(engine, state.wheelIndex, value)
        }

        private fun finishPointer(pointerId: Int) {
            val state = pointers.remove(pointerId) ?: return
            when {
                state.keyId >= 0 -> releaseKey(state.keyId)
                state.padIndex >= 0 -> nativeAnalog(engine, state.padIndex, 0)
                state.wheelIndex == 20 -> nativeAnalog(engine, 20, 535)
                state.wheelIndex == 21 -> nativeAnalog(engine, 21, 629)
            }
            invalidate()
        }

        fun releaseAllPointers() {
            if (engine != 0L) {
                for (state in pointers.values) {
                    when {
                        state.keyId >= 0 -> releaseKey(state.keyId)
                        state.padIndex >= 0 -> nativeAnalog(engine, state.padIndex, 0)
                        state.wheelIndex == 20 -> nativeAnalog(engine, 20, 535)
                        state.wheelIndex == 21 -> nativeAnalog(engine, 21, 629)
                    }
                }
            }
            pointers.clear()
        }
    }
}
