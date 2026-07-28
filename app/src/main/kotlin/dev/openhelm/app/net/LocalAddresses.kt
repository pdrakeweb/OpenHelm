package dev.openhelm.app.net

/**
 * Whether an address is one the app is willing to contact **on its own initiative**.
 *
 * The display lives on an isolated access point a few metres away. Nothing the app reaches for by
 * itself should ever be off that network, and the guard has to be applied here rather than trusted
 * to the transport: binding the process to Wi-Fi pins traffic to the *interface*, which is not the
 * same as keeping it on the *subnet*. If the boat's Wi-Fi has an internet path — a marina hotspot,
 * a router with a cellular uplink — an address that resolves publicly is reachable over it.
 *
 * The path that makes this worth defending is not a typo. The host of a discovered display comes
 * from an mDNS response, and anything on the same Wi-Fi can answer with whatever address it likes.
 * That address is then remembered, and remembered displays are probed automatically at every
 * launch. One advertisement from a hostile device on a marina network would otherwise buy a
 * connection attempt to an arbitrary internet host, every time the app opens, with no user action
 * at any point.
 *
 * ### What is allowed
 *
 * Only **numeric literals** in ranges that cannot be routed across the internet:
 *
 * | Range | |
 * |---|---|
 * | `10/8`, `172.16/12`, `192.168/16` | RFC 1918 private |
 * | `169.254/16` | RFC 3927 link-local, what a display falls back to with no DHCP |
 * | `127/8` | loopback, for the simulator on the same machine |
 * | `fc00::/7` | IPv6 unique local |
 * | `fe80::/10` | IPv6 link-local |
 * | `::1` | IPv6 loopback |
 *
 * Hostnames are rejected outright on automatic paths — including `.local` ones. Resolving a name
 * to decide whether it is safe would mean sending the query first, which is the thing being
 * avoided, and mDNS hands over a literal anyway so nothing legitimate is lost.
 *
 * `100.64/10` (carrier-grade NAT) is deliberately **not** allowed. It is not the public internet,
 * but it is not a boat's LAN either, and the failure mode of being too strict here is a manual
 * connect rather than an unwanted packet.
 *
 * ### What this does not cover
 *
 * Typing an address by hand. That is explicit intent, and someone with a routed or VPN'd setup has
 * a real reason to reach a display that is not on the local subnet — see `MainViewModel.
 * manualEndpoint`. The guard exists to stop the app acting *on its own*, not to stop the user.
 */
internal object LocalAddresses {

    /** True only for a numeric literal in one of the ranges above. */
    fun isLocal(host: String): Boolean {
        val bare = host.trim()
            .removeSurrounding("[", "]")
            .substringBefore('%') // IPv6 zone id, e.g. fe80::1%wlan0
            .lowercase()
        if (bare.isEmpty()) return false
        return if (bare.contains(':')) isLocalV6(bare) else isLocalV4(bare)
    }

    private fun isLocalV4(host: String): Boolean {
        val octets = parseV4(host) ?: return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }

    /** The first two octets, or null if [host] is not a dotted-quad literal. */
    private fun parseV4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = parts.map { part ->
            // Reject "", "+1", "01" and anything else that is not plainly a byte. Leading zeros
            // matter: some resolvers read them as octal, so they are not the number they look like.
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            if (!part.all { it in '0'..'9' }) return null
            part.toInt().also { if (it > 255) return null }
        }
        return values[0] to values[1]
    }

    private fun isLocalV6(host: String): Boolean {
        // An IPv4-mapped address carries its decision in the v4 part: ::ffff:192.168.1.7.
        val mapped = host.substringAfterLast(':')
        if (mapped.contains('.')) return isLocalV4(mapped)

        if (host == "::1") return true
        // fc00::/7 — the leading two bits after fc/fd are what define it; both prefixes qualify.
        if (host.startsWith("fc") || host.startsWith("fd")) return true
        // fe80::/10 — the top ten bits, which puts the boundary inside the second byte: fe8..feb.
        if (host.length >= 3 && host.startsWith("fe") && host[2] in "89ab") return true
        return false
    }
}
