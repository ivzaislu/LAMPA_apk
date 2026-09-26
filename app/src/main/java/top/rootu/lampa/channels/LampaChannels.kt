package top.rootu.lampa.channels

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.content.LampaProvider
import top.rootu.lampa.helpers.Helpers.isTvContentProviderAvailable

object LampaChannels {
    private const val TAG = "LampaChannels"
    private val lock = Any()
    private const val MAX_RECS_CAP = 30
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fullUpdateRunning = AtomicBoolean(false)

    @RequiresApi(Build.VERSION_CODES.O)
    fun update(sync: Boolean = true) {
        if (!isTvContentProviderAvailable) return

        if (!fullUpdateRunning.compareAndSet(false, true)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Full channel update already running, skip duplicate")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "update(sync: $sync)")

        // Keep recommendations first: ChannelManager gives RECS priority in its write queue.
        val channels = listOf(
            LampaProvider.RECS to {
                LampaProvider.get(LampaProvider.RECS, true)?.items.orEmpty().take(MAX_RECS_CAP)
            },
            LampaProvider.LIKE to {
                LampaProvider.get(LampaProvider.LIKE, false)?.items.orEmpty()
            },
            LampaProvider.BOOK to {
                LampaProvider.get(LampaProvider.BOOK, false)?.items.orEmpty()
            },
            LampaProvider.HIST to {
                LampaProvider.get(LampaProvider.HIST, false)?.items.orEmpty()
            },
            LampaProvider.LOOK to {
                LampaProvider.get(LampaProvider.LOOK, false)?.items.orEmpty()
            },
            LampaProvider.VIEW to {
                LampaProvider.get(LampaProvider.VIEW, false)?.items.orEmpty()
            },
            LampaProvider.SCHD to {
                LampaProvider.get(LampaProvider.SCHD, false)?.items.orEmpty()
            },
            LampaProvider.CONT to {
                LampaProvider.get(LampaProvider.CONT, false)?.items.orEmpty()
            },
            LampaProvider.THRW to {
                LampaProvider.get(LampaProvider.THRW, false)?.items.orEmpty()
            }
        )

        val enqueueChannels = {
            channels.forEach { (name, fetchFunction) ->
                ChannelManager.update(name, fetchFunction())
            }
        }

        if (sync) {
            try {
                // Scheduler calls this path from Dispatchers.IO.
                enqueueChannels()
                updateScope.launch {
                    try {
                        WatchNext.updateWatchNext()
                    } finally {
                        fullUpdateRunning.set(false)
                    }
                }
            } catch (e: Exception) {
                fullUpdateRunning.set(false)
                throw e
            }
        } else {
            updateScope.launch {
                try {
                    // Do not fan out channel refreshes: one background producer + one
                    // ChannelManager writer keeps TV-provider work from competing with the app.
                    enqueueChannels()
                    WatchNext.updateWatchNext()
                } finally {
                    fullUpdateRunning.set(false)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateRecsChannel() {
        if (!isTvContentProviderAvailable) return
        synchronized(lock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateRecsChannel()")
            val list =
                LampaProvider.get(LampaProvider.RECS, true)?.items.orEmpty().take(MAX_RECS_CAP)
            ChannelManager.update(LampaProvider.RECS, list)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateChanByName(name: String) {
        if (!isTvContentProviderAvailable) return
        synchronized(lock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateChanByName($name)")
            val list = LampaProvider.get(name, false)?.items.orEmpty()
            ChannelManager.update(name, list)
        }
    }
}