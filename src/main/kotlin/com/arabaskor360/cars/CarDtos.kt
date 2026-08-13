package com.arabaskor360.cars

import java.math.BigDecimal

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
)
