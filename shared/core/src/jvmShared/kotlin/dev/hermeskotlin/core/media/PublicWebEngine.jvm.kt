package dev.hermeskotlin.core.media

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy
import okhttp3.Dns
import okhttp3.OkHttpClient

internal actual fun publicWebEngine(): HttpClientEngine = OkHttp.create { config { publicOnly() } }

/**
 * Only the open internet: what a reply or the agent names is fetched without a tap, so never the user's own networks
 * (a router, the gateway, the tailnet).
 *
 * A public name can still point home (127.0.0.1.nip.io, a tailnet's MagicDNS), so the resolved addresses are checked
 * too. OkHttp connects to an address-like host without asking Dns, so the connected address is checked before the
 * request goes out. No proxy: one would resolve the name itself, out of these checks' sight.
 */
fun OkHttpClient.Builder.publicOnly(): OkHttpClient.Builder = proxy(Proxy.NO_PROXY)
    .dns { host -> Dns.SYSTEM.lookup(host).filter { publicAddress(it.address) }.ifEmpty { throw NotPublicAddress(host) } }
    .addNetworkInterceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address == null || !publicAddress(address.address)) throw NotPublicAddress(chain.request().url.host)
        chain.proceed(chain.request())
    }
