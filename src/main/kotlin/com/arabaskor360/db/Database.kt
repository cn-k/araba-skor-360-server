package com.arabaskor360.db

import com.arabaskor360.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.net.URI
import javax.sql.DataSource

private data class JdbcCoordinates(val jdbcUrl: String, val username: String, val password: String)

private fun toJdbcCoordinates(databaseUrl: String): JdbcCoordinates {
    if (databaseUrl.startsWith("jdbc:")) {
        error("DATABASE_URL should be a postgresql:// URI, not a jdbc: URL")
    }
    val uri = URI(databaseUrl)
    val userInfo = uri.userInfo?.split(":", limit = 2)
        ?: error("DATABASE_URL is missing user info (user:password@host)")
    val username = userInfo[0]
    val password = userInfo.getOrElse(1) { "" }
    val jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}"
    return JdbcCoordinates(jdbcUrl, username, password)
}

object AppDatabase {
    lateinit var dataSource: DataSource
        private set

    fun init() {
        val coords = toJdbcCoordinates(AppConfig.databaseUrl)
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = coords.jdbcUrl
            username = coords.username
            password = coords.password
            maximumPoolSize = 10
            poolName = "araba-skor-360-pool"
        }
        dataSource = HikariDataSource(hikariConfig)

        // The `public` schema already has pre-existing tables (model_variant, ...) owned by another
        // pipeline, so Flyway sees a "non-empty schema" and refuses to migrate unless baselined.
        // Baselining at version 0 (below our V1) makes it run V1 for real instead of skipping it.
        Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        Database.connect(dataSource)
    }
}
