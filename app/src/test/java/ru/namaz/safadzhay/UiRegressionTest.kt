package ru.namaz.safadzhay

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRegressionTest {
    private fun checkScale(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app)
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val errors = mutableListOf<String>()
        fun inspect(root: View, screen: String) {
            // Let adaptive card sizing finish its layout passes before checking clipping.
            repeat(8) {
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 360, 800)
            }
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v is TextView && v.text.isNotBlank() && v.text != "•" && v.layout != null) {
                    val l = v.layout
                    val room = v.height - v.compoundPaddingTop - v.compoundPaddingBottom
                    val w = v.width - v.compoundPaddingLeft - v.compoundPaddingRight
                    if (l.height > room + 2 || (0 until l.lineCount).any { l.getLineMax(it) > w + 2 || l.getEllipsisCount(it) > 0 } || l.getLineEnd(l.lineCount - 1) < v.text.length)
                        errors += "$screen [$scale] '${v.text}' size=${v.width}x${v.height}, text=${l.height}, lines=${l.lineCount}"
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            val folder=java.io.File("build/reports/ui").apply { mkdirs() }
            val bitmap=android.graphics.Bitmap.createBitmap(360,800,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(folder,"$screen-$scale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        try {
            inspect(c.get().findViewById(android.R.id.content), "home")
            ReflectionHelpers.setField(c.get(), "scheduleTabSelected", true)
            ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "update")
            inspect(c.get().findViewById(android.R.id.content), "calendar")
            ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showSettingsDialog")
            inspect(ReflectionHelpers.getField<Dialog>(c.get(), "settingsPanel").findViewById(android.R.id.content), "settings")
            fun clickText(value:String, root:View):Boolean {
                if(root is TextView && root.text.toString()==value) {
                    var target:View=root
                    while(!target.isClickable && target.parent is View) target=target.parent as View
                    return target.performClick()
                }
                if(root is ViewGroup) for(i in 0 until root.childCount) if(clickText(value,root.getChildAt(i))) return true
                return false
            }
            val dialog=ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel")
            assertTrue(clickText("Уведомления",dialog.window!!.decorView))
            inspect(dialog.findViewById(android.R.id.content),"notifications")
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun font100() = checkScale(1f)
    @Test fun font130() = checkScale(1.3f)
    @Test fun font150() = checkScale(1.5f)
    @Test fun font200() = checkScale(2f)
}
