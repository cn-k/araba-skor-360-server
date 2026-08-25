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
    // totalAnnualTl (real MTV+fuel) plus estimatedKaskoAnnualTl and
    // estimatedTrafficInsuranceAnnualTl folded in — "gerçekten bu arabayı bu motorla bir yıl
    // sahiplenmenin toplam maliyeti" as a range, since the insurance components are estimates.
    // Null whenever any input is missing (no totalAnnualTl, or no kasko vehicle value at all) —
    // never silently drops a real cost component to still show a number.
    val totalWithInsuranceAnnualTl: EstimateRange?,
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
    val tipKodu: Int,
    val make: String,
    val trim: String,
    val modelYear: Int,
    val valueTl: BigDecimal,
    val snapshotMonth: LocalDate,
    val source: String,
)

/**
 * One TSB snapshot's value for a FIXED model year, fixed trim — `year` is the snapshot's own
 * calendar year (when TSB published this value), not the vehicle's model year. `valueTl` is
 * nominal TL, not inflation-adjusted: TRY's own devaluation over these years is usually larger
 * than the car's real depreciation, so it commonly goes UP year over year even though the car
 * itself is getting older and objectively less valuable in real terms.
 *
 * `valueUsd` (= valueTl / that year's TCMB USD buying rate, from `exchange_rate`) strips out
 * TRY-specific devaluation — it's the actual real-world signal for "did this car gain or lose
 * value", not a full inflation adjustment (USD itself inflates too, just far more slowly and
 * predictably than TRY over this period), but far more honest than raw TL. Null only if no
 * exchange_rate row exists for this snapshot's date.
 */
data class ValueHistoryPoint(
    val year: Int,
    val valueTl: BigDecimal,
    val valueUsd: BigDecimal?,
)

/**
 * How a specific trim's TSB value has moved over calendar time, for ONE fixed model year —
 * e.g. "the 2018 model year of this trim, priced as of each TSB snapshot from 2020 to 2026".
 * This is the longitudinal view (same car, aging through real calendar time), as opposed to
 * comparing different model years within one snapshot (which conflates aging with generation
 * changes and was dropped from this API after review — see git history for
 * `DepreciationTrend`/`depreciationTrends`).
 *
 * Matched on exact (marka_adi, tip_adi, model_year) — all three fixed, only snapshot_month
 * varies. `nominalChangePct`/`nominalChangePerYearPct` are the RAW nominal TL change; see
 * `ValueHistoryPoint` doc comment for why a positive number there does not mean the car got more
 * valuable in real terms. `usdChangePct`/`usdChangePerYearPct` are the same calculation on
 * `valueUsd` instead — THIS is the number that answers "did the car actually gain or lose value",
 * null if any point in the series is missing an exchange rate.
 */
data class ValueHistoryTrend(
    val make: String,
    val trim: String,
    val modelYear: Int,
    val points: List<ValueHistoryPoint>, // sorted by year ascending
    val nominalChangePct: BigDecimal?, // null when fewer than 2 snapshots exist for this trim
    val nominalChangePerYearPct: BigDecimal?,
    val usdChangePct: BigDecimal?,
    val usdChangePerYearPct: BigDecimal?,
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
    // `options`). Feeds estimatedKaskoAnnualTl below when the caller doesn't supply trValueTl
    // (averaged across whatever trims matched) — NOT wired into MTV's value-band lookup, that's
    // a separate decision left for a follow-up.
    val kaskoDegerOptions: List<KaskoDegerOption>,
    // One entry per distinct trim in kaskoDegerOptions — that trim's fixed-model-year TSB value
    // across every ingested snapshot month (2020-08 onward), i.e. how its nominal TL value has
    // moved over calendar time. See ValueHistoryTrend doc comment for the nominal-TL caveat.
    val valueHistory: List<ValueHistoryTrend>,
    // Rough estimates, not real quotes — see the module doc comment on why these can't be
    // computed precisely the way MTV/fuel can. Both are per-response (not per-option), since
    // neither insurance figure depends on which engine/fuel variant the car has. Null only when
    // there's no vehicle value at all to base it on (no trValueTl AND no kaskoDegerOptions match).
    val estimatedKaskoAnnualTl: EstimateRange?,
    val estimatedTrafficInsuranceAnnualTl: EstimateRange,
    val notes: List<String>,
)
