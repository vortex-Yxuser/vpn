package com.yohan.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var verifyHostKey: CheckBox
    private lateinit var connectBtn: Button
    private lateinit var status: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = intent?.getStringExtra(YohanVpnService.EXTRA_STATUS) ?: return
            val msg = intent.getStringExtra("message") ?: ""
            status.text = msg
            connectBtn.isEnabled = s != YohanVpnService.STATUS_CONNECTING
            connectBtn.text = if (s == YohanVpnService.STATUS_CONNECTED) "قطع الاتصال" else "اتصال"
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        fun field(hint: String) = EditText(this).also { it.hint = hint; layout.addView(it) }

        host = field("SSH Host")
        port = field("SSH Port (22)")
        user = field("Username")
        pass = field("Password").also { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

        verifyHostKey = CheckBox(this).apply { text = "التحقق من مفتاح الخادم (أكثر أماناً)"; layout.addView(this) }

        connectBtn = Button(this).apply { text = "اتصال" }
        layout.addView(connectBtn)

        status = TextView(this).apply { text = "غير متصل"; textSize = 16f; setPadding(0, 32, 0, 0) }
        layout.addView(status)

        setContentView(layout)

        connectBtn.setOnClickListener {
            if (connectBtn.text == "قطع الاتصال") {
                stopService(Intent(this, YohanVpnService::class.java).setAction(YohanVpnService.ACTION_STOP))
                return@setOnClickListener
            }
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 7) else startTunnel()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(YohanVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statusReceiver) } catch (_: Throwable) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) startTunnel()
    }

    private fun startTunnel() {
        if (host.text.isBlank() || user.text.isBlank()) {
            Toast.makeText(this, "أدخل المضيف واسم المستخدم على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, YohanVpnService::class.java).apply {
            putExtra("host", host.text.toString().trim())
            putExtra("port", port.text.toString().toIntOrNull() ?: 22)
            putExtra("user", user.text.toString().trim())
            putExtra("password", pass.text.toString())
            putExtra("verifyHostKey", verifyHostKey.isChecked)
        }
        ContextCompat.startForegroundService(this, i)
        status.text = "جاري الاتصال..."
    }
}
