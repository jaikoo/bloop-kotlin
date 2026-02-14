package com.bloop.sdk

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class SpanType(val value: String) {
    GENERATION("generation"), TOOL("tool"), RETRIEVAL("retrieval"), CUSTOM("custom");
    override fun toString() = value
}

enum class SpanStatus(val value: String) {
    OK("ok"), ERROR("error");
    override fun toString() = value
}

enum class TraceStatus(val value: String) {
    RUNNING("running"), COMPLETED("completed"), ERROR("error");
    override fun toString() = value
}

class LLMSpan(
    val spanType: SpanType,
    val name: String = "",
    val model: String? = null,
    val provider: String? = null,
    val input: String? = null,
    val metadata: Map<String, Any?>? = null,
    val parentSpanId: String? = null,
) {
    val id: String = UUID.randomUUID().toString()
    val startedAt: Long = System.currentTimeMillis()

    var inputTokens: Int? = null; private set
    var outputTokens: Int? = null; private set
    var cost: Double? = null; private set
    var latencyMs: Int? = null; private set
    var timeToFirstTokenMs: Int? = null; private set
    var status: SpanStatus? = null; private set
    var errorMessage: String? = null; private set
    var output: String? = null; private set

    fun end(
        status: SpanStatus = SpanStatus.OK,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        cost: Double? = null,
        errorMessage: String? = null,
        output: String? = null,
        timeToFirstTokenMs: Int? = null,
    ) {
        this.latencyMs = (System.currentTimeMillis() - startedAt).toInt()
        this.status = status
        inputTokens?.let { this.inputTokens = it }
        outputTokens?.let { this.outputTokens = it }
        cost?.let { this.cost = it }
        errorMessage?.let { this.errorMessage = it }
        output?.let { this.output = it }
        timeToFirstTokenMs?.let { this.timeToFirstTokenMs = it }
    }

    fun setUsage(inputTokens: Int? = null, outputTokens: Int? = null, cost: Double? = null) {
        inputTokens?.let { this.inputTokens = it }
        outputTokens?.let { this.outputTokens = it }
        cost?.let { this.cost = it }
    }

    fun toJson(): String {
        val sb = StringBuilder("{")
        sb.traceAppendField("id", id)
        sb.traceAppendField("span_type", spanType.value)
        sb.traceAppendField("name", name)
        sb.traceAppendFieldLong("started_at", startedAt)
        sb.traceAppendField("status", (status ?: SpanStatus.OK).value)
        parentSpanId?.let { sb.traceAppendField("parent_span_id", it) }
        model?.let { sb.traceAppendField("model", it) }
        provider?.let { sb.traceAppendField("provider", it) }
        inputTokens?.let { sb.traceAppendFieldInt("input_tokens", it) }
        outputTokens?.let { sb.traceAppendFieldInt("output_tokens", it) }
        cost?.let { sb.traceAppendFieldDouble("cost", it) }
        latencyMs?.let { sb.traceAppendFieldInt("latency_ms", it) }
        timeToFirstTokenMs?.let { sb.traceAppendFieldInt("time_to_first_token_ms", it) }
        errorMessage?.let { sb.traceAppendField("error_message", it) }
        input?.let { sb.traceAppendField("input", it) }
        output?.let { sb.traceAppendField("output", it) }
        metadata?.let { sb.traceAppendFieldRaw("metadata", traceMapToJson(it)) }
        if (sb.last() == ',') sb.setLength(sb.length - 1)
        sb.append("}")
        return sb.toString()
    }
}

class LLMTrace(
    private val client: BloopClient,
    val name: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val input: String? = null,
    val metadata: Map<String, Any?>? = null,
    val promptName: String? = null,
    val promptVersion: String? = null,
) {
    val id: String = UUID.randomUUID().toString()
    val startedAt: Long = System.currentTimeMillis()
    val spans = CopyOnWriteArrayList<LLMSpan>()

    var status: TraceStatus = TraceStatus.RUNNING; private set
    var output: String? = null; private set
    var endedAt: Long? = null; private set

    fun startSpan(
        spanType: SpanType = SpanType.CUSTOM,
        name: String = "",
        model: String? = null,
        provider: String? = null,
        input: String? = null,
        metadata: Map<String, Any?>? = null,
        parentSpanId: String? = null,
    ): LLMSpan {
        val span = LLMSpan(
            spanType = spanType, name = name, model = model,
            provider = provider, input = input, metadata = metadata,
            parentSpanId = parentSpanId
        )
        spans.add(span)
        return span
    }

    fun end(status: TraceStatus = TraceStatus.COMPLETED, output: String? = null) {
        this.endedAt = System.currentTimeMillis()
        this.status = status
        output?.let { this.output = it }
        client.enqueueTrace(this)
    }

    fun toJson(): String {
        val sb = StringBuilder("{")
        sb.traceAppendField("id", id)
        sb.traceAppendField("name", name)
        sb.traceAppendField("status", status.value)
        sb.traceAppendFieldLong("started_at", startedAt)
        sessionId?.let { sb.traceAppendField("session_id", it) }
        userId?.let { sb.traceAppendField("user_id", it) }
        this.input?.let { sb.traceAppendField("input", it) }
        this.output?.let { sb.traceAppendField("output", it) }
        metadata?.let { sb.traceAppendFieldRaw("metadata", traceMapToJson(it)) }
        promptName?.let { sb.traceAppendField("prompt_name", it) }
        promptVersion?.let { sb.traceAppendField("prompt_version", it) }
        endedAt?.let { sb.traceAppendFieldLong("ended_at", it) }
        // spans array
        sb.append("\"spans\":[")
        spans.forEachIndexed { i, span ->
            if (i > 0) sb.append(',')
            sb.append(span.toJson())
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }
}

// -- JSON helper functions for tracing (internal, distinct names to avoid clash with BloopClient.kt) --

internal fun StringBuilder.traceAppendField(key: String, value: String) {
    append('"').append(key).append("\":\"").append(traceEscapeJson(value)).append("\",")
}

internal fun StringBuilder.traceAppendFieldLong(key: String, value: Long) {
    append('"').append(key).append("\":").append(value).append(',')
}

internal fun StringBuilder.traceAppendFieldInt(key: String, value: Int) {
    append('"').append(key).append("\":").append(value).append(',')
}

internal fun StringBuilder.traceAppendFieldDouble(key: String, value: Double) {
    append('"').append(key).append("\":").append(value).append(',')
}

internal fun StringBuilder.traceAppendFieldRaw(key: String, rawJson: CharSequence) {
    append('"').append(key).append("\":").append(rawJson).append(',')
}

internal fun traceEscapeJson(s: String): String = buildString(s.length) {
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

internal fun traceMapToJson(map: Map<String, Any?>): String = buildString {
    append('{')
    var first = true
    for ((k, v) in map) {
        if (!first) append(',')
        first = false
        append('"').append(traceEscapeJson(k)).append("\":")
        append(traceValueToJson(v))
    }
    append('}')
}

internal fun traceValueToJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"${traceEscapeJson(v)}\""
    is Number -> v.toString()
    is Boolean -> v.toString()
    is Map<*, *> -> traceMapToJson(@Suppress("UNCHECKED_CAST") (v as Map<String, Any?>))
    is List<*> -> "[${v.joinToString(",") { traceValueToJson(it) }}]"
    else -> "\"${traceEscapeJson(v.toString())}\""
}
