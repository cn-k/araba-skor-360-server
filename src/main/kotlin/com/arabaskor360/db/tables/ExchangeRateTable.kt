package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date

/**
 * Read-only mapping of the pre-existing `exchange_rate` table (owned by araba-skor-360-loader).
 * TCMB's (Türkiye Cumhuriyet Merkez Bankası) official daily USD/TRY buying/selling rate, pulled
 * for the same anchor dates as `kasko_deger`'s yearly backfill (2020-08-01 .. 2026-08-01) so a
 * TL value from that table can be converted to USD for the same snapshot — TRY's own
 * devaluation otherwise dominates any nominal-TL "value change" number (see
 * CostOfOwnershipRepository's computeValueHistory).
 *
 * `requested_date` is the date we asked TCMB for; `observed_date` is the date TCMB actually
 * published a rate for (can be a day or two earlier if `requested_date` fell on a weekend/
 * holiday). Joins against `kasko_deger.snapshot_month` use `requested_date`, since that's the
 * date deliberately chosen to line up with each snapshot.
 */
object ExchangeRateTable : Table("exchange_rate") {
    val id = integer("id")
    val currency = text("currency")
    val forexBuying = decimal("forex_buying", 14, 4)
    val forexSelling = decimal("forex_selling", 14, 4)
    val requestedDate = date("requested_date")
    val observedDate = date("observed_date")
    val sourceLabel = text("source")

    override val primaryKey = PrimaryKey(id)
}
