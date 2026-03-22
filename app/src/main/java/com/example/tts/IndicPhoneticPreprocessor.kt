package com.example.tts

enum class ScriptClass {
    LATIN,
    DEVANAGARI,
    TAMIL,
    TELUGU,
    OTHER
}

data class PrepToken(
    val raw: String,
    val script: ScriptClass,
    val transformed: String
)

data class PrepResult(
    val output: String,
    val dominantScript: ScriptClass,
    val transformedTokenCount: Int,
    val tokens: List<PrepToken>
)

object IndicPhoneticPreprocessor {
    private data class TokenSegment(
        val text: String,
        val isWord: Boolean
    )

    private val DEVANAGARI_RANGE = 0x0900..0x097F
    private val TAMIL_RANGE = 0x0B80..0x0BFF
    private val TELUGU_RANGE = 0x0C00..0x0C7F

    private const val DEVANAGARI_HALANT = 0x094D
    private const val DEVANAGARI_NUKTA = 0x093C
    private const val TAMIL_PULLI = 0x0BCD
    private const val TELUGU_VIRAMA = 0x0C4D

    private val devanagariIndependentVowels = mapOf(
        0x0905 to "a",
        0x0906 to "aa",
        0x0907 to "i",
        0x0908 to "ii",
        0x0909 to "u",
        0x090A to "uu",
        0x090B to "ri",
        0x090F to "e",
        0x0910 to "ai",
        0x0913 to "o",
        0x0914 to "au"
    )

    private val devanagariVowelSigns = mapOf(
        0x093E to "aa",
        0x093F to "i",
        0x0940 to "ii",
        0x0941 to "u",
        0x0942 to "uu",
        0x0943 to "ri",
        0x0947 to "e",
        0x0948 to "ai",
        0x094B to "o",
        0x094C to "au"
    )

    private val devanagariConsonants = mapOf(
        0x0915 to "k",
        0x0916 to "kh",
        0x0917 to "g",
        0x0918 to "gh",
        0x0919 to "ng",
        0x091A to "ch",
        0x091B to "chh",
        0x091C to "j",
        0x091D to "jh",
        0x091E to "ny",
        0x091F to "t",
        0x0920 to "th",
        0x0921 to "d",
        0x0922 to "dh",
        0x0923 to "n",
        0x0924 to "t",
        0x0925 to "th",
        0x0926 to "d",
        0x0927 to "dh",
        0x0928 to "n",
        0x092A to "p",
        0x092B to "ph",
        0x092C to "b",
        0x092D to "bh",
        0x092E to "m",
        0x092F to "y",
        0x0930 to "r",
        0x0932 to "l",
        0x0935 to "v",
        0x0936 to "sh",
        0x0937 to "sh",
        0x0938 to "s",
        0x0939 to "h",
        0x0958 to "q",
        0x0959 to "kh",
        0x095A to "gh",
        0x095B to "z",
        0x095C to "r",
        0x095D to "rh",
        0x095E to "f",
        0x095F to "y"
    )

    private val devanagariStandaloneSigns = mapOf(
        0x0901 to "n",
        0x0902 to "n",
        0x0903 to "h",
        0x093D to ""
    )

    private val tamilIndependentVowels = mapOf(
        0x0B85 to "a",
        0x0B86 to "aa",
        0x0B87 to "i",
        0x0B88 to "ii",
        0x0B89 to "u",
        0x0B8A to "uu",
        0x0B8E to "e",
        0x0B8F to "ee",
        0x0B90 to "ai",
        0x0B92 to "o",
        0x0B93 to "oo",
        0x0B94 to "au"
    )

    private val tamilVowelSigns = mapOf(
        0x0BBE to "aa",
        0x0BBF to "i",
        0x0BC0 to "ii",
        0x0BC1 to "u",
        0x0BC2 to "uu",
        0x0BC6 to "e",
        0x0BC7 to "ee",
        0x0BC8 to "ai",
        0x0BCA to "o",
        0x0BCB to "oo",
        0x0BCC to "au"
    )

    private val tamilConsonants = mapOf(
        0x0B95 to "k",
        0x0B99 to "ng",
        0x0B9A to "s",
        0x0B9E to "ny",
        0x0B9F to "t",
        0x0BA3 to "n",
        0x0BA4 to "th",
        0x0BA8 to "n",
        0x0BAA to "p",
        0x0BAE to "m",
        0x0BAF to "y",
        0x0BB0 to "r",
        0x0BB2 to "l",
        0x0BB5 to "v",
        0x0BB4 to "zh",
        0x0BB3 to "l",
        0x0BB1 to "r",
        0x0BA9 to "n",
        0x0B9C to "j",
        0x0BB7 to "sh",
        0x0BB8 to "s",
        0x0BB9 to "h"
    )

