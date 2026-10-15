package io.layertrace.core.xml

/**
 * Small, dependency-free XML parser. It behaves identically on the JVM and Android
 * and keeps processing instructions (InDesign stores special characters such as
 * page numbers as `<?ACE 18?>` inside story content).
 */
class XmlParseException(message: String) : RuntimeException(message)

object XmlParser {

    fun parse(bytes: ByteArray): XmlDocument = parse(decode(bytes))

    fun parse(text: String): XmlDocument = Parser(text).parseDocument()

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        val s = String(bytes, Charsets.UTF_8)
        return if (s.startsWith('﻿')) s.substring(1) else s
    }

    private class Builder(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<XmlContent>()
        fun build(): XmlNode {
            val hasElement = children.any { it is XmlNode }
            val kept = if (hasElement) children.filterNot { it is XmlText && it.text.isBlank() } else children
            return XmlNode(name, attrs, mergeText(kept))
        }

        private fun mergeText(list: List<XmlContent>): List<XmlContent> {
            if (list.size < 2) return list
            val out = ArrayList<XmlContent>(list.size)
            for (c in list) {
                val last = out.lastOrNull()
                if (c is XmlText && last is XmlText) out[out.size - 1] = XmlText(last.text + c.text) else out += c
            }
            return out
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseDocument(): XmlDocument {
            val prolog = ArrayList<XmlContent>()
            var root: XmlNode? = null
            while (i < s.length) {
                when {
                    s.startsWith("<?", i) -> {
                        val pi = readPI()
                        if (root == null) prolog += pi
                    }
                    s.startsWith("<!--", i) -> {
                        val c = readComment()
                        if (root == null) prolog += c
                    }
                    s.startsWith("<!DOCTYPE", i) -> skipDoctype()
                    s[i] == '<' -> {
                        if (root != null) throw XmlParseException("Multiple root elements at $i")
                        root = readElement()
                    }
                    else -> i++
                }
            }
            return XmlDocument(prolog, root ?: throw XmlParseException("No root element"))
        }

        private fun readElement(): XmlNode {
            // Iterative to survive deeply nested documents.
            val stack = ArrayList<Builder>()
            var result: XmlNode? = null
            fun add(c: XmlContent) {
                stack.last().children += c
            }
            do {
                when {
                    s.startsWith("</", i) -> {
                        val end = s.indexOf('>', i)
                        if (end < 0) throw XmlParseException("Unclosed end tag at $i")
                        val name = s.substring(i + 2, end).trim()
                        val b = stack.removeAt(stack.size - 1)
                        if (b.name != name) throw XmlParseException("Mismatched </$name>, expected </${b.name}> at $i")
                        i = end + 1
                        val node = b.build()
                        if (stack.isEmpty()) result = node else add(node)
                    }
                    s.startsWith("<?", i) -> add(readPI())
                    s.startsWith("<!--", i) -> add(readComment())
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        if (end < 0) throw XmlParseException("Unclosed CDATA at $i")
                        add(XmlText(s.substring(i + 9, end)))
                        i = end + 3
                    }
                    s[i] == '<' -> {
                        i++
                        val name = readName()
                        val attrs = LinkedHashMap<String, String>()
                        var selfClosing = false
                        while (true) {
                            skipWs()
                            if (i >= s.length) throw XmlParseException("Unexpected end in <$name>")
                            if (s[i] == '/') {
                                if (s.getOrNull(i + 1) != '>') throw XmlParseException("Bad tag end at $i")
                                i += 2; selfClosing = true; break
                            }
                            if (s[i] == '>') { i++; break }
                            val an = readName()
                            skipWs()
                            if (s.getOrNull(i) != '=') throw XmlParseException("Expected '=' after $an at $i")
                            i++
                            skipWs()
                            val q = s[i]
                            if (q != '"' && q != '\'') throw XmlParseException("Expected quote at $i")
                            val end = s.indexOf(q, i + 1)
                            if (end < 0) throw XmlParseException("Unclosed attribute at $i")
                            attrs[an] = unescape(s.substring(i + 1, end))
                            i = end + 1
                        }
                        val b = Builder(name, attrs)
                        if (selfClosing) {
                            val node = b.build()
                            if (stack.isEmpty()) result = node else add(node)
                        } else stack += b
                    }
                    else -> {
                        val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        if (stack.isNotEmpty()) add(XmlText(unescape(s.substring(i, end))))
                        i = end
                    }
                }
                if (i >= s.length && stack.isNotEmpty()) throw XmlParseException("Unexpected end of document")
            } while (stack.isNotEmpty())
            return result!!
        }

        private fun readPI(): XmlProcessingInstruction {
            val end = s.indexOf("?>", i)
            if (end < 0) throw XmlParseException("Unclosed processing instruction at $i")
            val body = s.substring(i + 2, end)
            i = end + 2
            val sp = body.indexOfFirst { it.isWhitespace() }
            return if (sp < 0) XmlProcessingInstruction(body, "")
            else XmlProcessingInstruction(body.substring(0, sp), body.substring(sp + 1).trim())
        }

        private fun readComment(): XmlComment {
            val end = s.indexOf("-->", i)
            if (end < 0) throw XmlParseException("Unclosed comment at $i")
            val c = XmlComment(s.substring(i + 4, end))
            i = end + 3
            return c
        }

        private fun skipDoctype() {
            var depth = 0
            while (i < s.length) {
                val ch = s[i++]
                if (ch == '[') depth++ else if (ch == ']') depth-- else if (ch == '>' && depth == 0) return
            }
        }

        private fun readName(): String {
            val start = i
            while (i < s.length) {
                val ch = s[i]
                if (ch.isWhitespace() || ch == '=' || ch == '>' || ch == '/' ) break
                i++
            }
            if (i == start) throw XmlParseException("Expected name at $i")
            return s.substring(start, i)
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    fun unescape(t: String): String {
        if (t.indexOf('&') < 0) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch == '&') {
                val semi = t.indexOf(';', i)
                if (semi > i) {
                    val ent = t.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "amp" -> "&"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) {
                        sb.append(rep); i = semi + 1; continue
                    }
                }
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }
}
