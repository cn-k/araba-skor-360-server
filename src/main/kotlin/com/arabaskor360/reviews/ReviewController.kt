package com.arabaskor360.reviews

import com.arabaskor360.cars.CarCache
import com.arabaskor360.cars.CarRepository
import com.arabaskor360.common.ApiException
import com.arabaskor360.common.BadRequestException
import com.arabaskor360.common.Lang
import com.arabaskor360.common.NotFoundException
import com.arabaskor360.common.resolveLang
import com.arabaskor360.common.t
import com.arabaskor360.cost.CostOfOwnershipRepository
import com.arabaskor360.platform.QuotaExceededException
import com.arabaskor360.platform.UserPlatformClient
import com.arabaskor360.platform.authorizationHeaderOrThrow
import com.arabaskor360.platform.requireUserContext
import io.javalin.http.Context
import io.javalin.http.bodyAsClass

private const val REVIEW_USAGE_METRIC = "daily_reviews"

class ReviewController(
    private val repository: ReviewRepository = ReviewRepository(),
    private val carRepository: CarRepository = CarRepository(),
    private val platformClient: UserPlatformClient = UserPlatformClient(),
    private val carCache: CarCache = CarCache(carRepository, CostOfOwnershipRepository()),
) {

    fun list(ctx: Context) {
        val variantId = requireVariantId(ctx)
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (ctx.queryParam("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        ctx.json(repository.listForCar(variantId, limit, offset))
    }

    fun getMine(ctx: Context) {
        val lang = ctx.resolveLang()
        val variantId = requireVariantId(ctx)
        val userContext = ctx.requireUserContext(platformClient, lang)
        val review = repository.findForUser(variantId, userContext.user.id)
            ?: throw NotFoundException(
                t(lang, "Bu kullanıcının $variantId için yorumu yok", "No review from this user for car $variantId"),
            )
        ctx.json(review)
    }

    fun upsert(ctx: Context) {
        val lang = ctx.resolveLang()
        val variantId = requireVariantId(ctx)
        val userContext = ctx.requireUserContext(platformClient, lang)

        val body = try {
            ctx.bodyAsClass<ReviewRequest>()
        } catch (e: Exception) {
            throw BadRequestException(t(lang, "Geçersiz istek gövdesi: ${e.message}", "Invalid request body: ${e.message}"))
        }
        requireInRange(lang, "score", body.score)
        body.interiorQualityScore?.let { requireInRange(lang, "interiorQualityScore", it) }
        body.powertrainHarmonyScore?.let { requireInRange(lang, "powertrainHarmonyScore", it) }
        body.nvhScore?.let { requireInRange(lang, "nvhScore", it) }
        body.rideComfortScore?.let { requireInRange(lang, "rideComfortScore", it) }

        try {
            platformClient.tryConsumeUsage(ctx.authorizationHeaderOrThrow(lang), REVIEW_USAGE_METRIC)
        } catch (e: QuotaExceededException) {
            throw ApiException(429, t(lang, "Kullanım kotası aşıldı", "Usage quota exceeded"))
        }

        val review = repository.upsert(
            modelVariantId = variantId,
            userId = userContext.user.id,
            firebaseUid = userContext.user.firebaseUid ?: userContext.user.id,
            displayName = userContext.user.displayName,
            avatarUrl = userContext.user.avatarUrl,
            score = body.score,
            interiorQualityScore = body.interiorQualityScore,
            powertrainHarmonyScore = body.powertrainHarmonyScore,
            nvhScore = body.nvhScore,
            rideComfortScore = body.rideComfortScore,
            comment = body.comment?.trim()?.ifBlank { null },
        )
        carCache.patchCommunityStats(variantId)
        ctx.json(review)
    }

    fun deleteMine(ctx: Context) {
        val lang = ctx.resolveLang()
        val variantId = requireVariantId(ctx)
        val userContext = ctx.requireUserContext(platformClient, lang)
        val deleted = repository.delete(variantId, userContext.user.id)
        if (!deleted) {
            throw NotFoundException(
                t(lang, "Bu kullanıcının $variantId için yorumu yok", "No review from this user for car $variantId"),
            )
        }
        carCache.patchCommunityStats(variantId)
        ctx.status(204)
    }

    private fun requireVariantId(ctx: Context): Int {
        val lang = ctx.resolveLang()
        val id = ctx.pathParam("id").toIntOrNull()
            ?: throw NotFoundException(t(lang, "Geçersiz araç id'si", "Invalid car id"))
        if (!carRepository.variantExists(id)) {
            throw NotFoundException(t(lang, "Araç bulunamadı: $id", "Car $id not found"))
        }
        return id
    }

    private fun requireInRange(lang: Lang, fieldName: String, value: Int) {
        if (value < 1 || value > 100) {
            throw BadRequestException(
                t(lang, "$fieldName 1 ile 100 arasında olmalı", "$fieldName must be between 1 and 100"),
            )
        }
    }
}
