package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.config.ConfigEntry
import dev.hermeskotlin.core.config.ConfigSection
import dev.hermeskotlin.core.config.ConfigSummary
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Config page's own words in the app's language. ConfigCatalog keeps Desktop's English names, which search
 * still matches; these replace them on screen. A section the gateway names (a schema category) and a field with no
 * curated name keep the name built from the gateway's key.
 */

/** A section's name: one of Desktop's in the app's language, a schema category's as the gateway names it. */
@Composable
internal fun sectionLabel(section: ConfigSection): String =
    SECTION_LABELS[section.id]?.let { stringResource(it) } ?: section.label

/** A setting's name: the curated one in the app's language, else the one made from its key. */
@Composable
internal fun entryLabel(entry: ConfigEntry): String =
    FIELD_LABELS[entry.key]?.let { stringResource(it) } ?: entry.label

/** What a setting's row says it's set to. */
@Composable
internal fun summaryText(summary: ConfigSummary): String = when (summary) {
    is ConfigSummary.Text -> summary.text
    ConfigSummary.NotSet -> stringResource(Res.string.config_not_set)
    ConfigSummary.None -> stringResource(Res.string.config_none)
    ConfigSummary.On -> stringResource(Res.string.config_on)
    ConfigSummary.Off -> stringResource(Res.string.config_off)
    is ConfigSummary.Entries -> pluralStringResource(Res.plurals.config_entries, summary.count, summary.count)
}

private val SECTION_LABELS: Map<String, StringResource> = mapOf(
    "model" to Res.string.config_section_model,
    "chat" to Res.string.config_section_chat,
    "workspace" to Res.string.config_section_workspace,
    "safety" to Res.string.config_section_safety,
    "browser" to Res.string.config_section_browser,
    "memory" to Res.string.config_section_memory,
    "voice" to Res.string.config_section_voice,
    "advanced" to Res.string.config_section_advanced,
)

