package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * Read-only mapping of the pre-existing `mtv_tariff` table (owned by araba-skor-360-loader,
 * migration 009_cost_of_ownership.sql). Official GİB MTV tariff — 'I' (registered 2018+, split by
 * taşıt değeri band) and 'I/A' (registered before 2018, cc-only) rows.
 */
object MtvTariffTable : Table("mtv_tariff") {
    val id = integer("id")
    val tarife = text("tarife")
    val engineCcMin = integer("engine_cc_min")
    val engineCcMax = integer("engine_cc_max").nullable()
    val valueBandMin = decimal("value_band_min", 18, 2).nullable()
    val valueBandMax = decimal("value_band_max", 18, 2).nullable()
    val age1To3 = decimal("age_1_3", 18, 2)
    val age4To6 = decimal("age_4_6", 18, 2)
    val age7To11 = decimal("age_7_11", 18, 2)
    val age12To15 = decimal("age_12_15", 18, 2)
    val age16Plus = decimal("age_16_plus", 18, 2)
    val effectiveYear = integer("effective_year")
    val sourceLabel = text("source")

    override val primaryKey = PrimaryKey(id)
}
