package com.bloop.sdk

/**
 * Minimal test harness for LLM tracing - no external test framework dependencies.
 * Uses assertions that throw on failure.
 */
object TracingTest {

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    private fun test(name: String, block: () -> Unit) {
        try {
            block()
            passed++
            println("  PASS: $name")
        } catch (e: Throwable) {
            failed++
            failures.add("$name: ${e.message}")
            println("  FAIL: $name - ${e.message}")
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?, msg: String = "") {
        if (expected != actual) {
            throw AssertionError("Expected <$expected> but was <$actual>. $msg")
        }
    }

    private fun assertNotNull(value: Any?, msg: String = "") {
        if (value == null) {
            throw AssertionError("Expected non-null value. $msg")
        }
    }

    private fun assertTrue(condition: Boolean, msg: String = "") {
        if (!condition) {
            throw AssertionError("Assertion failed. $msg")
        }
    }

    private fun assertFalse(condition: Boolean, msg: String = "") {
        if (condition) {
            throw AssertionError("Expected false but was true. $msg")
        }
    }

    private fun assertContains(haystack: String, needle: String, msg: String = "") {
        if (needle !in haystack) {
            throw AssertionError("Expected string to contain <$needle> but was <$haystack>. $msg")
        }
    }

    // ===== SpanType Enum Tests =====

    fun testSpanTypeValues() = test("SpanType enum values") {
        assertEquals("generation", SpanType.GENERATION.value)
        assertEquals("tool", SpanType.TOOL.value)
        assertEquals("retrieval", SpanType.RETRIEVAL.value)
        assertEquals("custom", SpanType.CUSTOM.value)
    }

    fun testSpanTypeToString() = test("SpanType toString returns value") {
        assertEquals("generation", SpanType.GENERATION.toString())
        assertEquals("tool", SpanType.TOOL.toString())
    }

    // ===== SpanStatus Enum Tests =====

    fun testSpanStatusValues() = test("SpanStatus enum values") {
        assertEquals("ok", SpanStatus.OK.value)
        assertEquals("error", SpanStatus.ERROR.value)
    }

    fun testSpanStatusToString() = test("SpanStatus toString returns value") {
        assertEquals("ok", SpanStatus.OK.toString())
        assertEquals("error", SpanStatus.ERROR.toString())
    }

    // ===== TraceStatus Enum Tests =====

    fun testTraceStatusValues() = test("TraceStatus enum values") {
        assertEquals("running", TraceStatus.RUNNING.value)
        assertEquals("completed", TraceStatus.COMPLETED.value)
        assertEquals("error", TraceStatus.ERROR.value)
    }

    fun testTraceStatusToString() = test("TraceStatus toString returns value") {
        assertEquals("running", TraceStatus.RUNNING.toString())
        assertEquals("completed", TraceStatus.COMPLETED.toString())
    }

    // ===== LLMSpan Tests =====

    fun testSpanCreation() = test("LLMSpan creation with defaults") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "test-span")
        assertNotNull(span.id, "span should have an id")
        assertTrue(span.id.isNotEmpty(), "span id should be non-empty")
        assertEquals(SpanType.GENERATION, span.spanType)
        assertEquals("test-span", span.name)
        assertTrue(span.startedAt > 0, "startedAt should be positive")
        assertEquals(null, span.model)
        assertEquals(null, span.provider)
        assertEquals(null, span.input)
        assertEquals(null, span.metadata)
        assertEquals(null, span.parentSpanId)
        assertEquals(null, span.inputTokens)
        assertEquals(null, span.outputTokens)
        assertEquals(null, span.cost)
        assertEquals(null, span.latencyMs)
        assertEquals(null, span.timeToFirstTokenMs)
        assertEquals(null, span.status)
        assertEquals(null, span.errorMessage)
        assertEquals(null, span.output)
    }

    fun testSpanCreationWithAllArgs() = test("LLMSpan creation with all constructor args") {
        val meta = mapOf("key" to "value")
        val span = LLMSpan(
            spanType = SpanType.TOOL,
            name = "tool-call",
            model = "gpt-4",
            provider = "openai",
            input = "hello",
            metadata = meta,
            parentSpanId = "parent-123"
        )
        assertEquals(SpanType.TOOL, span.spanType)
        assertEquals("tool-call", span.name)
        assertEquals("gpt-4", span.model)
        assertEquals("openai", span.provider)
        assertEquals("hello", span.input)
        assertEquals(meta, span.metadata)
        assertEquals("parent-123", span.parentSpanId)
    }

    fun testSpanUniqueIds() = test("LLMSpan generates unique IDs") {
        val span1 = LLMSpan(spanType = SpanType.CUSTOM)
        val span2 = LLMSpan(spanType = SpanType.CUSTOM)
        assertTrue(span1.id != span2.id, "Span IDs should be unique")
    }

    fun testSpanEnd() = test("LLMSpan.end() sets fields correctly") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "gen")
        // Simulate some time passing
        Thread.sleep(10)
        span.end(
            status = SpanStatus.OK,
            inputTokens = 100,
            outputTokens = 200,
            cost = 0.005,
            output = "response text",
            timeToFirstTokenMs = 50,
        )
        assertEquals(SpanStatus.OK, span.status)
        assertEquals(100, span.inputTokens)
        assertEquals(200, span.outputTokens)
        assertEquals(0.005, span.cost)
        assertEquals("response text", span.output)
        assertEquals(50, span.timeToFirstTokenMs)
        assertNotNull(span.latencyMs, "latencyMs should be set")
        assertTrue(span.latencyMs!! >= 10, "latencyMs should be >= 10ms (we slept 10ms)")
    }

    fun testSpanEndWithError() = test("LLMSpan.end() with error status") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "gen")
        span.end(status = SpanStatus.ERROR, errorMessage = "timeout")
        assertEquals(SpanStatus.ERROR, span.status)
        assertEquals("timeout", span.errorMessage)
    }

    fun testSpanEndDefaultStatus() = test("LLMSpan.end() defaults to OK status") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "gen")
        span.end()
        assertEquals(SpanStatus.OK, span.status)
    }

    fun testSpanSetUsage() = test("LLMSpan.setUsage() sets token counts") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "gen")
        span.setUsage(inputTokens = 50, outputTokens = 150, cost = 0.01)
        assertEquals(50, span.inputTokens)
        assertEquals(150, span.outputTokens)
        assertEquals(0.01, span.cost)
    }

    fun testSpanSetUsagePartial() = test("LLMSpan.setUsage() partial update does not null existing") {
        val span = LLMSpan(spanType = SpanType.GENERATION, name = "gen")
        span.setUsage(inputTokens = 50, outputTokens = 150, cost = 0.01)
        span.setUsage(inputTokens = 75)
        assertEquals(75, span.inputTokens)
        assertEquals(150, span.outputTokens, "outputTokens should be unchanged")
        assertEquals(0.01, span.cost, "cost should be unchanged")
    }

    // ===== LLMSpan JSON Tests =====

    fun testSpanToJsonMinimal() = test("LLMSpan.toJson() with minimal fields") {
        val span = LLMSpan(spanType = SpanType.CUSTOM, name = "test")
        span.end()
        val json = span.toJson()
        assertContains(json, "\"id\":\"${span.id}\"")
        assertContains(json, "\"span_type\":\"custom\"")
        assertContains(json, "\"name\":\"test\"")
        assertContains(json, "\"status\":\"ok\"")
        assertContains(json, "\"started_at\":")
        // Should NOT contain optional fields
        assertFalse(json.contains("\"model\":"), "should not have model field")
        assertFalse(json.contains("\"provider\":"), "should not have provider field")
        assertFalse(json.contains("\"parent_span_id\":"), "should not have parent_span_id")
    }

    fun testSpanToJsonFull() = test("LLMSpan.toJson() with all fields") {
        val span = LLMSpan(
            spanType = SpanType.GENERATION,
            name = "completion",
            model = "gpt-4",
            provider = "openai",
            input = "hello world",
            metadata = mapOf("temperature" to 0.7),
            parentSpanId = "parent-1"
        )
        span.end(
            status = SpanStatus.OK,
            inputTokens = 10,
            outputTokens = 20,
            cost = 0.001,
            output = "hi",
            timeToFirstTokenMs = 100,
        )
        val json = span.toJson()
        assertContains(json, "\"span_type\":\"generation\"")
        assertContains(json, "\"model\":\"gpt-4\"")
        assertContains(json, "\"provider\":\"openai\"")
        assertContains(json, "\"input\":\"hello world\"")
        assertContains(json, "\"output\":\"hi\"")
        assertContains(json, "\"input_tokens\":10")
        assertContains(json, "\"output_tokens\":20")
        assertContains(json, "\"cost\":0.001")
        assertContains(json, "\"time_to_first_token_ms\":100")
        assertContains(json, "\"parent_span_id\":\"parent-1\"")
        assertContains(json, "\"metadata\":{")
    }

    fun testSpanToJsonEscapesStrings() = test("LLMSpan.toJson() escapes special characters") {
        val span = LLMSpan(
            spanType = SpanType.CUSTOM,
            name = "test \"quoted\"",
            input = "line1\nline2\ttab"
        )
        span.end(output = "back\\slash")
        val json = span.toJson()
        assertContains(json, "test \\\"quoted\\\"")
        assertContains(json, "line1\\nline2\\ttab")
        assertContains(json, "back\\\\slash")
    }

    fun testSpanToJsonDefaultStatusWhenNotEnded() = test("LLMSpan.toJson() defaults to ok status when not ended") {
        val span = LLMSpan(spanType = SpanType.CUSTOM, name = "test")
        // Don't call end()
        val json = span.toJson()
        assertContains(json, "\"status\":\"ok\"")
    }

    // ===== LLMTrace Tests =====

    fun testTraceCreation() = test("LLMTrace creation with defaults") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(
            client = client,
            name = "my-trace"
        )
        assertNotNull(trace.id)
        assertTrue(trace.id.isNotEmpty())
        assertEquals("my-trace", trace.name)
        assertEquals(TraceStatus.RUNNING, trace.status)
        assertTrue(trace.startedAt > 0)
        assertEquals(null, trace.sessionId)
        assertEquals(null, trace.userId)
        assertEquals(null, trace.input)
        assertEquals(null, trace.metadata)
        assertEquals(null, trace.promptName)
        assertEquals(null, trace.promptVersion)
        assertEquals(null, trace.output)
        assertEquals(null, trace.endedAt)
        assertTrue(trace.spans.isEmpty())
        client.close()
    }

    fun testTraceCreationAllArgs() = test("LLMTrace creation with all args") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val meta = mapOf("key" to "value")
        val trace = LLMTrace(
            client = client,
            name = "trace-full",
            sessionId = "sess-1",
            userId = "user-1",
            input = "user question",
            metadata = meta,
            promptName = "my-prompt",
            promptVersion = "v2"
        )
        assertEquals("trace-full", trace.name)
        assertEquals("sess-1", trace.sessionId)
        assertEquals("user-1", trace.userId)
        assertEquals("user question", trace.input)
        assertEquals(meta, trace.metadata)
        assertEquals("my-prompt", trace.promptName)
        assertEquals("v2", trace.promptVersion)
        client.close()
    }

    fun testTraceUniqueIds() = test("LLMTrace generates unique IDs") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val t1 = LLMTrace(client = client, name = "t1")
        val t2 = LLMTrace(client = client, name = "t2")
        assertTrue(t1.id != t2.id, "Trace IDs should be unique")
        client.close()
    }

    fun testTraceStartSpan() = test("LLMTrace.startSpan() creates and tracks spans") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "trace")
        val span = trace.startSpan(
            spanType = SpanType.GENERATION,
            name = "llm-call",
            model = "gpt-4",
            provider = "openai",
            input = "hi"
        )
        assertEquals(1, trace.spans.size)
        assertEquals(span, trace.spans[0])
        assertEquals(SpanType.GENERATION, span.spanType)
        assertEquals("llm-call", span.name)
        assertEquals("gpt-4", span.model)
        client.close()
    }

    fun testTraceMultipleSpans() = test("LLMTrace tracks multiple spans") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "trace")
        val s1 = trace.startSpan(spanType = SpanType.RETRIEVAL, name = "retrieve")
        val s2 = trace.startSpan(spanType = SpanType.GENERATION, name = "generate", parentSpanId = s1.id)
        val s3 = trace.startSpan(spanType = SpanType.TOOL, name = "tool-call")
        assertEquals(3, trace.spans.size)
        assertEquals(s1.id, s2.parentSpanId)
        client.close()
    }

    fun testTraceEnd() = test("LLMTrace.end() sets status and endedAt") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "trace")
        Thread.sleep(10)
        trace.end(status = TraceStatus.COMPLETED, output = "done")
        assertEquals(TraceStatus.COMPLETED, trace.status)
        assertEquals("done", trace.output)
        assertNotNull(trace.endedAt)
        assertTrue(trace.endedAt!! >= trace.startedAt)
        client.close()
    }

    fun testTraceEndDefaultStatus() = test("LLMTrace.end() defaults to COMPLETED") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "trace")
        trace.end()
        assertEquals(TraceStatus.COMPLETED, trace.status)
        client.close()
    }

    fun testTraceEndError() = test("LLMTrace.end() with error status") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "trace")
        trace.end(status = TraceStatus.ERROR)
        assertEquals(TraceStatus.ERROR, trace.status)
        client.close()
    }

    // ===== LLMTrace JSON Tests =====

    fun testTraceToJsonMinimal() = test("LLMTrace.toJson() with minimal fields") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "test-trace")
        trace.end()
        val json = trace.toJson()
        assertContains(json, "\"id\":\"${trace.id}\"")
        assertContains(json, "\"name\":\"test-trace\"")
        assertContains(json, "\"status\":\"completed\"")
        assertContains(json, "\"started_at\":")
        assertContains(json, "\"ended_at\":")
        assertContains(json, "\"spans\":[]")
        // Should NOT contain optional fields
        assertFalse(json.contains("\"session_id\":"), "should not have session_id")
        assertFalse(json.contains("\"user_id\":"), "should not have user_id")
        assertFalse(json.contains("\"prompt_name\":"), "should not have prompt_name")
        client.close()
    }

    fun testTraceToJsonFull() = test("LLMTrace.toJson() with all fields and spans") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(
            client = client,
            name = "full-trace",
            sessionId = "s-1",
            userId = "u-1",
            input = "question",
            metadata = mapOf("env" to "test"),
            promptName = "prompt-1",
            promptVersion = "v3"
        )
        val span = trace.startSpan(spanType = SpanType.GENERATION, name = "gen", model = "gpt-4")
        span.end(inputTokens = 10, outputTokens = 20)
        trace.end(output = "answer")
        val json = trace.toJson()
        assertContains(json, "\"session_id\":\"s-1\"")
        assertContains(json, "\"user_id\":\"u-1\"")
        assertContains(json, "\"input\":\"question\"")
        assertContains(json, "\"output\":\"answer\"")
        assertContains(json, "\"prompt_name\":\"prompt-1\"")
        assertContains(json, "\"prompt_version\":\"v3\"")
        assertContains(json, "\"metadata\":{")
        assertContains(json, "\"spans\":[{")
        assertContains(json, "\"span_type\":\"generation\"")
        client.close()
    }

    fun testTraceToJsonMultipleSpans() = test("LLMTrace.toJson() with multiple spans") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = LLMTrace(client = client, name = "multi-span")
        trace.startSpan(spanType = SpanType.RETRIEVAL, name = "s1").end()
        trace.startSpan(spanType = SpanType.GENERATION, name = "s2").end()
        trace.end()
        val json = trace.toJson()
        // Count occurrences of "span_type" to verify both spans
        val count = "\"span_type\"".toRegex().findAll(json).count()
        assertEquals(2, count, "should have 2 spans in JSON")
        client.close()
    }

    // ===== BloopClient Integration Tests =====

    fun testClientStartTrace() = test("BloopClient.startTrace() creates LLMTrace") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = client.startTrace(
            name = "test",
            sessionId = "s-1",
            userId = "u-1",
            input = "hi",
            promptName = "p",
            promptVersion = "v1"
        )
        assertNotNull(trace)
        assertEquals("test", trace.name)
        assertEquals("s-1", trace.sessionId)
        assertEquals("u-1", trace.userId)
        assertEquals("hi", trace.input)
        assertEquals("p", trace.promptName)
        assertEquals("v1", trace.promptVersion)
        client.close()
    }

    fun testClientStartTraceMinimal() = test("BloopClient.startTrace() with minimal args") {
        val client = BloopClient(
            endpoint = "http://localhost:9999",
            secret = "test-secret",
            environment = "test",
            release = "1.0",
            flushIntervalMs = 999999L
        )
        val trace = client.startTrace(name = "simple")
        assertEquals("simple", trace.name)
        assertEquals(null, trace.sessionId)
        assertEquals(null, trace.userId)
        client.close()
    }

    // ===== JSON Helper Tests =====

    fun testEscapeJsonStr() = test("traceEscapeJson handles special chars") {
        assertEquals("hello", traceEscapeJson("hello"))
        assertEquals("say \\\"hi\\\"", traceEscapeJson("say \"hi\""))
        assertEquals("back\\\\slash", traceEscapeJson("back\\slash"))
        assertEquals("line\\n", traceEscapeJson("line\n"))
        assertEquals("tab\\t", traceEscapeJson("tab\t"))
        assertEquals("cr\\r", traceEscapeJson("cr\r"))
    }

    fun testMapToJson() = test("traceMapToJson produces valid JSON") {
        val result = traceMapToJson(mapOf("a" to "b", "num" to 42))
        assertContains(result, "\"a\":\"b\"")
        assertContains(result, "\"num\":42")
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
    }

    fun testMapToJsonNested() = test("traceMapToJson handles nested maps") {
        val result = traceMapToJson(mapOf("outer" to mapOf("inner" to "val")))
        assertContains(result, "\"outer\":{\"inner\":\"val\"}")
    }

    fun testValueToJsonStr() = test("traceValueToJson handles all types") {
        assertEquals("null", traceValueToJson(null))
        assertEquals("\"hello\"", traceValueToJson("hello"))
        assertEquals("42", traceValueToJson(42))
        assertEquals("3.14", traceValueToJson(3.14))
        assertEquals("true", traceValueToJson(true))
        assertEquals("false", traceValueToJson(false))
    }

    fun testValueToJsonStrList() = test("traceValueToJson handles lists") {
        assertEquals("[1,2,3]", traceValueToJson(listOf(1, 2, 3)))
        assertEquals("[\"a\",\"b\"]", traceValueToJson(listOf("a", "b")))
    }

    // ===== Run All Tests =====

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Bloop Kotlin SDK - LLM Tracing Tests ===\n")

        println("[SpanType Enums]")
        testSpanTypeValues()
        testSpanTypeToString()

        println("\n[SpanStatus Enums]")
        testSpanStatusValues()
        testSpanStatusToString()

        println("\n[TraceStatus Enums]")
        testTraceStatusValues()
        testTraceStatusToString()

        println("\n[LLMSpan Creation]")
        testSpanCreation()
        testSpanCreationWithAllArgs()
        testSpanUniqueIds()

        println("\n[LLMSpan Lifecycle]")
        testSpanEnd()
        testSpanEndWithError()
        testSpanEndDefaultStatus()
        testSpanSetUsage()
        testSpanSetUsagePartial()

        println("\n[LLMSpan JSON]")
        testSpanToJsonMinimal()
        testSpanToJsonFull()
        testSpanToJsonEscapesStrings()
        testSpanToJsonDefaultStatusWhenNotEnded()

        println("\n[LLMTrace Creation]")
        testTraceCreation()
        testTraceCreationAllArgs()
        testTraceUniqueIds()

        println("\n[LLMTrace Lifecycle]")
        testTraceStartSpan()
        testTraceMultipleSpans()
        testTraceEnd()
        testTraceEndDefaultStatus()
        testTraceEndError()

        println("\n[LLMTrace JSON]")
        testTraceToJsonMinimal()
        testTraceToJsonFull()
        testTraceToJsonMultipleSpans()

        println("\n[BloopClient Integration]")
        testClientStartTrace()
        testClientStartTraceMinimal()

        println("\n[JSON Helpers]")
        testEscapeJsonStr()
        testMapToJson()
        testMapToJsonNested()
        testValueToJsonStr()
        testValueToJsonStrList()

        println("\n=== Results: $passed passed, $failed failed ===")
        if (failures.isNotEmpty()) {
            println("\nFailures:")
            failures.forEach { println("  - $it") }
        }
        if (failed > 0) {
            System.exit(1)
        }
    }
}
