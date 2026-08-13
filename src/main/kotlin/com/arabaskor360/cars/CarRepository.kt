package com.arabaskor360.cars

import com.arabaskor360.db.tables.ModelVariantScoreTable
import com.arabaskor360.db.tables.ModelVariantTable
import com.arabaskor360.db.tables.UserCarReviewTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal

class CarRepository {

    fun listCars(query: String?, limit: Int, offset: Int): List<CarResponse> = transaction {
        val join = ModelVariantTable.leftJoin(
            ModelVariantScoreTable,
            { ModelVariantTable.id },
            { ModelVariantScoreTable.modelVariantId },
        )

        var stmt = join.selectAll()
        if (!query.isNullOrBlank()) {
            val pattern = "%${query.trim().lowercase()}%"
            stmt = stmt.where {
                (ModelVariantTable.make.lowerCase() like pattern) or
                    (ModelVariantTable.model.lowerCase() like pattern)
            }
        }

        val variantRows = stmt
            .orderBy(ModelVariantTable.make to SortOrder.ASC, ModelVariantTable.model to SortOrder.ASC)
            .limit(limit)
            .offset(offset.toLong())
            .toList()

        val variantIds = variantRows.map { it[ModelVariantTable.id] }
        val communityStats = communityStatsFor(variantIds)

        variantRows.map { row -> row.toCarResponse(communityStats) }
    }

    fun getCar(id: Int): CarResponse? = transaction {
        val join = ModelVariantTable.leftJoin(
            ModelVariantScoreTable,
            { ModelVariantTable.id },
            { ModelVariantScoreTable.modelVariantId },
        )

        val row = join.selectAll()
            .where { ModelVariantTable.id eq id }
            .singleOrNull() ?: return@transaction null

        val communityStats = communityStatsFor(listOf(id))
        row.toCarResponse(communityStats)
    }

    fun variantExists(id: Int): Boolean = transaction {
        ModelVariantTable.selectAll().where { ModelVariantTable.id eq id }.limit(1).any()
    }

    private fun communityStatsFor(variantIds: List<Int>): Map<Int, Pair<BigDecimal, Int>> {
        if (variantIds.isEmpty()) return emptyMap()
        val avgCol = UserCarReviewTable.score.avg(2)
        val countCol = UserCarReviewTable.id.count()
        return UserCarReviewTable
            .select(UserCarReviewTable.modelVariantId, avgCol, countCol)
            .where { UserCarReviewTable.modelVariantId inList variantIds }
            .groupBy(UserCarReviewTable.modelVariantId)
            .associate { row ->
                row[UserCarReviewTable.modelVariantId] to (row[avgCol]!! to row[countCol].toInt())
            }
    }

    private fun ResultRow.toCarResponse(
        communityStats: Map<Int, Pair<BigDecimal, Int>>,
    ): CarResponse {
        val variantId = this[ModelVariantTable.id]
        val stats = communityStats[variantId]
        return CarResponse(
            id = variantId,
            make = this[ModelVariantTable.make],
            model = this[ModelVariantTable.model],
            generation = this[ModelVariantTable.generation],
            yearStart = this[ModelVariantTable.yearStart],
            yearEnd = this[ModelVariantTable.yearEnd],
            trName = this[ModelVariantTable.trName],
            score = this[ModelVariantScoreTable.score],
            confidence = this.getOrNull(ModelVariantScoreTable.confidence),
            sampleSize = this.getOrNull(ModelVariantScoreTable.n),
            nhtsaCovered = this.getOrNull(ModelVariantScoreTable.nhtsaCovered),
            recallCount = this.getOrNull(ModelVariantScoreTable.recallCount),
            communityScore = stats?.first,
            communityReviewCount = stats?.second ?: 0,
        )
    }
}
