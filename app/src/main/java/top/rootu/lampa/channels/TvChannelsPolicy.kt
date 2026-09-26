package top.rootu.lampa.channels

import android.os.Build
import androidx.annotation.RequiresApi
import top.rootu.lampa.App
import top.rootu.lampa.helpers.Helpers.isTvContentProviderAvailable
import top.rootu.lampa.helpers.Prefs.androidTvChannelsEnabled

/**
 * Single switch for every Android TV Home integration path.
 *
 * The custom branch keeps the feature code intact, but defaults it to disabled.
 * When disabled, callers must not write preview channels or Watch Next rows.
 */
object TvChannelsPolicy {

    // User-facing master switch. This also covers the legacy pre-O recommendation path.
    val requestedEnabled: Boolean
        get() = App.context.androidTvChannelsEnabled

    // Modern TvProvider integration is available only when both the switch and provider are active.
    val enabled: Boolean
        get() = requestedEnabled && isTvContentProviderAvailable

    fun setEnabled(value: Boolean) {
        App.context.androidTvChannelsEnabled = value
    }

    /**
     * Remove Lampa-owned Android TV Home data when the integration is disabled.
     * Safe to call repeatedly.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun clearPublishedContent() {
        if (!isTvContentProviderAvailable) return
        ChannelManager.removeAll()
        WatchNext.clearAll()
    }
}
