package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object UserCarReviewTable : Table("user_car_review") {
    val id = long("id").autoIncrement()
    val modelVariantId = integer("model_variant_id").references(ModelVariantTable.id)
    val userId = text("user_id")
    val firebaseUid = text("firebase_uid")
    val displayName = text("display_name").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val score = integer("score") // genel memnuniyet (overall satisfaction) — the one required rating
    val interiorQualityScore = integer("interior_quality_score").nullable()
    val powertrainHarmonyScore = integer("powertrain_harmony_score").nullable()
    val nvhScore = integer("nvh_score").nullable() // ses yalıtımı: rüzgar/yol/motor sesi tek eksende
    val rideComfortScore = integer("ride_comfort_score").nullable()
    val comment = text("comment").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
