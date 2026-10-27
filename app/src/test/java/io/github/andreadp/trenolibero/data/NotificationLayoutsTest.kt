package io.github.andreadp.trenolibero.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * I layout delle notifiche sono RemoteViews: Android ne inflaziona solo alcune classi. Con una classe non ammessa
 * (per esempio un semplice View) la notifica non compare, senza errori nell'app.
 */
class NotificationLayoutsTest {
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "AbsoluteLayout", "AnalogClock", "Button",
        "Chronometer", "ImageButton", "ImageView", "ProgressBar", "TextClock", "TextView", "ViewFlipper",
        "ListView", "GridView", "StackView", "ViewStub", "AdapterViewFlipper",
    )

    private fun layoutDir(): File = listOf("src/main/res/layout", "app/src/main/res/layout").map(::File).first { it.isDirectory }

    private fun tags(e: Element): List<String> =
        listOf(e.tagName) + (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.flatMap(::tags)

    @Test
    fun notificationLayoutsUseOnlyRemoteViewsClasses() {
        val files = layoutDir().listFiles { f -> f.name.startsWith("notif_") && f.extension == "xml" }.orEmpty()
        assertTrue("nessun layout notif_*", files.isNotEmpty())
        val bad = files.flatMap { f ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
            tags(root).filter { it !in allowed }.map { "${f.name}: <$it>" }
        }
        assertTrue("Classi non ammesse nei RemoteViews: $bad", bad.isEmpty())
    }
}
