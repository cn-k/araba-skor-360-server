package com.arabaskor360.cost

import com.arabaskor360.cars.CarRepository
import com.arabaskor360.common.BadRequestException
import com.arabaskor360.common.NotFoundException
import com.arabaskor360.common.resolveLang
import com.arabaskor360.common.t
import io.javalin.http.Context

class CostOfOwnershipController(
    private val repository: CostOfOwnershipRepository = CostOfOwnershipRepository(),
    private val carRepository: CarRepository = CarRepository(),
) {

    fun get(ctx: Context) {
        val lang = ctx.resolveLang()
        val id = ctx.pathParam("id").toIntOrNull()
            ?: throw NotFoundException(t(lang, "Geçersiz araç id'si", "Invalid car id"))
        val car = carRepository.getCar(id)
            ?: throw NotFoundException(t(lang, "Araç bulunamadı: $id", "Car $id not found"))

        val registrationYear = ctx.queryParam("registrationYear")?.let {
            it.toIntOrNull() ?: throw BadRequestException(
                t(lang, "registrationYear bir tam sayı olmalı", "registrationYear must be an integer"),
            )
        } ?: car.yearStart

        val annualKm = ctx.queryParam("annualKm")?.let {
            it.toIntOrNull() ?: throw BadRequestException(
                t(lang, "annualKm bir tam sayı olmalı", "annualKm must be an integer"),
            )
        } ?: DEFAULT_ANNUAL_KM
        if (annualKm <= 0) {
            throw BadRequestException(t(lang, "annualKm pozitif olmalı", "annualKm must be positive"))
        }

        val trValueTl = ctx.queryParam("trValueTl")?.let {
            it.toBigDecimalOrNull() ?: throw BadRequestException(
                t(lang, "trValueTl bir sayı olmalı", "trValueTl must be a number"),
            )
        }

        ctx.json(
            repository.getCostOfOwnership(
                modelVariantId = id,
                make = car.make,
                kaskoTipSearchTerm = car.trName ?: car.model,
                registrationYear = registrationYear,
                annualKm = annualKm,
                trValueTl = trValueTl,
                lang = lang,
            ),
        )
    }
}
