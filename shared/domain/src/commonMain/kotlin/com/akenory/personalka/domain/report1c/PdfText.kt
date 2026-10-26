package com.akenory.personalka.domain.report1c

/** Кусок текста PDF (ТЗ 8.2, п. 1): начало строки [x], [y] в пунктах от левого нижнего угла страницы. */
data class PdfTextRun(val x: Double, val y: Double, val text: String)

/**
 * Текст PDF с координатами на чистом Kotlin (ТЗ 8.2, п. 1) — столько, сколько нужно для отчёта 1С: объекты без
 * потоков объектов, сжатие `FlateDecode`, шрифты с таблицей `ToUnicode`, операторы текста `Tj`, `TJ`, `'`, `"`.
 * Координата куска — точка начала его строки; ширина букв не считается: 1С пишет каждую строчку ячейки отдельно.
 */
object PdfText {
    class NotPdf(message: String) : Exception(message)

    /** Страницы по порядку, на каждой — куски в порядке записи. Не PDF или битый файл — [NotPdf]. */
    fun pages(bytes: ByteArray): List<List<PdfTextRun>> {
        if (!startsWith(bytes, "%PDF-")) throw NotPdf("нет заголовка PDF")
        val doc = Document(bytes)
        return try {
            doc.pages().map { doc.runs(it) }
        } catch (e: Inflate.Broken) {
            throw NotPdf(e.message ?: "битые данные")
        } catch (e: IndexOutOfBoundsException) {
            throw NotPdf("файл оборван")
        }
    }

    private fun startsWith(b: ByteArray, s: String): Boolean =
        b.size >= s.length && s.indices.all { b[it].toInt() == s[it].code }

    // ---------- объекты PDF ----------

    internal class Name(val v: String) {
        override fun equals(other: Any?) = other is Name && other.v == v
        override fun hashCode() = v.hashCode()
        override fun toString() = "/$v"
    }

    internal data class Ref(val num: Int)

    internal class Str(val bytes: ByteArray)

    internal class Op(val v: String)

    internal class Stream(val dict: Map<String, Any?>, val start: Int)

    private class Lexer(val b: ByteArray, var pos: Int, val end: Int = b.size) {
        fun ws(c: Int) = c == 0x20 || c == 0x0a || c == 0x0d || c == 0x09 || c == 0x0c || c == 0x00
        fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
            c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun at(i: Int) = b[i].toInt() and 0xff

        fun skip() {
            while (pos < end) {
                val c = at(pos)
                if (ws(c)) pos++
                else if (c == '%'.code) { while (pos < end && at(pos) != 0x0a && at(pos) != 0x0d) pos++ }
                else return
            }
        }

        /** Следующий простой знак: число, имя, строка, оператор, `[`, `]`, `<<`, `>>`; конец — `null`. */
        fun token(): Any? {
            skip()
            if (pos >= end) return null
            val c = at(pos)
            return when {
                c == '/'.code -> {
                    pos++
                    val s = StringBuilder()
                    while (pos < end && !ws(at(pos)) && !delim(at(pos))) {
                        if (at(pos) == '#'.code && pos + 2 < end) {
                            s.append(hex2(at(pos + 1), at(pos + 2)).toChar()); pos += 3
                        } else s.append(at(pos++).toChar())
                    }
                    Name(s.toString())
                }
                c == '('.code -> Str(literal())
                c == '<'.code && pos + 1 < end && at(pos + 1) == '<'.code -> { pos += 2; Op("<<") }
                c == '>'.code && pos + 1 < end && at(pos + 1) == '>'.code -> { pos += 2; Op(">>") }
                c == '<'.code -> Str(hexString())
                c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code -> { pos++; Op(c.toChar().toString()) }
                else -> {
                    val start = pos
                    while (pos < end && !ws(at(pos)) && !delim(at(pos))) pos++
                    if (pos == start) { pos++; return Op(c.toChar().toString()) }
                    val s = b.decodeToString(start, pos)
                    s.toDoubleOrNull() ?: Op(s)
                }
            }
        }

