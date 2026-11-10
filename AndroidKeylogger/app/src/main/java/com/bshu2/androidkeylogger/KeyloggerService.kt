package com.bshu2.androidkeylogger

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Consent-gated capture service. Refuses to log without recorded consent,
 * never records password fields, honours the per-app blocklist and the
 * global pause switch, and keeps an ongoing visible notification.
 */
class KeyloggerService : AccessibilityService() {

    private var io: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var catchingUp = false

    private val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val deviceName = safe(Build.MODEL)
    private val appLabels = HashMap<String, String>()
    private val lastTextByWindow = HashMap<String, String>()

    @Synchronized
    private fun io(): ExecutorService {
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()
        return io
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Starting service")
        if (!Prefs.hasConsent(this)) {
            Log.e(TAG, "no consent; capture stays off")
            MonitoringNotification.cancel(this)
            return
        }
        AlarmScheduler.schedule(this)
        MonitoringNotification.update(this)
        io().execute { UploadPipeline.run(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs.captureEnabled(this)) return

        val action = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
            else -> return
        }

        var source: AccessibilityNodeInfo? = null
        try {
            source = event.source
            val pkg = event.packageName?.toString() ?: "unknown"
            if (pkg in Prefs.blocklist(this)) return
            if (isSensitiveField(source)) return

            val app = appLabelOf(pkg)
            val title = windowTitleOf(event, source)
            val data = eventData(event, source, action, app, title) ?: return

            val ts = df.format(Calendar.getInstance().time)
            val line = "$ts | $deviceName | $app | $title | $action | $data\n"
            val archive = "keylog_${dayStamp()}.txt"
            io().execute {
                val dir = UploadPipeline.logDir(this)
                UploadPipeline.append(java.io.File(dir, archive), line)
                UploadPipeline.append(java.io.File(dir, UploadPipeline.PENDING_FILE), line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "event handling failed", e)
        } finally {
            recycle(source)
        }
        maybeCatchUp()
    }

    /** True for password fields and any node that asks to be masked. */
    private fun isSensitiveField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            if (node.isPassword) return true
            val type = node.inputType
            val mask = InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            return type and mask != 0
        } catch (e: Exception) {
            return true // fail closed: if unsure, do not log it
        }
    }

    private fun maybeCatchUp() {
        if (catchingUp) return
        val last = Prefs.lastRunAt(this)
        if (last > 0 && System.currentTimeMillis() - last < Prefs.intervalMs(this)) return
        catchingUp = true
        io().execute {
            try {
                UploadPipeline.run(this)
            } catch (e: Exception) {
                Log.e(TAG, "catch-up failed", e)
            } finally {
                catchingUp = false
            }
        }
    }

    private fun eventData(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?,
        action: String,
        app: String,
        title: String
    ): String? {
        val clean: String
        if (action == "TEXT") {
            var full = joinText(event)
            if (full.isEmpty()) full = describeNode(source)
            val key = "$app|$title|${widgetKey(event, source)}"
            var delta = full
            synchronized(lastTextByWindow) {
                val previous = lastTextByWindow[key]
                if (full == previous) return null
                if (previous != null && full.startsWith(previous)) {
                    delta = full.substring(previous.length)
                } else if (previous != null && previous.startsWith(full)) {
                    delta = "<backspace>"
                }
                lastTextByWindow[key] = full
                if (lastTextByWindow.size > MAX_TRACKED_WINDOWS) {
                    lastTextByWindow.clear()
                    lastTextByWindow[key] = full
                }
            }
            clean = sanitize(delta)
        } else {
            clean = sanitize(describeNode(source))
        }
        return if (clean == "-") null else clean
    }

    private fun widgetKey(event: AccessibilityEvent, source: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        sb.append(event.windowId).append('/')
        val cls = event.className
        sb.append(cls ?: "-")
        if (source != null) {
            try {
                source.viewIdResourceName?.let { sb.append('/').append(it) }
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    private fun joinText(event: AccessibilityEvent): String {
        val parts = event.text ?: return ""
        val sb = StringBuilder()
        parts.forEach { p ->
            if (p == null) return@forEach
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
        }
        return sb.toString()
    }

    private fun describeNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = node.text
        if (!text.isNullOrEmpty()) return text.toString()
        val desc = node.contentDescription
        if (!desc.isNullOrEmpty()) return desc.toString()
        return ""
    }

    private fun appLabelOf(pkg: String): String {
        synchronized(appLabels) { appLabels[pkg]?.let { return it } }
        var label = pkg
        try {
            val pm = packageManager
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
        }
        label = sanitize(label)
        synchronized(appLabels) {
            if (appLabels.size > MAX_TRACKED_WINDOWS) appLabels.clear()
            appLabels[pkg] = label
        }
        return label
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        node?.recycle()
    }

    @Suppress("DEPRECATION")
    private fun recycle(win: AccessibilityWindowInfo?) {
        win?.recycle()
    }

    private fun windowTitleOf(event: AccessibilityEvent, source: AccessibilityNodeInfo?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && source != null) {
            val win = source.window
            if (win != null) {
                try {
                    val t = win.title
                    if (!t.isNullOrEmpty()) return sanitize(t.toString())
                } finally {
                    recycle(win)
                }
            }
        }
        val cls = event.className
        return if (!cls.isNullOrEmpty()) sanitize(cls.toString()) else "-"
    }

    private fun sanitize(s: String?): String {
        if (s == null) return "-"
        var out = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim()
        if (out.length > MAX_DATA_CHARS) out = out.substring(0, MAX_DATA_CHARS) + "..."
        return if (out.isEmpty()) "-" else out
    }

    private fun safe(s: String?): String =
        if (s.isNullOrEmpty()) "unknown" else s.replace('|', '/')

    private fun dayStamp(): String = dayFormat.format(Date())

    override fun onInterrupt() {}

    override fun onDestroy() {
        val closing = io()
        closing.execute { UploadPipeline.rotatePending(this) }
        closing.shutdown()
        Thread({
            try {
                if (!closing.awaitTermination(30, TimeUnit.SECONDS)) closing.shutdownNow()
            } catch (_: InterruptedException) {
                closing.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }, "keylogger-shutdown").start()
        super.onDestroy()
    }

    companion object {
        private const val TAG = UploadPipeline.TAG
        private const val MAX_DATA_CHARS = 500
        private const val MAX_TRACKED_WINDOWS = 50
    }
}
