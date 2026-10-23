package io.github.ioannes78.voica.ai

/**
 * Temporary compatibility home for the historical summary result schema version.
 *
 * Online prompt/provider execution has been removed. This object exists only because
 * SummaryResultCodec still references the former symbol; it carries no prompt or
 * network/runtime behavior and should be folded into the codec during shim cleanup.
 */
internal object SummaryPromptFactory {
    const val RESULT_SCHEMA_VERSION = 1
}
