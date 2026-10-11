package dev.pulsestage.wear.ui

import dev.pulsestage.wear.BuildConfig
import dev.pulsestage.wear.data.Api
import dev.pulsestage.wear.data.ApiException
import dev.pulsestage.wear.service.HeartRateService
import dev.pulsestage.wear.ui.components.PulseDialView

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
    private lateinit var disconnect: Button
    private lateinit var scroll: ScrollView
    private var serverButton: Button? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val mint = Color.rgb(184, 243, 177)
    private val muted = Color.rgb(164, 183, 170)
    private var paired = false
    private var pairingAvailable = true
    private var playerName = ""
    private var busy = false
    private var consent = false
    private var lastPoll = 0L
    private var previousRunning = false
    private var previousStopping = false
    private val tick = object : Runnable {
        override fun run() {
            val running = HeartRateService.running
            if ((previousRunning && !running) || (previousStopping && !HeartRateService.stopping)) status.text = HeartRateService.statusMessage
            previousRunning = running
            previousStopping = HeartRateService.stopping
            if (running || HeartRateService.stopping) status.text = HeartRateService.statusMessage
            else if (HeartRateService.startFailure != null) status.text = HeartRateService.startFailure
            if (!running && api.ingestToken.isEmpty() && paired) {
                paired = false
                code.text = ""
                status.text = HeartRateService.statusMessage
            }
            if (api.pairingCode.isNotEmpty()) showPairingLink()
            refreshControls()
            if (!running && !HeartRateService.stopping && api.ingestToken.isNotEmpty() && !busy && SystemClock.elapsedRealtime() - lastPoll > 5000) poll()
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
                startService(Intent(this, HeartRateService::class.java).setAction(HeartRateService.STOP))
                status.text = "Menghentikan sesi sensor…"
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
        reset = button(layout, "Reset kode akses") {
            AlertDialog.Builder(this).setTitle("Reset kode akses?")
                .setMessage("Kode baru akan menggantikan kode lama. Semua akses browser dan tautan OBS lama dicabut. Hubungkan kembali web dengan kode baru.")
                .setPositiveButton("Reset kode") { _, _ -> resetPairing() }.setNegativeButton("Batal", null).show()
        }.apply { setTextColor(Color.rgb(255, 180, 167)) }
        disconnect = button(layout, "Hapus koneksi") {
            AlertDialog.Builder(this).setTitle("Hapus koneksi?")
                .setMessage("Hapus perangkat ini dari server dan cabut seluruh aksesnya?")
                .setPositiveButton("Hapus") { _, _ -> deleteRegistration() }.setNegativeButton("Batal", null).show()
        }
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
        val stopping = HeartRateService.stopping
        val fresh = SystemClock.elapsedRealtime() - HeartRateService.lastMeasurement < 15_000
        val value = if (running && fresh) HeartRateService.bpm else null
        val live = value != null && HeartRateService.statusMessage == "BPM terkirim"
        dial.update(value, live, if (live) "● LIVE" else if (value != null) "BPM TERBACA" else if (running) "MEMBACA SENSOR" else "HEART RATE")
        dial.visibility = if (paired || running) View.VISIBLE else View.GONE
        start.visibility = if (paired || running) View.VISIBLE else View.GONE
        start.text = if (stopping) "Menghentikan…" else if (running) "■  Stop sesi" else "▶  Start"
        start.contentDescription = if (running) "Stop, hentikan pembagian BPM" else "Start, mulai membagikan BPM"
        enable(start, !stopping && (running || (paired && !busy)))
        register.visibility = if (api.ingestToken.isNotEmpty() || running || stopping) View.GONE else View.VISIBLE
        register.text = if (busy) "Menghubungkan…" else "Tampilkan kode"
        enable(register, !running && !stopping && !busy)
        enable(reset, !running && !stopping && !busy && api.ingestToken.isNotEmpty())
        enable(disconnect, !running && !stopping && !busy && api.ingestToken.isNotEmpty())
        serverButton?.let { enable(it, !running && !stopping && !busy && api.ingestToken.isEmpty()) }
        if (api.pairingCode.isEmpty()) {
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
        if (HeartRateService.running || HeartRateService.stopping) return
        status.text = "Menyiapkan koneksi…"
        runTask {
            if (api.ingestToken.isEmpty()) api.register()
            else {
                try {
                    api.request("/api/device/status")
                    if (api.pairingCode.isEmpty()) error("Tekan Reset kode akses untuk membuat kode baru.")
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
            pairingAvailable = data.optBoolean("pairingAvailable", true)
            playerName = data.getString("playerName")
            showPairingLink()
            if (!HeartRateService.running && !HeartRateService.stopping) {
                status.text = HeartRateService.startFailure ?: if (paired) "Siap untuk stream.\nTekan Start untuk berbagi." else "Hubungkan kode ini\nmelalui browser Anda."
            }
            if (paired && !wasPaired) scroll.post { scroll.smoothScrollTo(0, 0) }
            refreshControls()
        }
    }
    private fun showPairingLink() {
        val formatted = api.pairingCode.chunked(4).joinToString("-")
        pairingTitle.text = if (pairingAvailable) "KODE · TANPA KEDALUWARSA" else "KODE LAMA SUDAH DIPAKAI"
        code.visibility = View.VISIBLE
        code.typeface = Typeface.create("monospace", Typeface.BOLD)
        code.textSize = 19f
        code.text = if (pairingAvailable) formatted else "—"
        code.alpha = 1f
        pairingHint.text = if (!pairingAvailable) "Tekan Reset kode akses\nuntuk kode yang dapat dipakai ulang."
            else (if (paired) "$playerName terhubung.\n" else "") + "Buka di browser:\n${api.origin}/$formatted\nBerlaku sampai direset."
    }
    private fun resetPairing() {
        if (HeartRateService.running || HeartRateService.stopping) return
        runTask {
            api.resetPairing()
            HeartRateService.startFailure = null
            updateStatus()
        }
    }
    private fun deleteRegistration() {
        if (HeartRateService.running || HeartRateService.stopping) return
        runTask {
            try { api.request("/api/device/registration", "DELETE") }
            catch (error: ApiException) { if (error.status != 401) throw error }
            api.clearRegistration()
            HeartRateService.startFailure = null
            runOnUiThread { paired = false; code.text = ""; status.text = "Koneksi dihapus.\nSiap dihubungkan kembali." }
        }
    }
    private fun editServer() {
        if (HeartRateService.running || HeartRateService.stopping || api.ingestToken.isNotEmpty()) return
        val input = EditText(this).apply { setText(api.origin); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("Alamat server").setView(input).setPositiveButton("Simpan") { _, _ ->
            try { api.setOrigin(input.text.toString()); status.text = "Server disimpan." }
            catch (error: Exception) { status.text = error.message }
        }.setNegativeButton("Batal", null).show()
    }
    private fun disclose() {
        AlertDialog.Builder(this).setTitle("Bagikan detak jantung?")
            .setMessage("Saat sesi aktif, PulseStage membaca BPM dan mengirimkannya ke ${api.origin}. Pemilik tautan overlay dan penonton siaran dapat melihat BPM Anda. Server menyimpan pembacaan terbaru sementara, tanpa riwayat BPM. Streaming memakai sesi aktivitas Wear OS, tanpa GPS. Hentikan olahraga di aplikasi lain terlebih dahulu. Tekan Stop kapan saja.")
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
        if (!paired || !consent || isFinishing || HeartRateService.running || HeartRateService.stopping) return
        consent = false
        HeartRateService.startFailure = null
        try { startForegroundService(Intent(this, HeartRateService::class.java)); status.text = "Memulai sensor…" }
        catch (error: Exception) { status.text = error.message ?: "Tidak dapat memulai sesi." }
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
