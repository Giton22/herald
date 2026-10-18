package dev.hermeskotlin.core.media

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy
import okhttp3.Dns

// A public name can still point home (127.0.0.1.nip.io, a tailnet's MagicDNS), so the resolved addresses are checked
// too. OkHttp connects to an address-like host without asking Dns, so the connected address is checked before the
// request goes out. No proxy: one would resolve the name itself, out of these checks' sight.
internal actual fun publicWebEngine(): HttpClientEngine = OkHttp.create {
    config {
        proxy(Proxy.NO_PROXY)
        dns { host ->
            Dns.SYSTEM.lookup(host).filter { publicAddress(it.address) }.ifEmpty { throw NotPublicAddress(host) }
        }
        addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || !publicAddress(address.address)) throw NotPublicAddress(chain.request().url.host)
            chain.proceed(chain.request())
        }
    }
}