        fun hex2(a: Int, c: Int) = (hexDigit(a) shl 4) or hexDigit(c)

        fun hexDigit(c: Int) = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> 0
        }

        fun hexString(): ByteArray {
            pos++
            val digits = ArrayList<Int>()
            while (pos < end && at(pos) != '>'.code) {
                val c = at(pos++)
                if (!ws(c)) digits.add(hexDigit(c))
            }
            pos++
            if (digits.size % 2 == 1) digits.add(0)
            return ByteArray(digits.size / 2) { ((digits[2 * it] shl 4) or digits[2 * it + 1]).toByte() }
        }

        fun literal(): ByteArray {
            pos++
            val out = ArrayList<Byte>()
            var depth = 1
            while (pos < end) {
                val c = at(pos++)
                when (c) {
                    '\\'.code -> {
                        val n = at(pos++)
                        when (n) {
                            'n'.code -> out.add(10)
                            'r'.code -> out.add(13)
                            't'.code -> out.add(9)
                            'b'.code -> out.add(8)
                            'f'.code -> out.add(12)
                            in '0'.code..'7'.code -> {
                                var v = n - '0'.code
                                var k = 1
                                while (k < 3 && pos < end && at(pos) in '0'.code..'7'.code) { v = v * 8 + at(pos++) - '0'.code; k++ }
                                out.add(v.toByte())
                            }
                            0x0d -> if (pos < end && at(pos) == 0x0a) pos++
                            0x0a -> {}
                            else -> out.add(n.toByte())
                        }
                    }
                    '('.code -> { depth++; out.add(c.toByte()) }
                    ')'.code -> { depth--; if (depth == 0) return out.toByteArray(); out.add(c.toByte()) }
                    else -> out.add(c.toByte())
                }
            }
            return out.toByteArray()
        }

        /** Объект: словарь, массив, ссылка `N G R` или простой знак. */
        fun value(): Any? = value(token())