    private val tamilStandaloneSigns = mapOf(
        0x0B82 to "m"
    )

    private val teluguIndependentVowels = mapOf(
        0x0C05 to "a",
        0x0C06 to "aa",
        0x0C07 to "i",
        0x0C08 to "ii",
        0x0C09 to "u",
        0x0C0A to "uu",
        0x0C0B to "ru",
        0x0C0E to "e",
        0x0C0F to "ee",
        0x0C10 to "ai",
        0x0C12 to "o",
        0x0C13 to "oo",
        0x0C14 to "au"
    )

    private val teluguVowelSigns = mapOf(
        0x0C3E to "aa",
        0x0C3F to "i",
        0x0C40 to "ii",
        0x0C41 to "u",
        0x0C42 to "uu",
        0x0C43 to "ru",
        0x0C46 to "e",
        0x0C47 to "ee",
        0x0C48 to "ai",
        0x0C4A to "o",
        0x0C4B to "oo",
        0x0C4C to "au"
    )

    private val teluguConsonants = mapOf(
        0x0C15 to "k",
        0x0C16 to "kh",
        0x0C17 to "g",
        0x0C18 to "gh",
        0x0C19 to "ng",
        0x0C1A to "ch",
        0x0C1B to "chh",
        0x0C1C to "j",
        0x0C1D to "jh",
        0x0C1E to "ny",
        0x0C1F to "t",
        0x0C20 to "th",
        0x0C21 to "d",
        0x0C22 to "dh",
        0x0C23 to "n",
        0x0C24 to "t",
        0x0C25 to "th",
        0x0C26 to "d",
        0x0C27 to "dh",
        0x0C28 to "n",
        0x0C2A to "p",
        0x0C2B to "ph",
        0x0C2C to "b",
        0x0C2D to "bh",
        0x0C2E to "m",
        0x0C2F to "y",
        0x0C30 to "r",
        0x0C32 to "l",
        0x0C35 to "v",
        0x0C36 to "sh",
        0x0C37 to "sh",
        0x0C38 to "s",
        0x0C39 to "h",
        0x0C33 to "l",
        0x0C31 to "r"
    )

    private val teluguStandaloneSigns = mapOf(
        0x0C01 to "n",
        0x0C02 to "n",
        0x0C03 to "h"
    )

    private val hindiDigits = listOf(
        "shoonya",
        "ek",
        "do",
        "teen",
        "chaar",
        "paanch",
        "chhah",
        "saat",
        "aath",
        "nau"
    )

    private val tamilDigits = listOf(
        "poojiyam",
        "onru",
        "irandu",
        "moondru",
        "naangu",
        "aindhu",
        "aaru",
        "yezhu",
        "ettu",
        "onbadhu"
    )

    private val teluguDigits = listOf(
        "sunna",
        "okati",
        "rendu",
        "moodu",
        "nalugu",
        "aidu",
        "aaru",
        "edu",
        "enimidi",
        "tommidi"
    )

    private val devanagariWordOverrides = mapOf(
        "नमस्ते" to "nuh muh stay",
        "यह" to "yeh",
        "एक" to "ayk",
        "परीक्षण" to "pareekshan",
        "वाक्य" to "vaakya",
        "है" to "hey"
    )

    private val latinWordOverrides = mapOf(
        "namaste" to "nuh muh stay"
    )

