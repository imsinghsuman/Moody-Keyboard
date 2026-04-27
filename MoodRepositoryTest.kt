package com.moody.keyboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moody.keyboard.data.database.MoodyDatabase
import com.moody.keyboard.data.database.dao.MoodEntryDao
import com.moody.keyboard.data.database.entity.MoodEntryEntity
import com.moody.keyboard.domain.model.MoodLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration tests for [MoodEntryDao] using an in-memory Room database.
 *
 * These tests run on an Android device or emulator (androidTest source set).
 * Each test gets a fresh in-memory database — state never leaks between tests.
 *
 * Covers:
 *  - Insert + retrieve (getLatestMoodEntry)
 *  - getMoodLastHour — boundary: entries older than 60 min not returned
 *  - getMoodLastWeek — 7-day span: entries from today and 6 days ago are included,
 *    entries 8 days ago are excluded
 */
@RunWith(AndroidJUnit4::class)
class MoodRepositoryTest {

    private lateinit var db: MoodyDatabase
    private lateinit var dao: MoodEntryDao

    @Before
    fun setUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MoodyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.moodEntryDao()
    }

    @After
    fun tearDownDatabase() {
        db.close()
    }

    // ── Insert + retrieve ──────────────────────────────────────────────────────

    @Test
    fun insertAndGetLatestEntry() = runBlocking {
        val entry = moodEntry(MoodLabel.POSITIVE, timestamp = System.currentTimeMillis())
        dao.insert(entry)

        val latest = dao.getLatestMoodEntry().first()
        assertNotNull(latest)
        assertEquals(MoodLabel.POSITIVE.name, latest!!.moodLabel)
    }

    @Test
    fun getLatestEntry_returnsNullWhenTableIsEmpty() = runBlocking {
        val latest = dao.getLatestMoodEntry().first()
        assertNull(latest)
    }

    @Test
    fun getLatestEntry_returnsMostRecentWhenMultipleInserted() = runBlocking {
        val oldEntry = moodEntry(MoodLabel.STRESSED, timestamp = System.currentTimeMillis() - 10_000)
        val newEntry = moodEntry(MoodLabel.POSITIVE, timestamp = System.currentTimeMillis())
        dao.insert(oldEntry)
        dao.insert(newEntry)

        val latest = dao.getLatestMoodEntry().first()
        assertEquals(MoodLabel.POSITIVE.name, latest?.moodLabel)
    }

    // ── getMoodLastHour boundary ───────────────────────────────────────────────

    @Test
    fun getMoodLastHour_includesEntriesWithinLastHour() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(moodEntry(MoodLabel.POSITIVE, timestamp = now - 30 * 60_000)) // 30 min ago
        dao.insert(moodEntry(MoodLabel.NEUTRAL,  timestamp = now - 59 * 60_000)) // 59 min ago

        val since = System.currentTimeMillis() - 60 * 60_000
        val entries = dao.getMoodEntriesSince(since).first()
        assertEquals(2, entries.size)
    }

    @Test
    fun getMoodLastHour_excludesEntriesOlderThanOneHour() = runBlocking {
        val now = System.currentTimeMillis()
        // 61 minutes ago — should NOT appear
        dao.insert(moodEntry(MoodLabel.STRESSED, timestamp = now - 61 * 60_000))

        val since = now - 60 * 60_000
        val entries = dao.getMoodEntriesSince(since).first()
        assertTrue("Expected no entries, got ${entries.size}", entries.isEmpty())
    }

    // ── getMoodLastWeek span ───────────────────────────────────────────────────

    @Test
    fun getMoodLastWeek_includesEntryFromSixDaysAgo() = runBlocking {
        val now = System.currentTimeMillis()
        val sixDaysAgo = now - 6L * 24 * 60 * 60 * 1000
        dao.insert(moodEntry(MoodLabel.FATIGUED, timestamp = sixDaysAgo + 1_000))

        val since = now - 7L * 24 * 60 * 60 * 1000
        val entries = dao.getMoodEntriesSince(since).first()
        assertEquals(1, entries.size)
        assertEquals(MoodLabel.FATIGUED.name, entries.first().moodLabel)
    }

    @Test
    fun getMoodLastWeek_excludesEntryFromEightDaysAgo() = runBlocking {
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - 8L * 24 * 60 * 60 * 1000
        dao.insert(moodEntry(MoodLabel.FATIGUED, timestamp = eightDaysAgo))

        val since = now - 7L * 24 * 60 * 60 * 1000
        val entries = dao.getMoodEntriesSince(since).first()
        assertTrue("Expected no entries, got ${entries.size}", entries.isEmpty())
    }

    @Test
    fun getMoodLastWeek_returnsMultipleEntriesInChronologicalOrder() = runBlocking {
        val now = System.currentTimeMillis()
        // Insert newest first, then oldest — DAO should order oldest→newest
        dao.insert(moodEntry(MoodLabel.POSITIVE,  timestamp = now - 1 * 86_400_000))
        dao.insert(moodEntry(MoodLabel.STRESSED,  timestamp = now - 3 * 86_400_000))
        dao.insert(moodEntry(MoodLabel.NEUTRAL,   timestamp = now - 2 * 86_400_000))

        val since = now - 7L * 86_400_000
        val entries = dao.getMoodEntriesSince(since).first()
        assertEquals(3, entries.size)

        // Verify ascending timestamp order
        for (i in 0 until entries.size - 1) {
            assertTrue(entries[i].timestamp <= entries[i + 1].timestamp)
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private fun moodEntry(
        label: MoodLabel,
        confidence: Float = 0.85f,
        timestamp: Long = System.currentTimeMillis()
    ) = MoodEntryEntity(
        id           = 0,
        timestamp    = timestamp,
        moodLabel    = label.name,
        confidence   = confidence,
        avgDwellTime = 100f,
        typingSpeed  = 3.5f
    )
}
