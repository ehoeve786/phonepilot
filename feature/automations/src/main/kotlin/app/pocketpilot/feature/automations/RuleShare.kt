package app.pocketpilot.feature.automations

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.SHARED_RULE_MIME_TYPE
import app.pocketpilot.core.automation.shareFileJson
import app.pocketpilot.core.automation.shareText
import java.io.File

private const val MAX_FILE_NAME_LENGTH = 64

/**
 * Opens the system share sheet with [rule] as a `.pocketpilot` file that PocketPilot opens as a proposal,
 * and its readable text as the message body.
 */
fun Context.shareRule(rule: Rule) {
    val dir = File(cacheDir, "shared").apply { mkdirs() }
    // Earlier shares are removed so rule files, which include agent prompts, do not pile up in the cache.
    dir.listFiles()?.forEach { it.delete() }
    val baseName = rule.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().take(MAX_FILE_NAME_LENGTH).ifBlank { "rule" }
    val file = File(dir, "$baseName.pocketpilot")
    file.writeText(rule.shareFileJson())
    val uri = FileProvider.getUriForFile(this, "$packageName.shared", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(SHARED_RULE_MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, rule.name)
            .putExtra(Intent.EXTRA_TEXT, rule.shareText())
            .apply { clipData = ClipData.newRawUri(rule.name, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser =
        Intent
            .createChooser(send, getString(R.string.feature_automations_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooser)
}
