package com.arabaskor360.platform

import com.arabaskor360.common.ApiException
import io.javalin.http.Context

private const val USER_CONTEXT_ATTRIBUTE = "userContext"
private const val AUTH_HEADER_ATTRIBUTE = "authorizationHeader"

/**
 * Verifies the request's Authorization header against user-platform-service and returns the
 * caller's platform context. Result is cached per-request so repeated calls in one handler chain
 * don't re-hit the auth service. Throws [ApiException] (401/503) on any failure — callers in
 * protected routes should let it propagate to the global exception mapper.
 */
fun Context.requireUserContext(client: UserPlatformClient): UserContextResponse {
    this.attribute<UserContextResponse>(USER_CONTEXT_ATTRIBUTE)?.let { return it }

    val authHeader = this.header("Authorization")
        ?: throw ApiException(401, "Missing Authorization header")

    val userContext = try {
        client.fetchUserContext(authHeader)
    } catch (e: UnauthorizedException) {
        throw ApiException(401, e.message ?: "Unauthorized")
    } catch (e: PlatformServiceUnavailableException) {
        throw ApiException(503, e.message ?: "Auth service unavailable")
    }

    this.attribute(USER_CONTEXT_ATTRIBUTE, userContext)
    this.attribute(AUTH_HEADER_ATTRIBUTE, authHeader)
    return userContext
}

/** Returns the raw Authorization header — only valid after [requireUserContext] has been called. */
fun Context.authorizationHeaderOrThrow(): String =
    this.attribute<String>(AUTH_HEADER_ATTRIBUTE) ?: throw ApiException(401, "Missing Authorization header")
