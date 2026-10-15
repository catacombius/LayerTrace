package io.layertrace.core.idml

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object IdmlPackage {
    const val MIMETYPE = "application/vnd.adobe.indesign-idml-package"

    fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) {
                    val name = e.name.replace('\\', '/').trimStart('/')
                    require(!name.split('/').contains("..")) { "Illegal entry name: ${e.name}" }
                    out[name] = zin.readBytes()
                }
                zin.closeEntry()
            }
        }
        return out
    }

    /** Writes the package with `mimetype` first and uncompressed, as the IDML spec requires. */
    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            val mt = MIMETYPE.toByteArray(Charsets.US_ASCII)
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED
            e.size = mt.size.toLong()
            e.compressedSize = mt.size.toLong()
            e.crc = CRC32().apply { update(mt) }.value
            zos.putNextEntry(e)
            zos.write(mt)
            zos.closeEntry()
            for ((name, data) in entries) {
                if (name == "mimetype") continue
                zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}

/** Detects native InDesign (.indd/.indt) files, which cannot be edited without Adobe InDesign. */
object InddDetector {
    private val DATABASE_GUID = byteArrayOf(
        0x06, 0x06, 0xED.toByte(), 0xF5.toByte(), 0xD8.toByte(), 0x1D, 0x46, 0xE5.toByte(),
        0xBD.toByte(), 0x31, 0xEF.toByte(), 0xE7.toByte(), 0xFE.toByte(), 0x74, 0xB7.toByte(), 0x1D,
    )

    fun isIndd(header: ByteArray): Boolean =
        header.size >= DATABASE_GUID.size && DATABASE_GUID.indices.all { header[it] == DATABASE_GUID[it] }

    fun isZip(header: ByteArray): Boolean = header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
        header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

    enum class Kind { IDML, INDD, UNKNOWN }

    fun sniff(header: ByteArray): Kind = when {
        isIndd(header) -> Kind.INDD
        isZip(header) -> Kind.IDML
        else -> Kind.UNKNOWN
    }
}
