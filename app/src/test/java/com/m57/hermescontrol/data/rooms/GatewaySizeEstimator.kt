package com.m57.hermescontrol.data.rooms

/**
 * Verbatim JVM port of the Desktop gateway-size estimator
 * `groupChatGatewayJsonSize` (hermes-agent apps/desktop/src/plugins/hermes-bots/plugin.js,
 * tag v2026.8.19; extracted verbatim in asgard-rooms tools/desktop_extract.js).
 *
 * The gateway charges 1 byte per ASCII character (2 for ',' and ':') and
 * 6/12 bytes per non-ASCII code point, regardless of its 3/4-byte UTF-8
 * representation. The committed asgard-rooms evidence artifact
 * `tests/fixtures/GATEWAY-SIZE-EVIDENCE.json` (mirrored byte-for-byte under
 * app/src/test/resources/fixtures/) records the Desktop-measured
 * cap-crossing thresholds for this estimator; the test consumes every
 * committed case, so an estimator disagreement fails the build.
 */
object GatewaySizeEstimator {
    const val GATEWAY_SIZE_LIMIT = 48_000

    fun gatewayBytesOf(jsonText: String): Int {
        var bytes = 0
        for (ch in jsonText) {
            val cp = ch.code
            if (cp <= 0x7f) {
                bytes += if (ch == ',' || ch == ':') 2 else 1
            } else {
                bytes += if (cp <= 0xffff) 6 else 12
            }
        }
        return bytes
    }
}
