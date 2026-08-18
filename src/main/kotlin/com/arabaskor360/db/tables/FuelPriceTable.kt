package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date

/**
 * Read-only mapping of the pre-existing `fuel_price` table (owned by araba-skor-360-loader,
 * migration 009_cost_of_ownership.sql). Small, manually-refreshed snapshot table — each refresh
 * inserts a new row rather than updating in place, so "latest" means highest `id`, not latest
 * `observed_at` (upstream sources can lag their own "as of" date by a few days).
 */
object FuelPriceTable : Table("fuel_price") {
    val id = integer("id")
    val fuelType = text("fuel_type")
    val pricePerLiterTl = decimal("price_per_liter_tl", 10, 4).nullable()
    val pricePerKwhTl = decimal("price_per_kwh_tl", 10, 4).nullable()
    val region = text("region")
    val observedAt = date("observed_at")
    val sourceLabel = text("source")

    override val primaryKey = PrimaryKey(id)
}
