package com.arabaskor360.cars

import com.arabaskor360.cost.CostOfOwnershipResponse
import java.math.BigDecimal

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
    // Precomputed, cheap to join — present on both list and detail, unlike costOfOwnership.
    val ncapRating: NcapRating?,
    // Only populated on GET /api/cars/{id} (single-car detail) — left null on the list endpoint
    // so listing cars doesn't multiply the extra cost-of-ownership DB work per row.
    val costOfOwnership: CostOfOwnershipResponse? = null,
)
