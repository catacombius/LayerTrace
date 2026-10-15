package io.layertrace.core.xml

/** Serialises [XmlDocument]s. Elements that contain text are written inline so whitespace is preserved. */
object XmlWriter {

    fun write(doc: XmlDocument): String = buildString {
        for (p in doc.prolog) {
            writeContent(this, p, 0, pretty = false)
            append('\n')
        }
        writeNode(this, doc.root, 0, pretty = true)
        append('\n')
    }

    fun toBytes(doc: XmlDocument): ByteArray = write(doc).toByteArray(Charsets.UTF_8)

    fun write(node: XmlNode): String = buildString { writeNode(this, node, 0, pretty = true) }

    private fun writeContent(sb: StringBuilder, c: XmlContent, depth: Int, pretty: Boolean) {
        when (c) {
            is XmlNode -> writeNode(sb, c, depth, pretty)
            is XmlText -> sb.append(escapeText(c.text))
            is XmlProcessingInstruction -> {
                sb.append("<?").append(c.target)
                if (c.data.isNotEmpty()) sb.append(' ').append(c.data)
                sb.append("?>")
            }
            is XmlComment -> sb.append("<!--").append(c.text).append("-->")
        }
    }

    private fun writeNode(sb: StringBuilder, n: XmlNode, depth: Int, pretty: Boolean) {
        sb.append('<').append(n.name)
        for ((k, v) in n.attrs) sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"')
        if (n.children.isEmpty()) {
            sb.append("/>")
            return
        }
        sb.append('>')
        // Mixed / text content must not get extra whitespace.
        val inline = !pretty || n.children.any { it is XmlText || it is XmlProcessingInstruction }
        if (inline) {
            for (c in n.children) writeContent(sb, c, depth + 1, pretty = false)
        } else {
            for (c in n.children) {
                sb.append('\n')
                repeat(depth + 1) { sb.append('\t') }
                writeContent(sb, c, depth + 1, pretty = true)
            }
            sb.append('\n')
            repeat(depth) { sb.append('\t') }
        }
        sb.append("</").append(n.name).append('>')
    }

    fun escapeText(t: String): String {
        var needs = false
        for (ch in t) if (ch == '<' || ch == '>' || ch == '&' || (ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r')) { needs = true; break }
        if (!needs) return t
        val sb = StringBuilder(t.length + 8)
        for (ch in t) when {
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '&' -> sb.append("&amp;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> sb.append("&#x").append(Integer.toHexString(ch.code)).append(';')
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    fun escapeAttr(t: String): String {
        val sb = StringBuilder(t.length + 8)
        for (ch in t) when (ch) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\n' -> sb.append("&#xa;")
            '\r' -> sb.append("&#xd;")
            '\t' -> sb.append("&#x9;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}
