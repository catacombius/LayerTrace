package io.layertrace.core

import io.layertrace.core.text.FontNameParser
import io.layertrace.core.xml.XmlNode
import io.layertrace.core.xml.XmlParser
import io.layertrace.core.xml.XmlProcessingInstruction
import io.layertrace.core.xml.XmlText
import io.layertrace.core.xml.XmlWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTest {
    @Test
    fun parsesAndWritesMixedContent() {
        val src = """<?xml version="1.0"?><?aid style="50"?><a x="1 &amp; 2"><b/><Content>Hi <?ACE 18?> &lt;there&gt;</Content><c><![CDATA[raw <x>]]></c></a>"""
        val doc = XmlParser.parse(src)
        assertEquals(2, doc.prolog.size)
        val a = doc.root
        assertEquals("1 & 2", a["x"])
        val content = a.child("Content")!!
        assertEquals(XmlText("Hi "), content.children[0])
        assertTrue(content.children[1] is XmlProcessingInstruction)
        assertEquals(XmlText(" <there>"), content.children[2])
        assertEquals("raw <x>", a.child("c")!!.textContent)
        val again = XmlParser.parse(XmlWriter.write(doc))
        assertEquals(doc.root, again.root)
    }

    @Test
    fun whitespaceOnlyContentIsKept() {
        val doc = XmlParser.parse("<a>\n\t<Content> </Content>\n</a>")
        assertEquals(1, doc.root.children.size)
        assertEquals(" ", (doc.root.children[0] as XmlNode).textContent)
    }

    @Test
    fun numericEntities() {
        assertEquals("A ", XmlParser.unescape("&#65;&#x2029;"))
    }

    @Test
    fun fontParserRejectsGarbage() {
        assertNull(FontNameParser.parse(ByteArray(10)))
    }
}
