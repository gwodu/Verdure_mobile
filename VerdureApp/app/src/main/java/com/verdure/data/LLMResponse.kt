package com.verdure.data

/**
 * Parsed LLM response with optional thinking section.
 * Handles multiple tag formats from different models:
 * - <thinking>/<think> for reasoning (Qwen uses <think>)
 * - <response>/<answer> for final output
 */
data class LLMResponse(
    val thinking: String?,
    val response: String
) {
    companion object {

        private val THINKING_REGEX = Regex(
            "<(?:thinking|think)>(.*?)</(?:thinking|think)>",
            RegexOption.DOT_MATCHES_ALL
        )

        private val RESPONSE_REGEX = Regex(
            "<(?:response|answer|output)>(.*?)</(?:response|answer|output)>",
            RegexOption.DOT_MATCHES_ALL
        )

        private val THINKING_PREFIX_REGEX = Regex(
            "(?is)^\\s*(?:thinking|thoughts?|reasoning|analysis)\\s*:\\s*(.+?)(?=\\n\\s*(?:response|answer|final)\\s*:|$)"
        )

        private val RESPONSE_PREFIX_REGEX = Regex(
            "(?is)^\\s*(?:response|answer|final)\\s*:\\s*(.+)$"
        )

        private val ALL_KNOWN_TAGS = Regex(
            "</?(?:thinking|think|response|answer|output)>",
            RegexOption.IGNORE_CASE
        )

        fun parse(rawOutput: String): LLMResponse {
            val cleanedRaw = stripTags(rawOutput).trim()
            val thinking = THINKING_REGEX.find(rawOutput)?.groupValues?.get(1)?.trim()
                ?: extractSection(cleanedRaw, "thinking")
            val response = RESPONSE_REGEX.find(rawOutput)?.groupValues?.get(1)?.trim()
                ?: extractSection(cleanedRaw, "response")
                ?: RESPONSE_PREFIX_REGEX.find(cleanedRaw)?.groupValues?.get(1)?.trim()

            return when {
                response != null -> {
                    LLMResponse(thinking, stripTags(response))
                }
                thinking != null -> {
                    val remainingText = rawOutput
                        .replace(THINKING_REGEX, "")
                        .let { stripTags(it) }
                        .trim()
                    LLMResponse(thinking, remainingText.ifEmpty { rawOutput.trim() })
                }
                else -> {
                    val prefixedThinking = THINKING_PREFIX_REGEX.find(cleanedRaw)?.groupValues?.get(1)?.trim()
                    if (!prefixedThinking.isNullOrBlank()) {
                        val finalText = RESPONSE_PREFIX_REGEX.find(cleanedRaw)?.groupValues?.get(1)?.trim()
                            ?: cleanedRaw.replace(THINKING_PREFIX_REGEX, "").trim()
                        LLMResponse(prefixedThinking, finalText.ifBlank { cleanedRaw })
                    } else {
                        LLMResponse(null, cleanedRaw)
                    }
                }
            }
        }

        private fun extractSection(text: String, section: String): String? {
            val regex = Regex(
                "(?is)$section\\s*:\\s*(.+?)(?=\\n\\s*(?:thinking|response|answer|final)\\s*:|$)"
            )
            return regex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        /**
         * Remove any leftover known tags so the user never sees raw markup.
         */
        private fun stripTags(text: String): String {
            return text.replace(ALL_KNOWN_TAGS, "").trim()
        }
    }
}
