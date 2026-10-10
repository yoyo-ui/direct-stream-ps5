package com.directstream.ps5.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

class UrlRejectedException(message: String) : Exception(message)

/**
 * Decides which URLs the app may fetch. Rules:
 *  - only http/https, no credentials embedded in the URL;
 *  - loopback / link-local / private / CGNAT targets are refused unless the user opted in
 *    for the *initial* URL (a LAN media server is legitimate);
 *  - a redirect may never move from a public host to a private one, nor downgrade https -> http.
 * Note: names are resolved here and again by the HTTP stack, so DNS rebinding is not fully excluded.
 */
class UrlPolicy(
    private val allowPrivateHosts: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() }
) {
    fun validateInitial(url: URL) {
        checkBasics(url)
        if (!allowPrivateHosts && isPrivate(url.host))
            throw UrlRejectedException("Address ${url.host} is on a private or local network. Enable 'allow local-network sources' to use it.")
    }

    fun validateRedirect(from: URL, to: URL) {
        checkBasics(to)
        if (from.protocol == "https" && to.protocol == "http")
            throw UrlRejectedException("Redirect from HTTPS to HTTP refused")
        if (!isPrivate(from.host) && isPrivate(to.host))
            throw UrlRejectedException("Redirect to a private or local address refused")
        if (!allowPrivateHosts && isPrivate(to.host))
            throw UrlRejectedException("Redirect to a private or local address refused")
    }

    private fun checkBasics(url: URL) {
        if (url.protocol != "http" && url.protocol != "https")
            throw UrlRejectedException("Only http and https links are supported (got '${url.protocol}')")
        if (url.host.isNullOrBlank()) throw UrlRejectedException("Link has no host")
        if (!url.userInfo.isNullOrEmpty())
            throw UrlRejectedException("Credentials inside the link are not accepted; use the headers field instead")
    }

    fun isPrivate(host: String): Boolean {
        val addrs = try { resolve(host) } catch (e: Exception) { return false /* unresolved: the connect will fail */ }
        return addrs.any { isPrivateAddress(it) }
    }

    companion object {
        fun isPrivateAddress(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            if (a is Inet4Address) {
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                if (b0 == 100 && b1 in 64..127) return true   // CGNAT 100.64.0.0/10
                if (b0 == 0) return true
            }
            if (a is Inet6Address && (b[0].toInt() and 0xfe) == 0xfc) return true   // unique-local fc00::/7
            return false
        }
    }
}
