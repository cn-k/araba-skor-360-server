package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date

/**
 * Read-only mapping of the pre-existing `kasko_deger` table (owned by araba-skor-360-loader,
 * migration 011_kasko_deger.sql). TSB's (Türkiye Sigorta Birliği) official monthly TR
 * resale/insurance value list — the same reference insurance companies themselves use for kasko
 * premiums, not a scraped estimate.
 *
 * Unlike `vca_fuel_consumption`, this is NOT matched to `model_variant` at ingest time — it's the
 * whole TSB file landed as-is (every make TSB tracks), so lookups happen at query time via
 * ILIKE substring matching on `marka_adi`/`tip_adi` plus an exact `model_year`. A generation spans
 * many trims with different values, same reasoning as VCA fuel data — never collapsed to one row.
 */
object KaskoDegerTable : Table("kasko_deger") {
    val id = integer("id")
    val markaKodu = integer("marka_kodu")
    val tipKodu = integer("tip_kodu")
    val markaAdi = text("marka_adi")
    val tipAdi = text("tip_adi")
    val modelYear = integer("model_year")
    val valueTl = decimal("value_tl", 14, 2)
    val snapshotMonth = date("snapshot_month")
    val sourceLabel = text("source")

    override val primaryKey = PrimaryKey(id)
}
