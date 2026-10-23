package io.theficos.ereader.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringParityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `every UI locale contains exactly the default translatable keys`() {
        val res = translationResourceDirectory()
        assertThat(catalog(File(res, "values")).resources).isNotEmpty()
        assertWithMessage("Missing or unexpected translated resources").that(gaps(res)).isEmpty()
    }

    @Test
    fun `a new locale with a missing key fails until it is complete`() {
        val res = temporary.newFolder()
        val defaults = File(res, "values").apply { mkdir() }
        val italian = File(res, "values-it").apply { mkdir() }
        File(defaults, "strings.xml").writeText("<resources><string name=\"a\">A</string></resources>")
        File(defaults, "feature.xml").writeText("<resources><plurals name=\"b\"><item quantity=\"other\">B</item></plurals></resources>")
        File(italian, "strings.xml").writeText("<resources><string name=\"a\">A</string></resources>")
        assertThat(gaps(res)).containsExactly("values-it: missing plurals/b")
        File(italian, "other-name.xml").writeText("<resources><plurals name=\"b\"><item quantity=\"other\">B</item></plurals></resources>")
        assertThat(gaps(res)).isEmpty()
    }

    @Test
    fun `translation-only keys fail and non-locale qualifiers are ignored`() {
        val res = temporary.newFolder()
        File(res, "values").apply { mkdir() }
        File(res, "values-night").apply { mkdir() }
        File(res, "values-b+it+IT").apply { mkdir() }.let {
            File(it, "strings.xml").writeText("<resources><string name=\"orphan\">X</string></resources>")
        }
        assertThat(gaps(res)).containsExactly("values-b+it+IT: unexpected string/orphan")
    }

    @Test
    fun `format arguments may move but cannot disappear or change type`() {
        val res = temporary.newFolder()
        val english = File(res, "values").apply { mkdir() }
        val italian = File(res, "values-it").apply { mkdir() }
        File(english, "strings.xml").writeText("""<resources><string name="a">%1${'$'}s %2${'$'}d</string></resources>""")
        val file = File(italian, "strings.xml")
        file.writeText("""<resources><string name="a">%2${'$'}d %1${'$'}s</string></resources>""")
        assertThat(gaps(res)).isEmpty()
        file.writeText("""<resources><string name="a">%1${'$'}s %2${'$'}s</string></resources>""")
        assertThat(gaps(res)).containsExactly("values-it: format string/a")
        file.writeText("""<resources><string name="a">%1${'$'}s</string></resources>""")
        assertThat(gaps(res)).containsExactly("values-it: format string/a")
    }

    @Test
    fun `blank duplicate and invalid format resources fail with their key`() {
        val res = temporary.newFolder()
        File(res, "values").apply { mkdir() }.let {
            File(it, "strings.xml").writeText("""<resources><string name="a">A</string><string name="b">%d</string></resources>""")
        }
        File(res, "values-it").apply { mkdir() }.let {
            File(it, "strings.xml").writeText("""<resources><string name="a"> </string><string name="a">A</string><string name="b">%q</string></resources>""")
        }
        assertThat(gaps(res)).containsAtLeast("values-it: blank string/a", "values-it: duplicate string/a", "values-it: format string/b")
    }

    @Test
    fun `plural branches and array entries preserve their own arguments`() {
        val res = temporary.newFolder()
        val english = File(res, "values").apply { mkdir() }
        val italian = File(res, "values-it").apply { mkdir() }
        val content = """<resources><plurals name="a"><item quantity="one">One</item><item quantity="other">%d items</item></plurals><string-array name="b"><item>%s</item><item>B</item></string-array></resources>"""
        File(english, "strings.xml").writeText(content)
        val file = File(italian, "strings.xml")
        file.writeText(content.replace("%d items", "%s items"))
        assertThat(gaps(res)).containsExactly("values-it: format plurals/a[other]")
        file.writeText(content.replace("<item>B</item>", ""))
        assertThat(gaps(res)).containsExactly("values-it: entries string-array/b")
        file.writeText(content.replace("<item quantity=\"other\">%d items</item>", ""))
        assertThat(gaps(res)).containsExactly("values-it: missing other plurals/a")
    }

    private data class Resource(val variants: Map<String, String>, val formatted: Boolean)
    private data class Catalog(val resources: Map<String, Resource>, val problems: List<String>)

    private fun gaps(res: File): List<String> {
        val defaults = catalog(File(res, "values"))
        return defaults.problems.map { "values: $it" } +
            res.listFiles().orEmpty().filter { it.isDirectory && UI_LOCALE_FOLDER.matches(it.name) }
                .sortedBy { it.name }.flatMap { folder ->
                    val translated = catalog(folder)
                    val expected = defaults.resources
                    val actual = translated.resources
                    val problems = translated.problems.toMutableList()
                    problems += (expected.keys - actual.keys).sorted().map { "missing $it" }
                    problems += (actual.keys - expected.keys).sorted().map { "unexpected $it" }
                    for (key in expected.keys.intersect(actual.keys).sorted()) {
                        val source = expected.getValue(key)
                        val target = actual.getValue(key)
                        if (key.startsWith("string-array/") && source.variants.keys != target.variants.keys) {
                            problems += "entries $key"
                            continue
                        }
                        for ((variant, text) in target.variants) {
                            val reference = source.variants[variant] ?: source.variants["other"] ?: continue
                            val label = key + if (variant.isEmpty()) "" else "[$variant]"
                            if (source.formatted != target.formatted || runCatching {
                                source.formatted && arguments(reference) != arguments(text)
                            }.getOrDefault(true)) problems += "format $label"
                        }
                    }
                    problems.map { "${folder.name}: $it" }
                }
    }

    private fun catalog(folder: File): Catalog {
        val resources = linkedMapOf<String, Resource>()
        val problems = mutableListOf<String>()
        folder.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
            (0 until children.length).mapNotNull { children.item(it) as? Element }
                .filter { it.tagName in setOf("string", "plurals", "string-array") }
                .filterNot { it.getAttribute("translatable") == "false" }.forEach { element ->
                    val key = "${element.tagName}/${element.getAttribute("name")}"
                    val variants = linkedMapOf<String, String>()
                    if (element.tagName == "string") variants[""] = element.textContent else {
                        val items = element.getElementsByTagName("item")
                        for (i in 0 until items.length) {
                            val item = items.item(i) as Element
                            val variant = if (element.tagName == "plurals") item.getAttribute("quantity") else i.toString()
                            if (variants.put(variant, item.textContent) != null) problems += "duplicate $key[$variant]"
                        }
                        if (element.tagName == "plurals" && "other" !in variants) problems += "missing other $key"
                    }
                    if (variants.isEmpty() || variants.values.any { it.trim().trim('"').isBlank() }) problems += "blank $key"
                    if (resources.put(key, Resource(variants, element.getAttribute("formatted") != "false")) != null) problems += "duplicate $key"
                }
        }
        return Catalog(resources, problems)
    }

    /** Compare argument positions and conversion types, allowing translators to reorder them. */
    private fun arguments(text: String): Map<Int, Set<String>> {
        val result = mutableMapOf<Int, MutableSet<String>>()
        var cursor = 0
        var implicit = 0
        var previous = 0
        while (true) {
            val start = text.indexOf('%', cursor)
            if (start < 0) break
            val match = FORMAT.find(text, start)
            require(match != null && match.range.first == start)
            cursor = match.range.last + 1
            val conversion = match.groupValues[4]
            if (conversion == "%" || conversion == "n") continue
            require(conversion.single() in "bBhHsScCdoxXeEfgGaA" || match.groupValues[3].isNotEmpty())
            val index = when {
                match.groupValues[2].contains('<') -> previous.also { require(it > 0) }
                match.groupValues[1].isNotEmpty() -> match.groupValues[1].toInt().also { require(it > 0) }
                else -> ++implicit
            }
            previous = index
            result.getOrPut(index) { mutableSetOf() } += match.groupValues[3].lowercase() + conversion.lowercase()
        }
        return result
    }

    private companion object {
        val FORMAT = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)\d*(?:\.\d+)?([tT])?([a-zA-Z%])""")
    }
}

internal fun translationResourceDirectory(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        listOf(File(dir, "src/main/res"), File(dir, "app/src/main/res"))
            .firstOrNull { File(it, "values").isDirectory }?.let { return it }
        dir = dir.parentFile
    }
    error("App resources not found")
}

internal val UI_LOCALE_FOLDER = Regex("values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\\+[^/]+)")
