package io.theficos.ereader.core.model

data class Progress(
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    // pr-α (Bundle 3) / coordinator §3.10: terminal-state invariant.
    // Mutually exclusive with `finishedAt` on the wire — the sync push
    // path always sets at most one. `null` for in-progress / unfinished.
    val abandonedAt: Long? = null,
) {
    init {
        require(percent in 0.0..1.0) { "percent must be in [0,1]" }
    }
}
