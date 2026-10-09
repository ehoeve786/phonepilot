package app.pocketpilot.core.policy

/** What tools may do while an app is in front (spec section 8). */
enum class AppMode {
    ALLOW,

    /** Screen reading only; no input. */
    READ_ONLY,

    /** Every interacting call needs the owner's confirmation. */
    CONFIRM_ALL,

    /** Nothing: no reading and no input. */
    DENY,
}

/**
 * Per-app modes. Apps the owner has not set fall back to the default deny list (banking, payment,
 * password managers, authenticators and PocketPilot itself) or else [AppMode.ALLOW].
 */
class AppPolicy(
    private val overrides: () -> Map<String, AppMode> = { emptyMap() },
    private val ownPackages: Set<String> = emptySet(),
) {
    fun modeFor(packageName: String): AppMode =
        overrides()[packageName]
            ?: when {
                packageName in ownPackages -> AppMode.DENY
                packageName in DEFAULT_DENY -> AppMode.DENY
                else -> AppMode.ALLOW
            }

    companion object {
        /**
         * Starter deny list. Matching by Play category arrives with the app policy screen; until then,
         * this list covers the common wallets, payment apps, password managers, authenticators and
         * the major North American banks.
         */
        val DEFAULT_DENY: Set<String> =
            setOf(
                // Wallets and payments
                "com.google.android.apps.walletnfcrel",
                "com.samsung.android.spay",
                "com.samsung.android.samsungpay.gear",
                "com.paypal.android.p2pmobile",
                "com.venmo",
                "com.squareup.cash",
                "com.zellepay.zelle",
                "com.revolut.revolut",
                "com.wise.android",
                "com.coinbase.android",
                // Password managers
                "com.x8bit.bitwarden",
                "com.agilebits.onepassword",
                "com.lastpass.lpandroid",
                "com.dashlane",
                "com.kpcmobile.keeper",
                "proton.android.pass",
                "keepass2android.keepass2android",
                // Authenticators
                "com.google.android.apps.authenticator2",
                "com.azure.authenticator",
                "com.authy.authy",
                "com.duosecurity.duomobile",
                "org.fedorahosted.freeotp",
                "com.beemdevelopment.aegis",
                // Banks (Canada)
                "com.td",
                "com.rbc.mobile.android",
                "com.cibc.android.mobi",
                "com.bmo.mobile",
                "com.scotiabank.banking",
                "com.desjardins.mobile",
                "ca.tangerine.clients.banking.app",
                "com.eq.mobile",
                // Banks (United States)
                "com.chase.sig.android",
                "com.wf.wellsfargomobile",
                "com.infonow.bofa",
                "com.citi.citimobile",
                "com.usbank.mobilebanking",
                "com.capitalone.mobile",
                "com.konylabs.capitalone",
                "com.americanexpress.android.acctsvcs.us",
                "com.discoverfinancial.mobile",
            )
    }
}
