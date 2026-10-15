package io.layertrace.core

import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.idml.IdmlWriter
import io.layertrace.core.idml.ImageSize
import io.layertrace.core.idml.InddDetector
import io.layertrace.core.idml.InddImporter
import io.layertrace.core.model.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class InddImportTest {

    /** Smallest valid baseline JPEG header: SOI, SOF0 (120x80), EOI. Enough for size sniffing. */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00, // APP0 stub
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x50, 0x00, 0x78, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun xmp(pages: Int, thumb: ByteArray?) = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:xmp="http://ns.adobe.com/xap/1.0/"
            xmlns:xmpGImg="http://ns.adobe.com/xap/1.0/g/img/"
            xmlns:xmpTPg="http://ns.adobe.com/xap/1.0/t/pg/"
            xmlns:stDim="http://ns.adobe.com/xap/1.0/sType/Dimensions#"
            xmlns:stFnt="http://ns.adobe.com/xap/1.0/sType/Font#"
            xmlns:xmpG="http://ns.adobe.com/xap/1.0/g/"
            xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
            xmlns:stMfs="http://ns.adobe.com/xap/1.0/sType/ManifestItem#"
            xmlns:stRef="http://ns.adobe.com/xap/1.0/sType/ResourceRef#">
           <xmpTPg:NPages>$pages</xmpTPg:NPages>
           <xmpTPg:MaxPageSize stDim:w="210.000000" stDim:h="297.000000" stDim:unit="Millimeters"/>
           <xmpTPg:Fonts><rdf:Bag>
             <rdf:li rdf:parseType="Resource"><stFnt:fontName>MinionPro-Regular</stFnt:fontName></rdf:li>
             <rdf:li rdf:parseType="Resource"><stFnt:fontFamily>Myriad Pro</stFnt:fontFamily><stFnt:fontFace>Bold</stFnt:fontFace></rdf:li>
           </rdf:Bag></xmpTPg:Fonts>
           <xmpTPg:Colorants><rdf:Seq>
             <rdf:li rdf:parseType="Resource"><xmpG:swatchName>Brand Teal</xmpG:swatchName><xmpG:mode>CMYK</xmpG:mode><xmpG:type>PROCESS</xmpG:type>
               <xmpG:cyan>80.0</xmpG:cyan><xmpG:magenta>0.0</xmpG:magenta><xmpG:yellow>30.0</xmpG:yellow><xmpG:black>10.0</xmpG:black></rdf:li>
             <rdf:li rdf:parseType="Resource"><xmpG:swatchName>Black</xmpG:swatchName><xmpG:mode>CMYK</xmpG:mode><xmpG:type>PROCESS</xmpG:type>
               <xmpG:cyan>0</xmpG:cyan><xmpG:magenta>0</xmpG:magenta><xmpG:yellow>0</xmpG:yellow><xmpG:black>100</xmpG:black></rdf:li>
             <rdf:li rdf:parseType="Resource"><xmpG:swatchName>Web Red</xmpG:swatchName><xmpG:mode>RGB</xmpG:mode><xmpG:type>SPOT</xmpG:type>
               <xmpG:red>220</xmpG:red><xmpG:green>20</xmpG:green><xmpG:blue>30</xmpG:blue></rdf:li>
           </rdf:Seq></xmpTPg:Colorants>
           <xmpMM:Manifest><rdf:Seq>
             <rdf:li rdf:parseType="Resource"><stMfs:linkForm>ReferenceStream</stMfs:linkForm>
               <stMfs:reference rdf:parseType="Resource"><stRef:filePath>file:///Users/me/Links/cover.psd</stRef:filePath></stMfs:reference></rdf:li>
           </rdf:Seq></xmpMM:Manifest>
           ${if (thumb == null) "" else """
           <xmp:Thumbnails><rdf:Alt><rdf:li rdf:parseType="Resource">
             <xmpGImg:format>JPEG</xmpGImg:format><xmpGImg:width>120</xmpGImg:width><xmpGImg:height>80</xmpGImg:height>
             <xmpGImg:image>${Base64.getMimeEncoder().encodeToString(thumb).replace("\r\n", "&#xA;")}</xmpGImg:image>
           </rdf:li></rdf:Alt></xmp:Thumbnails>"""}
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** A fake .indd: database header GUID, binary filler, an old XMP packet, filler, the current one. */
    private fun indd(pages: Int, thumb: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x06, 0x06, 0xED.toByte(), 0xF5.toByte(), 0xD8.toByte(), 0x1D, 0x46, 0xE5.toByte(),
            0xBD.toByte(), 0x31, 0xEF.toByte(), 0xE7.toByte(), 0xFE.toByte(), 0x74, 0xB7.toByte(), 0x1D))
        out.write(ByteArray(70_000) { (it * 31).toByte() })
        out.write(xmp(1, null).toByteArray())
        out.write(ByteArray(5000) { (it * 7).toByte() })
        out.write("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>".toByteArray())
        out.write(xmp(pages, thumb).toByteArray())
        out.write("<?xpacket end=\"w\"?>".toByteArray())
        out.write(ByteArray(3000))
        return out.toByteArray()
    }

    @Test fun jpegAndPngSizes() {
        assertEquals(120 to 80, ImageSize.of(jpeg))
        val png = ByteArray(32).also {
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
            it[19] = 200.toByte(); it[23] = 100
        }
        assertEquals(200 to 100, ImageSize.of(png))
        assertNull(ImageSize.of(ByteArray(40)))
    }

    @Test fun importsPagesSwatchesFontsLinksAndPreview() {
        val bytes = indd(4, jpeg)
        assertEquals(InddDetector.Kind.INDD, InddDetector.sniff(bytes.copyOf(16)))
        val r = InddImporter.import(bytes.inputStream(), "Annual Report.indd")
        assertTrue(r.hadMetadata)
        // The last packet wins (4 pages, not the stale 1-page one).
        assertEquals(4, r.pageCount)
        assertEquals(4, r.document.pageCount)
        assertEquals(595.27, r.pageWidth, 0.01)
        assertEquals(841.89, r.pageHeight, 0.01)
        assertEquals(listOf("MinionPro-Regular", "Myriad Pro Bold"), r.fonts)
        assertEquals(listOf("file:///Users/me/Links/cover.psd"), r.links)
        assertEquals(listOf("Brand Teal", "Web Red"), r.swatches)
        val teal = r.document.swatches.first { it.name == "Brand Teal" }
        assertEquals(ColorSpace.CMYK, teal.space)
        assertEquals(listOf(80.0, 0.0, 30.0, 10.0), teal.values)
        assertTrue(r.document.swatches.first { it.name == "Web Red" }.spot)

        assertTrue(r.hasPreview)
        val layer = r.document.layers.first()
        assertEquals(InddImporter.PREVIEW_LAYER, layer.name)
        assertTrue(layer.locked); assertFalse(layer.printable)
        val frame = r.document.spreads.first().items.single()
        assertEquals(layer.id, frame.layerId)
        val page = r.document.spreads.first().pages.first().boundsInSpread
        val img = assertNotNull(frame.image).let { frame.image!! }
        val mapped = img.transform.mapRect(img.bounds)
        assertEquals(page.left, mapped.left, 1e-6); assertEquals(page.bottom, mapped.bottom, 1e-6)
        assertEquals("Annual Report.idml", r.document.name)

        // The result is a normal document that survives an IDML round trip.
        val back = IdmlReader.read(IdmlWriter.write(r.document), "x")
        assertEquals(4, back.pageCount)
        assertTrue(back.swatches.any { it.name == "Brand Teal" })
        assertTrue(back.layers.any { it.name == InddImporter.PREVIEW_LAYER && it.locked && !it.printable })
        assertTrue(back.spreads.first().items.single().image!!.data!!.contentEquals(jpeg))
    }

    @Test fun withoutMetadataFallsBackToOneBlankPage() {
        val r = InddImporter.import(ByteArray(10_000).inputStream(), "x.indd")
        assertFalse(r.hadMetadata)
        assertFalse(r.hasPreview)
        assertEquals(1, r.document.pageCount)
    }
}
