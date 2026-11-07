package com.google.antigravity.network

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** A task-scoped request cannot follow a redirect to another bridge. No discovery/replay. */
internal fun scopedSwarmCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .build().newCall(request)