    fun preprocess(text: String): PrepResult {
        if (text.isBlank()) {
            return PrepResult(
                output = text,
                dominantScript = ScriptClass.OTHER,
                transformedTokenCount = 0,
                tokens = emptyList()
            )
        }

        val segments = tokenizePreservingSeparators(text)
        val tokens = mutableListOf<PrepToken>()
        val scriptCounts = mutableMapOf<ScriptClass, Int>()
        var transformedTokenCount = 0
        var recentIndicScript: ScriptClass? = null
        val out = StringBuilder(text.length + 32)

        for (segment in segments) {
            if (!segment.isWord) {
                out.append(segment.text)
                continue
            }

            val detected = detectTokenScript(segment.text)
            val hasDigits = containsAnyDigit(segment.text)
            val effectiveScript = if (detected == ScriptClass.OTHER && hasDigits) {
                recentIndicScript ?: ScriptClass.OTHER
            } else {
                detected
            }

            val transformed = transformToken(segment.text, effectiveScript)
            if (transformed != segment.text) {
                transformedTokenCount += 1
            }

            tokens += PrepToken(
                raw = segment.text,
                script = effectiveScript,
                transformed = transformed
            )
            out.append(transformed)

            if (effectiveScript == ScriptClass.DEVANAGARI ||
                effectiveScript == ScriptClass.TAMIL ||
                effectiveScript == ScriptClass.TELUGU
            ) {
                recentIndicScript = effectiveScript
            }

            val letters = countLetterCodePoints(segment.text)
            if (letters > 0 && detected != ScriptClass.OTHER) {
                scriptCounts[detected] = (scriptCounts[detected] ?: 0) + letters
            }
        }

        val dominantScript = scriptCounts.maxByOrNull { it.value }?.key ?: ScriptClass.OTHER

        return PrepResult(
            output = out.toString(),
            dominantScript = dominantScript,
            transformedTokenCount = transformedTokenCount,
            tokens = tokens
        )
    }

    internal fun detectTokenScript(token: String): ScriptClass {
        var latin = 0
        var devanagari = 0
        var tamil = 0
        var telugu = 0

        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            when {
                cp in DEVANAGARI_RANGE -> devanagari += 1
                cp in TAMIL_RANGE -> tamil += 1
                cp in TELUGU_RANGE -> telugu += 1
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN -> latin += 1
            }
            i += Character.charCount(cp)
        }

