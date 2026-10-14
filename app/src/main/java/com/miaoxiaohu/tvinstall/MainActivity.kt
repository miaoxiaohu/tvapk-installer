package com.miaoxiaohu.tvinstall

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.miaoxiaohu.tvinstall.qr.QrEncoder

/**
 * The TV-side screen: shows the URL and QR to scan, and doubles as a local app manager
 * so the box is still useful when nobody is pushing anything from a phone.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_INSTALL_APK = "install_apk"
        const val EXTRA_UNINSTALL_PKG = "uninstall_pkg"
        private const val REQ_POST_NOTIFICATIONS = 101
        private const val QR_TARGET_PX = 512
    }

    private lateinit var config: AppConfig
    private lateinit var repository: ApkRepository

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvPort: TextView
    private lateinit var hintText: TextView
    private lateinit var qrCode: ImageView
    private lateinit var btnToggle: Button
    private lateinit var btnRegenPin: Button
    private lateinit var btnGrant: Button
    private lateinit var btnClear: Button
    private lateinit var pendingContainer: LinearLayout
    private lateinit var installedContainer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pendingCount: TextView
    private lateinit var installedCount: TextView

    private val stateListener: () -> Unit = { runOnUiThread { refresh() } }

    /**
     * Rebuilding a list is expensive (per-APK getPackageArchiveInfo, per-app getApplicationLabel)
     * and it also throws away the D-pad focus. Both lists are therefore only re-rendered when
     * their contents actually changed since the last pass.
     */
    private var pendingSignature: String? = null
    private var installedSignature: String? = null

    /** Action buttons per pending / installed row, collected while rendering for [linkFocusChain]. */
    private var pendingStops: List<List<View>> = emptyList()
    private var installedStops: List<List<View>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        repository = ApkRepository(this)
        setContentView(R.layout.activity_main)
        bindViews()

        btnToggle.setOnClickListener { toggleService() }
        btnRegenPin.setOnClickListener { regeneratePin() }
        btnGrant.setOnClickListener { grantInstallPermission() }
        btnClear.setOnClickListener { clearPending() }
        tvPort.setOnClickListener { promptPort() }

        askForNotificationPermission()
        if (ServerState.status != ServerState.RUNNING) toggleServiceOn()
        handleIntent(intent)
        refresh()

        // The layout also marks btnToggle as focusedByDefault, but do not rely on it alone:
        // this retry ladder covers the case where the framework does not honour it.
        focusLater(btnToggle)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        // The screen is useless once it sleeps; the whole point is that you read it from the sofa.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ServerState.addListener(stateListener)
        InstallEvents.addListener(stateListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        ServerState.removeListener(stateListener)
        InstallEvents.removeListener(stateListener)
        super.onStop()
    }

    // ------------------------------------------------------------- view wiring

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        tvAddress = findViewById(R.id.tvAddress)
        tvUrl = findViewById(R.id.tvUrl)
        tvPin = findViewById(R.id.tvPin)
        tvPort = findViewById(R.id.tvPort)
        hintText = findViewById(R.id.hintText)
        qrCode = findViewById(R.id.qrCode)
        btnToggle = findViewById(R.id.btnToggle)
        btnRegenPin = findViewById(R.id.btnRegenPin)
        btnGrant = findViewById(R.id.btnGrant)
        btnClear = findViewById(R.id.btnClear)
        pendingContainer = findViewById(R.id.pendingContainer)
        installedContainer = findViewById(R.id.installedContainer)
        scroll = findViewById(R.id.scroll)
        pendingCount = findViewById(R.id.pendingCount)
        installedCount = findViewById(R.id.installedCount)
    }

    private fun refresh() {
        val running = ServerState.status == ServerState.RUNNING
        val address = NetUtil.primaryAddress() ?: getString(R.string.status_stopped)
        val port = if (running) ServerState.port else config.port

        statusText.text = when (ServerState.status) {
            ServerState.RUNNING -> getString(R.string.status_running)
            ServerState.STARTING -> getString(R.string.status_starting)
            ServerState.FAILED -> getString(R.string.status_error)
            else -> getString(R.string.status_stopped)
        }
        statusDot.background?.setTint(
            color(
                when (ServerState.status) {
                    ServerState.RUNNING -> R.color.success
                    ServerState.FAILED -> R.color.danger
                    ServerState.STARTING -> R.color.warning
                    else -> R.color.text_faint
                }
            )
        )

        tvAddress.text = "$address:$port"
        tvUrl.text = "http://$address:$port"
        tvPin.text = if (config.pinEnabled) config.pin else "已关闭"
        tvPort.text = "$port${if (running) " · 运行中" else " · 未启动"}"

        btnToggle.text = if (running) getString(R.string.action_stop) else getString(R.string.action_start)
        btnRegenPin.text = if (config.pinEnabled) getString(R.string.action_regen_pin)
        else getString(R.string.action_enable_pin)

        val hasPermission = ApkInstaller.hasInstallPermission(this)
        btnGrant.visibility = if (hasPermission) View.GONE else View.VISIBLE

        hintText.text = when {
            ServerState.status == ServerState.FAILED && ServerState.error != null ->
                ServerState.error!!
            !hasPermission -> getString(R.string.hint_grant_needed)
            !NetUtil.hasNetwork(this) -> "电视当前没有联网，局域网设备连不上"
            else -> getString(R.string.hint_tv_ready)
        }

        updateQr("http://$address:$port/?k=${config.pin}")
        renderPending()
        renderInstalled()
        buildFocusStops()
    }

    /**
     * The vertical D-pad chain, top to bottom. One entry is one stop: the buttons that sit
     * side by side at the same height.
     *
     * Built by hand because both shortcuts are unavailable. The framework's geometric search
     * cannot be trusted here - the row actions sit hard against the right edge while btnClear
     * is a narrow left-aligned control, so the downward beam cast from 删除 never covers it and
     * the button is unreachable with the remote. Rows are also created at runtime, so
     * nextFocusDown cannot be declared in XML, and View.setNextFocusDown(View) is not part of
     * the public SDK (only the *Id(int) XML variants are in android.jar).
     */
    private var focusStops: List<List<View>> = emptyList()

    private fun buildFocusStops() {
        focusStops = (listOf(listOf(btnToggle, btnRegenPin, btnGrant)) +
            pendingStops +
            listOf(listOf(btnClear)) +
            installedStops)
            .map { stop -> stop.filter { it.isFocusable } }
            .filter { it.isNotEmpty() }
    }

    /** Moves focus one stop up or down. False means "not ours", so the default handling applies. */
    private fun moveFocusVertically(down: Boolean): Boolean {
        val chain = focusStops
        if (chain.size < 2) return false
        val focused = findViewById<View>(android.R.id.content)?.findFocus() ?: return false
        val index = chain.indexOfFirst { stop -> stop.any { it === focused } }
        if (index < 0) return false
        val target = chain.getOrNull(if (down) index + 1 else index - 1) ?: return false
        return target.firstOrNull { it.isShown }?.requestFocus() ?: false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
        ) {
            if (moveFocusVertically(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ qr

    private fun updateQr(url: String) {
        try {
            val modules = QrEncoder.encode(url, quietZone = 3)
            val size = modules.size
            // Integer scaling keeps the module edges hard instead of blurred.
            val scale = (QR_TARGET_PX / size).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size * scale, size * scale, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) {
                val rowColor = if (modules[y * size]) Color.BLACK else Color.WHITE
                for (x in 0 until size) {
                    val color = if (modules[y * size + x]) Color.BLACK else rowColor
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            bitmap.setPixel(x * scale + dx, y * scale + dy, color)
                        }
                    }
                }
            }
            qrCode.setImageBitmap(bitmap)
            qrCode.visibility = View.VISIBLE
        } catch (t: Throwable) {
            qrCode.visibility = View.GONE
        }
    }

    // --------------------------------------------------------------- lists

    // Rebuilding a list destroys whatever the D-pad was pointing at. Remember the row key
    // before the rebuild and put focus back afterwards, otherwise every upload or install
    // broadcast makes the remote look dead.
    private fun focusedKey(): String? = (currentFocus?.tag as? String)

    /**
     * removeAllViews() empties the ScrollView, so its scroll offset collapses to 0 and the
     * content lands back on screen from the top - even though focus is correctly restored.
     * With a remote in your hand that reads as the page randomly jumping. Snapshot the
     * offset and put it back once the rows exist again.
     */
    private fun withScrollKept(body: () -> Unit) {
        val saved = scroll.scrollY
        body()
        if (saved > 0) scroll.scrollTo(0, saved)
    }

    /**
     * Lights the row while any of its buttons holds focus, so the user never loses
     * track of which row they are on. The row itself is not focusable - every action is
     * a real button, so left/right walks them and up/down walks the rows.
     */
    private fun bindRowFocusHighlight(row: View, vararg actions: View) {
        fun apply(hasFocus: Boolean) {
            row.setBackgroundResource(if (hasFocus) R.drawable.row_focus_ring else R.drawable.row_bg)
        }
        for (action in actions) {
            action.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> apply(hasFocus) }
        }
        // Coming back onto any button of the row must restore the lit row, not a dark one.
        row.setOnFocusChangeListener(null)
    }

    private fun restoreFocus(container: LinearLayout, key: String?) {
        if (key == null) return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag == key) {
                focusLater(child)
                return
            }
            // The action buttons inside a row carry their own suffixed keys.
            val group = child as? ViewGroup ?: continue
            for (j in 0 until group.childCount) {
                val inner = group.getChildAt(j)
                if (inner.tag == key) {
                    focusLater(inner)
                    return
                }
            }
        }
    }

    /**
     * Asks a view to take focus, retrying across several frames.
     *
     * requestFocus() on a view that has not been measured/laid out is a SILENT no-op -
     * it returns false and nothing throws. View.post() is not a reliable escape hatch
     * either: the runnable can run before the first traversal. When both miss, the
     * default focus search falls through to the first focusable view in document order,
     * which on this screen is the port field rather than the start button.
     */
    private fun focusLater(view: View) {
        for (delay in longArrayOf(0L, 32L, 120L, 400L)) {
            view.postDelayed({
                if (!view.isFocused) view.requestFocus()
            }, delay)
        }
    }

    private fun renderPending() {
        val entries = repository.listPending()
        // getPackageArchiveInfo runs per file; skip the whole rebuild when nothing moved.
        // installedVersion (not a boolean) so an on-box upgrade refreshes the badge too.
        val signature = entries.joinToString("|") { "${it.fileName}:${it.sizeBytes}:${it.installedVersion}" }
        if (signature == pendingSignature) return
        pendingSignature = signature

        val key = focusedKey()
        pendingCount.text = if (entries.isEmpty()) "空" else "${entries.size} 个"

        withScrollKept {
            pendingContainer.removeAllViews()
            pendingStops = emptyList()

            if (entries.isEmpty()) {
                pendingContainer.addView(placeholder("从手机扫码推送到这里"))
                return@withScrollKept
            }

            val inflater = LayoutInflater.from(this)
            val stops = ArrayList<List<View>>(entries.size)
            for (entry in entries) {
                val rowKey = "apk:${entry.fileName}"
                val row = inflater.inflate(R.layout.item_apk, pendingContainer, false)
                row.tag = rowKey
                row.findViewById<TextView>(R.id.title).text = entry.label
                row.findViewById<TextView>(R.id.subtitle).text = buildString {
                    append(entry.versionName ?: entry.fileName)
                    append(" · ").append(formatSize(entry.sizeBytes))
                    entry.packageName?.let { append(" · ").append(it) }
                }
                val badge = row.findViewById<TextView>(R.id.badge)
                if (entry.parseError != null) {
                    badge.text = "无法解析"
                    badge.setTextColor(color(R.color.warning))
                    badge.visibility = View.VISIBLE
                } else if (entry.installed) {
                    badge.text = "已装 " + (entry.installedVersion ?: "")
                    badge.setTextColor(color(R.color.success))
                    badge.visibility = View.VISIBLE
                }

                val installButton = row.findViewById<Button>(R.id.btnInstall)
                installButton.tag = "$rowKey:install"
                installButton.setOnClickListener { installFile(entry.fileName) }

                val deleteButton = row.findViewById<Button>(R.id.btnDelete)
                deleteButton.tag = "$rowKey:del"
                deleteButton.setOnClickListener {
                    repository.delete(entry.fileName)
                    toast(getString(R.string.toast_deleted, entry.fileName))
                    refresh()
                }
                bindRowFocusHighlight(row, installButton, deleteButton)
                pendingContainer.addView(row)
                stops.add(listOf(installButton, deleteButton))
            }
            pendingStops = stops
            restoreFocus(pendingContainer, key)
        }
    }

    private fun renderInstalled() {
        val apps = repository.installedApps(includeSystem = false)
        // getInstalledPackages + one loadLabel per app is heavy on a cheap TV SoC.
        val signature = apps.joinToString("|") { "${it.packageName}:${it.versionCode}" }
        if (signature == installedSignature) return
        installedSignature = signature

        val key = focusedKey()
        installedCount.text = if (apps.isEmpty()) "空" else "${apps.size} 个"

        withScrollKept {
            installedContainer.removeAllViews()
            installedStops = emptyList()

            if (apps.isEmpty()) {
                installedContainer.addView(placeholder("还没有装任何第三方应用"))
                return@withScrollKept
            }

            val inflater = LayoutInflater.from(this)
            val stops = ArrayList<List<View>>(apps.size)
            for (app in apps) {
                val rowKey = "app:${app.packageName}"
                val row = inflater.inflate(R.layout.item_app, installedContainer, false)
                row.tag = rowKey
                row.findViewById<TextView>(R.id.title).text = app.label
                row.findViewById<TextView>(R.id.subtitle).text = buildString {
                    if (app.versionName.isNotBlank()) append(app.versionName) else append("—")
                    append(" · ").append(app.packageName)
                }
                row.findViewById<ImageView>(R.id.icon).setImageDrawable(appIcon(app.packageName))

                val launchButton = row.findViewById<Button>(R.id.btnLaunch)
                launchButton.tag = "$rowKey:launch"
                launchButton.setOnClickListener {
                    val launch = repository.launchIntent(app.packageName)
                    if (launch != null) {
                        try {
                            startActivity(launch)
                        } catch (ignored: Exception) {
                            toast("无法打开 ${app.label}")
                        }
                    } else {
                        toast("${app.label} 没有可启动的界面")
                    }
                }

                val uninstallButton = row.findViewById<Button>(R.id.btnUninstall)
                uninstallButton.tag = "$rowKey:uninstall"
                uninstallButton.setOnClickListener {
                    if (repository.uninstall(this@MainActivity, app.packageName)) {
                        refresh()
                    } else {
                        toast("无法卸载 ${app.label}")
                    }
                }
                bindRowFocusHighlight(row, launchButton, uninstallButton)
                installedContainer.addView(row)
                stops.add(listOf(launchButton, uninstallButton))
            }
            installedStops = stops
            restoreFocus(installedContainer, key)
        }
    }

    private fun placeholder(text: String): View {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.text_faint))
            textSize = 15f
            setPadding(8, 18, 8, 18)
        }
        return view
    }

    private fun appIcon(packageName: String): android.graphics.drawable.Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (ignored: Exception) {
        null
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // --------------------------------------------------------------- actions

    private fun toggleService() {
        if (ServerState.status == ServerState.RUNNING) {
            startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
            // The service clears the state itself; claiming STARTING here would flash
            // "正在启动" right after the user pressed 关闭服务.
            ServerState.update(ServerState.STOPPED, 0, null)
        } else {
            toggleServiceOn()
            ServerState.update(ServerState.STARTING, config.port, null)
        }
        refresh()
    }

    private fun toggleServiceOn() {
        try {
            startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_START))
        } catch (ignored: Exception) {
            toast("无法启动服务")
        }
    }

    private fun regeneratePin() {
        if (!config.pinEnabled) {
            config.pinEnabled = true
            toast(getString(R.string.toast_pin_on, config.pin))
        } else {
            val pin = config.newPin()
            toast(getString(R.string.toast_pin_new, pin))
        }
        refresh()
    }

    private fun grantInstallPermission() {
        if (!ApkInstaller.openPermissionSettings(this)) {
            toast("这台电视没有提供「安装未知应用」设置页，请在系统设置里手动允许")
        }
    }

    private fun clearPending() {
        val pending = repository.listPending()
        if (pending.isEmpty()) {
            toast(getString(R.string.toast_no_apk))
            return
        }
        // Same reasoning as deleting "uninstall everything": one stray OK press used to
        // throw away every queued APK with no way to get them back.
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_clear))
            .setMessage("将删除 ${pending.size} 个待安装的 APK，确定吗？")
            .setPositiveButton("确定") { _, _ ->
                val removed = repository.deleteAll()
                toast("${getString(R.string.toast_cleared)}（$removed）")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptPort() {
        val input = EditText(this).apply {
            setText(config.port.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("服务端口")
            .setMessage("1024 - 65535")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val port = input.text.toString().trim().toIntOrNull()
                if (port == null || port < AppConfig.MIN_PORT || port > AppConfig.MAX_PORT) {
                    toast("端口不合法")
                } else {
                    config.port = port
                    toggleService()
                    toast("端口已改为 $port")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------------------------------------------------------------- intents

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val installName = intent.getStringExtra(EXTRA_INSTALL_APK)
        if (!installName.isNullOrBlank()) {
            intent.removeExtra(EXTRA_INSTALL_APK)
            installFile(installName)
        }

        val uninstallPkg = intent.getStringExtra(EXTRA_UNINSTALL_PKG)
        if (!uninstallPkg.isNullOrBlank()) {
            intent.removeExtra(EXTRA_UNINSTALL_PKG)
            repository.uninstall(this, uninstallPkg)
        }
    }

    private fun installFile(fileName: String) {
        val file = repository.fileFor(fileName)
        if (file == null) {
            toast("找不到 $fileName")
            return
        }
        if (!ApkInstaller.hasInstallPermission(this)) {
            toast(getString(R.string.hint_grant_needed))
            ApkInstaller.openPermissionSettings(this)
            return
        }
        when (val outcome = ApkInstaller.install(this, file)) {
            is ApkInstaller.Outcome.Launched -> Unit
            is ApkInstaller.Outcome.NeedsPermission -> {
                toast(getString(R.string.hint_grant_needed))
                ApkInstaller.openPermissionSettings(this)
            }
            is ApkInstaller.Outcome.Failed -> toast(outcome.reason)
        }
    }

    // ------------------------------------------------------------ permissions

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        } catch (ignored: Exception) {
            // Not fatal: the service still runs, the notification is just hidden.
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Theme-aware colour lookup that still compiles against minSdk 21. */
    private fun color(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) resources.getColor(id, theme)
        else @Suppress("DEPRECATION") resources.getColor(id)
}