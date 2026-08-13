package com.arabaskor360.platform

import com.arabaskor360.common.sharedObjectMapper
import com.arabaskor360.config.AppConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin client for the shared user-platform-service (Firebase auth, roles, usage quotas).
 * This app never verifies Firebase tokens itself — it forwards the caller's Authorization
 * header and trusts the platform service's response.
 */
class UserPlatformClient(
    private val baseUrl: String = AppConfig.userPlatformServiceUrl,
    private val platformSlug: String = AppConfig.platformSlug,
) {
    private val log = LoggerFactory.getLogger(UserPlatformClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Verifies the caller's bearer token and returns their platform context. Throws [UnauthorizedException]
     *  or [PlatformServiceUnavailableException] on failure — callers should treat both as "reject the request". */
    fun fetchUserContext(authorizationHeader: String): UserContextResponse {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/users/me/context/$platformSlug"))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("user-platform-service unreachable while fetching context", e)
            throw PlatformServiceUnavailableException("Auth service unavailable")
        }

        return when (response.statusCode()) {
            200 -> sharedObjectMapper.readValue(response.body(), UserContextResponse::class.java)
            401 -> throw UnauthorizedException("Invalid or expired token")
            403 -> throw UnauthorizedException("Forbidden")
            else -> {
                log.error("Unexpected response from user-platform-service context endpoint: ${response.statusCode()} ${response.body()}")
                throw PlatformServiceUnavailableException("Auth service returned ${response.statusCode()}")
            }
        }
    }

    /** Consumes one unit of the given quota metric for the caller. Returns the resulting usage, or null if
     *  the quota check itself failed for a reason unrelated to the limit (service down, etc — fail-open). */
    fun tryConsumeUsage(authorizationHeader: String, metricKey: String): UsageResponse? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/users/me/platforms/$platformSlug/usage/$metricKey/consume"))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(8))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("user-platform-service unreachable while consuming usage '$metricKey' — allowing request (fail-open)", e)
            return null
        }

        return when (response.statusCode()) {
            200, 201 -> sharedObjectMapper.readValue(response.body(), UsageResponse::class.java)
            429 -> throw QuotaExceededException("Daily quota for '$metricKey' exceeded")
            401, 403 -> throw UnauthorizedException("Invalid or expired token")
            else -> {
                log.warn("Unexpected response from usage-consume endpoint: ${response.statusCode()} ${response.body()} — allowing request (fail-open)")
                null
            }
        }
    }
}
