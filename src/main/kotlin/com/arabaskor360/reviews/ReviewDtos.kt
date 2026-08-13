package com.arabaskor360.reviews

import java.time.OffsetDateTime

data class ReviewRequest(
    val score: Int,
    val comment: String?,
)

data class ReviewResponse(
    val id: Long,
    val modelVariantId: Int,
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val score: Int,
    val comment: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
