package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * Read-only mapping of the pre-existing `ncap_rating` table (owned by araba-skor-360-loader).
 * Unlike cost-of-ownership, this is a precomputed table — one manually-researched Euro NCAP
 * rating per model_variant, not something computed live per request.
 */
object NcapRatingTable : Table("ncap_rating") {
    val modelVariantId = integer("model_variant_id").references(ModelVariantTable.id)
    val stars = decimal("stars", 4, 2)
    val adultOccupantPct = decimal("adult_occupant_pct", 6, 2).nullable()
    val childOccupantPct = decimal("child_occupant_pct", 6, 2).nullable()
    val vruPct = decimal("vru_pct", 6, 2).nullable()
    val safetyAssistPct = decimal("safety_assist_pct", 6, 2).nullable()
    val testYear = integer("test_year")
    val sourceLabel = text("source")
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(modelVariantId)
}
