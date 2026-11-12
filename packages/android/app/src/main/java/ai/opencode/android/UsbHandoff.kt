package ai.opencode.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONObject
import java.util.UUID

/** A single approved handoff. No integer FD, reopenable USB handle or remote URL is returned to MCP. */
internal class UsbHandoff(private val activity: Activity) {
    private val usb = activity.getSystemService(UsbManager::class.java)
    private var listener: BroadcastReceiver? = null
    private var callback: PendingIntent? = null

    fun open(args: JSONObject, complete: (Result<JSONObject>) -> Unit) {
        val target = inventory(usb, args.getInt("id"))
        check(target.vendorId == args.getInt("vendor") && target.productId == args.getInt("product") && target.deviceName == args.getString("address")) { "Selected device changed" }
        val receiver = args.getString("receiver"); require(receiver.matches(Regex("[a-f0-9]{32}"))) { "Expected the receiver ID printed by oc-usb-run" }
        fun transfer(): JSONObject {
            val current = inventory(usb, target.deviceId)
            check(current.deviceName == target.deviceName && current.vendorId == target.vendorId && current.productId == target.productId) { "USB device changed or detached" }
            check(usb.hasPermission(current)) { "Android USB permission denied" }
            LocalSocket().use { peer ->
                peer.connect(LocalSocketAddress("opencode.usb.$receiver", LocalSocketAddress.Namespace.ABSTRACT))
                val cred = peer.peerCredentials
                check(cred.uid == Process.myUid() && cred.pid > 0 && cred.pid != Process.myPid()) { "Expected a separate process in this app's sandbox" }
                val connection = usb.openDevice(current) ?: error("Android could not open the USB device")
                try {
                    ParcelFileDescriptor.fromFd(connection.fileDescriptor).use { fd ->
                        peer.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor)); peer.outputStream.write(85)
                    }
                } finally { connection.close() }
                return JSONObject().put("transferred", true).put("receiverPid", cred.pid).put("id", current.deviceId)
            }
        }
        if (usb.hasPermission(target)) { complete(runCatching { transfer() }); return }
        val action = "${activity.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (listener !== this || intent.action != action) return
                complete(runCatching {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    check(device?.deviceName == target.deviceName && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) { "Android USB permission denied" }
                    transfer()
                })
            }
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(resultReceiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(resultReceiver, IntentFilter(action))
        listener = resultReceiver
        val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(activity, 0, Intent(action).setPackage(activity.packageName), flags)
        callback = pi; usb.requestPermission(target, pi)
    }
    fun close() {
        listener?.let { activity.unregisterReceiver(it) }; listener = null
        callback?.cancel(); callback = null
    }
    companion object {
        fun inventory(manager: UsbManager, id: Int) = manager.deviceList.values.singleOrNull { it.deviceId == id } ?: error("USB device not attached")
    }
}
