package com.arabaskor360.cost

import java.math.BigDecimal
import java.time.LocalDate

data class MtvInfo(
    val tarife: String,
    val ageBracket: String,
    val annualTl: BigDecimal,
    val effectiveYear: Int,
)

data class FuelCostInfo(
    val pricePerLiterTl: BigDecimal,
    val observedAt: LocalDate,
    val annualCostTl: BigDecimal,
)

data class CostOption(
    val fuelType: String,
    val testingScheme: String,
    val engineCc: Int?,
    val literPer100km: BigDecimal?,
    val fuel: FuelCostInfo?,
    val mtv: MtvInfo?,
    val totalAnnualTl: BigDecimal?,
    val note: String?,
)

data class EstimateRange(
    val minTl: BigDecimal,
    val maxTl: BigDecimal,
)

/**
 * A single trim's TSB resale/insurance value for the requested model year — real, not an
 * estimate. `make`/`trim` are TSB's own strings (e.g. "TOFAS-FIAT", "EGEA SEDAN URBAN PLUS 1.6
 * M.JET 120"), not our own `make`/`trName` — surfaced as-is so a caller can tell exactly which
 * trim a value belongs to when several match.
 */
data class KaskoDegerOption(
    val make: String,
    val trim: String,
    val modelYear: Int,
    val valueTl: BigDecimal,
    val snapshotMonth: LocalDate,
    val source: String,
)

data class CostOfOwnershipResponse(
    val modelVariantId: Int,
    val registrationYear: Int,
    val ageYears: Int,
    val asOfYear: Int,
    val annualKm: Int,
    val options: List<CostOption>,
    // Real TSB values for trims matching this car + registrationYear — may be empty (no match),
    // one, or several (a generation covers many trims, same disclosed-list philosophy as
    // `options`). Purely informational for now: NOT wired into estimatedKaskoAnnualTl or MTV's
    // value-band lookup below, even though it could feed both — that's a bigger behavior change
    // than "show the car's value", left for a follow-up.
    val kaskoDegerOptions: List<KaskoDegerOption>,
    // Rough estimates, not real quotes — see the module doc comment on why these can't be
    // computed precisely the way MTV/fuel can. Both are per-response (not per-option), since
    // neither insurance figure depends on which engine/fuel variant the car has.
    val estimatedKaskoAnnualTl: EstimateRange?,
    val estimatedTrafficInsuranceAnnualTl: EstimateRange,
    val notes: List<String>,
)
