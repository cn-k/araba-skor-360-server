package com.arabaskor360.cars

import com.arabaskor360.common.Lang
import com.arabaskor360.common.t
import com.arabaskor360.db.tables.ModelVariantScoreTable
import com.arabaskor360.db.tables.ModelVariantTable
import com.arabaskor360.db.tables.NcapRatingTable
import com.arabaskor360.db.tables.UserCarReviewTable
import com.arabaskor360.db.tables.VcaFuelConsumptionTable
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal

/**
 * Single-row lookups only — filtering/sorting/pagination for the list endpoint now happens in
 * [CarCache] over already-assembled [CarResponse]s, not here via SQL. This class's job is just
 * "given an id, build one full (minus costOfOwnership) CarResponse" — used both by CarCache's
 * per-car builder and its lazy-fill-on-cache-miss path.
 */
class CarRepository {

    private fun baseJoin(): Join = ModelVariantTable
        .leftJoin(ModelVariantScoreTable, { ModelVariantTable.id }, { ModelVariantScoreTable.modelVariantId })
        .leftJoin(NcapRatingTable, { ModelVariantTable.id }, { NcapRatingTable.modelVariantId })

    fun getCar(id: Int, lang: Lang = Lang.TR): CarResponse? = transaction {
        val row = baseJoin().selectAll()
            .where { ModelVariantTable.id eq id }
            .singleOrNull() ?: return@transaction null

        val communityStats = communityStatsFor(listOf(id))
        val bestFuelConsumption = bestFuelConsumptionFor(listOf(id))
        row.toCarResponse(communityStats, bestFuelConsumption, lang)
    }

    fun variantExists(id: Int): Boolean = transaction {
        ModelVariantTable.selectAll().where { ModelVariantTable.id eq id }.limit(1).any()
    }

    /** Every model_variant id — used by CarCache's hourly sweep to know what to (re)build. */
    fun allVariantIds(): List<Int> = transaction {
        ModelVariantTable.selectAll().map { it[ModelVariantTable.id] }
    }

    private fun communityStatsFor(variantIds: List<Int>): Map<Int, CommunityStats> {
        if (variantIds.isEmpty()) return emptyMap()

        val avgScore = UserCarReviewTable.score.avg(2)
        val countScore = UserCarReviewTable.id.count()
        val avgInterior = UserCarReviewTable.interiorQualityScore.avg(2)
        val countInterior = UserCarReviewTable.interiorQualityScore.count()
        val avgPowertrain = UserCarReviewTable.powertrainHarmonyScore.avg(2)
        val countPowertrain = UserCarReviewTable.powertrainHarmonyScore.count()
        val avgNvh = UserCarReviewTable.nvhScore.avg(2)
        val countNvh = UserCarReviewTable.nvhScore.count()
        val avgRideComfort = UserCarReviewTable.rideComfortScore.avg(2)
        val countRideComfort = UserCarReviewTable.rideComfortScore.count()

        return UserCarReviewTable
            .select(
                UserCarReviewTable.modelVariantId, avgScore, countScore,
                avgInterior, countInterior, avgPowertrain, countPowertrain,
                avgNvh, countNvh, avgRideComfort, countRideComfort,
            )
            .where { UserCarReviewTable.modelVariantId inList variantIds }
            .groupBy(UserCarReviewTable.modelVariantId)
            .associate { row ->
                row[UserCarReviewTable.modelVariantId] to CommunityStats(
                    score = row[avgScore]!!,
                    reviewCount = row[countScore].toInt(),
                    interiorQualityScore = row[avgInterior],
                    interiorQualityCount = row[countInterior].toInt(),
                    powertrainHarmonyScore = row[avgPowertrain],
                    powertrainHarmonyCount = row[countPowertrain].toInt(),
                    nvhScore = row[avgNvh],
                    nvhCount = row[countNvh].toInt(),
                    rideComfortScore = row[avgRideComfort],
                    rideComfortCount = row[countRideComfort].toInt(),
                )
            }
    }

