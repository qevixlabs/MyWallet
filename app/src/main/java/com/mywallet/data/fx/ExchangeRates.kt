package com.mywallet.data.fx

import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.FxRateDao
import com.mywallet.data.db.entity.FxRateEntity
import com.mywallet.data.repo.Clock
import com.mywallet.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/** Result of converting an amount into the display currency. */
data class Converted(
    val amount: Money,
    /** Units of base per 1 unit of the source currency. */
    val rate: Double,
    /** False when no rate was available and 1.0 was assumed. */
    val isExact: Boolean,
)

/**
 * Exchange rates, cached on device.
 *
 * Two free sources, neither needing an API key or an account:
 *  1. `@fawazahmed0/currency-api` over the jsDelivr CDN — 338 currencies,
 *     updated daily, no rate limit;
 *  2. `open.er-api.com` as a fallback.
 *
 * Rates are never fetched on the critical path. Everything reads the cache, and
 * a refresh happens in the background — a money app that shows nothing on a
 * train with no signal is useless.
 */
@Singleton
class ExchangeRateRepository @Inject constructor(
    private val dao: FxRateDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory mirror of the cache, so conversion during typing is instant. */
    @Volatile
    private var cache: Pair<String, Map<String, Double>> = "" to emptyMap()

    /**
     * Rate to convert 1 unit of [from] into [base].
     *
     * Returns null when the pair is unknown — callers must decide what to do
     * rather than silently receiving a wrong 1.0.
     *
     * Only one table is ever fetched — the display currency's — because that is
     * the only conversion the totals need. Every *other* pair has to be worked
     * out from it, and for a long time none of them were: a transfer from a
     * rupee account to a dollar one at the same bank asked for NPR→USD, found
     * no table with USD as its base, and took the 1.0 fallback. रू 1,000 became
     * $1,000, both balances moved by the wrong figure, and the ledger recorded
     * it as fact. So the reverse row and a pivot through the base are tried
     * before giving up.
     */
    suspend fun rate(from: String, base: String): Double? {
        val f = from.uppercase()
        val b = base.uppercase()
        if (f == b) return 1.0
        directRate(f, b)?.let { return it }
        // Neither direction is on file, so go through whichever currency the
        // app does hold a table for: (pivot per f) ÷ (pivot per b).
        val pivot = cache.first.uppercase().takeIf { it.isNotEmpty() && it != f && it != b }
            ?: return null
        val fromInPivot = directRate(f, pivot) ?: return null
        val baseInPivot = directRate(b, pivot)?.takeIf { it != 0.0 } ?: return null
        return fromInPivot / baseInPivot
    }

    /**
     * Units of [base] per 1 unit of [from], from a row stored either way round.
     *
     * The table is written one base at a time, so the row that answers this
     * question may be the one that answers its opposite.
     */
    private suspend fun directRate(from: String, base: String): Double? {
        val cached = cache
        if (cached.first.equals(base, ignoreCase = true)) {
            cached.second[from]?.let { return it }
        }
        dao.rate(base, from)?.rate?.let { return it }
        val inverse = dao.rate(from, base)?.rate ?: return null
        return if (inverse == 0.0) null else 1.0 / inverse
    }

    /** Converts [amount] (given in [from], with [fromMinorUnits] decimals) into [base]. */
    suspend fun convert(
        amountMinor: Long,
        from: String,
        fromMinorUnits: Int,
        base: String,
        baseMinorUnits: Int,
    ): Converted {
        val rate = rate(from, base)
        if (rate == null) {
            return Converted(Money(amountMinor), rate = 1.0, isExact = false)
        }
        // Convert through major units so currencies with different decimal
        // counts (JPY has none, NPR has two) do not shift by a factor of 100.
        val major = amountMinor.toDouble() / pow10(fromMinorUnits)
        val converted = major * rate
        return Converted(
            amount = Money((converted * pow10(baseMinorUnits)).roundToLong()),
            rate = rate,
            isExact = true,
        )
    }

    /** True when the cache for [base] is missing or older than a day. */
    suspend fun isStale(base: String): Boolean {
        val last = dao.lastFetchedAt(base) ?: return true
        return clock.nowMillis() - last > TimeUnit.HOURS.toMillis(REFRESH_AFTER_HOURS)
    }

    suspend fun warmCache(base: String) = withContext(io) {
        val rows = dao.ratesFor(base)
        if (rows.isNotEmpty()) {
            cache = base to rows.associate { it.quoteCode to it.rate }
        }
    }

    /**
     * Fetches fresh rates for [base]. Safe to call often — it is a no-op while
     * the cache is fresh, and a failure leaves the previous cache untouched.
     */
    suspend fun refresh(base: String, force: Boolean = false): Result<Unit> = withContext(io) {
        runCatching {
            if (!force && !isStale(base)) {
                warmCache(base)
                return@runCatching
            }
            val rates = fetchFromPrimary(base) ?: fetchFromFallback(base)
            ?: throw IOException("Could not reach any exchange rate source")

            val now = clock.nowMillis()
            dao.upsertAll(
                rates.map { (quote, value) -> FxRateEntity(base, quote, value, now) }
            )
            cache = base to rates
        }
    }

    /**
     * jsDelivr mirror of `@fawazahmed0/currency-api`. Returns rates keyed by
     * uppercase currency code: how many units of the quote currency one unit of
     * [base] buys.
     */
    private fun fetchFromPrimary(base: String): Map<String, Double>? = runCatching {
        val code = base.lowercase()
        val body = httpGet(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$code.json"
        )
        val root = json.parseToJsonElement(body).jsonObject
        val table = root[code]?.jsonObject ?: return@runCatching null
        // The feed gives base -> quote; the app needs quote -> base, so invert.
        table.toRateMap().mapNotNull { (quote, perBase) ->
            if (perBase == 0.0) null else quote to 1.0 / perBase
        }.toMap()
    }.getOrNull()

    private fun fetchFromFallback(base: String): Map<String, Double>? = runCatching {
        val body = httpGet("https://open.er-api.com/v6/latest/${base.uppercase()}")
        val root = json.parseToJsonElement(body).jsonObject
        if (root["result"]?.jsonPrimitive?.content != "success") return@runCatching null
        val table = root["rates"]?.jsonObject ?: return@runCatching null
        table.toRateMap().mapNotNull { (quote, perBase) ->
            if (perBase == 0.0) null else quote to 1.0 / perBase
        }.toMap()
    }.getOrNull()

    private fun JsonObject.toRateMap(): Map<String, Double> = entries.mapNotNull { (key, value) ->
        val rate = runCatching { value.jsonPrimitive.doubleOrNull }.getOrNull() ?: return@mapNotNull null
        key.uppercase() to rate
    }.toMap()

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from $url")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun pow10(exponent: Int): Double = POWERS[exponent.coerceIn(0, 4)]

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val REFRESH_AFTER_HOURS = 12L
        val POWERS = doubleArrayOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0)
    }
}
