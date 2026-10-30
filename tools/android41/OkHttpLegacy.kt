// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.*

// OkHttp3.12 is the final line supporting Android API16; keep source API adaptation local.
val Response.body: ResponseBody? get() = body()
val Response.code: Int get() = code()
val Request.body: RequestBody? get() = body()
val Request.url: HttpUrl get() = url()
val Request.method: String get() = method()
val OkHttpClient.callTimeoutMillis: Int get() = callTimeoutMillis()
val HttpUrl.host: String get() = host()
val HttpUrl.encodedPath: String get() = encodedPath()
val HttpUrl.scheme: String get() = scheme()
val OkHttpClient.dispatcher: Dispatcher get() = dispatcher()
val OkHttpClient.connectionPool: ConnectionPool get() = connectionPool()
val Dispatcher.executorService: java.util.concurrent.ExecutorService get() = executorService()
fun String.toMediaType(): MediaType = requireNotNull(MediaType.parse(this))
fun String.toRequestBody(type: MediaType?): RequestBody = RequestBody.create(type, this)
fun ByteArray.toRequestBody(type: MediaType?): RequestBody = RequestBody.create(type, this)
fun String.toResponseBody(type: MediaType? = null): ResponseBody = ResponseBody.create(type, this)
fun ByteArray.toResponseBody(type: MediaType? = null): ResponseBody = ResponseBody.create(type, this)
