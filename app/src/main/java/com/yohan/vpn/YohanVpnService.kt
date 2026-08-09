package com.yohan.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

class YohanVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "yohan_vpn_channel"
        const val NOTIF_ID = 1
        const val ACTION_STATUS = "com.yohan.vpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val ACTION_STOP = "com.yohan.vpn.STOP"

        const val STATUS_CONNECTING = "CONNECTING"
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_DISCONNECTED = "DISCONNECTED"
    }

    private var tun: ParcelFileDescriptor? = null
    private var ssh: SshSocksServer? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel("تم الإيقاف من المستخدم")
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra("host").orEmpty()
        val port = intent?.getIntExtra("port", 22) ?: 22
        val user = intent?.getStringExtra("user").orEmpty()
        val pass = intent?.getStringExtra("password").orEmpty()
        val verifyHostKey = intent?.getBooleanExtra("verifyHostKey", false) ?: false

        startForeground(NOTIF_ID, buildNotification("جاري الاتصال..."))
        broadcastStatus(STATUS_CONNECTING, "جاري الاتصال بخادم SSH...")

        running = true
        worker = Thread {
            try {
                val server = SshSocksServer(host, port, user, pass, verifyHostKey)
                val socksPort = server.start()
                ssh = server

                val builder = Builder()
                    .setSession("Yohan VPN")
                    .addAddress("10.8.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                tun = builder.establish()

                if (tun == null) {
                    broadcastStatus(STATUS_ERROR, "فشل إنشاء واجهة TUN (تحقق من صلاحية VPN)")
                    stopTunnel(null)
                    return@Thread
                }

                updateNotification("متصل — بروكسي SOCKS5 محلي على المنفذ $socksPort")
                broadcastStatus(STATUS_CONNECTED, "متصل. بروكسي SOCKS5 يعمل محلياً على 127.0.0.1:$socksPort")

                // نقطة الربط مع جسر الحزم (tun2socks):
                // الجلسة SSH فعّالة الآن وتوفر بروكسي SOCKS5 حقيقي على socksPort.
                // واجهة TUN جاهزة عبر tun!!.fileDescriptor.
                // لتحويل هذا إلى نفق كامل لكل حركة الجهاز، مرّر tun!!.fileDescriptor
                // ومنفذ socksPort إلى مكتبة native (مثل hev-socks5-tunnel أو tun2socks)
                // توضع في app/src/main/jniLibs/<abi>/*.so — راجع README.
                //
                // بدون هذه المكتبة: التطبيق يعمل بشكل كامل وحقيقي كبروكسي SOCKS5
                // عبر SSH يمكن استخدامه من أي تطبيق يدعم إعداد بروكسي SOCKS5 يدوياً،
                // لكنه لا ينفق كل حركة الجهاز تلقائياً.

                while (running) {
                    if (ssh?.isConnected() != true) {
                        broadcastStatus(STATUS_ERROR, "انقطع اتصال SSH")
                        break
                    }
                    Thread.sleep(1000)
                }
            } catch (t: Throwable) {
                broadcastStatus(STATUS_ERROR, "فشل الاتصال: ${t.message}")
            } finally {
                stopTunnel(null)
            }
        }
        worker!!.start()

        return START_STICKY
    }

    private fun stopTunnel(reasonForUiOnly: String?) {
        running = false
        try { ssh?.stop() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        ssh = null
        tun = null
        broadcastStatus(STATUS_DISCONNECTED, reasonForUiOnly ?: "غير متصل")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel(null)
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel("تم إلغاء صلاحية VPN من النظام")
        super.onRevoke()
    }

    private fun broadcastStatus(status: String, message: String) {
        val i = Intent(ACTION_STATUS)
        i.putExtra(EXTRA_STATUS, status)
        i.putExtra("message", message)
        i.setPackage(packageName)
        sendBroadcast(i)
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Yohan VPN", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, YohanVpnService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Yohan VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع الاتصال", stopPending)
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }
}
