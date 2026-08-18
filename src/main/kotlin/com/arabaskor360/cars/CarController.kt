package com.arabaskor360.cars

import com.arabaskor360.common.NotFoundException
import com.arabaskor360.cost.CostOfOwnershipRepository
import com.arabaskor360.cost.DEFAULT_ANNUAL_KM
import io.javalin.http.Context

class CarController(
    private val repository: CarRepository = CarRepository(),
    private val costOfOwnershipRepository: CostOfOwnershipRepository = CostOfOwnershipRepository(),
) {

    fun list(ctx: Context) {
        val query = ctx.queryParam("q")
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (ctx.queryParam("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        ctx.json(repository.listCars(query, limit, offset))
    }

    fun get(ctx: Context) {
        val id = ctx.pathParam("id").toIntOrNull() ?: throw NotFoundException("Invalid car id")
        val car = repository.getCar(id) ?: throw NotFoundException("Car $id not found")

        // Cost of ownership needs a real car's registration year/mileage, which we don't have —
        // use sensible defaults (generation start year, 15,000 km/year) so the detail page gets
        // it in one call. Callers who know the buyer's actual car should hit
        // /api/cars/{id}/cost-of-ownership directly with their own registrationYear/annualKm.
        val costOfOwnership = costOfOwnershipRepository.getCostOfOwnership(
            modelVariantId = id,
            make = car.make,
            kaskoTipSearchTerm = car.trName ?: car.model,
            registrationYear = car.yearStart,
            annualKm = DEFAULT_ANNUAL_KM,
            trValueTl = null,
        )

        ctx.json(car.copy(costOfOwnership = costOfOwnership))
    }
}
