package com.arabaskor360.cost

import com.arabaskor360.common.sharedObjectMapper
import com.arabaskor360.db.tables.FuelPriceTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private val ISTANBUL = ZoneId.of("Europe/Istanbul")
private const val FUEL_PRICE_API_URL = "https://ucuzyakitbul.com.tr/api/prices/national"
private const val FUEL_PRICE_SOURCE = "ucuzyakitbul.com.tr /api/prices/national (live, Turkey-wide average)"

// API's fuelType string -> our fuel_price.fuel_type (matches the DB CHECK constraint) — same
// mapping as araba-skor-360-loader's fuel_price_ingest.py.
private val API_FUEL_TYPE_MAP = mapOf(
    "Benzin" to "Petrol",
    "Motorin" to "Diesel",
    "LPG" to "LPG",
)

/**
 * In-memory, once-per-TR-calendar-day cache of fuel prices, backed by the `fuel_price` table for
 * durability. Moves the daily refresh (previously a manual `python -m
 * carscore_ingest.fuel_price_ingest` re-run) into the serving layer: the first request after the
 * cached day rolls over triggers a refresh automatically.
 *
 * Refresh order on a stale/cold cache: check the DB for today's price first (another instance, or
 * an earlier request today, may have already fetched it — avoids hitting the external API on
 * every redeploy), and only call the external API if today truly has no price yet. If that call
 * fails, keep serving whatever was cached before rather than failing the request — this is a soft
 * dependency (a slightly stale fuel price), not something worth a 500 over.
 */
class FuelPriceCache {
    private val log = LoggerFactory.getLogger(FuelPriceCache::class.java)
    private val refreshLock = Any()

    @Volatile
    private var cachedDate: LocalDate? = null

    @Volatile
    private var cachedPrices: Map<String, BigDecimal> = emptyMap()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Returns (price, "as of" date) for the given tariff fuel type (Petrol/Diesel/LPG), or null
     *  if no price has ever been obtained for it. */
    fun getPrice(fuelType: String): Pair<BigDecimal, LocalDate>? {
        val today = LocalDate.now(ISTANBUL)
        if (cachedDate != today) {
            refresh(today)
        }
        val price = cachedPrices[fuelType] ?: return null
        return price to (cachedDate ?: today)
    }

    private fun refresh(today: LocalDate) {
        synchronized(refreshLock) {
            // Another thread may have already refreshed while we were waiting for the lock.
            if (cachedDate == today) return

            val fromDb = loadFromDb(today)
            if (fromDb.isNotEmpty()) {
                cachedPrices = fromDb
                cachedDate = today
                return
            }

            val fetched = try {
                fetchFromApi()
            } catch (e: Exception) {
                log.warn("Failed to fetch fuel prices from $FUEL_PRICE_API_URL — keeping last known prices", e)
                null
            }

            if (fetched.isNullOrEmpty()) return // stale cachedPrices/cachedDate left as-is, on purpose

            saveToDb(fetched, today)
            cachedPrices = fetched
            cachedDate = today
        }
    }

    private fun loadFromDb(today: LocalDate): Map<String, BigDecimal> = transaction {
        FuelPriceTable.selectAll()
            .where { FuelPriceTable.observedAt eq today }
            .mapNotNull { row ->
                val price = row[FuelPriceTable.pricePerLiterTl]
                if (price == null) null else row[FuelPriceTable.fuelType] to price
            }
            .toMap()
    }

    private fun fetchFromApi(): Map<String, BigDecimal> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(FUEL_PRICE_API_URL))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Fuel price API returned ${response.statusCode()}")
        }

        val prices = sharedObjectMapper.readTree(response.body())["prices"]
            ?: error("Fuel price API response missing 'prices'")

        val result = mutableMapOf<String, BigDecimal>()
        prices.forEach { entry ->
            val apiFuelType = entry["fuelType"]?.asText() ?: return@forEach
            val ourFuelType = API_FUEL_TYPE_MAP[apiFuelType] ?: return@forEach
            val price = entry["price"]?.decimalValue() ?: return@forEach
            result[ourFuelType] = price
        }
        return result
    }

    private fun saveToDb(prices: Map<String, BigDecimal>, observedAt: LocalDate) = transaction {
        prices.forEach { (fuelType, price) ->
            FuelPriceTable.insert {
                it[FuelPriceTable.fuelType] = fuelType
                it[FuelPriceTable.pricePerLiterTl] = price
                it[FuelPriceTable.pricePerKwhTl] = null
                it[FuelPriceTable.region] = "Turkey (national average)"
                it[FuelPriceTable.observedAt] = observedAt
                it[FuelPriceTable.sourceLabel] = FUEL_PRICE_SOURCE
            }
        }
    }
}