        val scored = listOf(
            ScriptClass.LATIN to latin,
            ScriptClass.DEVANAGARI to devanagari,
            ScriptClass.TAMIL to tamil,
            ScriptClass.TELUGU to telugu
        )
        val top = scored.maxByOrNull { it.second } ?: return ScriptClass.OTHER
        return if (top.second == 0) ScriptClass.OTHER else top.first
    }

    private fun transformToken(token: String, script: ScriptClass): String {
        expandSimpleNumericToken(token, script)?.let { return it }

        val transformed = when (script) {
            ScriptClass.DEVANAGARI -> transliterateIndicToken(
                token = token,
                independentVowels = devanagariIndependentVowels,
                vowelSigns = devanagariVowelSigns,
                consonants = devanagariConsonants,
                standaloneSigns = devanagariStandaloneSigns,
                virama = DEVANAGARI_HALANT,
                nukta = DEVANAGARI_NUKTA,
                dropFinalInherentVowel = true
            )

            ScriptClass.TAMIL -> transliterateIndicToken(
                token = token,
                independentVowels = tamilIndependentVowels,
                vowelSigns = tamilVowelSigns,
                consonants = tamilConsonants,
                standaloneSigns = tamilStandaloneSigns,
                virama = TAMIL_PULLI,
                nukta = null,
                dropFinalInherentVowel = false
            )

            ScriptClass.TELUGU -> transliterateIndicToken(
                token = token,
                independentVowels = teluguIndependentVowels,
                vowelSigns = teluguVowelSigns,
                consonants = teluguConsonants,
                standaloneSigns = teluguStandaloneSigns,
                virama = TELUGU_VIRAMA,
                nukta = null,
                dropFinalInherentVowel = true
            )

            else -> token
        }

        return applyPronunciationOverrides(token, transformed, script)
    }

    private fun applyPronunciationOverrides(
        token: String,
        transformed: String,
        script: ScriptClass
    ): String {
        if (token.isBlank()) return transformed

        return when (script) {
            ScriptClass.DEVANAGARI -> devanagariWordOverrides[token] ?: transformed
            ScriptClass.LATIN -> {
                val key = token.lowercase()
                latinWordOverrides[key] ?: transformed
            }
            else -> transformed
        }
    }

    private fun transliterateIndicToken(
        token: String,
        independentVowels: Map<Int, String>,
        vowelSigns: Map<Int, String>,
        consonants: Map<Int, String>,
        standaloneSigns: Map<Int, String>,
        virama: Int,
        nukta: Int?,
        dropFinalInherentVowel: Boolean
    ): String {
        val cps = toCodePoints(token)
        val out = StringBuilder(token.length * 2)
        var i = 0

        while (i < cps.size) {
            val cp = cps[i]
            if (Character.getType(cp) == Character.FORMAT.toInt()) {
                // Ignore ZWJ/ZWNJ style joiners in Indic clusters.
                i += 1
                continue
            }

            val independentVowel = independentVowels[cp]
            if (independentVowel != null) {
                out.append(independentVowel)
                i += 1
                continue
            }

            val consonant = consonants[cp]
            if (consonant != null) {
                var base = consonant
                var j = i + 1

                if (nukta != null && j < cps.size && cps[j] == nukta) {
                    j += 1
                }

                when {
                    j < cps.size && cps[j] == virama -> {
                        out.append(base)
                        i = j + 1
                    }

                    j < cps.size && vowelSigns.containsKey(cps[j]) -> {
                        out.append(base)
                        out.append(vowelSigns.getValue(cps[j]))
                        i = j + 1
                    }

                    else -> {
                        out.append(base)
                        out.append("a")
                        i = j
                    }
                }
                continue
            }

            val vowelSign = vowelSigns[cp]
            if (vowelSign != null) {
                out.append(vowelSign)
                i += 1
                continue
            }

            val standaloneSign = standaloneSigns[cp]
            if (standaloneSign != null) {
                out.append(standaloneSign)
                i += 1
                continue
            }

            if (cp == virama || (nukta != null && cp == nukta)) {
                i += 1
                continue
            }

            out.appendCodePoint(cp)
            i += 1
        }

        val raw = out.toString()
        if (!dropFinalInherentVowel) return raw
        if (raw.length <= 1) return raw
        if (raw.endsWith("aa")) return raw
        return if (raw.endsWith("a")) raw.dropLast(1) else raw
    }

    private fun expandSimpleNumericToken(token: String, script: ScriptClass): String? {
        if (script != ScriptClass.DEVANAGARI &&
            script != ScriptClass.TAMIL &&
            script != ScriptClass.TELUGU
        ) {
            return null
        }

        val asciiDigits = toAsciiDigits(token)
        if (asciiDigits.isEmpty() || !asciiDigits.all { it.isDigit() }) {
            return null
        }

        val words = when (script) {
            ScriptClass.DEVANAGARI -> hindiDigits
            ScriptClass.TAMIL -> tamilDigits
            ScriptClass.TELUGU -> teluguDigits
            else -> return null
        }

        return asciiDigits
            .map { digit -> words[digit - '0'] }
            .joinToString(" ")
    }

    private fun tokenizePreservingSeparators(text: String): List<TokenSegment> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<TokenSegment>()
        val current = StringBuilder()
        var currentIsWord: Boolean? = null
        var i = 0

        while (i < text.length) {
            val cp = text.codePointAt(i)
            val isWord = isWordCodePoint(cp)
            val chars = Character.charCount(cp)

            if (currentIsWord == null) {
                currentIsWord = isWord
            }

            if (currentIsWord != isWord) {
                result += TokenSegment(current.toString(), currentIsWord!!)
                current.setLength(0)
                currentIsWord = isWord
            }

            current.appendCodePoint(cp)
            i += chars
        }

        if (current.isNotEmpty() && currentIsWord != null) {
            result += TokenSegment(current.toString(), currentIsWord!!)
        }

        return result
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        val type = Character.getType(cp)
        return Character.isLetterOrDigit(cp) ||
            cp == '\''.code ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
    }

    private fun containsAnyDigit(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isDigit(cp) || isIndicDigit(cp)) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    private fun countLetterCodePoints(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                count += 1
            }
            i += Character.charCount(cp)
        }
        return count
    }

    private fun toAsciiDigits(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val mapped = mapIndicDigitToAscii(cp)
            if (mapped != null) {
                out.append(mapped)
            } else {
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun mapIndicDigitToAscii(cp: Int): Char? {
        return when (cp) {
            in 0x0966..0x096F -> ('0'.code + (cp - 0x0966)).toChar()
            in 0x0BE6..0x0BEF -> ('0'.code + (cp - 0x0BE6)).toChar()
            in 0x0C66..0x0C6F -> ('0'.code + (cp - 0x0C66)).toChar()
            in '0'.code..'9'.code -> cp.toChar()
            else -> null
        }
    }

    private fun isIndicDigit(cp: Int): Boolean {
        return cp in 0x0966..0x096F || cp in 0x0BE6..0x0BEF || cp in 0x0C66..0x0C6F
    }

    private fun toCodePoints(text: String): IntArray {
        val out = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.add(cp)
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }
}
