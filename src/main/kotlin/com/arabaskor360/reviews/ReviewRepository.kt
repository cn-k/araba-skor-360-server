package com.arabaskor360.reviews

import com.arabaskor360.db.tables.UserCarReviewTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

class ReviewRepository {

    fun listForCar(modelVariantId: Int, limit: Int, offset: Int): List<ReviewResponse> = transaction {
        UserCarReviewTable.selectAll()
            .where { UserCarReviewTable.modelVariantId eq modelVariantId }
            .orderBy(UserCarReviewTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toReviewResponse() }
    }

    fun findForUser(modelVariantId: Int, userId: String): ReviewResponse? = transaction {
        UserCarReviewTable.selectAll()
            .where { (UserCarReviewTable.modelVariantId eq modelVariantId) and (UserCarReviewTable.userId eq userId) }
            .singleOrNull()
            ?.toReviewResponse()
    }

    fun upsert(
        modelVariantId: Int,
        userId: String,
        firebaseUid: String,
        displayName: String?,
        avatarUrl: String?,
        score: Int,
        comment: String?,
    ): ReviewResponse = transaction {
        val now = OffsetDateTime.now()
        val matcher = { (UserCarReviewTable.modelVariantId eq modelVariantId) and (UserCarReviewTable.userId eq userId) }

        val updatedRows = UserCarReviewTable.update(where = { matcher() }) {
            it[UserCarReviewTable.score] = score
            it[UserCarReviewTable.comment] = comment
            it[UserCarReviewTable.displayName] = displayName
            it[UserCarReviewTable.avatarUrl] = avatarUrl
            it[UserCarReviewTable.firebaseUid] = firebaseUid
            it[UserCarReviewTable.updatedAt] = now
        }

        if (updatedRows == 0) {
            UserCarReviewTable.insert {
                it[UserCarReviewTable.modelVariantId] = modelVariantId
                it[UserCarReviewTable.userId] = userId
                it[UserCarReviewTable.firebaseUid] = firebaseUid
                it[UserCarReviewTable.displayName] = displayName
                it[UserCarReviewTable.avatarUrl] = avatarUrl
                it[UserCarReviewTable.score] = score
                it[UserCarReviewTable.comment] = comment
                it[UserCarReviewTable.createdAt] = now
                it[UserCarReviewTable.updatedAt] = now
            }
        }

        UserCarReviewTable.selectAll()
            .where { matcher() }
            .single()
            .toReviewResponse()
    }

    fun delete(modelVariantId: Int, userId: String): Boolean = transaction {
        val deleted = UserCarReviewTable.deleteWhere {
            (UserCarReviewTable.modelVariantId eq modelVariantId) and (UserCarReviewTable.userId eq userId)
        }
        deleted > 0
    }

    private fun ResultRow.toReviewResponse() = ReviewResponse(
        id = this[UserCarReviewTable.id],
        modelVariantId = this[UserCarReviewTable.modelVariantId],
        userId = this[UserCarReviewTable.userId],
        displayName = this[UserCarReviewTable.displayName],
        avatarUrl = this[UserCarReviewTable.avatarUrl],
        score = this[UserCarReviewTable.score],
        comment = this[UserCarReviewTable.comment],
        createdAt = this[UserCarReviewTable.createdAt],
        updatedAt = this[UserCarReviewTable.updatedAt],
    )
}
