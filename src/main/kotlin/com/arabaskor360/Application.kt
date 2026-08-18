package com.arabaskor360

import com.arabaskor360.cars.CarController
import com.arabaskor360.common.ApiException
import com.arabaskor360.common.BadRequestException
import com.arabaskor360.common.NotFoundException
import com.arabaskor360.common.sharedObjectMapper
import com.arabaskor360.config.AppConfig
import com.arabaskor360.cost.CostOfOwnershipController
import com.arabaskor360.cost.CostOfOwnershipRepository
import com.arabaskor360.db.AppDatabase
import com.arabaskor360.reviews.ReviewController
import io.javalin.Javalin
import io.javalin.json.JavalinJackson
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import io.javalin.plugin.bundled.CorsPlugin
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

private const val OPENAPI_PATH = "/openapi.yaml"

private fun loadOpenApiSpec(): String =
    object {}.javaClass.getResourceAsStream("/openapi.yaml")
        ?.bufferedReader()
        ?.readText()
        ?: error("openapi.yaml not found on classpath")

fun main() {
    AppDatabase.init()

    // Shared across both controllers so there's a single FuelPriceCache instance (and thus a
    // single daily external fuel-price fetch) for the whole process, not one per controller.
    val costOfOwnershipRepository = CostOfOwnershipRepository()
    val carController = CarController(costOfOwnershipRepository = costOfOwnershipRepository)
    val reviewController = ReviewController()
    val costOfOwnershipController = CostOfOwnershipController(repository = costOfOwnershipRepository)
    val openApiSpec = loadOpenApiSpec()

    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(sharedObjectMapper, false))
        config.registerPlugin(
            CorsPlugin { cors ->
                cors.addRule { rule ->
                    if (AppConfig.corsAllowedOrigin == "*") {
                        rule.anyHost()
                    } else {
                        rule.allowHost(AppConfig.corsAllowedOrigin)
                    }
                }
            },
        )
        config.registerPlugin(
            SwaggerPlugin { swagger ->
                // We hand-write openapi.yaml rather than generating it via OpenApiPlugin's
                // annotation processor, so point Swagger UI at it as a "custom version"
                // instead of the (KSP-generated) default documentationPath mechanism.
                swagger.injectCustomVersion("v1", OPENAPI_PATH)
                swagger.uiPath = "/docs"
                swagger.title = "Araba Skor 360 API"
            },
        )
    }

    app.get("/health") { ctx -> ctx.json(mapOf("status" to "ok")) }
    app.get(OPENAPI_PATH) { ctx -> ctx.contentType("application/yaml; charset=utf-8").result(openApiSpec) }

    app.get("/api/cars", carController::list)
    app.get("/api/cars/{id}", carController::get)
    app.get("/api/cars/{id}/cost-of-ownership", costOfOwnershipController::get)
    app.get("/api/cars/{id}/reviews", reviewController::list)
    app.post("/api/cars/{id}/reviews", reviewController::upsert)
    app.get("/api/cars/{id}/reviews/me", reviewController::getMine)
    app.delete("/api/cars/{id}/reviews/me", reviewController::deleteMine)

    app.exception(ApiException::class.java) { e, ctx ->
        ctx.status(e.status).json(mapOf("error" to e.message))
    }
    app.exception(NotFoundException::class.java) { e, ctx ->
        ctx.status(404).json(mapOf("error" to e.message))
    }
    app.exception(BadRequestException::class.java) { e, ctx ->
        ctx.status(400).json(mapOf("error" to e.message))
    }
    app.exception(Exception::class.java) { e, ctx ->
        log.error("Unhandled exception on ${ctx.method()} ${ctx.path()}", e)
        ctx.status(500).json(mapOf("error" to "Internal server error"))
    }

    app.start(AppConfig.port)
}
