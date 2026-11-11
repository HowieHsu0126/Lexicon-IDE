package dev.lexicon.ide.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Test-only screen ownership check. It never dismisses or authorizes a system dialog. */
internal class SpeedFocusWindowGuard(private val packageName: String, private val report: JSONObject) : AutoCloseable {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val originalFlags = requireNotNull(automation.serviceInfo).flags
    private var safeContent: Rect? = null
    init {
        val info = requireNotNull(automation.serviceInfo)
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        report.put("windowChecks", JSONArray())
    }

    /** Called on the existing Activity's main thread after its first real layout. */
    fun bindSafeContent(activity: Activity) {
        val decor = activity.window.decorView
        val insets = requireNotNull(decor.rootWindowInsets)
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val position = IntArray(2).also(decor::getLocationOnScreen)
        check(decor.width > insets.left + insets.right && decor.height > insets.top + insets.bottom)
        safeContent = Rect(position[0] + insets.left, position[1] + insets.top,
            position[0] + decor.width - insets.right, position[1] + decor.height - insets.bottom)
        report.put("windowGuardSafeContentScreenPx", bounds(requireNotNull(safeContent)))
            .put("windowGuardInsetsPx", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
            .put("windowGuardDecorScreenPx", JSONArray(listOf(position[0], position[1], decor.width, decor.height)))
    }

    fun check(stage: String) {
        val activePackage = automation.rootInActiveWindow?.packageName?.toString()
        val windows = automation.windows.orEmpty()
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            it.root?.packageName?.toString() == packageName }
        val appWindow = appWindows.maxByOrNull { it.layer }
        val appBounds = Rect().also { appWindow?.getBoundsInScreen(it) }
        val safe = requireNotNull(safeContent) { "Measure the Activity's safe content before interaction" }
        val offenders = windows.filter { window ->
            val owner = window.root?.packageName?.toString()
            val rect = Rect().also(window::getBoundsInScreen)
            val overlap = Rect(rect).apply { if (!intersect(safe)) setEmpty() }
            owner != packageName && (window.isActive || window.isFocused ||
                overlap.width() > 1 && overlap.height() > 1 &&
                    (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        appWindow != null && window.layer > appWindow.layer))
        }
        val clean = activePackage == packageName && appWindow != null && !appBounds.isEmpty && offenders.isEmpty()
        val receipt = JSONObject().put("stage", stage).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("activePackage", activePackage ?: JSONObject.NULL).put("appOwnsVisibleScreen", clean)
            .put("safeContentScreenPx", bounds(safe))
            .put("windows", JSONArray().apply { windows.forEach { window ->
                val rect = Rect().also(window::getBoundsInScreen)
                put(JSONObject().put("package", window.root?.packageName?.toString() ?: JSONObject.NULL)
                    .put("type", window.type).put("layer", window.layer).put("active", window.isActive)
                    .put("focused", window.isFocused).put("bounds", bounds(rect)))
            } })
        report.getJSONArray("windowChecks").put(receipt)
        if (!clean) {
            report.put("systemWindowBlocked", true).put("systemWindowBlockedStage", stage)
            throw SystemWindowBlocked()
        }
    }

    fun safeContentHeightPx(): Int = requireNotNull(safeContent).height()

    override fun close() {
        val info = requireNotNull(automation.serviceInfo)
        info.flags = originalFlags
        automation.serviceInfo = info
    }

    private fun bounds(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    class SystemWindowBlocked : IllegalStateException("A system or foreign window obscures the application; trial evidence is invalid")
}
