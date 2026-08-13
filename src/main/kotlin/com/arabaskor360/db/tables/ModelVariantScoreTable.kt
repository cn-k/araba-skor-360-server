package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Read-only mapping of the pre-existing `model_variant_score` table. Not managed by our migrations. */
object ModelVariantScoreTable : Table("model_variant_score") {
    val modelVariantId = integer("model_variant_id")
    val score = decimal("score", 10, 4).nullable()
    val n = integer("n")
    val confidence = text("confidence")
    val sePoints = decimal("se_points", 10, 4).nullable()
    val initPassRate = decimal("init_pass_rate", 10, 4).nullable()
    val finalPassRate = decimal("final_pass_rate", 10, 4).nullable()
    val passDelta = decimal("pass_delta", 10, 4).nullable()
    val dangerousDelta = decimal("dangerous_delta", 10, 4).nullable()
    val mechanicalDelta = decimal("mechanical_delta", 10, 4).nullable()
    val nhtsaCovered = bool("nhtsa_covered")
    val recallCount = integer("recall_count")
    val computedAt = timestampWithTimeZone("computed_at")

    override val primaryKey = PrimaryKey(modelVariantId)
}