private val FIELD_LABELS: Map<String, StringResource> = mapOf(
    "model" to Res.string.config_label_model,
    "model_context_length" to Res.string.config_label_model_context_length,
    "fallback_providers" to Res.string.config_label_fallback_providers,
    "toolsets" to Res.string.config_label_toolsets,
    "timezone" to Res.string.config_label_timezone,
    "display.personality" to Res.string.config_label_display_personality,
    "display.show_reasoning" to Res.string.config_label_display_show_reasoning,
    "desktop.repo_scan_enabled" to Res.string.config_label_desktop_repo_scan_enabled,
    "desktop.repo_scan_roots" to Res.string.config_label_desktop_repo_scan_roots,
    "desktop.repo_scan_exclude_paths" to Res.string.config_label_desktop_repo_scan_exclude_paths,
    "agent.max_turns" to Res.string.config_label_agent_max_turns,
    "agent.image_input_mode" to Res.string.config_label_agent_image_input_mode,
    "agent.api_max_retries" to Res.string.config_label_agent_api_max_retries,
    "agent.service_tier" to Res.string.config_label_agent_service_tier,
    "agent.tool_use_enforcement" to Res.string.config_label_agent_tool_use_enforcement,
    "terminal.cwd" to Res.string.config_label_terminal_cwd,
    "terminal.backend" to Res.string.config_label_terminal_backend,
    "terminal.timeout" to Res.string.config_label_terminal_timeout,
    "terminal.persistent_shell" to Res.string.config_label_terminal_persistent_shell,
    "terminal.env_passthrough" to Res.string.config_label_terminal_env_passthrough,
    "terminal.docker_image" to Res.string.config_label_terminal_docker_image,
    "terminal.singularity_image" to Res.string.config_label_terminal_singularity_image,
    "terminal.modal_image" to Res.string.config_label_terminal_modal_image,
    "terminal.daytona_image" to Res.string.config_label_terminal_daytona_image,
    "file_read_max_chars" to Res.string.config_label_file_read_max_chars,
    "tool_output.max_bytes" to Res.string.config_label_tool_output_max_bytes,
    "tool_output.max_lines" to Res.string.config_label_tool_output_max_lines,
    "tool_output.max_line_length" to Res.string.config_label_tool_output_max_line_length,
    "code_execution.mode" to Res.string.config_label_code_execution_mode,
    "approvals.mode" to Res.string.config_label_approvals_mode,
    "approvals.timeout" to Res.string.config_label_approvals_timeout,
    "approvals.mcp_reload_confirm" to Res.string.config_label_approvals_mcp_reload_confirm,
    "command_allowlist" to Res.string.config_label_command_allowlist,
    "security.redact_secrets" to Res.string.config_label_security_redact_secrets,
    "security.allow_private_urls" to Res.string.config_label_security_allow_private_urls,
    "browser.allow_private_urls" to Res.string.config_label_browser_allow_private_urls,
    "browser.auto_local_for_private_urls" to Res.string.config_label_browser_auto_local_for_private_urls,
    "browser.use_real_profile" to Res.string.config_label_browser_use_real_profile,
    "checkpoints.enabled" to Res.string.config_label_checkpoints_enabled,
    "checkpoints.max_snapshots" to Res.string.config_label_checkpoints_max_snapshots,
    "voice.max_recording_seconds" to Res.string.config_label_voice_max_recording_seconds,
    "voice.auto_tts" to Res.string.config_label_voice_auto_tts,
    "voice.voice_chat_mode" to Res.string.config_label_voice_voice_chat_mode,
    "voice.gpt_live.voice" to Res.string.config_label_voice_gpt_live_voice,
    "voice.gpt_live.instructions" to Res.string.config_label_voice_gpt_live_instructions,
    "voice.client_direct" to Res.string.config_label_voice_client_direct,
    "stt.enabled" to Res.string.config_label_stt_enabled,
    "stt.echo_transcripts" to Res.string.config_label_stt_echo_transcripts,
    "stt.provider" to Res.string.config_label_stt_provider,
    "stt.streaming" to Res.string.config_label_stt_streaming,
    "stt.local.model" to Res.string.config_label_stt_local_model,
    "stt.local.language" to Res.string.config_label_stt_local_language,
    "stt.openai.model" to Res.string.config_label_stt_openai_model,
    "stt.openai.streaming_model" to Res.string.config_label_stt_openai_streaming_model,
    "stt.groq.model" to Res.string.config_label_stt_groq_model,
    "stt.mistral.model" to Res.string.config_label_stt_mistral_model,
    "stt.xai.model" to Res.string.config_label_stt_xai_model,
    "stt.deepinfra.model" to Res.string.config_label_stt_deepinfra_model,
    "stt.elevenlabs.model_id" to Res.string.config_label_stt_elevenlabs_model_id,
    "stt.elevenlabs.language_code" to Res.string.config_label_stt_elevenlabs_language_code,
    "stt.elevenlabs.tag_audio_events" to Res.string.config_label_stt_elevenlabs_tag_audio_events,
    "stt.elevenlabs.diarize" to Res.string.config_label_stt_elevenlabs_diarize,
    "tts.provider" to Res.string.config_label_tts_provider,
    "tts.edge.voice" to Res.string.config_label_tts_edge_voice,
    "tts.openai.model" to Res.string.config_label_tts_openai_model,
    "tts.openai.voice" to Res.string.config_label_tts_openai_voice,
    "tts.elevenlabs.voice_id" to Res.string.config_label_tts_elevenlabs_voice_id,
    "tts.elevenlabs.model_id" to Res.string.config_label_tts_elevenlabs_model_id,
    "tts.xai.voice_id" to Res.string.config_label_tts_xai_voice_id,
    "tts.xai.language" to Res.string.config_label_tts_xai_language,
    "tts.xai.speed" to Res.string.config_label_tts_xai_speed,
    "tts.xai.auto_speech_tags" to Res.string.config_label_tts_xai_auto_speech_tags,
    "tts.xai.optimize_streaming_latency" to Res.string.config_label_tts_xai_optimize_streaming_latency,
    "tts.xai.sample_rate" to Res.string.config_label_tts_xai_sample_rate,
    "tts.xai.bit_rate" to Res.string.config_label_tts_xai_bit_rate,
    "tts.minimax.model" to Res.string.config_label_tts_minimax_model,
    "tts.minimax.voice_id" to Res.string.config_label_tts_minimax_voice_id,
    "tts.mistral.model" to Res.string.config_label_tts_mistral_model,
    "tts.mistral.voice_id" to Res.string.config_label_tts_mistral_voice_id,
    "tts.gemini.model" to Res.string.config_label_tts_gemini_model,
    "tts.gemini.voice" to Res.string.config_label_tts_gemini_voice,
    "tts.neutts.model" to Res.string.config_label_tts_neutts_model,
    "tts.neutts.device" to Res.string.config_label_tts_neutts_device,
    "tts.kittentts.model" to Res.string.config_label_tts_kittentts_model,
    "tts.kittentts.voice" to Res.string.config_label_tts_kittentts_voice,
    "tts.piper.voice" to Res.string.config_label_tts_piper_voice,
    "tts.deepinfra.model" to Res.string.config_label_tts_deepinfra_model,
    "tts.deepinfra.voice" to Res.string.config_label_tts_deepinfra_voice,
    "memory.memory_enabled" to Res.string.config_label_memory_memory_enabled,
    "memory.user_profile_enabled" to Res.string.config_label_memory_user_profile_enabled,
    "memory.memory_char_limit" to Res.string.config_label_memory_memory_char_limit,
    "memory.user_char_limit" to Res.string.config_label_memory_user_char_limit,
    "memory.provider" to Res.string.config_label_memory_provider,
    "context.engine" to Res.string.config_label_context_engine,
    "compression.enabled" to Res.string.config_label_compression_enabled,
    "compression.threshold" to Res.string.config_label_compression_threshold,
    "compression.codex_gpt55_autoraise" to Res.string.config_label_compression_codex_gpt55_autoraise,
    "compression.target_ratio" to Res.string.config_label_compression_target_ratio,
    "compression.protect_last_n" to Res.string.config_label_compression_protect_last_n,
    "auxiliary.compression.timeout" to Res.string.config_label_auxiliary_compression_timeout,
    "delegation.model" to Res.string.config_label_delegation_model,
    "delegation.provider" to Res.string.config_label_delegation_provider,
    "delegation.max_iterations" to Res.string.config_label_delegation_max_iterations,
    "delegation.max_concurrent_children" to Res.string.config_label_delegation_max_concurrent_children,
    "delegation.child_timeout_seconds" to Res.string.config_label_delegation_child_timeout_seconds,
    "delegation.reasoning_effort" to Res.string.config_label_delegation_reasoning_effort,
    "updates.non_interactive_local_changes" to Res.string.config_label_updates_non_interactive_local_changes,
)
