package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiConfig(
    val configured: Boolean,
    @SerialName("base_url_host") val baseUrlHost: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("sources_enabled") val sourcesEnabled: List<String> = emptyList(),
    @SerialName("daily_budget") val dailyBudget: Int = 0,
    @SerialName("regen_daily_limit") val regenDailyLimit: Int = 0,
)

@Serializable
data class AiStyle(
    val tone: String = "neutral",
    val language: String = "auto",
)

@Serializable
data class AiPreferences(
    @SerialName("ai_enabled") val aiEnabled: Boolean,
    val style: AiStyle = AiStyle(),
)

@Serializable
data class AiPreferencesBody(
    @SerialName("ai_enabled") val aiEnabled: Boolean? = null,
    val style: AiStyle? = null,
)

@Serializable
data class Citation(
    val kind: String,
    val title: String,
    val url: String? = null,
    val snippet: String = "",
)

@Serializable
data class AuthorInsight(
    val bio: String? = null,
    @SerialName("notable_works") val notableWorks: List<String>? = null,
)

@Serializable
data class SeriesInsight(
    val name: String,
    val position: Int? = null,
    val context: String? = null,
)

@Serializable
data class ComparativeAnchor(
    val book: String,
    val author: String,
    @SerialName("similar_in") val similarIn: String,
    @SerialName("different_in") val differentIn: String? = null,
)

@Serializable
data class BookInsightPayload(
    val intro: String? = null,
    val author: AuthorInsight? = null,
    val series: SeriesInsight? = null,
    val analysis: String? = null,
    @SerialName("content_warnings") val contentWarnings: List<String>? = null,
    // PR-ε v3 catch-up: server-side `themes` field has existed since PR3.
    val themes: List<String>? = null,
    // PR-ε / schema v4 — per-book depth fields. All optional, all default null.
    @SerialName("theme_analysis") val themeAnalysis: Map<String, String>? = null,
    @SerialName("craft_notes") val craftNotes: String? = null,
    @SerialName("comparative_anchors") val comparativeAnchors: List<ComparativeAnchor>? = null,
    @SerialName("distinctive_take") val distinctiveTake: String? = null,
    @SerialName("discussion_prompts") val discussionPrompts: List<String>? = null,
    val confidence: String = "low",
    @SerialName("schema_version") val schemaVersion: Int = 4,
)

@Serializable
data class BookInsightResponse(
    val payload: BookInsightPayload,
    val sources: List<Citation>,
    @SerialName("model_id") val modelId: String,
    @SerialName("prompt_version") val promptVersion: String,
    @SerialName("generated_at") val generatedAt: String,
)

@Serializable
data class InsightLookupBody(
    val identity: DocumentIdentity,
    val bundle: MetadataBundle,
)

@Serializable
data class InsightGetBody(val identity: DocumentIdentity)

/** Body of the inner detail object on a 429 response. */
@Serializable
data class QuotaInfo(
    val used: Int,
    val limit: Int,
    @SerialName("resets_at") val resetsAt: String,
)

/**
 * One entry in [AiHealthResponse.retrievalSources].
 *
 * Tri-state [reachable]:
 *  - `null`  → never observed this process lifetime.
 *  - `true`  → last HTTP call to this source completed (any status code).
 *  - `false` → last call failed at the transport level (timeout, DNS, …).
 */
@Serializable
data class RetrievalSourceHealth(
    val name: String,
    val reachable: Boolean? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
)

/**
 * Body of `GET /ai/v1/health`. Process-local snapshot from one server replica.
 * On process restart all fields reset to null; that is part of the contract
 * and is rendered as "not yet checked" by the UI.
 */
@Serializable
data class AiHealthResponse(
    @SerialName("provider_reachable") val providerReachable: Boolean? = null,
    @SerialName("provider_last_checked_at") val providerLastCheckedAt: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("last_failure_at") val lastFailureAt: String? = null,
    @SerialName("last_failure_class") val lastFailureClass: String? = null,
    @SerialName("retrieval_sources") val retrievalSources: List<RetrievalSourceHealth> = emptyList(),
)
