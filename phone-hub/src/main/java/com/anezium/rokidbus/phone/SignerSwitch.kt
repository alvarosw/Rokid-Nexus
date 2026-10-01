package com.anezium.rokidbus.phone

/**
 * Moving a plugin to a build signed with another key: the user confirms in the Store, the system
 * uninstaller removes the installed copy, then the Store installs the registry build and the new
 * signer's access goes through approval again.
 */
internal object SignerSwitch {
    enum class Next {
        INSTALL,

        /** The package is gone but the plugin catalogue has not caught up yet. */
        WAIT,

        /** The installed copy is still there: the user declined the system uninstall. */
        ABANDON,
    }

    const val MAX_WAIT_ATTEMPTS = 10
    const val WAIT_INTERVAL_MS = 400L

    fun afterUninstallPrompt(entry: StoreEntry?): Next = when {
        entry == null -> Next.ABANDON
        entry.state == StoreEntryState.AVAILABLE -> Next.INSTALL
        entry.installedVersionCode == null -> Next.WAIT
        else -> Next.ABANDON
    }
}
