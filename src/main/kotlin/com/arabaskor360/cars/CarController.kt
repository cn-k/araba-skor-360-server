package com.arabaskor360.cars

import com.arabaskor360.common.NotFoundException
import io.javalin.http.Context

class CarController(private val repository: CarRepository = CarRepository()) {

    fun list(ctx: Context) {
        val query = ctx.queryParam("q")
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (ctx.queryParam("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        ctx.json(repository.listCars(query, limit, offset))
    }

    fun get(ctx: Context) {
        val id = ctx.pathParam("id").toIntOrNull() ?: throw NotFoundException("Invalid car id")
        val car = repository.getCar(id) ?: throw NotFoundException("Car $id not found")
        ctx.json(car)
    }
}
