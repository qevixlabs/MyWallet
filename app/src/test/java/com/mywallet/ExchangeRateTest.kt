package com.mywallet

import com.mywallet.data.db.dao.FxRateDao
import com.mywallet.data.db.entity.FxRateEntity
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.repo.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Every pair the app can be asked about, out of the one table it fetches.
 *
 * Only the display currency's rates are ever downloaded, and for a long time a
 * pair that did not involve it silently came back as 1.0 — which is how a
 * transfer of रू 1,000 landed as $1,000 in the other account and was written
 * down as fact.
 */
class ExchangeRateTest {

    /** Rows exactly as the feed stores them: units of [base] per 1 of [quote]. */
    private class FakeRates(private val rows: List<FxRateEntity>) : FxRateDao {
        override suspend fun ratesFor(base: String) = rows.filter { it.baseCode == base }

        override suspend fun rate(base: String, quote: String) =
            rows.firstOrNull { it.baseCode == base && it.quoteCode == quote }

        override suspend fun lastFetchedAt(base: String): Long? =
            rows.filter { it.baseCode == base }.maxOfOrNull { it.fetchedAt }

        override suspend fun upsertAll(rates: List<FxRateEntity>) = Unit

        override suspend fun clear(base: String) = Unit
    }

    private object FixedClock : Clock {
        override fun today(): LocalDate = LocalDate.of(2026, 7, 30)
        override fun nowMillis(): Long = 0L
    }

    /** The table the app actually holds: everything against NPR. */
    private fun nprTable() = FakeRates(
        listOf(
            FxRateEntity("NPR", "USD", 141.0, 0L),
            FxRateEntity("NPR", "EUR", 153.0, 0L),
        )
    )

    private suspend fun repo(dao: FxRateDao): ExchangeRateRepository =
        ExchangeRateRepository(dao, FixedClock, Dispatchers.Unconfined).also {
            it.warmCache("NPR")
        }

    @Test
    fun `the table's own pairs are read straight off it`() = runTest {
        assertEquals(141.0, repo(nprTable()).rate("USD", "NPR")!!, 1e-9)
    }

    @Test
    fun `the reverse of a pair on file is one over it`() = runTest {
        // NPR to USD is the transfer the user actually made, and there is no
        // table with USD as its base — only the row that answers the opposite.
        val rate = repo(nprTable()).rate("NPR", "USD")!!
        assertEquals(1.0 / 141.0, rate, 1e-12)
        // रू 1,000 is about $7, and emphatically not $1,000.
        assertEquals(7.09, 1000.0 * rate, 0.01)
    }

    @Test
    fun `a pair the table names neither side of goes through the base`() = runTest {
        // USD to EUR: 141 rupees to the dollar, 153 to the euro.
        assertEquals(141.0 / 153.0, repo(nprTable()).rate("USD", "EUR")!!, 1e-12)
    }

    @Test
    fun `a currency with no rate on file is refused rather than assumed`() = runTest {
        // Not 1.0. The caller has to be able to tell "I could not value this"
        // from "these are worth the same".
        assertNull(repo(nprTable()).rate("GBP", "NPR"))
        assertNull(repo(nprTable()).rate("USD", "GBP"))
    }

    @Test
    fun `a currency against itself is one whatever the table holds`() = runTest {
        assertEquals(1.0, repo(FakeRates(emptyList())).rate("npr", "NPR")!!, 1e-12)
    }
}
