package com.arabaskor360.platform

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlatformUser(
    val id: String,
    val firebaseUid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserContextResponse(
    val user: PlatformUser,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val platformSlug: String? = null,
    val profileData: Any? = null,
    val subscription: Any? = null,
    val organizations: List<Any> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UsageResponse(
    val metricKey: String,
    val used: Int,
    val limit: Int? = null,
    val remaining: Int? = null,
    val unlimited: Boolean = false,
    val periodStart: String? = null,
    val periodEnd: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlatformErrorResponse(
    val status: Int? = null,
    val error: String? = null,
    val message: String? = null,
)

/** Thrown when the caller's Authorization header could not be verified by user-platform-service. */
class UnauthorizedException(message: String = "Unauthorized") : RuntimeException(message)

/** Thrown when user-platform-service itself is unreachable or errors unexpectedly. */
class PlatformServiceUnavailableException(message: String) : RuntimeException(message)

/** Thrown when a quota-gated action has exceeded its limit for the period. */
class QuotaExceededException(message: String = "Usage quota exceeded") : RuntimeException(message)
