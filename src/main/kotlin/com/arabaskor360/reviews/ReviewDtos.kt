package com.arabaskor360.reviews

import java.time.OffsetDateTime

data class ReviewRequest(
    val score: Int,
    val interiorQualityScore: Int? = null,
    val powertrainHarmonyScore: Int? = null,
    val nvhScore: Int? = null,
    val rideComfortScore: Int? = null,
    val comment: String?,
)

data class ReviewResponse(
    val id: Long,
    val modelVariantId: Int,
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val score: Int,
    val interiorQualityScore: Int?,
    val powertrainHarmonyScore: Int?,
    val nvhScore: Int?,
    val rideComfortScore: Int?,
    val comment: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
