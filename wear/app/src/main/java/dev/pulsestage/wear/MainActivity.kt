package dev.pulsestage.wear

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var api: Api
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var pairingTitle: TextView
    private lateinit var pairingHint: TextView
    private lateinit var pairingCard: LinearLayout
    private lateinit var dial: PulseDialView
    private lateinit var start: Button
    private lateinit var register: Button
    private lateinit var reset: Button
    private lateinit var scroll: ScrollView
    private var serverButton: Button? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val mint = Color.rgb(184, 243, 177)
    private val muted = Color.rgb(164, 183, 170)
    private var paired = false
    private var busy = false
    private var consent = false
    private var lastPoll = 0L
    private var previousRunning = false
    private val tick = object : Runnable {
        override fun run() {
            val running = HeartRateService.running
            if (previousRunning && !running) status.text = HeartRateService.message
            previousRunning = running
            if (running) status.text = HeartRateService.message
            else if (api.ingestToken.isEmpty() && paired) {
                paired = false
                code.text = ""
                status.text = HeartRateService.message
            }
            if (!running && !paired && api.pairingCode.isNotEmpty()) showPairingLink()
            refreshControls()
            if (!running && api.ingestToken.isNotEmpty() && !busy && SystemClock.elapsedRealtime() - lastPoll > 5000) poll()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(if (resources.configuration.isScreenRound) 24 else 16), dp(24), dp(40))
            setBackgroundColor(Color.rgb(8, 15, 11))
        }
        scroll = ScrollView(this).apply {
            addView(layout)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(44)).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        label(layout, "PULSESTAGE", 12f, mint).apply {
            letterSpacing = .15f
            setTypeface(null, Typeface.BOLD)
        }
        dial = PulseDialView(this).also {
            layout.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)))
        }
        start = button(layout, "▶  Start", primary = true) {
            if (HeartRateService.running) {
                stopService(Intent(this, HeartRateService::class.java))
                previousRunning = false
                status.text = "Sesi dihentikan."
            } else disclose()
        }
        status = label(layout, "Hubungkan jam ke web\nuntuk memulai sesi.", 11f, muted).apply {
            setPadding(dp(4), dp(7), dp(4), dp(8))
        }
        register = button(layout, "Tampilkan kode", primary = true) { connect() }
        pairingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(13), dp(10), dp(13))
            background = shape(Color.rgb(19, 31, 23), 18f, Color.rgb(49, 71, 53))
        }
        layout.addView(pairingCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(7) })
        pairingTitle = label(pairingCard, "HUBUNGKAN KE WEB", 9f, muted).apply { letterSpacing = .1f }
        code = label(pairingCard, "", 19f, mint).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        }
        pairingHint = label(pairingCard, "Tampilkan kode, lalu buka\ntautannya di browser Anda.", 10f, muted)
        label(layout, "PENGATURAN", 9f, muted).apply {
            letterSpacing = .14f
            setPadding(0, dp(19), 0, dp(7))
        }
        button(layout, "Privasi & data") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${api.origin}/privacy"))) }
            catch (_: Exception) { AlertDialog.Builder(this).setMessage("Buka ${api.origin}/privacy di browser. BPM dikirim saat Start, hanya pembacaan terbaru disimpan sementara. Tautan overlay memungkinkan orang lain melihat BPM Anda.").setPositiveButton("OK", null).show() }
        }
        reset = button(layout, "Reset pairing") {
            AlertDialog.Builder(this).setTitle("Reset pairing?")
                .setMessage("Hapus pairing dan cabut tautan overlay? Hentikan sesi terlebih dahulu.")
                .setPositiveButton("Hapus") { _, _ -> resetPairing() }.setNegativeButton("Batal", null).show()
        }.apply { setTextColor(Color.rgb(255, 180, 167)) }
        if (BuildConfig.DEBUG) serverButton = button(layout, "Server pengembangan") { editServer() }
        label(layout, "Sesi maksimal 4 jam.\nKendali selalu di tangan Anda.", 10f, muted).setPadding(dp(5), dp(15), dp(5), 0)
        refreshControls()
        scroll.requestFocus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(parent: LinearLayout, value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(color)
        includeFontPadding = false
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private fun button(parent: LinearLayout, title: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = if (primary) 13f else 11f
        isAllCaps = false
        setTypeface(null, if (primary) Typeface.BOLD else Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        stateListAnimator = null
        setPadding(dp(12), dp(10), dp(12), dp(10))
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), shape(if (primary) mint else Color.rgb(25, 39, 29), 25f), null)
        setTextColor(if (primary) Color.rgb(19, 37, 17) else Color.rgb(223, 237, 226))
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(3) })
        setOnClickListener { action() }
    }
    private fun enable(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else .4f
    }
    private fun refreshControls() {
        val running = HeartRateService.running
        val fresh = SystemClock.elapsedRealtime() - HeartRateService.lastMeasurement < 15_000
        val value = if (running && fresh) HeartRateService.bpm else null
        val live = value != null && HeartRateService.message == "BPM terkirim"
        dial.update(value, live, if (live) "● LIVE" else if (running) "MEMBACA SENSOR" else "HEART RATE")
        dial.visibility = if (paired || running) View.VISIBLE else View.GONE
        start.visibility = if (paired || running) View.VISIBLE else View.GONE
        start.text = if (running) "■  Stop sesi" else "▶  Start"
        start.contentDescription = if (running) "Stop, hentikan pembagian BPM" else "Start, mulai membagikan BPM"
        enable(start, running || (paired && !busy))
        register.visibility = if (paired || running) View.GONE else View.VISIBLE
        register.text = if (busy) "Menghubungkan…" else if (api.pairingCode.isNotEmpty()) "Perbarui kode" else "Tampilkan kode"
        enable(register, !running && !busy)
        enable(reset, !running && !busy && api.ingestToken.isNotEmpty())
        serverButton?.let { enable(it, !running && !busy && api.ingestToken.isEmpty()) }
        if (paired) pairingTitle.text = "PEMAIN TERHUBUNG"
        else if (api.pairingCode.isEmpty()) {
            pairingTitle.text = "HUBUNGKAN KE WEB"
            code.visibility = View.GONE
            pairingHint.text = "Tampilkan kode, lalu buka\ntautannya di browser Anda."
        }
    }
    private fun runTask(action: () -> Unit) {
        if (busy) return
        busy = true
        refreshControls()
        worker.execute {
            try { action() }
            catch (error: Exception) { runOnUiThread { status.text = error.message ?: "Server tidak dapat diakses." } }
            finally { runOnUiThread { busy = false; refreshControls() } }
        }
    }
    private fun connect() {
        if (HeartRateService.running) return
        status.text = "Menyiapkan koneksi…"
        runTask {
            if (api.ingestToken.isEmpty()) api.register()
            else {
                try {
                    val state = api.request("/api/device/status")
                    if (!state.getBoolean("paired")) api.renewPairing()
                } catch (error: ApiException) {
                    if (error.status != 401) throw error
                    api.clearRegistration(); api.register()
                }
            }
            updateStatus()
            runOnUiThread { scroll.post { scroll.smoothScrollTo(0, pairingCard.top - dp(28)) } }
        }
    }
    private fun poll() {
        lastPoll = SystemClock.elapsedRealtime()
        runTask {
            try { updateStatus() }
            catch (error: ApiException) {
                if (error.status == 401) {
                    api.clearRegistration()
                    runOnUiThread { paired = false; code.text = "" }
                }
                throw error
            }
        }
    }
    private fun updateStatus() {
        val data = api.request("/api/device/status")
        runOnUiThread {
            val wasPaired = paired
            paired = data.getBoolean("paired")
            if (paired) {
                code.visibility = View.VISIBLE
                code.alpha = 1f
                code.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                code.textSize = 16f
                code.text = data.getString("playerName")
                pairingHint.text = "Overlay siap digunakan.\nSalin tautannya dari web ke OBS."
                if (!HeartRateService.running) status.text = "Siap untuk stream.\nTekan Start untuk berbagi."
                if (!wasPaired) scroll.post { scroll.smoothScrollTo(0, 0) }
            } else {
                status.text = "Hubungkan kode ini\nmelalui browser Anda."
                showPairingLink()
            }
            refreshControls()
        }
    }
    private fun showPairingLink() {
        val seconds = ((api.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val formatted = api.pairingCode.chunked(4).joinToString("-")
        pairingTitle.text = if (seconds == 0L) "KODE KEDALUWARSA" else "PAIRING · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        code.visibility = View.VISIBLE
        code.typeface = Typeface.create("monospace", Typeface.BOLD)
        code.textSize = 19f
        code.text = formatted
        code.alpha = if (seconds == 0L) .4f else 1f
        pairingHint.text = if (seconds == 0L) "Tekan Perbarui kode\nuntuk tautan baru." else "Buka di browser:\n${api.origin}/$formatted"
    }
    private fun resetPairing() {
        if (HeartRateService.running) return
        runTask {
            try { api.request("/api/device/registration", "DELETE") }
            catch (error: ApiException) { if (error.status != 401) throw error }
            api.clearRegistration()
            runOnUiThread { paired = false; code.text = ""; status.text = "Pairing dihapus.\nSiap dihubungkan kembali." }
        }
    }
    private fun editServer() {
        if (HeartRateService.running || api.ingestToken.isNotEmpty()) return
        val input = EditText(this).apply { setText(api.origin); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("Alamat server").setView(input).setPositiveButton("Simpan") { _, _ ->
            try { api.setOrigin(input.text.toString()); status.text = "Server disimpan." }
            catch (error: Exception) { status.text = error.message }
        }.setNegativeButton("Batal", null).show()
    }
    private fun disclose() {
        AlertDialog.Builder(this).setTitle("Bagikan detak jantung?")
            .setMessage("Saat sesi aktif, PulseStage membaca BPM dan mengirimkannya ke ${api.origin}. Pemilik tautan overlay dan penonton siaran dapat melihat BPM Anda. Server menyimpan pembacaan terbaru sementara, tanpa riwayat BPM. Tekan Stop kapan saja.")
            .setPositiveButton("Setuju & lanjut") { _, _ -> consent = true; ensurePermissions() }
            .setNegativeButton("Batal", null).show()
    }
    private fun ensurePermissions() {
        val sensorPermission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        val required = mutableListOf(sensorPermission)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) begin() else requestPermissions(missing.toTypedArray(), 10)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        if (requestCode == 10 && consent && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) begin()
        else status.text = "Izin heart rate diperlukan untuk memulai."
    }
    private fun begin() {
        if (!paired || !consent || isFinishing) return
        consent = false
        try { startForegroundService(Intent(this, HeartRateService::class.java)); status.text = "Memulai sensor…" }
        catch (error: Exception) { status.text = error.message ?: "Tidak dapat memulai sesi." }
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
