package com.castla.mirror.network

/**
 * Command line for the bundled cloudflared connector.
 *
 * - `--protocol quic`: NOT http2. With http2, cloudflared cannot carry
 *   WebSockets (cloudflare/cloudflared#1208): every browser socket is dropped
 *   right after the upgrade, which is exactly the instant disconnect/reconnect
 *   loop seen in the field. Pinned (rather than "auto") so it never silently
 *   falls back to http2.
 * - `--ha-connections 2`: two edge connections instead of one, so a single
 *   connection reset does not take the car's page down while it reconnects.
 */
object CloudflaredArgs {

    const val PROTOCOL = "quic"
    const val HA_CONNECTIONS = 2

    fun namedTunnel(binaryPath: String, token: String): List<String> = listOf(
        binaryPath, "tunnel",
        "--protocol", PROTOCOL,
        "--edge-ip-version", "4",
        "--ha-connections", HA_CONNECTIONS.toString(),
        "--no-autoupdate",
        "run", "--token", token
    )

    /** Loggable form: no binary path, token replaced. */
    fun redacted(args: List<String>): String =
        args.drop(1).dropLast(1).joinToString(" ") + " <redacted>"
}
