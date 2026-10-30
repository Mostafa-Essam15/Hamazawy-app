package com.hamzawy.radio

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/**
 * Shows the "connect phone" dialog: starts [ConnectServer], shows the address to type on the phone
 * and closes everything as soon as something arrives or the dialog is dismissed.
 */
class PhoneLink(
    private val activity: AppCompatActivity,
    private val allowImage: Boolean,
    private val allowStation: Boolean,
    private val imageHandler: (ByteArray) -> Unit,
    private val stationHandler: (String, String) -> Unit
) {

    private var server: ConnectServer? = null
    private var dialog: AlertDialog? = null

    fun show() {
        dismiss()
        val ip = localIp()
        if (ip == null) {
            Toast.makeText(activity, R.string.phone_no_network, Toast.LENGTH_LONG).show()
            return
        }
        val token = UUID.randomUUID().toString().replace("-", "").take(6)
        val s = ConnectServer(token, allowImage, allowStation, object : ConnectServer.Listener {
            override fun onImage(bytes: ByteArray) {
                activity.runOnUiThread {
                    dismiss()
                    imageHandler(bytes)
                }
            }

            override fun onStation(name: String, url: String) {
                activity.runOnUiThread {
                    dismiss()
                    stationHandler(name, url)
                }
            }
        })
        if (!s.start()) {
            Toast.makeText(activity, R.string.phone_server_failed, Toast.LENGTH_LONG).show()
            return
        }
        server = s

        val address = "http://" + ip + ":" + s.port + "/" + token + "/"
        val message = activity.getString(
            if (allowStation) R.string.phone_message_all else R.string.phone_message_theme,
            address
        )
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.phone_title)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { stopServer() }
            .create()
        dialog = d
        d.show()
    }

    fun dismiss() {
        val d = dialog
        dialog = null
        d?.dismiss()
        stopServer()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun localIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) return host
                        if (fallback == null) fallback = host
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            return null
        }
    }
}
