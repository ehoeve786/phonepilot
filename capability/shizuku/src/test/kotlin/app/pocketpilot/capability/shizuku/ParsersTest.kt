package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.screen.Bounds
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    @Test
    fun `parses uiautomator xml into raw nodes with parent links`() {
        val xml =
            """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
                """<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.settings" """ +
                """content-desc="" checkable="false" checked="false" clickable="false" enabled="true" """ +
                """focusable="false" focused="false" """ +
                """scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">""" +
                """<node index="0" text="Display &amp; brightness" resource-id="android:id/title" class="android.widget.TextView" """ +
                """package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" """ +
                """focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" """ +
                """bounds="[40,210][400,260]" />""" +
                """<node index="1" text="" class="android.widget.Switch" package="com.android.settings" checkable="true" """ +
                """checked="true" clickable="true" enabled="false" bounds="[950,250][1040,300]" /></node></hierarchy>"""
        val nodes = UiAutomatorXml.parse(xml)
        assertEquals(3, nodes.size)
        assertNull(nodes[0].parent)
        assertEquals("com.android.settings", nodes[0].windowTitle)
        assertEquals("Display & brightness", nodes[1].text)
        assertEquals(0, nodes[1].parent)
        assertEquals(Bounds(40, 210, 400, 260), nodes[1].bounds)
        assertEquals(0, nodes[2].parent, "a self-closing sibling shares the parent")
        assertTrue(nodes[2].checked)
        assertEquals(false, nodes[2].enabled)
    }

    @Test
    fun `decodes raw screencap with either header size`() {
        for (headerSize in listOf(12, 16)) {
            val buffer = ByteBuffer.allocate(headerSize + 2 * 1 * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(2).putInt(1).putInt(1)
            if (headerSize == 16) buffer.putInt(0)
            buffer.put(byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0x80.toByte()))
            val frame = RawScreencap.decode(buffer.array())
            assertEquals(2, frame.width)
            assertEquals(0xFF102030.toInt(), frame.pixels[0])
            assertEquals(0x80FF0000.toInt(), frame.pixels[1])
        }
        assertFailsWith<IllegalArgumentException> { RawScreencap.decode(ByteArray(20)) }
    }

    @Test
    fun `finds the resumed activity and expands short class names`() {
        val dump =
            """
            mFocusedApp=ActivityRecord{abc u0 com.android.settings/.Settings t42}
            topResumedActivity=ActivityRecord{1a2b3c u0 com.android.settings/.SubSettings t42}
            """.trimIndent()
        assertEquals("com.android.settings/com.android.settings.SubSettings", ForegroundParser.parse(dump))
        assertNull(ForegroundParser.parse("nothing here"))
    }
}
