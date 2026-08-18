package com.arabaskor360.cost

import com.arabaskor360.cars.CarRepository
import com.arabaskor360.common.BadRequestException
import com.arabaskor360.common.NotFoundException
import io.javalin.http.Context

class CostOfOwnershipController(
    private val repository: CostOfOwnershipRepository = CostOfOwnershipRepository(),
    private val carRepository: CarRepository = CarRepository(),
) {

    fun get(ctx: Context) {
        val id = ctx.pathParam("id").toIntOrNull() ?: throw NotFoundException("Invalid car id")
        val car = carRepository.getCar(id) ?: throw NotFoundException("Car $id not found")

        val registrationYear = ctx.queryParam("registrationYear")?.let {
            it.toIntOrNull() ?: throw BadRequestException("registrationYear must be an integer")
        } ?: car.yearStart

        val annualKm = ctx.queryParam("annualKm")?.let {
            it.toIntOrNull() ?: throw BadRequestException("annualKm must be an integer")
        } ?: DEFAULT_ANNUAL_KM
        if (annualKm <= 0) throw BadRequestException("annualKm must be positive")

        val trValueTl = ctx.queryParam("trValueTl")?.let {
            it.toBigDecimalOrNull() ?: throw BadRequestException("trValueTl must be a number")
        }

        ctx.json(
            repository.getCostOfOwnership(
                modelVariantId = id,
                make = car.make,
                kaskoTipSearchTerm = car.trName ?: car.model,
                registrationYear = registrationYear,
                annualKm = annualKm,
                trValueTl = trValueTl,
            ),
        )
    }
}
