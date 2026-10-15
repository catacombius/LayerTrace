package io.layertrace.core.xml

/** Immutable XML content tree used for IDML round-tripping. */
sealed interface XmlContent

data class XmlText(val text: String) : XmlContent

data class XmlProcessingInstruction(val target: String, val data: String) : XmlContent

data class XmlComment(val text: String) : XmlContent

data class XmlNode(
    val name: String,
    val attrs: Map<String, String> = emptyMap(),
    val children: List<XmlContent> = emptyList(),
) : XmlContent {

    operator fun get(attr: String): String? = attrs[attr]

    val elements: List<XmlNode> get() = children.filterIsInstance<XmlNode>()

    fun child(name: String): XmlNode? = children.firstOrNull { it is XmlNode && it.name == name } as XmlNode?

    fun childrenNamed(name: String): List<XmlNode> = children.filter { it is XmlNode && it.name == name }.map { it as XmlNode }

    /** Depth-first search for the first descendant with [name]. */
    fun find(name: String): XmlNode? {
        for (c in children) if (c is XmlNode) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String, out: MutableList<XmlNode> = mutableListOf()): List<XmlNode> {
        for (c in children) if (c is XmlNode) {
            if (c.name == name) out += c
            c.findAll(name, out)
        }
        return out
    }

    /** Concatenated text of all text descendants. */
    val textContent: String
        get() = buildString {
            fun walk(n: XmlNode) {
                for (c in n.children) when (c) {
                    is XmlText -> append(c.text)
                    is XmlNode -> walk(c)
                    else -> {}
                }
            }
            walk(this@XmlNode)
        }

    fun withAttr(key: String, value: String?): XmlNode =
        copy(attrs = LinkedHashMap(attrs).apply { if (value == null) remove(key) else put(key, value) })

    fun withAttrs(values: Map<String, String?>): XmlNode {
        val m = LinkedHashMap(attrs)
        for ((k, v) in values) if (v == null) m.remove(k) else m[k] = v
        return copy(attrs = m)
    }

    fun withoutChildren(vararg names: String): XmlNode =
        copy(children = children.filterNot { it is XmlNode && it.name in names })

    /** Replace (or add, at [index] or the end) the first child element named [name]. Null removes it. */
    fun withChild(name: String, node: XmlNode?, index: Int = -1): XmlNode {
        val list = children.toMutableList()
        val i = list.indexOfFirst { it is XmlNode && it.name == name }
        if (i >= 0) {
            if (node == null) list.removeAt(i) else list[i] = node
        } else if (node != null) {
            if (index in 0..list.size) list.add(index, node) else list.add(node)
        }
        return copy(children = list)
    }
}

/** A parsed XML file: anything before the root element (declaration, processing instructions) plus the root. */
data class XmlDocument(val prolog: List<XmlContent>, val root: XmlNode)
