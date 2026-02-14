package com.bloop.sdk

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight bloop error reporting client for Android/JVM.
 * Zero external dependencies beyond the standard library.
 *
 * Usage:
 * ```
 * // Initialize once (e.g. in Application.onCreate)
 * BloopClient.configure(
 *     endpoint = "https://bloop.yoursite.com",
 *     secret = "your-hmac-secret",
 *     environment = "production",
 *     release = "1.0.0"
 * )
 *
 * // Capture errors
 * BloopClient.shared?.capture(exception)
 * BloopClient.shared?.capture(exception, route = "/home", screen = "HomeScreen")
 *
 * // Flush on shutdown
 * BloopClient.shared?.flush()
 * ```
 */
class BloopClient(
    private val endpoint: String,
    private val secret: String,
    private val projectKey: String? = null,
    private val source: String = "android",
    private val environment: String,
    private val release: String,
    private val appVersion: String? = null,
    private val buildNumber: String? = null,
    private val maxBufferSize: Int = 20,
    private val flushIntervalMs: Long = 5000L,
    private val enrichDevice: Boolean = true,
) {
    private val buffer = CopyOnWriteArrayList<ErrorEvent>()
    private val traceBuffer = CopyOnWriteArrayList<String>() // pre-serialized trace JSON
    private val timer = Timer("bloop-flush", true)
    private val deviceInfo: Map<String, String> by lazy { collectDeviceInfo() }

    init {
        timer.schedule(object : TimerTask() {
            override fun run() { flush() }
        }, flushIntervalMs, flushIntervalMs)
    }

    /** Capture a Throwable with optional context. */
    fun capture(
        throwable: Throwable,
        route: String? = null,
        screen: String? = null,
        httpStatus: Int? = null,
        requestId: String? = null,
        userIdHash: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val mergedMetadata = mergeDeviceMetadata(metadata)
        val event = ErrorEvent(
            timestamp = System.currentTimeMillis(),
            source = source,
            environment = environment,
            release = release,
            appVersion = appVersion,
            buildNumber = buildNumber,
            routeOrProcedure = route,
            screen = screen,
            errorType = throwable.javaClass.simpleName,
            message = throwable.message ?: throwable.toString(),
            stack = throwable.stackTraceToString().take(8192),
            httpStatus = httpStatus,
            requestId = requestId,
            userIdHash = userIdHash,
            metadata = mergedMetadata,
        )
        enqueue(event)
    }

    /** Capture a structured error event. */
    fun capture(
        errorType: String,
        message: String,
        route: String? = null,
        screen: String? = null,
        stack: String? = null,
        httpStatus: Int? = null,
        requestId: String? = null,
        userIdHash: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val mergedMetadata = mergeDeviceMetadata(metadata)
        val event = ErrorEvent(
            timestamp = System.currentTimeMillis(),
            source = source,
            environment = environment,
            release = release,
            appVersion = appVersion,
            buildNumber = buildNumber,
            routeOrProcedure = route,
            screen = screen,
            errorType = errorType,
            message = message,
            stack = stack?.take(8192),
            httpStatus = httpStatus,
            requestId = requestId,
            userIdHash = userIdHash,
            metadata = mergedMetadata,
        )
        enqueue(event)
    }

    /** Start an LLM trace. Call [LLMTrace.end] when the operation completes to enqueue it for flushing. */
    fun startTrace(
        name: String,
        sessionId: String? = null,
        userId: String? = null,
        input: String? = null,
        metadata: Map<String, Any?>? = null,
        promptName: String? = null,
        promptVersion: String? = null,
    ): LLMTrace {
        return LLMTrace(
            client = this, name = name, sessionId = sessionId,
            userId = userId, input = input, metadata = metadata,
            promptName = promptName, promptVersion = promptVersion
        )
    }

    /** Install a global uncaught exception handler that reports to bloop, then delegates to the default handler. */
    fun installUncaughtExceptionHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            capture(throwable)
            flushSync()
            default?.uncaughtException(thread, throwable)
        }
    }

    /** Flush buffered events and traces asynchronously. */
    fun flush() {
        val events = drainBuffer()
        if (events.isNotEmpty()) Thread { sendBatch(events) }.start()
        flushTraces()
    }

    /** Flush buffered events and traces synchronously. Use for crash handlers / shutdown. */
    fun flushSync() {
        val events = drainBuffer()
        if (events.isNotEmpty()) sendBatch(events)
        flushTracesSync()
    }

    /** Stop the flush timer and send remaining events. Call on app shutdown. */
    fun close() {
        timer.cancel()
        flushSync()
    }

    /** Called by [LLMTrace.end] to buffer a completed trace for flushing. */
    internal fun enqueueTrace(trace: LLMTrace) {
        traceBuffer.add(trace.toJson())
        if (traceBuffer.size >= maxBufferSize) {
            flushTraces()
        }
    }

    // -- Private --

    private fun mergeDeviceMetadata(userMetadata: Map<String, Any?>?): Map<String, Any?>? {
        if (!enrichDevice) return userMetadata
        if (deviceInfo.isEmpty()) return userMetadata

        // Device info as base, user-provided metadata takes precedence
        val merged = mutableMapOf<String, Any?>()
        merged.putAll(deviceInfo)
        userMetadata?.let { merged.putAll(it) }
        return merged
    }

    private fun collectDeviceInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            // Try Android Build class via reflection
            val buildClass = Class.forName("android.os.Build")
            buildClass.getField("MODEL").get(null)?.toString()?.let { info["device_model"] = it }
            buildClass.getField("MANUFACTURER").get(null)?.toString()?.let { info["device_manufacturer"] = it }
            buildClass.getField("BRAND").get(null)?.toString()?.let { info["device_brand"] = it }

            val versionClass = Class.forName("android.os.Build\$VERSION")
            versionClass.getField("RELEASE").get(null)?.toString()?.let { info["os_version"] = it }
            versionClass.getField("SDK_INT").getInt(null).let { info["api_level"] = it.toString() }

            info["os_name"] = "Android"
        } catch (_: Exception) {
            // Not on Android — fall back to JVM system properties
            System.getProperty("os.name")?.let { info["os_name"] = it }
            System.getProperty("os.version")?.let { info["os_version"] = it }
            System.getProperty("os.arch")?.let { info["os_arch"] = it }
        }
        return info
    }

    private fun enqueue(event: ErrorEvent) {
        buffer.add(event)
        if (buffer.size >= maxBufferSize) {
            flush()
        }
    }

    private fun drainBuffer(): List<ErrorEvent> {
        if (buffer.isEmpty()) return emptyList()
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    private fun sendBatch(events: List<ErrorEvent>) {
        try {
            val body = buildBatchJson(events)
            val signature = hmacSha256(body, secret)

            val url = URL("$endpoint/v1/ingest/batch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Signature", signature)
            projectKey?.let { conn.setRequestProperty("X-Project-Key", it) }
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            if (code !in 200..299) {
                logDebug("flush failed: HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            logDebug("flush error: ${e.message}")
        }
    }

    private fun flushTraces() {
        if (traceBuffer.isEmpty()) return
        val snapshot = traceBuffer.toList()
        traceBuffer.clear()
        Thread { sendTraces(snapshot) }.start()
    }

    private fun flushTracesSync() {
        if (traceBuffer.isEmpty()) return
        val snapshot = traceBuffer.toList()
        traceBuffer.clear()
        sendTraces(snapshot)
    }

    private fun sendTraces(traceJsons: List<String>) {
        try {
            val body = """{"traces":[${traceJsons.joinToString(",")}]}"""
            val signature = hmacSha256(body, secret)
            val url = URL("$endpoint/v1/traces/batch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Signature", signature)
            projectKey?.let { conn.setRequestProperty("X-Project-Key", it) }
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) logDebug("trace flush failed: HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            logDebug("trace flush error: ${e.message}")
        }
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun logDebug(msg: String) {
        try {
            // Use Android Log if available, fall back to stderr
            Class.forName("android.util.Log")
                .getMethod("w", String::class.java, String::class.java)
                .invoke(null, "bloop", msg)
        } catch (_: Exception) {
            System.err.println("[bloop] $msg")
        }
    }

    companion object {
        @Volatile
        var shared: BloopClient? = null
            private set

        /** Configure the shared singleton. Call once at app startup. */
        fun configure(
            endpoint: String,
            secret: String,
            projectKey: String? = null,
            environment: String,
            release: String,
            source: String = "android",
            appVersion: String? = null,
            buildNumber: String? = null,
            maxBufferSize: Int = 20,
            flushIntervalMs: Long = 5000L,
            enrichDevice: Boolean = true,
        ) {
            shared = BloopClient(
                endpoint = endpoint,
                secret = secret,
                projectKey = projectKey,
                source = source,
                environment = environment,
                release = release,
                appVersion = appVersion,
                buildNumber = buildNumber,
                maxBufferSize = maxBufferSize,
                flushIntervalMs = flushIntervalMs,
                enrichDevice = enrichDevice,
            )
        }
    }
}

// -- JSON serialization (no external deps) --

private data class ErrorEvent(
    val timestamp: Long,
    val source: String,
    val environment: String,
    val release: String,
    val appVersion: String?,
    val buildNumber: String?,
    val routeOrProcedure: String?,
    val screen: String?,
    val errorType: String,
    val message: String,
    val stack: String?,
    val httpStatus: Int?,
    val requestId: String?,
    val userIdHash: String?,
    val metadata: Map<String, Any?>?,
)

private fun buildBatchJson(events: List<ErrorEvent>): String {
    val sb = StringBuilder()
    sb.append("""{"events":[""")
    events.forEachIndexed { i, e ->
        if (i > 0) sb.append(',')
        sb.append(e.toJson())
    }
    sb.append("]}")
    return sb.toString()
}

private fun ErrorEvent.toJson(): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.appendField("timestamp", timestamp)
    sb.appendField("source", source)
    sb.appendField("environment", environment)
    sb.appendField("release", release)
    sb.appendField("error_type", errorType)
    sb.appendField("message", message)
    appVersion?.let { sb.appendField("app_version", it) }
    buildNumber?.let { sb.appendField("build_number", it) }
    routeOrProcedure?.let { sb.appendField("route_or_procedure", it) }
    screen?.let { sb.appendField("screen", it) }
    stack?.let { sb.appendField("stack", it) }
    httpStatus?.let { sb.appendField("http_status", it) }
    requestId?.let { sb.appendField("request_id", it) }
    userIdHash?.let { sb.appendField("user_id_hash", it) }
    metadata?.let { sb.appendField("metadata", mapToJson(it)) }
    // Remove trailing comma
    if (sb.last() == ',') sb.setLength(sb.length - 1)
    sb.append('}')
    return sb.toString()
}

private fun StringBuilder.appendField(key: String, value: Long) {
    append('"').append(key).append("\":").append(value).append(',')
}

private fun StringBuilder.appendField(key: String, value: Int) {
    append('"').append(key).append("\":").append(value).append(',')
}

private fun StringBuilder.appendField(key: String, value: String) {
    append('"').append(key).append("\":\"").append(escapeJson(value)).append("\",")
}

/** Append a raw JSON value (for nested objects). */
private fun StringBuilder.appendField(key: String, rawJson: CharSequence) {
    append('"').append(key).append("\":").append(rawJson).append(',')
}

private fun escapeJson(s: String): String = buildString(s.length) {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

private fun mapToJson(map: Map<String, Any?>): String = buildString {
    append('{')
    var first = true
    for ((k, v) in map) {
        if (!first) append(',')
        first = false
        append('"').append(escapeJson(k)).append("\":")
        append(valueToJson(v))
    }
    append('}')
}

private fun valueToJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"${escapeJson(v)}\""
    is Number -> v.toString()
    is Boolean -> v.toString()
    is Map<*, *> -> mapToJson(@Suppress("UNCHECKED_CAST") (v as Map<String, Any?>))
    is List<*> -> "[${v.joinToString(",") { valueToJson(it) }}]"
    else -> "\"${escapeJson(v.toString())}\""
}
