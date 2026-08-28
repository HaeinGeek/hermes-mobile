package com.m57.hermescontrol.data.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gateway-size estimator parity against the committed asgard-rooms evidence
 * artifact `GATEWAY-SIZE-EVIDENCE.json` (mirrored byte-for-byte under
 * app/src/test/resources/fixtures/ and pinned by fixture-set.sha256).
 *
 * Every committed case is consumed: for each code point the artifact records
 * the Desktop-measured first repeated-codepoint count whose gateway size
 * crosses the 48 KB cap, the gateway bytes of the count just below it, and
 * the gateway bytes at the crossing itself. The JVM port of
 * `groupChatGatewayJsonSize` must reproduce all of those numbers, and the
 * recorded limit must match the port's constant — so an estimator (or
 * evidence) disagreement fails the build rather than a hand-transcribed
 * single witness.
 */
class GatewaySizeEstimatorTest {
    private fun evidence(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("fixtures/GATEWAY-SIZE-EVIDENCE.json")!!
                .readBytes().decodeToString(),
        ).jsonObject

    /** Rebuild the Desktop payload shape the artifact was measured with: {"text": <codePoint × count>}. */
    private fun payloadJson(
        codePointHex: String,
        count: Int,
    ): String {
        val cp = codePointHex.removePrefix("U+").toInt(16)
        val literal = buildString { repeat(count) { appendCodePoint(it, cp) } }
        return """{"text":"$literal"}"""
    }

    private fun StringBuilder.appendCodePoint(
        index: Int,
        cp: Int,
    ) {
        if (cp <= 0xffff) {
            append(cp.toChar())
        } else {
            val offset = cp - 0x10000
            append(((offset ushr 10) + 0xD800).toChar())
            append(((offset and 0x3FF) + 0xDC00).toChar())
        }
    }

    @Test
    fun `gateway size estimator matches every committed GATEWAY-SIZE-EVIDENCE case`() {
        val artifact = evidence()
        assertEquals(
            "evidence sourceFunction must stay the Desktop estimator this port mirrors",
            "groupChatGatewayJsonSize",
            artifact["sourceFunction"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "committed gatewaySizeLimit must match the ported constant",
            GatewaySizeEstimator.GATEWAY_SIZE_LIMIT,
            artifact["gatewaySizeLimit"]!!.jsonPrimitive.int,
        )

        val cases = artifact["cases"]!!.jsonArray
        assertTrue("a committed case disappearing must fail this test", cases.size >= 3)

        cases.forEach { element ->
            val case = element.jsonObject
            val label = case["label"]!!.jsonPrimitive.content
            val codePoint = case["codePoint"]!!.jsonPrimitive.content
            val countOverCap = case["firstCodePointCountOverCap"]!!.jsonPrimitive.int

            // The payload one below the crossing still fits the cap …
            val previous =
                GatewaySizeEstimator.gatewayBytesOf(payloadJson(codePoint, countOverCap - 1))
            assertEquals(
                "previousGatewayBytes mismatch for $label ($codePoint)",
                case["previousGatewayBytes"]!!.jsonPrimitive.int,
                previous,
            )
            assertTrue(
                "the count below the crossing must still fit the cap for $label",
                previous <= GatewaySizeEstimator.GATEWAY_SIZE_LIMIT,
            )

            // … and the crossing count itself exceeds it by exactly the recorded bytes.
            val first = GatewaySizeEstimator.gatewayBytesOf(payloadJson(codePoint, countOverCap))
            assertEquals(
                "firstGatewayBytesOverCap mismatch for $label ($codePoint)",
                case["firstGatewayBytesOverCap"]!!.jsonPrimitive.int,
                first,
            )
            assertTrue(
                "the crossing count must exceed the cap for $label",
                first > GatewaySizeEstimator.GATEWAY_SIZE_LIMIT,
            )

            // The per-codepoint gateway cost implied by the two recorded byte counts
            // must exceed the code point's UTF-8 cost — the estimator overcharges
            // non-ASCII, which is exactly why the artifact exists.
            val perCodePointGatewayCost = first - previous
            val utf8 = case["utf8BytesPerCodePoint"]!!.jsonPrimitive.int
            assertTrue(
                "gateway cost per $label code point ($perCodePointGatewayCost) must be >= its UTF-8 cost ($utf8)",
                perCodePointGatewayCost >= utf8,
            )
        }
    }

    /** The estimator's documented ASCII punctuation rule: ',' and ':' cost 2. */
    @Test
    fun `estimator charges two bytes for ascii comma and colon`() {
        assertEquals(2, GatewaySizeEstimator.gatewayBytesOf(","))
        assertEquals(2, GatewaySizeEstimator.gatewayBytesOf(":"))
        assertEquals(1, GatewaySizeEstimator.gatewayBytesOf("a"))
        assertEquals(6, GatewaySizeEstimator.gatewayBytesOf("가"))
        assertEquals(12, GatewaySizeEstimator.gatewayBytesOf("\uD83D\uDE00")) // U+1F600 surrogate pair
    }
}
