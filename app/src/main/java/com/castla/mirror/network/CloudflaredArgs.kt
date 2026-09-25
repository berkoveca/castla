package com.castla.mirror.network

/**
 * Command line for the bundled cloudflared connector.
 *
 * - `--protocol http2`: the default (QUIC over UDP) drops whenever a mobile
 *   carrier or hotspot NAT expires the idle UDP mapping; the phone uploads the
 *   whole video stream over its cellular uplink, so TCP-based http2 is the
 *   robust choice here.
 * - `--ha-connections 2`: two edge connections instead of one, so a single
 *   connection reset does not take the car's page down while it reconnects.
 */
object CloudflaredArgs {

    const val PROTOCOL = "http2"
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
