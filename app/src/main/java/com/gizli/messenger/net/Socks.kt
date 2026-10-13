package com.gizli.messenger.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Tor (Orbot) üzerinden bağlanmak için SOCKS vekil fabrikaları.
 * Uygulama 127.0.0.1:9050 adresindeki SOCKS5 vekiline bağlanır; Orbot
 * trafiği Tor ağına taşır. Böylece broker/relay sunucusu gerçek IP'yi görmez.
 */
object Socks {

    fun proxy(host: String, port: Int): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))

    private fun rawSocket(host: String, port: Int, destHost: String, destPort: Int): Socket {
        val s = Socket(proxy(host, port))
        s.connect(InetSocketAddress(destHost, destPort), 20000)
        return s
    }

    /** Düz TCP (tcp://) için SOCKS soket fabrikası. */
    class Plain(private val host: String, private val port: Int) : SocketFactory() {
        override fun createSocket(): Socket = Socket(proxy(host, port))
        override fun createSocket(host2: String?, port2: Int): Socket =
            rawSocket(host, port, host2!!, port2)
        override fun createSocket(host2: String?, port2: Int, localHost: InetAddress?, localPort: Int): Socket =
            rawSocket(host, port, host2!!, port2)
        override fun createSocket(host2: InetAddress?, port2: Int): Socket =
            rawSocket(host, port, host2!!.hostAddress, port2)
        override fun createSocket(addr: InetAddress?, port2: Int, localAddr: InetAddress?, localPort: Int): Socket =
            rawSocket(host, port, addr!!.hostAddress, port2)
    }

    /** TLS (ssl://) için SOCKS üzerinden kurulan SSLSocket fabrikası. */
    class Tls(
        private val host: String,
        private val port: Int,
        private val inner: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    ) : SSLSocketFactory() {
        private fun wrap(destHost: String, destPort: Int): Socket {
            val s = rawSocket(host, port, destHost, destPort)
            return inner.createSocket(s, destHost, destPort, true)
        }

        override fun getDefaultCipherSuites(): Array<String> = inner.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites
        override fun createSocket(): Socket = createSocket("", 0)
        override fun createSocket(host2: String?, port2: Int): Socket = wrap(host2!!, port2)
        override fun createSocket(host2: String?, port2: Int, localHost: InetAddress?, localPort: Int): Socket =
            wrap(host2!!, port2)
        override fun createSocket(host2: InetAddress?, port2: Int): Socket = wrap(host2!!.hostAddress, port2)
        override fun createSocket(addr: InetAddress?, port2: Int, localAddr: InetAddress?, localPort: Int): Socket =
            wrap(addr!!.hostAddress, port2)
        override fun createSocket(s: Socket?, host2: String?, port2: Int, autoClose: Boolean): Socket =
            inner.createSocket(s, host2, port2, autoClose)
    }
}
