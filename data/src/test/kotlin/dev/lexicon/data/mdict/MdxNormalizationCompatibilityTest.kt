package dev.lexicon.data.mdict

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import java.util.Random

/** Frozen 1.19 normalization contract for a later fast path; no dictionary corpus is required. */
class MdxNormalizationCompatibilityTest {
    @Test fun everyAsciiSingleCharacterMatchesTheFrozenNormalizer() {
        for (code in 0 until 128) equivalent(code.toChar().toString(), "ASCII character $code")
    }

    @Test fun everyAsciiPairMatchesTheFrozenNormalizerInBothOrders() {
        for (first in 0 until 128) for (second in 0 until 128) {
            equivalent(charArrayOf(first.toChar(), second.toChar()).concatToString(), "ASCII pair $first,$second")
        }
    }

    @Test fun seededShortStringsCoverControlWhitespaceCaseAndBoundaryInteractions() {
        val random = Random(0x4D44583119L)
        val boundaries = listOf("", " ", "\t\r\n", "\u0000", "\u001c", "\u007f", "\"", "'", "((", "))", ".,:;!?", "\" \t", "\t \"")
        val emphasized = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz \t\r\n\u000b\u000c\"'.,:;!?()"
        repeat(12_000) { sample ->
            val length = random.nextInt(65)
            val body = buildString(length) {
                repeat(length) {
                    append(if (random.nextBoolean()) random.nextInt(128).toChar() else emphasized[random.nextInt(emphasized.length)])
                }
            }
            val before = boundaries[random.nextInt(boundaries.size)]
            val after = boundaries[random.nextInt(boundaries.size)]
            equivalent(before + body + after, "seeded ASCII sample $sample")
        }
    }

    @Test fun punctuationRemovalDoesNotTrimTheWhitespaceItExposes() {
        // This order is intentional: an ASCII fast path must not perform another final trim.
        val expected = linkedMapOf(
            "\" ALPHA \"" to " alpha ",
            "(( ALPHA ))" to " alpha ",
            "\"\tALPHA\t\"" to " alpha ",
            "'\t'" to " ",
            "\" \"" to " ",
            " \t((ALPHA))\r\n" to "alpha",
            "'\u001cALPHA\u001c'" to "\u001calpha\u001c",
            "\u001cALPHA\u001c" to "alpha",
            "\u0000ALPHA\u0000" to "\u0000alpha\u0000",
            "A\t\r\n\u000b\u000c B" to "a b",
            "A\u001cB" to "a\u001cb",
            "\"\u00a0ALPHA\u00a0\"" to " alpha ",
            "[ALPHA]" to "[alpha]",
            "ALPHA-BETA" to "alpha-beta",
        )
        expected.entries.forEachIndexed { index, (input, output) ->
            assertEquals("frozen order fixture $index", output, previousNormalize(input))
            assertEquals("production order fixture $index", output, MdxReader.normalize(input))
        }
    }

    @Test fun nfkcRootCaseAndExtendedCharactersKeepTheirExistingMeaning() {
        val samples = listOf(
            "", "I", "İ", "ı", "i\u0307", "I\u0307", "Iİıi", "Straße", "ẞ", "Σ σ ς", "ΟΣ ΟΣΑ",
            "ﬀ ﬁ ﬂ ﬃ ﬄ", "ＡＬＰＨＡ", "（ＡＬＰＨＡ）", "＂ ＷＯＲＤ ＂", "K Å Ω", "① Ⅳ ² ㍍",
            "café", "cafe\u0301", "n\u0303", "a\u0301\u0327", "\u0301A", "rəˈbʌst wɜːld ɪŋ",
            "\u00a0Word\u00a0", "A\u00a0B", "A\u2002B", "\u3000WORD\u3000", "A\u2007B",
            "A\u0085B", "A\u2028B", "A\u2029B", "A\u200bB", "A\u2060B", "\ufeffWORD\ufeff",
            "😀", "👩‍💻", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳🇬🇧", "✈️", "1️⃣", "A😀Z", "𐐀𐐨", "中文 文本",
            "क्‍ष", "العَرَبِيَّة", "עברית", "한글", "가", "\ud800", "\udc00", "A\ud800B", "A\udc00B",
        )
        val wrappers = listOf("" to "", "\"" to "\"", "( " to " )", "\t\n" to "\r\n", "Ａ" to "Ｚ")
        samples.forEachIndexed { index, sample ->
            wrappers.forEachIndexed { wrapperIndex, (before, after) ->
                equivalent(before + sample + after, "Unicode sample $index wrapper $wrapperIndex")
            }
        }
        assertEquals("i\u0307", MdxReader.normalize("İ"))
        assertEquals("ffi", MdxReader.normalize("ﬃ"))
        assertEquals("alpha", MdxReader.normalize("（ＡＬＰＨＡ）"))
    }

    @Test fun normalizationDoesNotApplyQueryOrMdxKeyLengthLimits() {
        val lengths = listOf(0, 1, 2, 31, 32, 63, 64, 127, 128, 199, 200, 201, 255, 256, 4095, 4096, 4097, 60_000)
        for (length in lengths) {
            val ascii = "A".repeat(length)
            equivalent(ascii, "ASCII length $length")
            assertEquals("result retains normalized length $length", length, MdxReader.normalize(ascii).length)
            equivalent("\" \t$ascii \t\"", "exposed whitespace length $length")
            val unit = "Ａﬃİ😀 \t"
            val unicode = unit.repeat((length + unit.length - 1) / unit.length).take(length)
            assertEquals(length, unicode.length)
            equivalent(unicode, "Unicode prefix length $length")
        }
    }

    private fun equivalent(input: String, label: String) {
        assertEquals(label, previousNormalize(input), MdxReader.normalize(input))
    }

    // Preserve this predecessor literally. Do not replace any step with production helpers.
    private fun previousNormalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .trim().trim('"', '\'', '.', ',', ':', ';', '!', '?', '(', ')').replace(Regex("\\s+"), " ")
}
