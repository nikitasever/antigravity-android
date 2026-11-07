package com.google.antigravity.model

/** Immutable routing and ownership for one PC task batch. Not an authorization token. */
internal data class SwarmRunOrigin(val requestId: String, val conversationId: String, val bridgeUrl: String) {
    init { require(requestId.isNotBlank() && conversationId.isNotBlank() && bridgeUrl.isNotBlank()) }
    // Sticky per-launch intent: cancel may reach the bridge before dispatch is accepted.
    var cancellationRequested: Boolean = false
        private set
    fun requestCancellation() { cancellationRequested = true }
    fun accepts(snapshot: SwarmSnapshot): Boolean = snapshot.batchId.isBlank() || snapshot.batchId == requestId
    fun isCurrent(current: SwarmRunOrigin?): Boolean = this === current
    fun matchesCancellation(id: String): Boolean = id.isNotBlank() && id == requestId
}
