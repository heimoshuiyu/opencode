package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button

class ServerPicker : Activity() {
    private lateinit var stop: Button
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        with(PickerPage(this)) {
            header(R.string.choose_server)
            panel.addView(text(getString(R.string.choose_server_hint), 16, muted)); gap(20)
            choice("01", R.string.local_server, R.string.local_detail, true) { open(true) }
            choice("02", R.string.remote_server, R.string.remote_detail) { open(false) }
            section(getString(R.string.device_settings))
            choice("03", R.string.file_access, R.string.folder_detail) {
                startActivity(Intent(this@ServerPicker, FileAccessActivity::class.java))
            }
            stop = action(getString(R.string.stop_server)) {
                AlertDialog.Builder(this@ServerPicker).setMessage(R.string.stop_confirm).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.stop_server) { _, _ ->
                        if (LocalHostService.running) startService(Intent(this@ServerPicker, LocalHostService::class.java).setAction(LocalHostService.STOP))
                        stop.visibility = View.GONE
                    }.show()
            }; panel.addView(stop)
        }
    }
    private fun open(local: Boolean) = startActivity(Intent(this, MainActivity::class.java).putExtra("local", local))
    override fun onResume() { super.onResume(); stop.visibility = if (LocalHostService.running) View.VISIBLE else View.GONE }
}
