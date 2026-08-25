package com.arabaskor360.cars

import com.arabaskor360.common.BadRequestException
import com.arabaskor360.common.NotFoundException
import com.arabaskor360.common.resolveLang
import com.arabaskor360.common.t
import io.javalin.http.Context

class CarController(
    private val carCache: CarCache,
) {

    fun list(ctx: Context) {
        val lang = ctx.resolveLang()
        val query = ctx.queryParam("q")
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (ctx.queryParam("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val sortBy = ctx.queryParam("sortBy")?.let {
            CarSortBy.fromApiValue(it) ?: throw BadRequestException(
                t(
                    lang,
                    "sortBy şunlardan biri olmalı: ${CarSortBy.entries.joinToString { s -> s.apiValue }}",
                    "sortBy must be one of: ${CarSortBy.entries.joinToString { s -> s.apiValue }}",
                ),
            )
        }
        val descending = when (ctx.queryParam("order")?.lowercase()) {
            "asc" -> false
            "desc" -> true
            else -> sortBy?.defaultDescending ?: true
        }
        val makes = ctx.queryParam("make")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
        val minYear = ctx.queryParam("minYear")?.let {
            it.toIntOrNull() ?: throw BadRequestException(t(lang, "minYear bir tam sayı olmalı", "minYear must be an integer"))
        }
        val maxYear = ctx.queryParam("maxYear")?.let {
            it.toIntOrNull() ?: throw BadRequestException(t(lang, "maxYear bir tam sayı olmalı", "maxYear must be an integer"))
        }
        if (minYear != null && maxYear != null && minYear > maxYear) {
            throw BadRequestException(t(lang, "minYear, maxYear'dan büyük olamaz", "minYear cannot be greater than maxYear"))
        }
        ctx.json(carCache.list(query, makes, minYear, maxYear, sortBy, descending, limit, offset, lang))
    }

    fun get(ctx: Context) {
        val lang = ctx.resolveLang()
        val id = ctx.pathParam("id").toIntOrNull()
            ?: throw NotFoundException(t(lang, "Geçersiz araç id'si", "Invalid car id"))
        val car = carCache.get(id, lang)
            ?: throw NotFoundException(t(lang, "Araç bulunamadı: $id", "Car $id not found"))
        ctx.json(car)
    }
}
