package com.arabaskor360.config

import java.io.File

private fun loadDotEnv(path: String = ".env"): Map<String, String> {
    val file = File(path)
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf('=')
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }
}

private val dotEnv = loadDotEnv()

private fun env(key: String, default: String? = null): String {
    return System.getenv(key) ?: dotEnv[key] ?: default
        ?: error("Missing required environment variable: $key")
}

object AppConfig {
    val port: Int = env("PORT", "7070").toInt()
    val databaseUrl: String = env("DATABASE_URL")
    val userPlatformServiceUrl: String = env("USER_PLATFORM_SERVICE_URL", "http://localhost:8089")
    val platformSlug: String = env("PLATFORM_SLUG", "araba-skor")
    val adminSecret: String? = System.getenv("ADMIN_SECRET") ?: dotEnv["ADMIN_SECRET"]
    val corsAllowedOrigin: String = env("CORS_ALLOWED_ORIGIN", "*")
}
