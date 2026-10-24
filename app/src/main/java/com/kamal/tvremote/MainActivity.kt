package com.kamal.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kamal.tvcore.CertStore
import com.kamal.tvcore.ConnState
import com.kamal.tvcore.SamsungTv
import com.kamal.tvcore.SsdpDiscovery
import com.kamal.tvcore.TokenStore
import com.kamal.tvcore.isValidHostAddress
import com.kamal.tvcore.TvApp
import com.kamal.tvcore.TvDevice
import com.kamal.tvcore.WakeOnLan
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.thread

// Design tokens (v1.6 minimal redesign). Names kept from v1.5 for call sites.
private val BG = Color.rgb(15, 17, 21) // #0F1115
private val BAR = Color.rgb(20, 23, 29) // #14171D
private val PANEL = Color.rgb(24, 27, 33) // #181B21
private val PANEL2 = Color.rgb(32, 36, 44) // #20242C
private val STROKE = Color.argb(31, 255, 255, 255) // hairline only: 1dp, 12% white
private val ON_BG = Color.rgb(242, 244, 247) // #F2F4F7
private val DIM = Color.rgb(139, 147, 161) // #8B93A1
private val ACCENT = Color.rgb(59, 130, 246) // #3B82F6
private val DANGER = Color.rgb(239, 68, 68) // #EF4444
private val GREEN = Color.rgb(61, 214, 140)
private const val PORTFOLIO_URL = "https://neupanekamal.com.np"

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("tvremote", Context.MODE_PRIVATE)
    val tokens = object : TokenStore {
        override fun get(ip: String): String? = p.getString("token_$ip", null)
        override fun put(ip: String, token: String) {
            p.edit().putString("token_$ip", token).apply()
        }
        override fun remove(ip: String) {
            p.edit().remove("token_$ip").apply()
        }
    }
    val certs = object : CertStore {
        override fun get(ip: String): String? = p.getString("cert_$ip", null)
        override fun put(ip: String, fingerprint: String) {
            p.edit().putString("cert_$ip", fingerprint).apply()
        }
        override fun remove(ip: String) {
            p.edit().remove("cert_$ip").apply()
        }
    }

    var savedIp: String?
        get() = p.getString("tv_ip", null)
        set(v) { p.edit().putString("tv_ip", v).apply() }
    var savedName: String?
        get() = p.getString("tv_name", null)
        set(v) { p.edit().putString("tv_name", v).apply() }
    var savedModel: String?
        get() = p.getString("tv_model", null)
        set(v) { p.edit().putString("tv_model", v).apply() }
    var savedMac: String?
        get() = p.getString("tv_mac", null)
        set(v) { p.edit().putString("tv_mac", v).apply() }
    var lang: String
        get() = p.getString("lang", "en") ?: "en"
        set(v) { p.edit().putString("lang", v).apply() }
}

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var tv: SamsungTv
    private lateinit var ir: IrRemote

    /** "wifi" (Samsung protocol) or "ir" (IR blaster) — routes every key. */
    private var mode = "wifi"
    private var tab = "remote"

    /** Connection state the current Settings page was built against. */
    private var settingsState: ConnState? = null
    private var screen = "search" // search | main | apps | input | mirror
    private var returnTab = "touchpad"
    private var mirrorQuality = 1

    private var statusChip: FrameLayout? = null
    private var statusIcon: ImageView? = null
    private var statusText: TextView? = null
    private var searchList: LinearLayout? = null
    private var searchTitle: TextView? = null
    private var searchProgress: ProgressBar? = null
    private var scanGen = 0
    private var scanJob: kotlinx.coroutines.Job? = null
    private val iconCache = java.util.Collections.synchronizedMap(HashMap<String, android.graphics.Bitmap>())
    private val iconGate = kotlinx.coroutines.sync.Semaphore(4)
    private val irHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var irRepeatKey: String? = null
    private val irRepeater = object : Runnable {
        override fun run() {
            val key = irRepeatKey ?: return
            irSend(key)
            irHandler.postDelayed(this, 130)
        }
    }

    companion object {
        private const val REQ_CAPTURE = 91
        private const val REQ_PICK = 92
        private const val REQ_VOICE = 93
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Strings.lang = prefs.lang
        tv = SamsungTv(tokens = prefs.tokens, certs = prefs.certs)
        ir = IrRemote(this)
        window.statusBarColor = BG
        window.navigationBarColor = BAR
        scope.launch { tv.state.collect { refreshChrome() } }
        if (savedInstanceState != null) {
            mode = savedInstanceState.getString("mode", "wifi") ?: "wifi"
            tab = savedInstanceState.getString("tab", "remote") ?: "remote"
            screen = savedInstanceState.getString("screen", "main") ?: "main"
            returnTab = savedInstanceState.getString("returnTab", "touchpad") ?: "touchpad"
            mirrorQuality = savedInstanceState.getInt("mirrorQuality", 1)
            pendingMediaKind = savedInstanceState.getString("pendingMediaKind", "video") ?: "video"
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Without this, the mirroring notification (and its STOP action)
            // is silently suppressed on Android 13+.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 94)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (!handleBack()) finish() }
        }
        val saved = prefs.savedIp
        val restored = savedInstanceState != null
        when {
            mode == "ir" -> showMain(tab)
            restored && screen == "help" -> showHelp()
            restored && screen == "search" -> showSearch()
            saved != null -> {
                tv.connect(saved)
                when {
                    restored && screen == "mirror" -> showMirror()
                    restored && screen == "apps" -> showApps()
                    restored && screen == "input" -> showInput()
                    else -> showMain(tab)
                }
            }
            else -> showSearch()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode)
        outState.putString("tab", tab)
        outState.putString("screen", screen)
        outState.putString("returnTab", returnTab)
        outState.putInt("mirrorQuality", mirrorQuality)
        outState.putString("pendingMediaKind", pendingMediaKind)
    }

    override fun onDestroy() {
        // Always disconnect: on a non-finishing recreation this instance's
        // socket would otherwise leak; the new instance reconnects itself.
        tv.disconnect()
        irRepeatKey = null
        irHandler.removeCallbacks(irRepeater)
        irExecutor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest keeps this activity alive across rotation, so the
        // current screen must be rebuilt to pick the orientation layout:
        // without this, a portrait-built tab only re-measures into
        // landscape, and the Remote D-pad collapses to the short height
        // (the 2026-10-24 landscape report). Search rebuilds the same
        // way; the other screens re-measure well enough on their own.
        when (screen) {
            "main" -> showMain(tab)
            "search" -> showSearch()
        }
    }

    /** Returns true when the press was handled in-app. */
    private fun handleBack(): Boolean {
        when (screen) {
            "apps", "input" -> showMain(returnTab)
            "help" -> showMain("settings")
            "mirror" -> showMain("mirroring")
            "search" -> {
                if (tv.connected) showMain(tab) else return false
            }
            else -> return false
        }
        return true
    }

    @Deprecated("back routing")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    // ---------------- key routing ----------------

    /** Single background thread for IR sends, so a held key never spawns a thread per repeat. */
    private val irExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** IR send that tolerates the executor being shut down during teardown. */
    private fun irSend(key: String) {
        try {
            irExecutor.execute { ir.send(key) }
        } catch (e: Exception) { /* the activity is going away */ }
    }

    /** Connect, dropping cached icons when switching to a different TV. */
    private fun connectTo(ip: String) {
        if (ip != tv.ip) iconCache.clear()
        tv.connect(ip)
    }

    private fun pressKey(key: String) {
        if (mode == "ir") {
            irSend(key)
        } else {
            tv.click(key)
        }
    }

    private fun holdable(view: View, key: String) {
        // Accessibility activation (a TalkBack double-tap) arrives as a
        // click, not as touch events: give it a single-press equivalent.
        // Touch users never see this fire; the touch listener below
        // consumes their events before a click can be dispatched.
        view.setOnClickListener { pressKey(key) }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    v.isPressed = true
                    if (mode == "ir") {
                        irSend(key)
                        // IR has no press/release pair: repeat while held.
                        irRepeatKey = key
                        irHandler.removeCallbacks(irRepeater)
                        irHandler.postDelayed(irRepeater, 450)
                    } else {
                        tv.press(key)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (mode == "ir") {
                        irRepeatKey = null
                        irHandler.removeCallbacks(irRepeater)
                    } else {
                        tv.release(key)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- shared builders ----------------

    private fun icon(name: String, sizeDp: Float, tint: Int): ImageView {
        val iv = ImageView(this)
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id != 0) iv.setImageResource(id)
        iv.setColorFilter(tint)
        iv.layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        return iv
    }

    /** Round icon button on a panel circle, like the reference's image buttons. */
    private fun iconBtn(name: String, tint: Int = ON_BG, boxDp: Float = 56f, iconDp: Float = 26f, desc: String? = null): FrameLayout {
        val box = FrameLayout(this)
        box.background = oval(PANEL)
        if (desc != null) box.contentDescription = desc
        val iv = icon(name, iconDp, tint)
        iv.layoutParams = FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER)
        box.addView(iv)
        box.layoutParams = ViewGroup.LayoutParams(dp(boxDp), dp(boxDp))
        return box
    }

    /** Icon button with a small caption under it. */
    private fun labeledBtn(name: String, caption: String, tint: Int = ON_BG, key: String? = null, hold: Boolean = false): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val btn = iconBtn(name, tint, 56f, 24f)
        btn.contentDescription = caption
        col.addView(btn)
        col.addView(label(caption, 12f, DIM, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) }
        })
        if (key != null) {
            if (hold) holdable(btn, key) else btn.tap { pressKey(key) }
        }
        return col
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------- shell (banner + content + bottom nav) ----------------

    private fun showMain(newTab: String) {
        tab = newTab
        settingsState = if (newTab == "settings") tv.state.value else null
        screen = "main"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        if (newTab != "settings") {
            root.addView(buildStatusRow())
        } else {
            // No status line on Settings: its Connection section already
            // shows the TV and its state, and the line read as clutter at
            // the top of that page (Kamal, 2026-10-23). Null the fields
            // so refreshChrome skips the detached views.
            statusChip = null
            statusIcon = null
            statusText = null
        }
        val content = when (newTab) {
            "control" -> controlTab()
            "touchpad" -> touchpadTab()
            "mirroring" -> mirroringTab()
            "settings" -> settingsTab()
            else -> remoteTab()
        }
        if (newTab == "remote" || newTab == "touchpad" || (newTab == "mirroring" && !isLandscape())) {
            // These tabs size themselves from the available height — no scroll.
            // Mirroring is the exception in landscape: its fixed card grid is
            // taller than a landscape height, so it scrolls there instead
            // of clipping its bottom note off screen.
            root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this).apply {
                setBackgroundColor(BG)
                isVerticalScrollBarEnabled = false
                addView(content)
            }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val nav = buildNav(newTab)
        root.addView(nav)
        setContentView(root)
        // Edge-to-edge: status-bar inset pads the shell top; the nav bar
        // absorbs the bottom inset into its own padding (BAR runs to the edge).
        val navBaseBottom = nav.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            root.setPadding(0, topInset, 0, 0)
            nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, navBaseBottom + bottomInset)
            insets
        }
        root.requestApplyInsets()
        refreshChrome()
    }

    private fun buildStatusRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // The status is an icon chip plus a short state word, centred
            // on the screen and tight to the top. It used to be a bare dot
            // beside a sentence, which read as generated filler, and the
            // failure sentence now lives in Settings > Debug instead.
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(2f), dp(24f), dp(0f))
        }
        val chip = chipIcon("ic_tv", DIM)
        statusChip = chip
        statusIcon = chip.getChildAt(0) as ImageView
        row.addView(chip, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        statusText = label("", 12f, DIM)
        row.addView(statusText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        return row
    }

    /**
     * A small icon in a tinted circle: the designed replacement for the
     * bare coloured dot that used to sit beside status text. The chip
     * keeps the row's height (22dp), so no screen gains a scrollbar.
     */
    private fun chipIcon(iconName: String, color: Int): FrameLayout {
        val box = FrameLayout(this).apply {
            background = oval(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
        }
        val iv = icon(iconName, 13f, color)
        iv.layoutParams = FrameLayout.LayoutParams(dp(13f), dp(13f), Gravity.CENTER)
        box.addView(iv)
        return box
    }

    /** Recolour a status chip and swap its icon without rebuilding the row. */
    private fun setChip(iconName: String, color: Int) {
        val chip = statusChip ?: return
        chip.background = oval(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
        val iv = statusIcon ?: return
        val id = resources.getIdentifier(iconName, "drawable", packageName)
        if (id != 0) iv.setImageResource(id)
        iv.setColorFilter(color)
    }

    /** Installed version name (for the connection diagnostics line). */
    private fun appVersion(): String =
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun refreshChrome() {
        if (tab == "settings" && mode == "wifi" && settingsState != null &&
            tv.state.value != settingsState
        ) {
            // The Settings page is rendered from the connection state
            // (the Connect row and the Disconnect row depend on it) but
            // was never updated afterwards, so a successful reconnect
            // left the previous failure's rows on screen next to a
            // Connected status (Kamal's report, 2026-10-23). Rebuild
            // the page on a state change.
            showMain("settings")
            return
        }
        if (statusChip == null || statusText == null) return
        val txt = statusText ?: return
        if (mode == "ir") {
            setChip("ic_ir", ACCENT)
            txt.text = Strings.t("ir_mode")
            return
        }
        when (tv.state.value) {
            ConnState.CONNECTED -> {
                setChip("ic_check", GREEN)
                val name = prefs.savedName ?: prefs.savedIp ?: ""
                txt.text = if (name.isNotEmpty()) "$name · ${Strings.t("connected_to")}" else Strings.t("connected_to")
            }
            ConnState.CONNECTING -> {
                setChip("ic_retry", ACCENT)
                txt.text = Strings.t("connecting")
            }
            else -> {
                // The front view carries only the short state word. The
                // failure sentence and the diagnostics moved to
                // Settings > Debug, so a failure no longer prints its
                // reason across the main screen.
                setChip("ic_tv", DIM)
                txt.text = Strings.t("status_disconnected")
            }
        }
    }

    /**
     * A changed TV certificate is a security decision, not a retry: show
     * the fingerprint the TV presented and trust it only after an explicit
     * confirmation. Trusting stores the new pin and drops the pairing
     * token, so the TV will ask to pair again.
     */
    private fun certTrustDialog(ip: String) {
        val fp = tv.pendingPinFingerprint
        val message = Strings.t("cert_confirm") +
            (fp?.let { "\n\n" + it.uppercase().chunked(4).joinToString(" ") } ?: "")
        android.app.AlertDialog.Builder(this)
            .setTitle(Strings.t("trust_new"))
            .setMessage(message)
            .setPositiveButton(Strings.t("trust_new")) { _, _ ->
                if (tv.trustPendingCertificate()) {
                    prefs.tokens.remove(ip)
                    tv.connect(ip)
                } else {
                    toast(Strings.t("err_failed"))
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    /**
     * The one place for connection insider info: state plus the
     * diagnostics line (target, token state, certificate state, failure
     * kind and detail, never a token value). The front view no longer
     * prints any of this; it shows only the short state word.
     */
    private fun debugDialog() {
        val diag = tv.diagLine(appVersion())
        val stateLine = "State: " + tv.state.value.name.lowercase()
        val message = if (diag.isEmpty()) {
            stateLine + "\n" + Strings.t("debug_no_tv") + "\nApp " + appVersion()
        } else {
            stateLine + "\n" + diag.replace(" · ", "\n")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(Strings.t("set_debug"))
            .setMessage(message)
            .setPositiveButton(Strings.t("close"), null)
            .show()
    }

    private fun buildNav(active: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR)
        }
        bar.addView(View(this).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(-1, dp(1f)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(7f), dp(4f), dp(7f))
        }
        val items = if (mode == "ir") {
            listOf(
                Triple("remote", "ic_dpad", "tab_remote"),
                Triple("control", "ic_apps", "tab_control"),
                Triple("settings", "ic_gear", "tab_settings"),
            )
        } else {
            listOf(
                Triple("remote", "ic_dpad", "tab_remote"),
                Triple("control", "ic_apps", "tab_control"),
                Triple("touchpad", "ic_move", "tab_touchpad"),
                Triple("mirroring", "ic_cast", "tab_mirroring"),
                Triple("settings", "ic_gear", "tab_settings"),
            )
        }
        items.forEach { (id, ic, labelKey) ->
            val selected = id == active
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2f), dp(3f), dp(2f), dp(3f))
            }
            cell.addView(icon(ic, 24f, if (selected) ACCENT else DIM))
            cell.addView(
                label(Strings.t(labelKey), 11f, if (selected) ACCENT else DIM, bold = selected, gravity = Gravity.CENTER).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) }
                }
            )
            cell.tap { if (!selected) showMain(id) }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(row)
        return bar
    }


    // ---------------- Remote tab (fit-to-screen, weighted zones) ----------------

    private fun remoteTab(): View {
        if (isLandscape()) return remoteTabLandscape()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }

        root.addView(remoteTopRow(), LinearLayout.LayoutParams(-1, dp(56f)))
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(16f)))

        // D-pad zone: flexible height; the pad itself is sized from this zone.
        val dpadZone = FrameLayout(this)
        dpadZone.addView(buildDpad(), FrameLayout.LayoutParams(-1, -1))
        root.addView(dpadZone, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(16f)))

        // Rocker zone: same flexible height as the D-pad zone. Tools grid sits
        // in the center column between the rockers — no dead gap.
        val rockerZone = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rockerZone.addView(rocker("vol", "KEY_VOLUP", "KEY_VOLDOWN"), LinearLayout.LayoutParams(dp(76f), -1))
        rockerZone.addView(remoteToolsGrid(), LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(16f); rightMargin = dp(16f)
        })
        rockerZone.addView(rocker("ch", "KEY_CHUP", "KEY_CHDOWN"), LinearLayout.LayoutParams(dp(76f), -1))
        root.addView(rockerZone, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(16f)))

        root.addView(remoteBottomRow(), LinearLayout.LayoutParams(-1, -2))
        return root
    }

    /** Top row shared by both Remote orientations: Power left, Guide + Source right. */
    private fun remoteTopRow(): LinearLayout {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val power = iconBtn("ic_power", DANGER, 56f, 24f, desc = Strings.t("power"))
        power.tap { pressKey("KEY_POWER") }
        top.addView(power)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val guide = iconBtn("ic_guide", ON_BG, 56f, 24f, desc = Strings.t("guide"))
        guide.tap { pressKey("KEY_GUIDE") }
        top.addView(guide)
        val source = iconBtn("ic_source", ON_BG, 56f, 24f, desc = Strings.t("source"))
        source.tap { pressKey("KEY_SOURCE") }
        top.addView(source, LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { leftMargin = dp(12f) })
        return top
    }

    /** Tools/Info/Back/Exit grid from the portrait rocker zone, with captions. */
    private fun remoteToolsGrid(): LinearLayout {
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolRows = listOf(
            listOf(Triple("ic_tools", "tools", "KEY_TOOLS"), Triple("ic_info", "info", "KEY_INFO")),
            listOf(Triple("ic_back", "back", "KEY_RETURN"), Triple("ic_exit", "exit", "KEY_EXIT")),
        )
        toolRows.forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pair.forEach { (ic, lk, key) ->
                row.addView(centeredCell(labeledBtn(ic, Strings.t(lk), key = key)), LinearLayout.LayoutParams(0, -1, 1f))
            }
            center.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return center
    }

    /** Bottom row shared by portrait: Home · Mute · Menu, with captions. */
    private fun remoteBottomRow(): LinearLayout {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("ic_home", "home", "KEY_HOME"),
            Triple("ic_mute", "mute", "KEY_MUTE"),
            Triple("ic_menu", "menu", "KEY_MENU"),
        ).forEach { (ic, lk, key) ->
            bottom.addView(labeledBtn(ic, Strings.t(lk), key = key), LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bottom
    }

    /**
     * Landscape Remote: the portrait stack cannot work here, because the
     * D-pad zone is half of an already short height, which shrank the pad
     * to a dot (the 2026-10-24 report). Instead the pad gets the full
     * content height on the left, and every other control stacks on the
     * right: top row, rockers with an icon-only tools grid between them,
     * and an icon-only Home/Mute/Menu row. No captions on the right side:
     * at this height a labeled button would overflow its row.
     */
    private fun remoteTabLandscape(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        }
        val dpadZone = FrameLayout(this)
        dpadZone.addView(buildDpad(), FrameLayout.LayoutParams(-1, -1))
        root.addView(dpadZone, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(remoteTopRow(), LinearLayout.LayoutParams(-1, dp(56f)))
        right.addView(View(this), LinearLayout.LayoutParams(-1, dp(12f)))
        val rockerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rockerRow.addView(rocker("vol", "KEY_VOLUP", "KEY_VOLDOWN"), LinearLayout.LayoutParams(dp(76f), -1))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf(Triple("ic_tools", "tools", "KEY_TOOLS"), Triple("ic_info", "info", "KEY_INFO")),
            listOf(Triple("ic_back", "back", "KEY_RETURN"), Triple("ic_exit", "exit", "KEY_EXIT")),
        ).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            pair.forEach { (ic, lk, key) ->
                val b = iconBtn(ic, ON_BG, 48f, 24f, desc = Strings.t(lk))
                b.tap { pressKey(key) }
                row.addView(centeredCell(b), LinearLayout.LayoutParams(0, -1, 1f))
            }
            tools.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        rockerRow.addView(tools, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(12f); rightMargin = dp(12f)
        })
        rockerRow.addView(rocker("ch", "KEY_CHUP", "KEY_CHDOWN"), LinearLayout.LayoutParams(dp(76f), -1))
        right.addView(rockerRow, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(View(this), LinearLayout.LayoutParams(-1, dp(12f)))
        val bottomIcons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("ic_home", "home", "KEY_HOME"),
            Triple("ic_mute", "mute", "KEY_MUTE"),
            Triple("ic_menu", "menu", "KEY_MENU"),
        ).forEach { (ic, lk, key) ->
            val b = iconBtn(ic, ON_BG, 48f, 24f, desc = Strings.t(lk))
            b.tap { pressKey(key) }
            bottomIcons.addView(centeredCell(b), LinearLayout.LayoutParams(0, -2, 1f))
        }
        right.addView(bottomIcons, LinearLayout.LayoutParams(-1, -2))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1.2f).apply { leftMargin = dp(16f) })
        return root
    }

    private fun centeredCell(child: View): FrameLayout {
        val holder = FrameLayout(this)
        child.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        holder.addView(child)
        return holder
    }

    private fun buildDpad(): View {
        val stage = FrameLayout(this)
        val bg = View(this).apply { background = oval(PANEL) }
        stage.addView(bg)

        fun arrow(rotation: Float, key: String): Pair<FrameLayout, ImageView> {
            val zone = FrameLayout(this)
            val iv = icon("ic_back", 24f, ON_BG)
            iv.rotation = rotation
            zone.addView(iv)
            holdable(zone, key)
            return zone to iv
        }
        val (up, upIcon) = arrow(90f, "KEY_UP")
        val (down, downIcon) = arrow(270f, "KEY_DOWN")
        val (left, leftIcon) = arrow(0f, "KEY_LEFT")
        val (right, rightIcon) = arrow(180f, "KEY_RIGHT")
        up.contentDescription = Strings.t("dir_up")
        down.contentDescription = Strings.t("dir_down")
        left.contentDescription = Strings.t("dir_left")
        right.contentDescription = Strings.t("dir_right")
        stage.addView(up); stage.addView(down); stage.addView(left); stage.addView(right)

        val ok = FrameLayout(this)
        ok.background = oval(ACCENT)
        val okLabel = label("OK", 16f, Color.WHITE, bold = true, gravity = Gravity.CENTER)
        okLabel.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        ok.addView(okLabel)
        ok.contentDescription = "OK"
        ok.tap { pressKey("KEY_ENTER") }
        stage.addView(ok)

        stage.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val size = minOf(w, h).coerceAtMost(dp(360f))
            bg.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            val cell = size / 3
            val iconSize = (cell * 0.45f).toInt().coerceIn(dp(24f), dp(40f))
            val circleLeft = (w - size) / 2
            val circleTop = (h - size) / 2
            fun place(v: View, col: Int, row: Int) {
                v.layoutParams = FrameLayout.LayoutParams(cell, cell).apply {
                    leftMargin = circleLeft + col * cell
                    topMargin = circleTop + row * cell
                }
            }
            place(up, 1, 0); place(down, 1, 2); place(left, 0, 1); place(right, 2, 1)
            listOf(upIcon, downIcon, leftIcon, rightIcon).forEach {
                it.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            val okSize = (size * 0.32f).toInt()
            ok.layoutParams = FrameLayout.LayoutParams(okSize, okSize, Gravity.CENTER)
        }
        return stage
    }

    private fun rocker(labelKey: String, upKey: String, downKey: String): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL2, 24f, this@MainActivity)
        }
        fun zone(iconName: String, key: String, descKey: String): LinearLayout {
            val z = LinearLayout(this).apply { gravity = Gravity.CENTER }
            z.contentDescription = Strings.t(labelKey) + " " + Strings.t(descKey)
            z.addView(icon(iconName, 24f, ON_BG))
            holdable(z, key)
            return z
        }
        pill.addView(zone("ic_plus", upKey, "dir_up"), LinearLayout.LayoutParams(-1, 0, 1f))
        pill.addView(
            label(Strings.t(labelKey), 12f, DIM, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(-1, -2)
        )
        pill.addView(zone("ic_minus", downKey, "dir_down"), LinearLayout.LayoutParams(-1, 0, 1f))
        pill.layoutParams = LinearLayout.LayoutParams(dp(76f), -1)
        return pill
    }


    // ---------------- Control tab ----------------

    private fun controlTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        // Number pad: soft rounded keys, equal sizes, no borders.
        val grid = GridLayout(this).apply { columnCount = 3 }
        val cells = listOf(
            "1" to "KEY_1", "2" to "KEY_2", "3" to "KEY_3",
            "4" to "KEY_4", "5" to "KEY_5", "6" to "KEY_6",
            "7" to "KEY_7", "8" to "KEY_8", "9" to "KEY_9",
            "−" to "KEY_MINUS", "0" to "KEY_0", Strings.t("prech") to "KEY_PRECH",
        )
        cells.forEach { (txt, key) ->
            val cell = label(txt, if (txt.length > 1) 12f else 24f, ON_BG, bold = true, gravity = Gravity.CENTER).apply {
                background = rippleKey(PANEL, 16f)
            }
            cell.tap { pressKey(key) }
            cell.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(64f)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            grid.addView(cell)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // Media row: four equal circles.
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("ic_rewind", "KEY_REWIND", "m_rewind"),
            Triple("ic_play", "KEY_PLAY", "m_play"),
            Triple("ic_pause", "KEY_PAUSE", "m_pause"),
            Triple("ic_forward", "KEY_FF", "m_forward"),
        ).forEach { (ic, key, descKey) ->
            val holder = LinearLayout(this).apply { gravity = Gravity.CENTER }
            val btn = iconBtn(ic, ON_BG, 56f, 24f, desc = Strings.t(descKey))
            btn.tap { pressKey(key) }
            holder.addView(btn)
            media.addView(holder, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(media, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })

        // Color keys, slightly desaturated.
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colorDefs = listOf(
            Triple("A", Color.rgb(198, 84, 84), "KEY_RED"),
            Triple("B", Color.rgb(92, 158, 107), "KEY_GREEN"),
            Triple("C", Color.rgb(205, 170, 72), "KEY_YELLOW"),
            Triple("D", Color.rgb(92, 129, 196), "KEY_BLUE"),
        )
        colorDefs.forEachIndexed { i, (letter, color, key) ->
            val b = label(letter, 12f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
                background = rippleKey(color, 16f)
            }
            // TalkBack should hear the color name, not just the letter.
            b.contentDescription = when (key) {
                "KEY_RED" -> Strings.t("col_red") + " " + letter
                "KEY_GREEN" -> Strings.t("col_green") + " " + letter
                "KEY_YELLOW" -> Strings.t("col_yellow") + " " + letter
                else -> Strings.t("col_blue") + " " + letter
            }
            b.tap { pressKey(key) }
            colors.addView(b, LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
                if (i > 0) leftMargin = dp(8f)
            })
        }
        root.addView(colors, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        return root
    }


    // ---------------- Touchpad tab ----------------

    private fun touchpadTab(): View {
        if (isLandscape()) return touchpadTabLandscape()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }
        // Top actions: three equal pills.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(touchpadPill("ic_keyboard", "tp_keyboard") { returnTab = "touchpad"; showInput() }, LinearLayout.LayoutParams(0, dp(48f), 1f))
        top.addView(touchpadPill("ic_mic", "tp_voice") { startVoice() }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(8f) })
        top.addView(touchpadPill("ic_apps", "apps") { returnTab = "touchpad"; showApps() }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(8f) })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Swipe surface fills the remaining height.
        val pad = touchpadPad()
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16f) })

        // Back · Home bars.
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(touchpadBar("ic_back", "back", "KEY_RETURN"), LinearLayout.LayoutParams(0, dp(52f), 1f))
        bottom.addView(touchpadBar("ic_home", "home", "KEY_HOME"), LinearLayout.LayoutParams(0, dp(52f), 1f).apply { leftMargin = dp(12f) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        return root
    }

    private fun touchpadPill(iconName: String, descKey: String, action: () -> Unit): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippleKey(PANEL, 24f)
            contentDescription = Strings.t(descKey)
        }
        p.addView(icon(iconName, 24f, ON_BG))
        p.tap { action() }
        return p
    }

    private fun touchpadBar(iconName: String, labelKey: String, key: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippleKey(PANEL, 16f)
        }
        row.addView(icon(iconName, 24f, ON_BG))
        row.addView(label(Strings.t(labelKey), 12f, ON_BG, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
        })
        row.tap { pressKey(key) }
        return row
    }

    private fun touchpadPad(): FrameLayout {
        val pad = FrameLayout(this).apply {
            background = rounded(PANEL, 24f, this@MainActivity)
            contentDescription = Strings.t("swipe_hint")
        }
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        centerCol.addView(icon("ic_move", 40f, DIM))
        centerCol.addView(label(Strings.t("swipe_hint"), 12f, DIM, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) }
        })
        centerCol.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        pad.addView(centerCol)
        attachSwipePad(pad)
        return pad
    }

    /**
     * Landscape Touchpad: the portrait stack squeezed the swipe surface to
     * about 110dp tall. Here the pad takes the full content height on the
     * left and the five actions stack in a side column, so swiping gets a
     * tall surface instead of a wide strip.
     */
    private fun touchpadTabLandscape(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(12f))
        }
        root.addView(touchpadPad(), LinearLayout.LayoutParams(0, -1, 1f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun sideItem(v: View, first: Boolean = false) {
            side.addView(v, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (!first) topMargin = dp(8f)
            })
        }
        sideItem(touchpadPill("ic_keyboard", "tp_keyboard") { returnTab = "touchpad"; showInput() }, first = true)
        sideItem(touchpadPill("ic_mic", "tp_voice") { startVoice() })
        sideItem(touchpadPill("ic_apps", "apps") { returnTab = "touchpad"; showApps() })
        sideItem(touchpadBar("ic_back", "back", "KEY_RETURN"))
        sideItem(touchpadBar("ic_home", "home", "KEY_HOME"))
        root.addView(side, LinearLayout.LayoutParams(dp(132f), -1).apply { leftMargin = dp(12f) })
        return root
    }

    private fun attachSwipePad(pad: View) {
        var downX = 0f
        var downY = 0f
        var anchorX = 0f
        var anchorY = 0f
        var downT = 0L
        var fired = false
        val step = dp(54f).toFloat()
        pad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.x; downY = e.y
                    anchorX = e.x; anchorY = e.y
                    downT = System.currentTimeMillis()
                    fired = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - anchorX
                    val dy = e.y - anchorY
                    if (kotlin.math.abs(dx) > step || kotlin.math.abs(dy) > step) {
                        val key = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            if (dx > 0) "KEY_RIGHT" else "KEY_LEFT"
                        } else {
                            if (dy > 0) "KEY_DOWN" else "KEY_UP"
                        }
                        pressKey(key)
                        fired = true
                        anchorX = e.x; anchorY = e.y
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dt = System.currentTimeMillis() - downT
                    val moved = kotlin.math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                    if (!fired && moved > step) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        val key = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            if (dx > 0) "KEY_RIGHT" else "KEY_LEFT"
                        } else {
                            if (dy > 0) "KEY_DOWN" else "KEY_UP"
                        }
                        pressKey(key)
                    } else if (dt < 350 && moved < dp(20f)) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        pressKey("KEY_ENTER")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, Strings.t("voice_hint"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: Exception) {
            toast(Strings.t("speech_error"))
        }
    }


    // ---------------- Mirroring tab + screen ----------------

    private fun mirroringTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun card(iconName: String, labelText: String, action: () -> Unit) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rippleKey(PANEL, 16f)
                setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
            }
            val iconCircle = FrameLayout(this).apply {
                background = oval(Color.argb(38, 59, 130, 246)) // ACCENT at ~15%
            }
            val iv = icon(iconName, 40f, ACCENT)
            iv.layoutParams = FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.CENTER)
            iconCircle.addView(iv)
            iconCircle.layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
            c.addView(iconCircle)
            c.addView(label(labelText, 15f, ON_BG, bold = true, gravity = Gravity.CENTER).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) }
            })
            c.tap { action() }
            c.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(120f)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            grid.addView(c)
        }
        card("ic_cast", Strings.t("screen_mirroring")) { showMirror() }
        card("ic_photo", Strings.t("photos")) { pickMedia("image") }
        card("ic_video", Strings.t("videos")) { pickMedia("video") }
        card("ic_audio", Strings.t("audio")) { pickMedia("audio") }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // A failed cast leaves its error here, not only in a toast that
        // disappears after a few seconds.
        if (MirrorState.error != null) {
            root.addView(label(mirrorErrorText(), 13f, DANGER).apply {
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        }

        // A running media cast gets its stop control here (the mirror screen
        // only covers screen mirroring).
        if (MirrorState.running && MirrorState.mode == "media") {
            val castRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(PANEL, 16f, this@MainActivity)
                setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
            }
            castRow.addView(label(Strings.t("cast_active"), 14f, ON_BG).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val stopBtn = label(Strings.t("stop"), 12f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
                background = rippleKey(DANGER, 16f)
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            }
            stopBtn.tap { stopMirroring(); showMain("mirroring") }
            castRow.addView(stopBtn)
            root.addView(castRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        }

        // Wi-Fi note pinned near the bottom, with the same icon chip the
        // status row uses instead of a bare dot.
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        note.addView(chipIcon("ic_globe", if (tv.connected) GREEN else DIM), LinearLayout.LayoutParams(dp(22f), dp(22f)))
        note.addView(label(Strings.t("same_wifi"), 12f, DIM).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
        })
        root.addView(note, LinearLayout.LayoutParams(-1, -2))
        return root
    }


    private fun showMirror() {
        screen = "mirror"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        // Toolbar on BG: back arrow + title (no colored bar).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(16f), dp(8f))
        }
        val backBtn = FrameLayout(this)
        val backIv = icon("ic_back", 24f, ON_BG)
        backIv.layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
        backBtn.addView(backIv)
        backBtn.contentDescription = Strings.t("back")
        backBtn.layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
        backBtn.tap { showMain("mirroring") }
        bar.addView(backBtn)
        bar.addView(label(Strings.t("screen_mirroring"), 20f, ON_BG, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4f) }
        })
        root.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        body.addView(label(Strings.t("quality"), 12f, DIM, bold = true))
        // Segmented control: one PANEL pill, selected segment ACCENT.
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(PANEL, 24f, this@MainActivity)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        listOf("q_smooth" to 0, "q_standard" to 1, "q_sharp" to 2).forEach { (lk, q) ->
            val selected = q == mirrorQuality
            val chip = label(Strings.t(lk), 12f, if (selected) Color.WHITE else DIM, bold = selected, gravity = Gravity.CENTER).apply {
                if (selected) background = rounded(ACCENT, 20f, this@MainActivity)
                setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            }
            chip.tap { if (!selected) { mirrorQuality = q; showMirror() } }
            seg.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        body.addView(label(Strings.t("mirror_howto"), 14f, DIM).apply {
            setLineSpacing(dp(3f).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) }
        })

        val running = MirrorState.running && MirrorState.mode == "mirror"
        val startBtn = label(if (running) Strings.t("stop") else Strings.t("start"), 12f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rippleKey(if (running) DANGER else ACCENT, 16f)
        }
        startBtn.tap { if (running) stopMirroring() else startMirroring() }
        body.addView(startBtn, LinearLayout.LayoutParams(-1, dp(52f)).apply { topMargin = dp(24f) })

        if (running) {
            body.addView(label(Strings.t("mirror_running"), 12f, GREEN, gravity = Gravity.CENTER).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) }
            })
        }
        val mErr = MirrorState.error
        if (!running && mErr != null) {
            body.addView(label(
                mirrorErrorText(),
                12f, DANGER, gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) }
            })
        }
        val url = MirrorState.pageUrl()
        val urlChip = label(url, 12f, ON_BG, gravity = Gravity.CENTER).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(PANEL, 16f, this@MainActivity)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        urlChip.tap {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            toast(Strings.t("copied"))
        }
        body.addView(urlChip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.enterFx(0)
        root.applySystemInsets()
    }

    private fun startMirroring() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun stopMirroring() {
        MirrorState.running = false
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
        stopService(Intent(this, MirrorService::class.java))
        scope.launch {
            delay(450)
            if (screen == "mirror") showMirror()
        }
    }

    private fun pickMedia(kind: String) {
        val type = when (kind) {
            "image" -> "image/*"
            "audio" -> "audio/*"
            else -> "video/*"
        }
        pendingMediaKind = kind // set before the picker returns, not after launching it
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, Strings.t("tab_mirroring")), REQ_PICK)
        } catch (e: Exception) {
            toast(Strings.t("err_failed"))
        }
    }

    private var pendingMediaKind = "video"

    /**
     * Poll the mirror server until it answers (or ~4s pass) instead of
     * guessing with a fixed delay. Returns false when the service reported
     * a failure or the server never came up, so callers show the error
     * instead of opening a dead URL on the TV.
     */
    private suspend fun waitForMirrorServer(): Boolean {
        if (MirrorState.error != null) return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Only an address the TV can actually open counts as "up";
            // a loopback probe would prove nothing about TV reachability.
            val host = MirrorState.boundIp ?: MirrorState.localIp() ?: return@withContext false
            val url = java.net.URL("http://$host:${MirrorState.PORT}/?t=${MirrorState.token()}")
            repeat(20) {
                if (MirrorState.error != null) return@withContext false
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 300
                    conn.readTimeout = 300
                    // In media mode "/" redirects to the watch page; the
                    // redirect itself already proves the server is up.
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    if (code == 200 || code == 302) return@withContext true
                } catch (e: Exception) { /* server not up yet */ } finally {
                    conn?.disconnect()
                }
                delay(200)
            }
            MirrorState.running
        }
    }

    /** The mirror failure as a user message (port busy has its own string). */
    private fun mirrorErrorText(): String = when (MirrorState.error) {
        "port_busy" -> Strings.t("mirror_port_busy")
        "no_network" -> Strings.t("mirror_no_network")
        else -> Strings.t("err_failed")
    }

    /** Launch the TV browser and type the URL (same trick as the reference). */
    private fun openOnTv(url: String) {
        if (mode == "wifi" && tv.connected) {
            scope.launch {
                if (!tv.launchApp(SamsungTv.APP_BROWSER)) {
                    toast(Strings.t("err_failed"))
                    return@launch
                }
                delay(3500)
                if (!tv.sendText(url)) {
                    toast(Strings.t("err_failed"))
                    return@launch
                }
                delay(500)
                tv.click("KEY_ENTER")
            }
        } else {
            toast(url)
        }
    }

    // ---------------- Apps screen (reference: activity_apps) ----------------

    private fun showApps() {
        screen = "apps"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = iconBtn("ic_close", ON_BG, 48f, 24f, desc = Strings.t("close"))
        close.tap { showMain(returnTab) }
        top.addView(close)
        top.addView(label(Strings.t("apps"), 20f, ON_BG, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14f) }
        })
        root.addView(top)
        val status = label(Strings.t("apps_loading"), 12f, DIM, gravity = Gravity.CENTER)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        val grid = GridLayout(this).apply { columnCount = 3 }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.enterFx(0)
        root.applySystemInsets()

        if (!tv.connected) {
            status.text = Strings.t("err_failed")
            return
        }
        scope.launch {
            val apps = try {
                tv.getApps().filter { it.isVisible && it.appId.isNotEmpty() }
            } catch (e: IllegalStateException) {
                // Transport failure (timeout, disconnect, send failure):
                // that is not the same thing as a TV with no apps.
                status.text = Strings.t("err_failed")
                return@launch
            }
            if (apps.isEmpty()) {
                status.text = Strings.t("apps_empty")
                return@launch
            }
            status.visibility = View.GONE
            apps.forEach { app -> grid.addView(appCard(app)) }
        }
    }

    private fun appCard(app: TvApp): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rippleKey(PANEL, 16f)
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        }
        val iconBox = FrameLayout(this)
        val letter = label(app.name.firstOrNull()?.uppercase() ?: "?", 22f, ACCENT, bold = true, gravity = Gravity.CENTER)
        letter.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        iconBox.addView(letter)
        val img = ImageView(this).apply {
            visibility = View.INVISIBLE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        img.layoutParams = FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.CENTER)
        iconBox.addView(img)
        iconBox.layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
        card.addView(iconBox)
        card.addView(label(app.name, 12f, ON_BG, gravity = Gravity.CENTER).apply {
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) }
        })
        card.tap {
            tv.launchApp(app.appId, app.appType)
            toast(app.name)
        }
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        if (app.iconPath.isNotEmpty()) {
            val cached = iconCache[app.iconPath]
            if (cached != null) {
                img.setImageBitmap(cached)
                img.visibility = View.VISIBLE
                letter.visibility = View.INVISIBLE
            } else {
                scope.launch {
                    // Cap concurrent icon fetches: a large app list must not
                    // flood the TV with requests all at once.
                    val b64 = iconGate.withPermit { tv.getAppIcon(app.iconPath) } ?: return@launch
                    // Decode off the main thread; icons are cached for the session.
                    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    }
                    if (bmp != null) {
                        if (iconCache.size > 80) iconCache.clear()
                        iconCache[app.iconPath] = bmp
                        img.setImageBitmap(bmp)
                        img.visibility = View.VISIBLE
                        letter.visibility = View.INVISIBLE
                    }
                }
            }
        }
        return card
    }

    // ---------------- Input screen (reference: activity_input) ----------------

    private fun showInput() {
        screen = "input"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val close = iconBtn("ic_close", ON_BG, 48f, 24f, desc = Strings.t("close"))
        close.tap { showMain(returnTab) }
        top.addView(close)
        root.addView(top)

        val field = EditText(this).apply {
            hint = Strings.t("input_hint")
            setTextColor(ON_BG)
            setHintTextColor(DIM)
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        }
        fun doSend() {
            val text = field.text.toString()
            if (text.isBlank()) return
            if (mode == "wifi") {
                if (tv.sendText(text)) {
                    toast(Strings.t("sent"))
                    field.text.clear()
                } else {
                    toast(Strings.t("err_failed"))
                }
            } else {
                toast(Strings.t("ir_none"))
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                doSend(); true
            } else false
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 16f, this@MainActivity)
            setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
        }
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        val send = FrameLayout(this)
        send.background = oval(ACCENT)
        val sendIv = icon("ic_send", 24f, Color.WHITE)
        sendIv.layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
        send.addView(sendIv)
        send.contentDescription = Strings.t("send")
        send.layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
        send.tap { doSend() }
        bar.addView(send)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        root.addView(label(Strings.t("input_note"), 12f, DIM).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) }
        })
        setContentView(root)
        root.applySystemInsetsIme()
        field.requestFocus()
        field.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------- Settings tab (reference: fragment_settings) ----------------


    // ---------------- Settings tab: grouped section cards ----------------

    private fun settingRow2(title: String, value: String?, iconName: String, danger: Boolean = false, chevron: Boolean = true, subBelow: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(12f), dp(14f))
        }
        row.addView(icon(iconName, 24f, if (danger) DANGER else DIM))
        val titleTv = label(title, 16f, if (danger) DANGER else ON_BG).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (subBelow && value != null) {
            // Title over subtitle in one weighted column. The side-value
            // layout starved the TV row's title to a sliver ("[..") when
            // the value was a long "ip - model" string, because the value
            // label measured at full width before the title got the rest.
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(titleTv)
            col.addView(label(value, 12.5f, DIM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) })
        } else {
            row.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) })
            if (value != null) {
                row.addView(label(value, 12f, DIM).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
        if (chevron) {
            val iv = icon("ic_back", 20f, DIM)
            iv.rotation = 180f
            row.addView(iv, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        }
        return row
    }

    private fun sectionCard(rows: List<View>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, 16f, this@MainActivity)
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                card.addView(hairline(), LinearLayout.LayoutParams(-1, dp(1f)).apply {
                    leftMargin = dp(16f); rightMargin = dp(16f)
                })
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    private fun settingsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        fun addSection(headerKey: String?, card: View, first: Boolean) {
            if (headerKey != null) {
                root.addView(label(Strings.t(headerKey), 12f, DIM, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (first) 0 else dp(24f) }
                })
            }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (headerKey != null) dp(8f) else 0 })
        }

        var first = true
        if (mode == "ir") {
            val status = settingRow2(Strings.t("ir_mode"), null, "ic_ir", chevron = false)
            val exitIr = settingRow2(Strings.t("ir_exit"), null, "ic_exit")
            exitIr.tap {
                mode = "wifi"
                prefs.savedIp?.let { tv.connect(it) }
                showMain("remote")
            }
            addSection(null, sectionCard(listOf(status, exitIr)), true)
            first = false
        } else {
            val tvTitle = prefs.savedName ?: Strings.t("set_tv")
            val tvSub = listOfNotNull(prefs.savedIp, prefs.savedModel).joinToString(" · ")
            val tvRow = settingRow2(tvTitle, tvSub.ifEmpty { null }, "ic_tv", subBelow = true)
            tvRow.tap { tvDetailsDialog() }
            val change = settingRow2(Strings.t("set_change_tv"), null, "ic_search")
            change.tap { showSearch() }
            val wake = settingRow2(Strings.t("set_wake"), prefs.savedMac, "ic_power")
            wake.tap {
                val mac = prefs.savedMac
                if (mac == null) {
                    tvDetailsDialog()
                } else {
                    scope.launch {
                        val ok = WakeOnLan.wake(mac)
                        toast(if (ok) Strings.t("woke") else Strings.t("err_failed"))
                    }
                }
            }
            val irRow = settingRow2(Strings.t("set_ir"), null, "ic_ir")
            irRow.tap { enterIr() }
            val connRows = mutableListOf<View>(tvRow, change, wake, irRow)
            if (!tv.connected && prefs.savedIp != null) {
                // A failed connection used to strand the user here: the TV
                // row only opens details and there was no retry on this
                // screen at all, so the app just looked dead (Kamal's
                // report, 2026-10-23). Offer the retry, or the certificate
                // trust action when that is the failure, right here.
                val actRow = settingRow2(
                    if (tv.lastError == "cert") Strings.t("trust_new") else Strings.t("connect"),
                    null, "ic_play")
                actRow.tap {
                    val ip = prefs.savedIp
                    if (ip != null) {
                        if (tv.lastError == "cert") certTrustDialog(ip) else connectTo(ip)
                    }
                }
                connRows.add(1, actRow)
                // The diagnostics line used to sit here too; it now lives
                // only in Settings > Debug, so this card stays an action
                // card instead of an error dump.
            }
            addSection("section_connection", sectionCard(connRows), true)
            first = false
        }

        val langRow = settingRow2(Strings.t("set_language"), null, "ic_globe")
        langRow.tap {
            Strings.lang = if (Strings.lang == "ne") "en" else "ne"
            prefs.lang = Strings.lang
            showMain("settings")
        }
        addSection("section_preferences", sectionCard(listOf(langRow)), first)
        first = false

        val aboutRows = mutableListOf<View>()
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val layoutRow = settingRow2(Strings.t("set_layout"), null, "ic_tools")
            layoutRow.tap { layoutCheckDialog() }
            aboutRows.add(layoutRow)
        }
        val helpRow = settingRow2(Strings.t("help"), null, "ic_guide")
        helpRow.tap { showHelp() }
        aboutRows.add(helpRow)
        val debugRow = settingRow2(Strings.t("set_debug"), null, "ic_tools")
        debugRow.tap { debugDialog() }
        aboutRows.add(debugRow)
        val shareRow = settingRow2(Strings.t("set_share"), null, "ic_send")
        shareRow.tap {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "TV Remote — control your Samsung TV from your phone. Made by Kamal · $PORTFOLIO_URL")
            }
            startActivity(Intent.createChooser(intent, Strings.t("set_share")))
        }
        aboutRows.add(shareRow)
        val aboutRow = settingRow2(Strings.t("set_about").substringBefore("·").trim(), null, "ic_info", chevron = false)
        aboutRow.tap { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PORTFOLIO_URL))) }
        aboutRows.add(aboutRow)
        addSection("section_about", sectionCard(aboutRows), first)

        if (mode == "wifi" && prefs.savedIp != null) {
            // Connection-ending actions live in their own card, styled
            // like every other Settings row. They used to be two bare
            // centred red text labels floating under the About card,
            // which read as broken UI (Kamal's report, 2026-10-23).
            val actionRows = mutableListOf<View>()
            if (tv.connected) {
                val disc = settingRow2(Strings.t("set_disconnect"), null, "ic_exit", chevron = false)
                disc.tap {
                    tv.disconnect()
                    prefs.savedIp = null
                    prefs.savedName = null
                    prefs.savedModel = null
                    prefs.savedMac = null
                    showSearch()
                }
                actionRows.add(disc)
            }
            val forget = settingRow2(Strings.t("forget_tv"), null, "ic_close", danger = true, chevron = false)
            forget.tap {
                android.app.AlertDialog.Builder(this)
                    .setTitle(Strings.t("forget_tv"))
                    .setMessage(Strings.t("forget_tv_msg"))
                    .setPositiveButton(Strings.t("forget_tv")) { _, _ ->
                        prefs.savedIp?.let { ip ->
                            prefs.tokens.remove(ip)
                            prefs.certs.remove(ip)
                        }
                        iconCache.clear() // the forgotten TV's icons must not outlive it
                        tv.disconnect()
                        prefs.savedIp = null
                        prefs.savedName = null
                        prefs.savedModel = null
                        prefs.savedMac = null
                        showSearch()
                    }
                    .setNegativeButton(Strings.t("cancel"), null)
                    .show()
            }
            actionRows.add(forget)
            root.addView(sectionCard(actionRows), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24f) })
        }
        return root
    }


    // ---------------- Help screen ----------------

    private fun showHelp() {
        screen = "help"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = iconBtn("ic_close", ON_BG, 48f, 24f, desc = Strings.t("close"))
        close.tap { showMain("settings") }
        top.addView(close)
        top.addView(label(Strings.t("help"), 20f, ON_BG, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) }
        })
        root.addView(top)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label(Strings.t("help_intro"), 14f, DIM).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) }
        })
        fun section(iconName: String, titleKey: String, bodyKey: String, imageName: String?) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val circle = FrameLayout(this).apply { background = oval(PANEL) }
            val iv = icon(iconName, 24f, ACCENT)
            iv.layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
            circle.addView(iv)
            circle.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            header.addView(circle)
            header.addView(label(Strings.t(titleKey), 16f, ON_BG, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) }
            })
            body.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24f) })
            body.addView(label(Strings.t(bodyKey), 14f, DIM).apply {
                setLineSpacing(dp(3f).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) }
            })
            if (imageName != null) {
                val resId = resources.getIdentifier(imageName, "drawable", packageName)
                if (resId != 0) {
                    val img = ImageView(this).apply {
                        setImageResource(resId)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = rounded(PANEL, 16f, this@MainActivity)
                        clipToOutline = true
                    }
                    body.addView(img, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
                }
            }
        }
        section("ic_tv", "h_conn_t", "h_conn_b", "help_search")
        section("ic_dpad", "h_remote_t", "h_remote_b", "help_remote")
        section("ic_apps", "h_control_t", "h_control_b", "help_control")
        section("ic_move", "h_touch_t", "h_touch_b", "help_touch")
        section("ic_cast", "h_mirror_t", "h_mirror_b", "help_mirror")
        section("ic_ir", "h_ir_t", "h_ir_b", null)
        section("ic_gear", "h_set_t", "h_set_b", "help_settings")

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f) })
        setContentView(root)
        root.applySystemInsets()
        scroll.enterFx(0)
    }

    private fun enterIr() {
        if (!ir.hasBlaster()) {
            toast(Strings.t("ir_none"))
            return
        }
        mode = "ir"
        showMain("remote")
    }

    private fun tvDetailsDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(4f))
        }
        fun info(labelText: String, value: String?) {
            if (value == null) return
            col.addView(label(labelText, 12.5f, DIM))
            col.addView(label(value, 16f, ON_BG).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10f) }
            })
        }
        info(Strings.t("f_tv"), prefs.savedName)
        info(Strings.t("f_ip"), prefs.savedIp)
        info(Strings.t("f_model"), prefs.savedModel)
        col.addView(label(Strings.t("f_mac"), 12.5f, DIM))
        val macField = EditText(this).apply {
            setText(prefs.savedMac ?: "")
            hint = "AA:BB:CC:DD:EE:FF"
            isSingleLine = true
        }
        col.addView(macField)
        AlertDialog.Builder(this)
            .setTitle(Strings.t("set_tv"))
            .setView(col)
            .setPositiveButton(Strings.t("save")) { _, _ ->
                val v = macField.text.toString().trim()
                when {
                    v.isEmpty() -> prefs.savedMac = null
                    WakeOnLan.isValidMac(v) -> prefs.savedMac = v
                    else -> toast(Strings.t("err_failed")) // invalid MAC: keep the old one
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    private fun layoutCheckDialog() {
        val lines = mutableListOf<String>()
        fun walk(v: View, depth: Int) {
            if (lines.size >= 90 || depth > 5) return
            val name = v.javaClass.simpleName
            val vis = if (v.visibility == View.VISIBLE) "" else " GONE/INV"
            val txt = if (v is TextView) " \"${v.text.toString().take(14)}\"" else ""
            lines.add("${"  ".repeat(depth)}$name ${v.width}x${v.height} @${v.top}$vis$txt")
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(window.decorView, 0)
        val tvOut = TextView(this).apply {
            text = lines.joinToString("\n")
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val scroll = ScrollView(this).apply { addView(tvOut) }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("set_layout"))
            .setView(scroll)
            .setPositiveButton(Strings.t("close"), null)
            .show()
    }

    // ---------------- Search screen (reference: activity_search) ----------------

    private fun showSearch() {
        screen = "search"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24f), dp(12f), dp(24f), dp(16f))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = iconBtn("ic_close", ON_BG, 48f, 24f, desc = Strings.t("close"))
        close.tap { if (tv.connected) showMain(tab) else finish() }
        val rescan = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippleKey(PANEL, 24f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        rescan.addView(icon("ic_search", 16f, ACCENT))
        rescan.addView(label(Strings.t("rescan"), 12f, ACCENT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
        })
        rescan.tap { startScan() }
        top.addView(rescan)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { leftMargin = dp(8f) })
        root.addView(top)

        val logoBox = FrameLayout(this).apply { background = oval(PANEL) }
        val logoIv = icon("ic_tv", 50f, ACCENT)
        logoIv.layoutParams = FrameLayout.LayoutParams(dp(50f), dp(50f), Gravity.CENTER)
        logoBox.addView(logoIv)
        val logoWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        logoWrap.addView(logoBox, LinearLayout.LayoutParams(dp(96f), dp(96f)))
        root.addView(logoWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })

        searchTitle = label(Strings.t("searching"), 20f, ON_BG, bold = true, gravity = Gravity.CENTER)
        root.addView(searchTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })

        searchProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }
        val progWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        progWrap.addView(searchProgress, LinearLayout.LayoutParams(dp(30f), dp(30f)))
        root.addView(progWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        searchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(searchList) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f) })

        root.addView(label(Strings.t("same_wifi"), 12f, DIM, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }
        })
        val irBtn = label(Strings.t("use_ir_mode"), 12f, ACCENT, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(Color.TRANSPARENT, 24f, this@MainActivity, ACCENT)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        irBtn.tap { enterIr() }
        root.addView(irBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        val manual = label(Strings.t("manual_ip"), 12f, DIM, gravity = Gravity.CENTER).apply {
            setPadding(dp(10f), dp(12f), dp(10f), dp(4f))
        }
        manual.tap { manualIpDialog() }
        root.addView(manual)
        setContentView(root)
        root.applySystemInsets()
        startScan()
    }

    private fun startScan() {
        val gen = ++scanGen
        val list = searchList ?: return
        list.removeAllViews()
        searchTitle?.text = Strings.t("searching")
        searchProgress?.visibility = View.VISIBLE
        prefs.savedIp?.let { ip ->
            addDeviceCard(TvDevice(ip, prefs.savedName, prefs.savedModel, null), saved = true)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val lock = wifi.createMulticastLock("kamal-tv-remote")
        lock.setReferenceCounted(false)
        lock.acquire()
        scanJob?.cancel() // a rescan supersedes the previous discovery run
        scanJob = scope.launch {
            val found = try {
                SsdpDiscovery.discover(4000)
            } catch (e: Exception) {
                emptyList()
            } finally {
                try { lock.release() } catch (e: Exception) { }
            }
            if (gen != scanGen || screen != "search") return@launch
            found.filter { it.ip != prefs.savedIp }.forEach { addDeviceCard(it, saved = false) }
            searchProgress?.visibility = View.GONE
            searchTitle?.text = Strings.t("select_tv")
            if (list.childCount == 0) showSearchEmpty()
        }
    }

    private fun addDeviceCard(dev: TvDevice, saved: Boolean) {
        val list = searchList ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippleKey(PANEL, 16f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val iconBox = FrameLayout(this).apply { background = oval(PANEL2) }
        val iv = icon("ic_tv", 24f, ACCENT)
        iv.layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
        iconBox.addView(iv)
        card.addView(iconBox, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(13f) }
        }
        col.addView(label(dev.name ?: "Samsung TV", 16f, ON_BG, bold = true))
        col.addView(label(dev.ip + (dev.model?.let { " · $it" } ?: ""), 12f, DIM))
        card.addView(col)
        if (saved) {
            val chip = label(Strings.t("saved"), 11f, GREEN, bold = true, gravity = Gravity.CENTER).apply {
                background = rounded(PANEL2, 8f, this@MainActivity)
                setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            }
            card.addView(chip)
        }
        val arrow = icon("ic_back", 20f, DIM)
        arrow.rotation = 180f
        card.addView(arrow, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        card.tap {
            if (dev.ip != prefs.savedIp) prefs.savedMac = null // the old TV's MAC must not wake the new one
            prefs.savedIp = dev.ip
            prefs.savedName = dev.name
            prefs.savedModel = dev.model
            connectTo(dev.ip)
            showMain("remote")
        }
        card.enterFx(list.childCount)
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
    }

    private fun showSearchEmpty() {
        val list = searchList ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        }
        val circle = FrameLayout(this).apply { background = oval(PANEL) }
        val iv = icon("ic_tv", 30f, DIM)
        iv.layoutParams = FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.CENTER)
        circle.addView(iv)
        box.addView(circle, LinearLayout.LayoutParams(dp(72f), dp(72f)))
        box.addView(label(Strings.t("no_tvs"), 16f, ON_BG, bold = true, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) }
        })
        box.addView(label(Strings.t("no_tvs_hint"), 12f, DIM, gravity = Gravity.CENTER).apply {
            setLineSpacing(dp(3f).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }
        })
        box.enterFx(0)
        list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36f) })
    }

    private fun manualIpDialog() {
        val field = EditText(this).apply {
            hint = Strings.t("tv_ip_hint")
            setText(prefs.savedIp ?: "")
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("manual_ip"))
            .setView(field)
            .setPositiveButton(Strings.t("connect")) { _, _ ->
                val ip = field.text.toString().trim()
                if (isValidHostAddress(ip)) {
                    if (ip != prefs.savedIp) prefs.savedMac = null // the old TV's MAC must not wake the new one
                    prefs.savedIp = ip
                    prefs.savedName = null
                    prefs.savedModel = null
                    connectTo(ip)
                    showMain("remote")
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    // ---------------- activity results ----------------

    @Deprecated("activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, MirrorService::class.java).apply {
                        action = MirrorService.ACTION_MIRROR
                        putExtra(MirrorService.EXTRA_CODE, resultCode)
                        putExtra(MirrorService.EXTRA_DATA, data)
                        putExtra(MirrorService.EXTRA_QUALITY, mirrorQuality)
                    }
                    MirrorState.error = null // a stale error from a previous attempt must not fail this one
                    startForegroundService(intent)
                    scope.launch {
                        if (waitForMirrorServer()) {
                            openOnTv(MirrorState.pageUrl())
                            if (screen == "mirror") showMirror()
                        } else {
                            toast(mirrorErrorText())
                            showMirror() // the screen that shows the error
                        }
                    }
                }
            }
            REQ_PICK -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) { /* temporary grant still works in-process */ }
                    val intent = Intent(this, MirrorService::class.java).apply {
                        action = MirrorService.ACTION_MEDIA
                        putExtra(MirrorService.EXTRA_URI, uri.toString())
                        putExtra(MirrorService.EXTRA_KIND, pendingMediaKind)
                    }
                    MirrorState.error = null // a stale error from a previous attempt must not fail this one
                    startForegroundService(intent)
                    scope.launch {
                        if (waitForMirrorServer()) {
                            openOnTv(MirrorState.watchUrl())
                            toast(Strings.t("media_opening") + " " + MirrorState.watchUrl())
                            showMain("mirroring") // rebuild the tab so the stop row appears now, not on re-entry
                        } else {
                            toast(mirrorErrorText())
                            showMain("mirroring")
                        }
                    }
                }
            }
            REQ_VOICE -> {
                if (resultCode == RESULT_OK) {
                    val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank() && mode == "wifi") {
                        toast(if (tv.sendText(spoken)) Strings.t("sent") else Strings.t("err_failed"))
                    }
                }
            }
        }
    }
}
