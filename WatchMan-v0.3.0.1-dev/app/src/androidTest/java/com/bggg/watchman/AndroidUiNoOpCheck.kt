package com.bggg.watchman

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Real Android setters and existing controls; does not replace the production view hierarchy. */
object AndroidUiNoOpCheck {
    fun run(instrumentation: Instrumentation, activity: Activity): String {
        instrumentation.runOnMainSync {
            val type = MainActivity::class.java
            val layout = type.getDeclaredMethod("applyNavigationLayout").apply { isAccessible = true }
            val textSetter = type.getDeclaredMethod("setTextIfChanged", TextView::class.java, String::class.java)
                .apply { isAccessible = true }
            val marginSetter = type.getDeclaredMethod("setBottomMarginPixels", View::class.java, Integer.TYPE)
                .apply { isAccessible = true }
            fun control(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(activity) as TextView
            layout.invoke(activity)
            val tools = control("toolsButton")
            val routeMode = control("routeModeButton")
            check(tools.text.toString() == "TOOLS · PREVIEW") { "Preview tools label changed" }
            val expectedMode = routeMode.text.toString()
            val toolsWatcher = Counter()
            val modeWatcher = Counter()
            tools.addTextChangedListener(toolsWatcher)
            routeMode.addTextChangedListener(modeWatcher)
            try {
                repeat(5) { layout.invoke(activity) }
                check(toolsWatcher.changes == 0 && modeWatcher.changes == 0) {
                    "Unchanged layout rewrote controls: tools=${toolsWatcher.changes}, mode=${modeWatcher.changes}"
                }
                check(tools.text.toString() == "TOOLS · PREVIEW" && routeMode.text.toString() == expectedMode)
            } finally {
                tools.removeTextChangedListener(toolsWatcher)
                routeMode.removeTextChangedListener(modeWatcher)
            }

            val text = TextView(activity).apply { this.text = "unchanged" }
            val textWatcher = Counter()
            text.addTextChangedListener(textWatcher)
            try {
                repeat(5) { textSetter.invoke(activity, text, "unchanged") }
                check(textWatcher.changes == 0) { "Identical text dispatched callbacks" }
                textSetter.invoke(activity, text, "changed")
                check(textWatcher.changes == 1 && text.text.toString() == "changed") { "Changed text did not propagate once" }
                textSetter.invoke(activity, text, "")
                check(textWatcher.changes == 2 && text.text.isEmpty()) { "Empty replacement did not propagate" }
            } finally { text.removeTextChangedListener(textWatcher) }

            val view = RequestCountingView(activity)
            view.layoutParams = FrameLayout.LayoutParams(10, 10).apply { bottomMargin = 10 }
            view.requests = 0
            repeat(5) { marginSetter.invoke(activity, view, 10) }
            check(view.requests == 0) { "Identical margin requested layout" }
            marginSetter.invoke(activity, view, 11)
            check(view.requests == 1 && (view.layoutParams as FrameLayout.LayoutParams).bottomMargin == 11) {
                "Changed margin did not propagate once"
            }
        }
        return "Android no-op UI PASS: repeated preview controls/text/margins emit no redundant updates; changed/empty text and changed margin propagate."
    }

    private class Counter : TextWatcher {
        var changes = 0
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes++ }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private class RequestCountingView(context: Context) : View(context) {
        var requests = 0
        override fun requestLayout() { requests++; super.requestLayout() }
    }
}
