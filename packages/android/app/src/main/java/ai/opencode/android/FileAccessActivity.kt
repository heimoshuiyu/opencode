package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executors

class FileAccessActivity : Activity() {
    private lateinit var page: PickerPage
    private val docs by lazy { SharedDocuments(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = PickerPage(this)
        with(page) {
            header(R.string.file_access, back = true)
            panel.addView(text(getString(R.string.file_access_help), 15, muted)); gap(16)
            choice("R", R.string.grant_ro, R.string.grant_ro_hint) { pick(read) }
            choice("RW", R.string.grant_rw, R.string.grant_rw_hint) { pick(read or write) }
            section(getString(R.string.granted_folders))
            panel.addView(rows)
        }
    }
    private val rows by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private fun pick(modes: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), modes)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun refresh() {
        // Cloud document providers may need IPC or network to supply a display name.
        worker.execute {
            val grants = docs.folders().map { it to docs.label(it.uri) }.sortedBy { it.second.lowercase() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                rows.removeAllViews()
                if (grants.isEmpty()) rows.addView(page.text(getString(R.string.no_folders), 15, page.muted))
                grants.forEach { (grant, name) -> addRow(grant, name) }
            }
        }
    }
    private fun addRow(grant: UriPermission, name: String) = with(page) {
        val card = LinearLayout(this@FileAccessActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(2))
        }
        card.addView(text(name, 17, ink, true))
        card.addView(text(DocumentsContract.getTreeDocumentId(grant.uri), 13, muted, mono = true).apply { setPadding(0, dp(4), 0, 0) })
        val footer = LinearLayout(this@FileAccessActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = text(getString(if (grant.isWritePermission) R.string.read_write else R.string.read_only), 13, if (grant.isWritePermission) paper else ink, mono = true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); setBackgroundColor(if (grant.isWritePermission) ink else surface)
        }
        footer.addView(badge); footer.addView(android.view.View(this@FileAccessActivity), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(action(getString(R.string.revoke), getString(R.string.revoke_named, name)) {
            AlertDialog.Builder(this@FileAccessActivity).setMessage(getString(R.string.confirm_revoke, name))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.revoke) { _, _ ->
                    try { contentResolver.releasePersistableUriPermission(grant.uri, read or (if (grant.isWritePermission) write else 0)) }
                    catch (_: SecurityException) { /* Already revoked by the provider. */ }
                    refresh()
                }.show()
        }); card.addView(footer)
        rows.addView(card); rule(rows)
    }

    @Deprecated("System directory picker result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK || data == null || (code != read && code != (read or write))) return
        val uri: Uri = data.data ?: return
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) return
        try {
            val modes = code and data.flags
            require(modes and read != 0)
            // Re-selecting an existing RW directory via the read-only action must drop its old write bit.
            if (modes and write == 0 && docs.folders().any { it.uri == uri && it.isWritePermission })
                contentResolver.releasePersistableUriPermission(uri, write)
            contentResolver.takePersistableUriPermission(uri, modes)
            if (modes != code) Toast.makeText(this, R.string.provider_read_only, Toast.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, R.string.grant_failed, Toast.LENGTH_LONG).show() }
    }
}
