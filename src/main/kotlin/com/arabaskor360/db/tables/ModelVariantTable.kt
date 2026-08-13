package com.arabaskor360.db.tables

import org.jetbrains.exposed.v1.core.Table

/** Read-only mapping of the pre-existing `model_variant` table. Not managed by our migrations. */
object ModelVariantTable : Table("model_variant") {
    val id = integer("id")
    val make = text("make")
    val model = text("model")
    val generation = text("generation").nullable()
    val yearStart = integer("year_start")
    val yearEnd = integer("year_end")
    val trName = text("tr_name").nullable()
    val nhtsaMake = text("nhtsa_make")
    val nhtsaModel = text("nhtsa_model")
    val dvsaMake = text("dvsa_make").nullable()
    val dvsaModel = text("dvsa_model").nullable()

    override val primaryKey = PrimaryKey(id)
}