    /** MIN(metric_combined_l_per_100km) per variant — the most fuel-efficient real engine this
     *  generation offers, NOT an average across its (possibly very different) engines. Rows
     *  with no non-null l/100km at all (electric-only variants, or no VCA data) are simply
     *  absent from the result map — callers should treat a missing key as null/unknown. */
    private fun bestFuelConsumptionFor(variantIds: List<Int>): Map<Int, BigDecimal> {
        if (variantIds.isEmpty()) return emptyMap()

        val minL100 = VcaFuelConsumptionTable.metricCombinedL100km.min()
        return VcaFuelConsumptionTable
            .select(VcaFuelConsumptionTable.modelVariantId, minL100)
            .where { VcaFuelConsumptionTable.modelVariantId inList variantIds }
            .groupBy(VcaFuelConsumptionTable.modelVariantId)
            .mapNotNull { row -> row[minL100]?.let { row[VcaFuelConsumptionTable.modelVariantId] to it } }
            .toMap()
    }

    private data class CommunityStats(
        val score: BigDecimal,
        val reviewCount: Int,
        val interiorQualityScore: BigDecimal?,
        val interiorQualityCount: Int,
        val powertrainHarmonyScore: BigDecimal?,
        val powertrainHarmonyCount: Int,
        val nvhScore: BigDecimal?,
        val nvhCount: Int,
        val rideComfortScore: BigDecimal?,
        val rideComfortCount: Int,
    )

    /** `model_variant_score.confidence` is a small, closed enum owned by the external scoring
     *  pipeline (High/Medium/Low) — safe to translate with a fixed lookup, unlike free-text
     *  fields (e.g. ncapRating.notes) that the pipeline could change without notice. Falls back
     *  to the raw value for anything unrecognized rather than dropping it. */
    private fun localizeConfidence(confidence: String?, lang: Lang): String? = when (confidence) {
        "High" -> t(lang, "Yüksek", "High")
        "Medium" -> t(lang, "Orta", "Medium")
        "Low" -> t(lang, "Düşük", "Low")
        else -> confidence
    }

    private fun ResultRow.toCarResponse(
        communityStats: Map<Int, CommunityStats>,
        bestFuelConsumption: Map<Int, BigDecimal>,
        lang: Lang,
    ): CarResponse {
        val variantId = this[ModelVariantTable.id]
        val stats = communityStats[variantId]
        val ncapStars = this.getOrNull(NcapRatingTable.stars)
        return CarResponse(
            id = variantId,
            make = this[ModelVariantTable.make],
            model = this[ModelVariantTable.model],
            generation = this[ModelVariantTable.generation],
            yearStart = this[ModelVariantTable.yearStart],
            yearEnd = this[ModelVariantTable.yearEnd],
            trName = this[ModelVariantTable.trName],
            score = this[ModelVariantScoreTable.score],
            confidence = localizeConfidence(this.getOrNull(ModelVariantScoreTable.confidence), lang),
            sampleSize = this.getOrNull(ModelVariantScoreTable.n),
            nhtsaCovered = this.getOrNull(ModelVariantScoreTable.nhtsaCovered),
            recallCount = this.getOrNull(ModelVariantScoreTable.recallCount),
            communityScore = stats?.score,
            communityReviewCount = stats?.reviewCount ?: 0,
            communityCategoryScores = CommunityCategoryScores(
                interiorQualityScore = stats?.interiorQualityScore,
                interiorQualityCount = stats?.interiorQualityCount ?: 0,
                powertrainHarmonyScore = stats?.powertrainHarmonyScore,
                powertrainHarmonyCount = stats?.powertrainHarmonyCount ?: 0,
                nvhScore = stats?.nvhScore,
                nvhCount = stats?.nvhCount ?: 0,
                rideComfortScore = stats?.rideComfortScore,
                rideComfortCount = stats?.rideComfortCount ?: 0,
            ),
            bestFuelConsumptionL100km = bestFuelConsumption[variantId],
            ncapRating = if (ncapStars == null) {
                null
            } else {
                NcapRating(
                    stars = ncapStars,
                    adultOccupantPct = this.getOrNull(NcapRatingTable.adultOccupantPct),
                    childOccupantPct = this.getOrNull(NcapRatingTable.childOccupantPct),
                    vruPct = this.getOrNull(NcapRatingTable.vruPct),
                    safetyAssistPct = this.getOrNull(NcapRatingTable.safetyAssistPct),
                    testYear = this.getOrNull(NcapRatingTable.testYear)!!,
                    source = this.getOrNull(NcapRatingTable.sourceLabel)!!,
                    notes = this.getOrNull(NcapRatingTable.notes),
                )
            },
        )
    }
}
