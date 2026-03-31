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

        private val ALL_KNOWN_TAGS = Regex(
            "</?(?:thinking|think|response|answer|output)>",
            RegexOption.IGNORE_CASE
        )

        fun parse(rawOutput: String): LLMResponse {
            val thinking = THINKING_REGEX.find(rawOutput)?.groupValues?.get(1)?.trim()
            val response = RESPONSE_REGEX.find(rawOutput)?.groupValues?.get(1)?.trim()

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
                    LLMResponse(null, stripTags(rawOutput).trim())
                }
            }
        }

        /**
         * Remove any leftover known tags so the user never sees raw markup.
         */
        private fun stripTags(text: String): String {
            return text.replace(ALL_KNOWN_TAGS, "").trim()
        }
    }
}
