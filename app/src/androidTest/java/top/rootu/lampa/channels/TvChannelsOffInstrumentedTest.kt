package top.rootu.lampa.channels

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.lampa.content.LampaProvider
import top.rootu.lampa.helpers.ChannelHelper
import top.rootu.lampa.helpers.Helpers
import top.rootu.lampa.helpers.Prefs.androidTvChannelsEnabled
import top.rootu.lampa.models.LampaCard
import top.rootu.lampa.receivers.HomeWatch
import top.rootu.lampa.sched.ContentJobService
import top.rootu.lampa.sched.Scheduler

@RunWith(AndroidJUnit4::class)
class TvChannelsOffInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue("Test requires Android O or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        assertTrue(
            "Test must run on an Android TV image with TvProvider",
            Helpers.isTvContentProviderAvailable
        )
        assertTrue(
            "TvProvider must be queryable; otherwise OFF would be a vacuous pass",
            Helpers.isTvChannelContentProviderAccessible(context)
        )

        context.androidTvChannelsEnabled = false
        TvChannelsPolicy.clearPublishedContent()
        cancelSchedulerJobs()
        waitUntil { noTestRowsRemain() }
    }

    @After
    fun tearDown() {
        context.androidTvChannelsEnabled = false
        TvChannelsPolicy.clearPublishedContent()
        cancelSchedulerJobs()
    }

    @Test
    fun offBlocksDirectWritesEventUpdatesBroadcastsAndScheduler() = runBlocking {
        val card = testCard()

        assertFalse(TvChannelsPolicy.requestedEnabled)
        assertFalse(TvChannelsPolicy.enabled)

        // Exercise the same public write entry points the real app uses.
        ChannelManager.update(LampaProvider.RECS, listOf(card))
        LampaChannels.update(sync = true)
        LampaChannels.updateRecsChannel()
        LampaChannels.updateChanByName(LampaProvider.BOOK)

        ChannelHelper.add(TEST_PLUGIN_CHANNEL, "Blocked direct helper")
        assertFalse(PluginChannels.publish(pluginPayload()))
        assertFalse(PluginChannels.clear("ci-tv-off-probe"))
        WatchNext.add(card)
        WatchNext.addLastPlayed(card, "{}")
        WatchNext.updateWatchNext()

        // System Android TV broadcast path.
        HomeWatch().onReceive(
            context,
            Intent(TvContractCompat.ACTION_INITIALIZE_PROGRAMS)
        )

        // Explicit scheduler paths must stay dead too.
        Scheduler.scheduleUpdate(true)
        Scheduler.scheduleUpdate(false)
        Scheduler.updateContent(true)
        Scheduler.updateContent(false)

        Thread.sleep(750)

        assertNull("OFF created the recs channel", ChannelHelper.get(LampaProvider.RECS))
        assertNull("OFF created the bookmarks channel", ChannelHelper.get(LampaProvider.BOOK))
        assertFalse("OFF inserted a preview program", previewProgramContains(TEST_ID))
        assertFalse("OFF inserted a Watch Next row", watchNextContains(TEST_ID))
        assertFalse("OFF scheduled ContentJobService", contentJobIsScheduled())
    }

    @Test
    fun sameWritePathsWorkWhenOnAndAreActuallyRemovedWhenTurnedOff() {
        val card = testCard()

        TvChannelsPolicy.setEnabled(true)
        assertTrue(TvChannelsPolicy.requestedEnabled)
        assertTrue(
            "TvProvider is present but policy did not become enabled",
            TvChannelsPolicy.enabled
        )

        // Prove the environment and write paths are real: ON must create rows.
        ChannelManager.update(LampaProvider.RECS, listOf(card))
        WatchNext.add(card)
        assertTrue(PluginChannels.publish(pluginPayload()))

        waitUntil {
            ChannelHelper.get(LampaProvider.RECS) != null &&
                    ChannelHelper.get(TEST_PLUGIN_CHANNEL) != null &&
                    previewProgramContains(TEST_ID) &&
                    watchNextContains(TEST_ID)
        }

        assertNotNull("ON failed to create recs channel", ChannelHelper.get(LampaProvider.RECS))
        assertNotNull("ON failed to create plugin channel", ChannelHelper.get(TEST_PLUGIN_CHANNEL))
        assertTrue("ON failed to insert preview program", previewProgramContains(TEST_ID))
        assertTrue("ON failed to insert Watch Next row", watchNextContains(TEST_ID))

        // Now switch OFF and perform the exact production cleanup.
        TvChannelsPolicy.setEnabled(false)
        TvChannelsPolicy.clearPublishedContent()

        waitUntil { noTestRowsRemain() }

        assertFalse(TvChannelsPolicy.requestedEnabled)
        assertNull("OFF cleanup left recs channel behind", ChannelHelper.get(LampaProvider.RECS))
        assertNull("OFF cleanup left plugin channel behind", ChannelHelper.get(TEST_PLUGIN_CHANNEL))
        assertFalse("OFF cleanup left preview program behind", previewProgramContains(TEST_ID))
        assertFalse("OFF cleanup left Watch Next row behind", watchNextContains(TEST_ID))
    }

    @Test
    fun waitingPublishersCannotWriteAfterOffCleanup() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val request = requireNotNull(PluginChannelRequest.parse(pluginPayload()))
        val writes: List<() -> Unit> = listOf(
            { ChannelManager.update(LampaProvider.RECS, listOf(testCard())) },
            { ChannelManager.replacePluginChannel(request) },
            { ChannelHelper.add(TEST_PLUGIN_CHANNEL, "Direct helper") },
            { WatchNext.add(testCard()) },
            { WatchNext.addLastPlayed(testCard(), "{}") }
        )
        val workers = mutableListOf<Thread>()
        synchronized(TvChannelsPolicy.publicationLock) {
            TvChannelsPolicy.setEnabled(true)
            writes.forEach { write ->
                val worker = Thread {
                    try { write() } catch (error: Throwable) { failures.add(error) }
                }
                workers.add(worker)
                worker.start()
                waitUntil { worker.state == Thread.State.BLOCKED || !worker.isAlive }
                assertTrue("Writer must wait at the publication boundary", worker.isAlive)
            }
            TvChannelsPolicy.setEnabled(false)
            TvChannelsPolicy.clearPublishedContent()
        }
        workers.forEach { it.join(8000); assertFalse("Writer did not finish", it.isAlive) }
        assertTrue("Publisher failed: $failures", failures.isEmpty())
        assertTrue("A waiting publisher recreated content after OFF", noTestRowsRemain())
    }

    @Test
    fun deferredBuiltInProgramsCannotReappearAfterOffCleanup() {
        synchronized(TvChannelsPolicy.publicationLock) {
            TvChannelsPolicy.setEnabled(true)
            // update creates the channel now and queues program writes on Dispatchers.IO.
            ChannelManager.update(LampaProvider.RECS, listOf(testCard()))
            assertNotNull(ChannelHelper.get(LampaProvider.RECS))
            TvChannelsPolicy.setEnabled(false)
            TvChannelsPolicy.clearPublishedContent()
        }
        top.rootu.lampa.helpers.Coroutines.join("update_channel_${LampaProvider.RECS}")
        assertTrue("Deferred program publication survived OFF cleanup", noTestRowsRemain())
    }

    @Test
    fun offRemovesOwnedWatchNextEvenWithoutLaunchIntent() {
        TvChannelsPolicy.setEnabled(true)
        val legacy = WatchNextProgram.Builder()
            .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setInternalProviderId(TEST_ID)
            .setTitle("Legacy row without intent")
            .build()
        assertNotNull(context.contentResolver.insert(
            TvContractCompat.WatchNextPrograms.CONTENT_URI, legacy.toContentValues()))
        assertTrue(watchNextContains(TEST_ID))
        TvChannelsPolicy.setEnabled(false)
        TvChannelsPolicy.clearPublishedContent()
        assertFalse("OFF left an owned row with no intent", watchNextContains(TEST_ID))
    }

    private fun pluginPayload(): String =
        """{"id":"ci-tv-off-probe","title":"CI Plugin","items":[{"id":"$TEST_ID","type":"movie","title":"CI probe"}]}"""

    private fun testCard(): LampaCard =
        Gson().fromJson(
            """
            {
              "source": "tmdb",
              "type": "movie",
              "id": "$TEST_ID",
              "title": "CI Android TV OFF probe",
              "overview": "Created only by instrumentation tests",
              "release_year": "2026",
              "runtime": 90
            }
            """.trimIndent(),
            LampaCard::class.java
        )

    private fun previewProgramContains(id: String): Boolean {
        return context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )
            if (index < 0) return@use false

            while (cursor.moveToNext()) {
                if (id == cursor.getString(index)) return@use true
            }
            false
        } ?: false
    }

    private fun watchNextContains(id: String): Boolean {
        return context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )
            if (index < 0) return@use false

            while (cursor.moveToNext()) {
                if (id == cursor.getString(index)) return@use true
            }
            false
        } ?: false
    }

    private fun noTestRowsRemain(): Boolean =
        ChannelHelper.get(LampaProvider.RECS) == null &&
                ChannelHelper.get(TEST_PLUGIN_CHANNEL) == null &&
                !previewProgramContains(TEST_ID) &&
                !watchNextContains(TEST_ID)

    private fun contentJobIsScheduled(): Boolean {
        val scheduler =
            context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return scheduler.allPendingJobs.any {
            it.service.className == ContentJobService::class.java.name
        }
    }

    private fun cancelSchedulerJobs() {
        val scheduler =
            context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.allPendingJobs
            .filter { it.service.className == ContentJobService::class.java.name }
            .forEach { scheduler.cancel(it.id) }
    }

    private fun waitUntil(timeoutMs: Long = 8000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for TvProvider state", condition())
    }

    companion object {
        private const val TEST_ID = "ci-tv-off-probe-2026"
        private const val TEST_PLUGIN_CHANNEL = "plugin:ci-tv-off-probe"
    }
}
