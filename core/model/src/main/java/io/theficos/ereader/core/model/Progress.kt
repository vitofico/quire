package io.theficos.ereader.core.model

data class Progress(
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
    val finishedAt: Long? = null,
) {
    init {
        require(percent in 0.0..1.0) { "percent must be in [0,1]" }
    }
}
