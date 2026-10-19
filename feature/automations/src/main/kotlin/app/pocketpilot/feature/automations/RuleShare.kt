package app.pocketpilot.feature.automations

import android.content.Context
import android.content.Intent
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.shareText

/** Opens the system share sheet with [rule] as plain text, for SMS, email or any messaging app. */
fun Context.shareRule(rule: Rule) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, rule.name)
            .putExtra(Intent.EXTRA_TEXT, rule.shareText())
    val chooser =
        Intent
            .createChooser(send, getString(R.string.feature_automations_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooser)
}
