package dev.lexicon.data.mdict

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Freeze 1.17's visible text contract while changing the cost of inserting line breaks. */
class MdxTextCompatibilityTest {
    @Test fun visibleTextMatchesThePreviousParserAcrossHtmlContexts() {
        val fragments = listOf(
            "plain text", "a&nbsp;&nbsp;b &amp; c &#x2014; d", "a\r\nb\t c",
            "<p>alpha</p><p>beta</p>", "<div>outer<section>inner<br>next</section>tail</div>",
            "<ul><li>one<li>two<ul><li>nested</li></ul></ul>",
            "<table><tr><td>first</td><td>second</td></tr><tr><td>last</td></tr></table>",
            "<table>before<tr><td><p>inside</p>tail</td></tr>after</table>",
            "<tr><td>orphan</td></tr><div>after</div>", "<br><br><hr><div></div><p>last</p>",
            "<p>not closed<div>nested<p>last", "<pre>keep\n lines\t here</pre><textarea>a\nb</textarea>",
            "<script>secret</script><style>hidden</style><iframe>frame</iframe><object>object</object><embed>visible",
            "<p>before<img src='https://invalid.test/pixel'>after<audio>audio</audio><video>video</video></p>",
            "<h1>title</h1><blockquote>quote</blockquote><h2>next</h2>",
            "<b>ＡＬＰＨＡ</b><br>meaning", "<p>ALPHA</p><p>first sense</p><p>second sense</p>",
            "<font face='Kingsoft Phonetic Plain, Tahoma'>rE5bQst</font><br><font face='Other'>E5</font>",
            "<font face='Kingsoft Phonetic Plain'>%</font>", "<p>${"long text ".repeat(7_000)}</p><div>end</div>",
        )
        val wrappers = listOf<Pair<String, String>>("" to "", "<div>" to "</div>", "<table><tr><td>" to "</td></tr></table>")
        for ((i, fragment) in fragments.withIndex()) for ((j, wrapper) in wrappers.withIndex()) {
            val html = wrapper.first + fragment + wrapper.second
            for (headword in listOf(null, "alpha")) assertEquals("fixture $i wrapper $j headword=$headword",
                previousText(html, headword), MdxReader.plainText(html, headword))
        }
    }

    @Test fun paragraphAndPhoneticContractIsExplicit() {
        assertEquals("rəˈbʌst\nfirst sense\nsecond sense", MdxReader.plainText(
            "<b>robust</b><hr><font face='Kingsoft Phonetic Plain'>rE5bQst</font><p>first sense</p><div>second sense</div>", "robust"))
        assertEquals(60_000, MdxReader.plainText("x".repeat(60_100)).length)
        assertEquals("a b\nc d", MdxReader.plainText("<p>a&nbsp; b</p><p>c\t d</p>"))
    }

    // Deliberately preserved predecessor. Do not optimize this differential-test oracle.
    private fun previousText(html: String, headword: String?): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("font[face]").filter { element -> element.attr("face").split(',').any {
            it.trim().equals("Kingsoft Phonetic Plain", ignoreCase = true)
        } }.forEach { it.text(KingsoftPhonetics.decode(it.wholeText()) ?: "〔音标未显示〕") }
        doc.select("script,style,iframe,object,embed,img,audio,video,link").remove()
        doc.select("br").forEach { it.after("\n") }
        doc.select("p,div,li,section,article,blockquote,h1,h2,h3,h4,hr,tr").forEach { it.before("\n").after("\n") }
        val lines = doc.body().wholeText().replace('\u00a0', ' ').lineSequence()
            .map { it.trim().replace(Regex("[\\t ]+"), " ") }.filter { it.isNotBlank() }.toList()
        return (if (headword != null && lines.size > 1 && MdxReader.normalize(lines.first()) == MdxReader.normalize(headword))
            lines.drop(1) else lines).joinToString("\n").take(60_000)
    }
}
