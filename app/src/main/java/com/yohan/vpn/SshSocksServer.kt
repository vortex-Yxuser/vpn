package com.yohan.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.net.ServerSocket

/**
 * يفتح جلسة SSH ثم يفعّل Dynamic Port Forwarding (وهو ما يجعل SSH
 * يعمل كبروكسي SOCKS5 حقيقي على المنفذ المحلي). هذا يصحح الإصدار القديم
 * الذي كان يستخدم Local Forwarding بمنفذ وجهة 0 (غير صالح وغير فعّال).
 */
class SshSocksServer(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val verifyHostKey: Boolean = false
) {
    private var session: Session? = null
    var localPort: Int = 0
        private set

    /** يرجع رقم منفذ SOCKS5 المحلي عند النجاح، أو يرمي استثناء بسبب واضح عند الفشل. */
    fun start(): Int {
        val jsch = JSch()
        val s = jsch.getSession(user, host, port)
        if (pass.isNotEmpty()) s.setPassword(pass)
        s.setConfig("StrictHostKeyChecking", if (verifyHostKey) "yes" else "no")
        s.timeout = 15000
        s.connect(15000)

        val probe = ServerSocket(0)
        val chosenPort = probe.localPort
        probe.close()

        // Dynamic Forwarding = بروكسي SOCKS5 عام يقبل أي وجهة، وهذا هو المطلوب
        // كي يستقبل جسر الحزم (tun2socks) اتصالاته منه.
        s.setPortForwardingD("127.0.0.1", chosenPort)

        session = s
        localPort = chosenPort
        return chosenPort
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun stop() {
        try { session?.delPortForwardingD("127.0.0.1", localPort) } catch (_: Throwable) {}
        try { session?.disconnect() } catch (_: Throwable) {}
        session = null
    }
}
