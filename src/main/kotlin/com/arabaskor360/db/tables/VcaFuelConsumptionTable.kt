package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * Read-only mapping of the pre-existing `vca_fuel_consumption` table (owned by
 * araba-skor-360-loader, migration 009_cost_of_ownership.sql). One row per UK VCA fuel-consumption
 * trim matched to a model_variant — deliberately not collapsed to one row per variant, a
 * generation has multiple engine/fuel options with genuinely different consumption.
 *
 * `raw_row` (JSONB) and `fetched_at` are intentionally omitted — unused here, and JSONB mapping
 * would need the exposed-json module.
 */
object VcaFuelConsumptionTable : Table("vca_fuel_consumption") {
    val id = integer("id")
    val modelVariantId = integer("model_variant_id").references(ModelVariantTable.id)
    val vcaManufacturer = text("vca_manufacturer")
    val vcaModel = text("vca_model")
    val vcaDescription = text("vca_description")
    val fuelType = text("fuel_type").nullable()
    val engineCapacityCc = integer("engine_capacity_cc").nullable()
    val testingScheme = text("testing_scheme")
    val metricCombinedL100km = decimal("metric_combined_l_per_100km", 10, 4).nullable()
    val co2GKm = decimal("co2_g_km", 10, 4).nullable()
    val sourceYear = text("source_year")

    override val primaryKey = PrimaryKey(id)
}
