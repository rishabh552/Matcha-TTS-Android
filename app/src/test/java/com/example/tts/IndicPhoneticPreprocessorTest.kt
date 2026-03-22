package com.example.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicPhoneticPreprocessorTest {
    @Test
    fun hindi_text_is_transformed_to_phonetic_latin() {
        val result = IndicPhoneticPreprocessor.preprocess("नमस्ते दुनिया")

        assertEquals(ScriptClass.DEVANAGARI, result.dominantScript)
        assertTrue(result.transformedTokenCount >= 2)
        assertFalse(result.output.any { it.code in 0x0900..0x097F })
    }

    @Test
    fun tamil_text_is_transformed_to_phonetic_latin() {
        val result = IndicPhoneticPreprocessor.preprocess("வணக்கம் உலகம்")

        assertEquals(ScriptClass.TAMIL, result.dominantScript)
        assertTrue(result.transformedTokenCount >= 2)
        assertFalse(result.output.any { it.code in 0x0B80..0x0BFF })
    }

    @Test
    fun telugu_text_is_transformed_to_phonetic_latin() {
        val result = IndicPhoneticPreprocessor.preprocess("నమస్కారం ప్రపంచం")

        assertEquals(ScriptClass.TELUGU, result.dominantScript)
        assertTrue(result.transformedTokenCount >= 2)
        assertFalse(result.output.any { it.code in 0x0C00..0x0C7F })
    }

    @Test
    fun mixed_text_keeps_latin_and_punctuation() {
        val result = IndicPhoneticPreprocessor.preprocess("Hello नमस्ते உலகம்!")

        assertTrue(result.output.contains("Hello"))
        assertTrue(result.output.endsWith("!"))
        assertTrue(result.transformedTokenCount >= 2)
    }

    @Test
    fun unsupported_script_is_passed_through_safely() {
        val result = IndicPhoneticPreprocessor.preprocess("ಹಲೋ दुनिया")

        assertTrue(result.output.contains("ಹಲೋ"))
        assertFalse(result.output.contains("दुनिया"))
    }

    @Test
    fun numeric_tokens_in_hindi_context_are_expanded() {
        val result = IndicPhoneticPreprocessor.preprocess("यह 25 किताबें हैं")

        assertFalse(result.output.contains("25"))
        assertTrue(result.output.contains("do"))
        assertTrue(result.output.contains("paanch"))
    }

    @Test
    fun namaste_uses_pronunciation_override() {
        val devanagari = IndicPhoneticPreprocessor.preprocess("नमस्ते")
        val latin = IndicPhoneticPreprocessor.preprocess("namaste")

        assertEquals("nuh muh stay", devanagari.output)
        assertEquals("nuh muh stay", latin.output)
    }

    @Test
    fun hindi_phrase_uses_targeted_overrides() {
        val result = IndicPhoneticPreprocessor.preprocess("यह एक परीक्षण वाक्य है")

        assertEquals("yeh ayk pareekshan vaakya hey", result.output)
    }

    @Test
    fun zwj_in_conjunct_does_not_create_spacing_artifacts() {
        val result = IndicPhoneticPreprocessor.preprocess("परीक्\u200Dषण")

        assertFalse(result.output.contains("  "))
        assertTrue(result.output.contains("ksh"))
    }
}