        fun value(t: Any?): Any? = when {
            t is Op && t.v == "<<" -> {
                val m = LinkedHashMap<String, Any?>()
                while (true) {
                    val k = token()
                    if (k == null || (k is Op && k.v == ">>")) break
                    if (k is Name) m[k.v] = value()
                }
                m
            }
            t is Op && t.v == "[" -> {
                val l = ArrayList<Any?>()
                while (true) {
                    val x = token()
                    if (x == null || (x is Op && x.v == "]")) break
                    l.add(value(x))
                }
                l
            }
            t is Double -> {
                val save = pos
                val g = token()
                val r = token()
                if (g is Double && r is Op && r.v == "R") Ref(t.toInt()) else { pos = save; t }
            }
            else -> t
        }
    }

    private class Document(val b: ByteArray) {
        /** Смещение каждого объекта «N G obj»; у повторного номера побеждает последний — так PDF дописывают правки. */
        val offsets = HashMap<Int, Int>()
        val cache = HashMap<Int, Any?>()

        init {
            var i = 0
            val n = b.size
            while (i < n - 3) {
                if (startsAt(i, "stream") && i > 0 && b[i - 1] != 'd'.code.toByte()) {
                    i = findEndstream(i + 6) + 9
                    continue
                }
                if (b[i] == 'o'.code.toByte() && b[i + 1] == 'b'.code.toByte() && b[i + 2] == 'j'.code.toByte() &&
                    (i + 3 >= n || !isRegular(b[i + 3].toInt()))
                ) {
                    objectStart(i)?.let { (num, start) -> offsets[num] = start }
                }
                i++
            }
        }

        fun isRegular(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code

        /** Назад от «obj»: пробел, номер поколения, пробел, номер объекта. */
        fun objectStart(objAt: Int): Pair<Int, Int>? {
            var j = objAt - 1
            fun spaces(): Boolean { val s = j; while (j >= 0 && (b[j] == 0x20.toByte() || b[j] == 0x0a.toByte() || b[j] == 0x0d.toByte())) j--; return j < s }
            fun digits(): Int? { val e = j; while (j >= 0 && b[j] in '0'.code.toByte()..'9'.code.toByte()) j--; return if (j < e) b.decodeToString(j + 1, e + 1).toIntOrNull() else null }
            if (!spaces()) return null
            digits() ?: return null
            if (!spaces()) return null
            val num = digits() ?: return null
            return num to objAt + 3
        }

        fun obj(num: Int): Any? = cache.getOrPut(num) {
            val start = offsets[num] ?: return@getOrPut null
            val lx = Lexer(b, start)
            val v = lx.value()
            if (v is Map<*, *>) {
                val save = lx.pos
                val t = lx.token()
                if (t is Op && t.v == "stream") {
                    var p = lx.pos
                    if (p < b.size && b[p] == 0x0d.toByte()) p++
                    if (p < b.size && b[p] == 0x0a.toByte()) p++
                    @Suppress("UNCHECKED_CAST")
                    return@getOrPut Stream(v as Map<String, Any?>, p)
                }
                lx.pos = save
            }
            v
        }

        fun resolve(v: Any?): Any? {
            var x = v
            var guard = 0
            while (x is Ref && guard++ < 32) x = obj(x.num)
            return x
        }

        @Suppress("UNCHECKED_CAST")
        fun dict(v: Any?): Map<String, Any?>? = when (val x = resolve(v)) {
            is Map<*, *> -> x as Map<String, Any?>
            is Stream -> x.dict
            else -> null
        }

        fun list(v: Any?): List<Any?> = when (val x = resolve(v)) {
            is List<*> -> x
            null -> emptyList()
            else -> listOf(x)
        }

        fun number(v: Any?): Double? = resolve(v) as? Double

        /** Данные потока, распакованные; длина — из `/Length`, неверная — до «endstream». */
        fun data(s: Stream): ByteArray {
            val declared = number(s.dict["Length"])?.toInt()
            val end = if (declared != null && s.start + declared <= b.size && endsStream(s.start + declared)) {
                s.start + declared
            } else {
                findEndstream(s.start)
            }
            var raw = b.copyOfRange(s.start, end)
            for (f in list(s.dict["Filter"])) {
                raw = when ((f as? Name)?.v) {
                    "FlateDecode", "Fl" -> Inflate.inflate(raw)
                    else -> throw NotPdf("сжатие ${(f as? Name)?.v} не поддерживается")
                }
            }
            return raw
        }

        fun endsStream(at: Int): Boolean {
            var p = at
            while (p < b.size && (b[p] == 0x0d.toByte() || b[p] == 0x0a.toByte() || b[p] == 0x20.toByte())) p++
            return startsAt(p, "endstream")
        }

        fun startsAt(p: Int, s: String) = p + s.length <= b.size && s.indices.all { b[p + it].toInt() == s[it].code }

        fun findEndstream(from: Int): Int {
            var p = from
            while (p < b.size && !startsAt(p, "endstream")) p++
            var e = p
            if (e > from && b[e - 1] == 0x0a.toByte()) e--
            if (e > from && b[e - 1] == 0x0d.toByte()) e--
            return e
        }

        /** Страницы в порядке дерева `/Pages` от каталога; ресурсы наследуются от родителя. */
        fun pages(): List<Map<String, Any?>> {
            val catalog = offsets.keys.sorted().firstNotNullOfOrNull { n ->
                dict(Ref(n))?.takeIf { (it["Type"] as? Name)?.v == "Catalog" }
            } ?: throw NotPdf("нет каталога")
            val out = ArrayList<Map<String, Any?>>()
            fun walk(node: Map<String, Any?>, inherited: Any?, depth: Int) {
                if (depth > 32) return
                val res = node["Resources"] ?: inherited
                when ((node["Type"] as? Name)?.v) {
                    "Pages" -> list(node["Kids"]).forEach { k -> dict(k)?.let { walk(it, res, depth + 1) } }
                    else -> out.add(node + ("Resources" to res))
                }
            }
            walk(dict(catalog["Pages"]) ?: throw NotPdf("нет страниц"), null, 0)
            return out
        }

        fun runs(page: Map<String, Any?>): List<PdfTextRun> {
            val content = ArrayList<Byte>()
            for (c in list(page["Contents"])) {
                val s = resolve(c) as? Stream ?: continue
                content.addAll(data(s).toList())
                content.add(0x0a)
            }
            val fonts = dict(dict(page["Resources"])?.get("Font")) ?: emptyMap()
            return Interpreter(content.toByteArray(), fonts, this).run()
        }

        val fontCache = HashMap<Any, Font>()

        fun font(ref: Any?): Font {
            val key = ref ?: "none"
            return fontCache.getOrPut(key) { Font.of(dict(ref), this) }
        }
    }

    /** Перевод кодов шрифта в буквы: по `ToUnicode`, иначе — байт как буква Latin-1. */
    private class Font(val width: Int, val map: Map<Int, String>) {
        fun text(s: ByteArray): String {
            val out = StringBuilder()
            var i = 0
            while (i + width <= s.size) {
                var code = 0
                for (k in 0 until width) code = (code shl 8) or (s[i + k].toInt() and 0xff)
                i += width
                out.append(map[code] ?: if (width == 1) code.toChar().toString() else "")
            }
            return out.toString()
        }

        companion object {
            fun of(f: Map<String, Any?>?, doc: Document): Font {
                if (f == null) return Font(1, emptyMap())
                val type0 = (f["Subtype"] as? Name)?.v == "Type0"
                val map = HashMap<Int, String>()
                var width = if (type0) 2 else 1
                (doc.resolve(f["ToUnicode"]) as? Stream)?.let { s ->
                    val cmap = CMap.parse(doc.data(s))
                    map.putAll(cmap.first)
                    if (cmap.second > 0) width = cmap.second
                }
                return Font(width, map)
            }
        }
    }

    /** Таблица `ToUnicode`: `bfchar` и `bfrange` (в том числе со списком); ширина кода — из `codespacerange`. */
    internal object CMap {
        fun parse(data: ByteArray): Pair<Map<Int, String>, Int> {
            val lx = Lexer(data, 0)
            val map = HashMap<Int, String>()
            var width = 0
            var mode = ""
            val args = ArrayList<Any?>()
            while (true) {
                val t = lx.value() ?: break
                if (t !is Op) {
                    if (mode.isNotEmpty()) args.add(t)
                    if (mode == "bfchar" && args.size == 2) {
                        map[code(args[0])] = utf16(args[1])
                        args.clear()
                    } else if (mode == "bfrange" && args.size == 3) {
                        range(code(args[0]), code(args[1]), args[2], map)
                        args.clear()
                    } else if (mode == "codespacerange" && args.size == 2) {
                        width = (args[0] as? Str)?.bytes?.size ?: width
                        args.clear()
                    }
                    continue
                }
                when (t.v) {
                    "begincodespacerange" -> mode = "codespacerange"
                    "beginbfchar" -> mode = "bfchar"
                    "beginbfrange" -> mode = "bfrange"
                    "endcodespacerange", "endbfchar", "endbfrange" -> mode = ""
                }
                args.clear()
            }
            return map to width
        }

        private fun range(lo: Int, hi: Int, dst: Any?, map: MutableMap<Int, String>) {
            if (lo !in 0..hi || hi - lo >= 65536) return
            if (dst is List<*>) {
                for (c in lo..hi) dst.getOrNull(c - lo)?.let { map[c] = utf16(it) }
            } else {
                val base = utf16(dst)
                if (base.isNotEmpty()) for (c in lo..hi) map[c] = base.dropLast(1) + (base.last() + (c - lo))
            }
        }

        private fun code(x: Any?): Int {
            val bs = (x as? Str)?.bytes ?: return -1
            var v = 0
            for (byte in bs) v = (v shl 8) or (byte.toInt() and 0xff)
            return v
        }

        private fun utf16(x: Any?): String {
            val bs = (x as? Str)?.bytes ?: return ""
            return CharArray(bs.size / 2) { (((bs[2 * it].toInt() and 0xff) shl 8) or (bs[2 * it + 1].toInt() and 0xff)).toChar() }
                .concatToString()
        }
    }

    /** Исполнитель потока страницы: матрицы `cm`, `q`/`Q`, `Tm`, `Td`, `TD`, `T*`, `TL` и показ текста. */
    private class Interpreter(data: ByteArray, val fonts: Map<String, Any?>, val doc: Document) {
        val lx = Lexer(data, 0)
        val out = ArrayList<PdfTextRun>()
        val operands = ArrayList<Any?>()
        var ctm = Matrix.IDENTITY
        val saved = ArrayList<Matrix>()
        var tm = Matrix.IDENTITY
        var tlm = Matrix.IDENTITY
        var leading = 0.0
        var font = Font(1, emptyMap())

        fun num(i: Int) = operands.getOrNull(i) as? Double ?: 0.0

        fun run(): List<PdfTextRun> {
            while (true) {
                val t = lx.token() ?: break
                if (t !is Op || t.v == "[" || t.v == "<<") {
                    operands.add(lx.value(t))
                    continue
                }
                when (t.v) {
                    "q" -> saved.add(ctm)
                    "Q" -> if (saved.isNotEmpty()) ctm = saved.removeAt(saved.size - 1)
                    "cm" -> if (operands.size >= 6) ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)) * ctm
                    "BT" -> { tm = Matrix.IDENTITY; tlm = Matrix.IDENTITY }
                    "Tf" -> font = doc.font(fonts[(operands.getOrNull(0) as? Name)?.v])
                    "TL" -> leading = num(0)
                    "Td" -> move(num(0), num(1))
                    "TD" -> { leading = -num(1); move(num(0), num(1)) }
                    "Tm" -> if (operands.size >= 6) { tlm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)); tm = tlm }
                    "T*" -> move(0.0, -leading)
                    "Tj" -> show(listOf(operands.lastOrNull()))
                    "TJ" -> show(operands.lastOrNull() as? List<*> ?: emptyList<Any?>())
                    "'" -> { move(0.0, -leading); show(listOf(operands.lastOrNull())) }
                    "\"" -> { move(0.0, -leading); show(listOf(operands.lastOrNull())) }
                    "BI" -> skipInlineImage()
                }
                operands.clear()
            }
            return out
        }

        fun move(dx: Double, dy: Double) {
            tlm = Matrix(1.0, 0.0, 0.0, 1.0, dx, dy) * tlm
            tm = tlm
        }

        fun show(parts: List<*>) {
            val text = StringBuilder()
            for (p in parts) if (p is Str) text.append(font.text(p.bytes))
            if (text.isEmpty()) return
            val m = tm * ctm
            out.add(PdfTextRun(round2(m.e), round2(m.f), text.toString()))
        }

        fun round2(v: Double) = kotlin.math.round(v * 100) / 100

        fun skipInlineImage() {
            val b = lx.b
            while (lx.pos + 1 < lx.end) {
                if (b[lx.pos] == 'E'.code.toByte() && b[lx.pos + 1] == 'I'.code.toByte() &&
                    (lx.pos == 0 || lx.ws(lx.at(lx.pos - 1))) && (lx.pos + 2 >= lx.end || lx.ws(lx.at(lx.pos + 2)))
                ) { lx.pos += 2; return }
                lx.pos++
            }
        }
    }

    private data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        operator fun times(o: Matrix) = Matrix(
            a * o.a + b * o.c, a * o.b + b * o.d,
            c * o.a + d * o.c, c * o.b + d * o.d,
            e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f,
        )

        companion object {
            val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }
}
