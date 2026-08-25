package com.arabaskor360.cars

import com.arabaskor360.cost.CostOfOwnershipResponse
import java.math.BigDecimal

/** `?sortBy` values for `GET /api/cars`. Deliberately excludes cost-of-ownership: that field is
 *  never computed on the list endpoint (needs a real registrationYear/annualKm per car and
 *  several extra DB round-trips — see CarResponse.costOfOwnership doc comment), so there is
 *  nothing cheap to sort by there. `defaultDescending` lets each field pick its own "best first"
 *  direction when `?order` isn't given — higher is better for score/ncapStars/communityScore,
 *  but LOWER l/100km is better, so fuelConsumption defaults to ascending. */
enum class CarSortBy(val apiValue: String, val defaultDescending: Boolean) {
    SCORE("score", true),
    NCAP_STARS("ncapStars", true),
    COMMUNITY_SCORE("communityScore", true),
    FUEL_CONSUMPTION("fuelConsumption", false),
    ;

    companion object {
        fun fromApiValue(value: String): CarSortBy? = entries.find { it.apiValue.equals(value, ignoreCase = true) }
    }
}

data class NcapRating(
    val stars: BigDecimal,
    val adultOccupantPct: BigDecimal?,
    val childOccupantPct: BigDecimal?,
    val vruPct: BigDecimal?,
    val safetyAssistPct: BigDecimal?,
    val testYear: Int,
    val source: String,
    val notes: String?,
)

/**
 * Per-category community averages, mirroring the optional detail categories on a single review
 * ([com.arabaskor360.reviews.ReviewResponse]). Each category has its own count because a category
 * is optional per-review — fewer users may have rated e.g. NVH than filled in the overall
 * `score`, so its average can't be assumed to rest on the same sample size as `communityScore`.
 */
data class CommunityCategoryScores(
    val interiorQualityScore: BigDecimal?,
    val interiorQualityCount: Int,
    val powertrainHarmonyScore: BigDecimal?,
    val powertrainHarmonyCount: Int,
    val nvhScore: BigDecimal?,
    val nvhCount: Int,
    val rideComfortScore: BigDecimal?,
    val rideComfortCount: Int,
)

data class CarResponse(
    val id: Int,
    val make: String,
    val model: String,
    val generation: String?,
    val yearStart: Int,
    val yearEnd: Int,
    val trName: String?,
    val score: BigDecimal?,
    val confidence: String?,
    val sampleSize: Int?,
    val nhtsaCovered: Boolean?,
    val recallCount: Int?,
    val communityScore: BigDecimal?,
    val communityReviewCount: Int,
    val communityCategoryScores: CommunityCategoryScores,
    // MIN(metric_combined_l_per_100km) across this variant's vca_fuel_consumption rows — the
    // most fuel-efficient engine/trim this generation offers, NOT a blended "average engine"
    // (see CostOfOwnershipRepository's engine-grouping fix for why blending different real
    // engines produces a fictional number). Null for electric-only variants (Tesla etc. have
    // no l/100km value in the source data at all, not just a zero) or ones with no VCA rows.
    val bestFuelConsumptionL100km: BigDecimal?,
    // Precomputed, cheap to join — present on both list and detail, unlike costOfOwnership.
    val ncapRating: NcapRating?,
    // Only populated on GET /api/cars/{id} (single-car detail) — left null on the list endpoint
    // so listing cars doesn't multiply the extra cost-of-ownership DB work per row.
    val costOfOwnership: CostOfOwnershipResponse? = null,
)
