package io.layertrace.core

import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.edit.ItemOps
import io.layertrace.core.idml.IdmlFormatException
import io.layertrace.core.idml.IdmlPackage
import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.idml.IdmlWriter
import io.layertrace.core.idml.InddDetector
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Justification
import io.layertrace.core.model.LayerColor
import io.layertrace.core.text.OBJECT_CHAR
import io.layertrace.core.text.StoryEditing
import io.layertrace.core.text.StyleResolver
import io.layertrace.core.xml.XmlNode
import io.layertrace.core.xml.XmlParser
import io.layertrace.core.xml.XmlProcessingInstruction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class IdmlRoundTripTest {

    private fun read(): Document = IdmlReader.read(Fixtures.idml(), "Fixture")

    private fun roundTrip(doc: Document): Document = IdmlReader.read(IdmlWriter.write(doc), doc.name)

    @Test
    fun readsDocumentStructure() {
        val doc = read()
        assertEquals(612.0, doc.setup.pageWidth, 0.0)
        assertTrue(doc.setup.facingPages)
        assertEquals(9.0, doc.setup.bleedTop, 0.0)
        assertEquals(listOf("Text", "Background"), doc.layers.map { it.name })
        assertEquals(LayerColor.Red, doc.layers[0].color)
        assertEquals(2, doc.spreads.size)
        assertEquals(3, doc.pageCount)
        assertEquals(1, doc.masters.size)
        assertEquals("A-Master", doc.masters[0].masterName)
        assertEquals("ud6", doc.spreads[0].pages[0].appliedMasterId)
        assertNull(doc.spreads[1].pages[1].appliedMasterId)
        assertEquals(1, doc.spreads[0].pages[0].guides.size)
        assertEquals(2, doc.spreads[0].pages[0].margins.columns)
    }

    @Test
    fun readsItems() {
        val doc = read()
        val idx = DocIndex(doc)
        val rect = idx["u220"]!!.item
        assertEquals(ItemKind.Rectangle, rect.kind)
        assertEquals("Tint/u91", rect.fill)
        assertEquals(50.0, rect.opacity, 0.0)
        assertEquals(6.0, rect.cornerRadius, 0.0)
        assertNotNull(rect.image?.data)
        assertEquals(8, rect.image!!.data!!.size.coerceAtMost(8))
        val group = idx["u230"]!!.item
        assertEquals(ItemKind.Group, group.kind)
        assertEquals(2, group.children.size)
        assertEquals("u230", idx["u231"]!!.parentGroupId)
        assertEquals(ItemKind.Unknown, idx["u240"]!!.item.kind)
        val frame2 = idx["u211"]!!.item
        assertEquals(2, frame2.columns)
        assertEquals(18.0, frame2.columnGutter, 0.0)
        assertEquals(4.0, frame2.inset.top, 0.0)
        assertEquals(listOf("u210", "u211"), idx.chain("u200").map { it.id })
        assertEquals(45.0, idx["u310"]!!.world.rotationDegrees, 1e-6)
    }

    @Test
    fun readsStoriesAndStyles() {
        val doc = read()
        val story = doc.stories["u200"]!!
        assertEquals(3, story.paragraphs.size)
        assertEquals("Hello & welcome", story.paragraphs[0].text)
        assertTrue(story.paragraphs[1].text.startsWith("Body text with emphasis and page $OBJECT_CHAR number."))
        assertEquals("${OBJECT_CHAR}After table.", story.paragraphs[2].text)
        assertTrue(story.paragraphs[1].runs.any { it.inline is XmlProcessingInstruction })
        assertTrue(story.paragraphs[2].runs.any { (it.inline as? XmlNode)?.name == "Table" })

        val h1 = doc.paragraphStyle("ParagraphStyle/Headings%3aH1")!!
        assertEquals("Headings:H1", h1.name)
        assertEquals("ParagraphStyle/\$ID/NormalParagraphStyle", h1.basedOn)
        val resolver = StyleResolver(doc)
        val a = resolver.paragraph(story.paragraphs[0])
        assertEquals(24.0, a.pointSize!!, 0.0)
        assertEquals(28.0, a.leading!!, 0.0)
        assertEquals("Helvetica Neue", a.fontFamily)
        assertEquals(Justification.CenterAlign, a.justification)
        val body = resolver.run(story.paragraphs[1], story.paragraphs[1].runs[0].format)
        assertEquals(10.0, body.pointSize!!, 0.0)
        assertEquals(13.0, body.leading!!, 0.0)
        assertEquals("Minion Pro", body.fontFamily)
        val emph = resolver.run(story.paragraphs[1], story.paragraphs[1].runs[1].format)
        assertEquals("Italic", emph.fontStyle)
    }

    @Test
    fun unchangedDocumentRoundTripsLosslessly() {
        val doc = read()
        val out = IdmlWriter.entries(doc)
        // Unedited stories and unmodelled files are byte-identical.
        assertArrayEquals(Fixtures.entries()["Stories/Story_u200.xml"], out["Stories/Story_u200.xml"])
        assertArrayEquals(Fixtures.entries()["META-INF/metadata.xml"], out["META-INF/metadata.xml"])
        val again = roundTrip(doc)
        assertEquals(doc.pageCount, again.pageCount)
        assertEquals(doc.stories["u200"]!!.text, again.stories["u200"]!!.text)
        assertEquals(doc.swatches.map { it.id }, again.swatches.map { it.id })
        assertEquals(doc.paragraphStyles.map { it.id }, again.paragraphStyles.map { it.id })
        val i1 = DocIndex(doc); val i2 = DocIndex(again)
        for (l in i1.all) {
            val o = i2[l.item.id]
            assertNotNull("item ${l.item.id} survives", o)
            assertEquals(l.item.transform, o!!.item.transform)
            assertEquals(l.item.paths, o.item.paths)
        }
        // Unknown attributes and elements survive.
        val dm = XmlParser.parse(out["designmap.xml"]!!).root
        assertEquals("keep-me", dm["CustomThing"])
        val s1 = XmlParser.parse(out["Spreads/Spread_ud3.xml"]!!).root.child("Spread")!!
        assertNotNull(s1.child("FlattenerPreference"))
        val tf = s1.childrenNamed("TextFrame").first()
        assertNotNull(tf.child("TextWrapPreference"))
        assertEquals("Off", tf.child("TextFramePreference")!!["AutoSizingType"])
        val button = s1.child("Button")!!
        assertNotNull(button.child("State"))
        assertEquals("Multiply", s1.child("Rectangle")!!.child("TransparencySetting")!!.child("BlendingSetting")!!["BlendMode"])
    }

    @Test
    fun editedStoryKeepsInlineContentAndUnknownAttributes() {
        var doc = read()
        val st = StoryEditing.insert(doc.stories["u200"]!!, 0, "New ")
        doc = doc.copy(stories = doc.stories + ("u200" to st))
        val out = IdmlWriter.entries(doc)
        val xml = String(out["Stories/Story_u200.xml"]!!)
        assertTrue(xml.contains("<?ACE 18?>"))
        assertTrue(xml.contains("<Table Self=\"u400\""))
        assertTrue(xml.contains("Ligatures=\"false\""))
        assertTrue(xml.contains("Hyphenation=\"false\""))
        assertTrue(xml.contains("AppliedLanguage"))
        val again = IdmlReader.read(out, "x")
        assertEquals(st.text, again.stories["u200"]!!.text)
        assertEquals(3, again.stories["u200"]!!.paragraphs.size)
    }

    @Test
    fun editsSurviveRoundTrip() {
        var doc = read()
        doc = ItemOps.move(doc, setOf("u220"), 15.0, -5.0)
        doc = ItemOps.delete(doc, setOf("u240"))
        doc = doc.copy(layers = doc.layers.map { if (it.id == "ub4") it.copy(name = "BG", locked = true) else it })
        val again = roundTrip(doc)
        val rect = DocIndex(again)["u220"]!!.item
        assertEquals(115.0, rect.transform.tx, 1e-9)
        assertEquals(-5.0, rect.transform.ty, 1e-9)
        assertNotNull(rect.image?.data)
        assertNull(DocIndex(again)["u240"])
        assertEquals("BG", again.layer("ub4")!!.name)
        assertTrue(again.layer("ub4")!!.locked)
    }

    @Test
    fun deletingSwatchPrunesColorGroupReference() {
        var doc = read()
        doc = doc.copy(swatches = doc.swatches.filterNot { it.id == "Color/u90" || it.id == "Tint/u91" })
        val dm = XmlParser.parse(IdmlWriter.entries(doc)["designmap.xml"]!!).root
        val refs = dm.findAll("ColorGroupSwatch").map { it["SwatchItemRef"] }
        assertEquals(listOf("Swatch/None"), refs)
    }

    @Test
    fun newDocumentProducesValidPackage() {
        val doc = DocumentFactory.create(DocumentFactory.Params(name = "Fresh", pages = 4, facing = true))
        assertEquals(4, doc.pageCount)
        assertEquals(listOf(1, 2, 1), doc.spreads.map { it.pages.size })
        val bytes = IdmlWriter.write(doc)
        // mimetype is the first entry and stored uncompressed
        ZipInputStream(bytes.inputStream()).use { z ->
            val first = z.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals(IdmlPackage.MIMETYPE, String(z.readBytes()))
        }
        val entries = IdmlPackage.unzip(bytes)
        for (required in listOf("META-INF/container.xml", "designmap.xml", "Resources/Graphic.xml", "Resources/Styles.xml", "Resources/Preferences.xml", "Resources/Fonts.xml", "XML/BackingStory.xml")) {
            assertTrue("missing $required", required in entries)
        }
        // All XML parses and all designmap includes exist
        for ((p, d) in entries) if (p.endsWith(".xml")) XmlParser.parse(d)
        val dm = XmlParser.parse(entries["designmap.xml"]!!).root
        for (inc in dm.elements.filter { it.name.startsWith("idPkg:") }) assertTrue(inc["src"] in entries)
        val section = dm.child("Section")!!
        assertEquals(doc.pages[0].id, section["PageStart"])
        assertEquals("4", section["Length"])
        val prefs = XmlParser.parse(entries["Resources/Preferences.xml"]!!).root.child("DocumentPreference")!!
        assertEquals("4", prefs["PagesPerDocument"])
        val again = IdmlReader.read(bytes, "Fresh")
        assertEquals(4, again.pageCount)
        assertEquals(doc.masters[0].id, again.pages[1].appliedMasterId)
    }

    @Test
    fun detectsIndd() {
        val header = byteArrayOf(0x06, 0x06, 0xED.toByte(), 0xF5.toByte(), 0xD8.toByte(), 0x1D, 0x46, 0xE5.toByte(), 0xBD.toByte(), 0x31, 0xEF.toByte(), 0xE7.toByte(), 0xFE.toByte(), 0x74, 0xB7.toByte(), 0x1D, 1, 2, 3)
        assertEquals(InddDetector.Kind.INDD, InddDetector.sniff(header))
        assertEquals(InddDetector.Kind.IDML, InddDetector.sniff(Fixtures.idml()))
        assertEquals(InddDetector.Kind.UNKNOWN, InddDetector.sniff("hello".toByteArray()))
        val ex = runCatching { IdmlReader.read(header, "x") }.exceptionOrNull()
        assertTrue(ex is IdmlFormatException)
        assertTrue(ex!!.message!!.contains("IDML"))
    }

    @Test
    fun rejectsZipSlip() {
        val bad = IdmlPackage.zip(mapOf("../evil.xml" to ByteArray(1)))
        assertFalse(runCatching { IdmlPackage.unzip(bad) }.isSuccess)
    }
}
