package com.arabaskor360.cost

import com.arabaskor360.common.Lang
import com.arabaskor360.common.t
import com.arabaskor360.db.tables.ExchangeRateTable
import com.arabaskor360.db.tables.KaskoDegerTable
import com.arabaskor360.db.tables.MtvTariffTable
import com.arabaskor360.db.tables.VcaFuelConsumptionTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate

const val DEFAULT_ANNUAL_KM = 15_000

// Kasko (comprehensive insurance) has no public price API — premiums are driver-specific
// (age, no-claims discount, city, coverage). The one commonly-cited rule of thumb is that the
// annual premium runs roughly 2-5% of the car's insured ("kasko") value. We don't have a per-car
// kasko value in our own data (that's a whole separate TSB reference list, not yet ingested), so
// this reuses the caller-supplied `trValueTl` (already an MTV value-band input) as a stand-in —
// if the caller doesn't supply it, we simply can't estimate kasko at all rather than guessing a
// vehicle value from nowhere.
private val KASKO_RATE_MIN = BigDecimal("0.02")
private val KASKO_RATE_MAX = BigDecimal("0.05")

// Zorunlu trafik sigortası (mandatory third-party liability) is SEDDK-regulated with published
// ceiling prices, but they vary by vehicle tariff class (15 classes), city, and the driver's
// hasar basamağı (0-8 damage step — can differ premium by up to 500%), none of which this app
// tracks per car or per user. This is one flat, generic passenger-car range (not model_variant-
// specific) covering the commonly-cited 2026 low-risk-to-new-driver bracket, not a real quote.
private val TRAFFIC_INSURANCE_MIN_TL = BigDecimal("8500")
private val TRAFFIC_INSURANCE_MAX_TL = BigDecimal("16000")

// computeValueHistory's fixed yearly anchor — matches the month araba-skor-360-loader's
// historical kasko_deger backfill used (2020-08-01 .. 2026-08-01). Any snapshot ingested in a
// different month (e.g. a future freshness re-ingest) is ignored by that function so the
// longitudinal trend keeps an even 12-month cadence between points.
private const val VALUE_HISTORY_ANCHOR_MONTH = 8

/**
 * Kotlin port of araba-skor-360-loader's `cost_of_ownership.py` (compute_mtv,
 * get_latest_fuel_price, compute_fuel_cost_per_year) — see that module's docstring for why this
 * is computed live per-request rather than precomputed like model_variant_score: MTV and fuel
 * cost depend on a specific real car's registration year and annual mileage, not on a generation.
 */
class CostOfOwnershipRepository(
    private val fuelPriceCache: FuelPriceCache = FuelPriceCache(),
) {

    private val vcaFuelTypeToTariffFuelType = mapOf(
        "Petrol" to "Petrol",
        "Diesel" to "Diesel",
        "Petrol Electric" to "Petrol", // mild hybrid — still fills up on petrol
        "Petrol / LPG" to "LPG", // bi-fuel — LPG is the cheaper running cost
    )

    fun getCostOfOwnership(
        modelVariantId: Int,
        make: String,
        kaskoTipSearchTerm: String,
        registrationYear: Int,
        annualKm: Int,
        trValueTl: BigDecimal?,
        lang: Lang = Lang.TR,
    ): CostOfOwnershipResponse = transaction {
        val asOfYear = LocalDate.now().year
        val ageYears = asOfYear - registrationYear
        val notes = mutableListOf<String>()

        val fuelRows = VcaFuelConsumptionTable.selectAll()
            .where { VcaFuelConsumptionTable.modelVariantId eq modelVariantId }
            .toList()

        // Grouped by (fuelType, testingScheme, engineCapacityCc) — NOT just the first two. A
        // single fuel type commonly spans multiple genuinely different engines (e.g. Kadjar
        // "Petrol" covers both a 1197cc TCe 130 and a 1618cc TCe 130 — verified live: averaging
        // them produced a fictional "1337cc" that fell into a THIRD MTV cc band, showing 4,354₺
        // when the real engines pay 2,238₺ and 8,145₺ respectively, nearly 2x off in both
        // directions). Rows that share the same real engine (e.g. different wheel sizes) still
        // get averaged together inside buildOption — that's a legitimate average of the same
        // physical engine, not a blend of different ones.
        val options = fuelRows
            .groupBy { row ->
                Triple(
                    row[VcaFuelConsumptionTable.fuelType] ?: "Unknown",
                    row[VcaFuelConsumptionTable.testingScheme],
                    row[VcaFuelConsumptionTable.engineCapacityCc],
                )
            }
            .map { (key, rows) -> buildOption(key.first, key.second, rows, registrationYear, asOfYear, annualKm, trValueTl, notes, lang) }
            .sortedWith(compareBy({ it.fuelType }, { it.testingScheme }, { it.engineCc }))

        if (options.isEmpty()) {
            notes += t(lang, "Bu araç için VCA yakıt tüketimi verisi bulunamadı.", "No VCA fuel consumption data found for this car.")
        }

        val kaskoDegerOptions = lookupKaskoDeger(make, kaskoTipSearchTerm, registrationYear)
        if (kaskoDegerOptions.isEmpty()) {
            notes += t(
                lang,
                "'$make $kaskoTipSearchTerm' ($registrationYear model yılı) için TSB kasko değer eşleşmesi bulunamadı.",
                "No TSB kasko value match found for '$make $kaskoTipSearchTerm' (model year $registrationYear).",
            )
        }
        val valueHistory = computeValueHistory(kaskoDegerOptions)
        if (kaskoDegerOptions.isNotEmpty() && valueHistory.isEmpty()) {
            notes += t(
                lang,
                "Eşleşen TSB trim(ler)i için sadece tek bir anlık görüntü mevcut, değer geçmişi hesaplanamadı.",
                "Only a single snapshot exists for the matched TSB trim(s), value history could not be computed.",
            )
        }

        // Prefer a caller-supplied trValueTl (they know their real car); otherwise fall back to
        // the average of the matched TSB trims — real data, not a guess, just averaged across
        // whichever trims matched (same reasoning as averaging VCA fuel/cc across trim matches).
        val (vehicleValueForKasko, vehicleValueSource) = when {
            trValueTl != null -> trValueTl to t(lang, "verilen trValueTl", "the supplied trValueTl")
            kaskoDegerOptions.isNotEmpty() -> {
                val avg = kaskoDegerOptions
                    .fold(BigDecimal.ZERO) { acc, o -> acc + o.valueTl }
                    .divide(BigDecimal(kaskoDegerOptions.size), 2, java.math.RoundingMode.HALF_UP)
                avg to t(
                    lang,
                    "TSB kasko değeri ortalaması (${kaskoDegerOptions.size} eşleşen trim)",
                    "average TSB kasko value (${kaskoDegerOptions.size} matched trims)",
                )
            }
            else -> null to null
        }

        val estimatedKasko = if (vehicleValueForKasko == null) {
            notes += t(
                lang,
                "Kasko tahmini için araç değeri bulunamadı (ne trValueTl verildi, ne de TSB eşleşmesi var).",
                "No vehicle value available for the kasko estimate (neither trValueTl was supplied nor a TSB match found).",
            )
            null
        } else {
            notes += t(
                lang,
                "Kasko tahmini gerçek bir teklif değildir — araç değerinin ($vehicleValueSource) %2-5'i arası kaba bir aralık.",
                "Kasko estimate is NOT a real quote — a rough 2-5% range of the vehicle value ($vehicleValueSource).",
            )
            EstimateRange(
                minTl = (vehicleValueForKasko * KASKO_RATE_MIN).setScale(2, java.math.RoundingMode.HALF_UP),
                maxTl = (vehicleValueForKasko * KASKO_RATE_MAX).setScale(2, java.math.RoundingMode.HALF_UP),
            )
        }

        notes += t(
            lang,
            "Trafik sigortası tahmini bu araca özel değildir — hasar basamağı, il ve araç sınıfına göre gerçek fiyat bu aralığın dışına da çıkabilir.",
            "Traffic insurance estimate is not specific to this vehicle — the real price can fall outside this range depending on damage step, city, and vehicle class.",
        )
        val estimatedTrafficInsurance = EstimateRange(
            minTl = TRAFFIC_INSURANCE_MIN_TL,
            maxTl = TRAFFIC_INSURANCE_MAX_TL,
        )

        CostOfOwnershipResponse(
            modelVariantId = modelVariantId,
            registrationYear = registrationYear,
            ageYears = ageYears,
            asOfYear = asOfYear,
            annualKm = annualKm,
            options = options,
            kaskoDegerOptions = kaskoDegerOptions,
            valueHistory = valueHistory,
            estimatedKaskoAnnualTl = estimatedKasko,
            estimatedTrafficInsuranceAnnualTl = estimatedTrafficInsurance,
            notes = notes.distinct(),
        )
    }

    private fun buildOption(
        fuelType: String,
        testingScheme: String,
        rows: List<ResultRow>,
        registrationYear: Int,
        asOfYear: Int,
        annualKm: Int,
        trValueTl: BigDecimal?,
        notes: MutableList<String>,
        lang: Lang,
    ): CostOption {
        val ccValues = rows.mapNotNull { it[VcaFuelConsumptionTable.engineCapacityCc] }
        val avgCc = if (ccValues.isNotEmpty()) ccValues.sum() / ccValues.size else null

        val l100Values = rows.mapNotNull { it[VcaFuelConsumptionTable.metricCombinedL100km] }
        val avgL100 = if (l100Values.isNotEmpty()) {
            l100Values.fold(BigDecimal.ZERO) { acc, v -> acc + v }.divide(BigDecimal(l100Values.size), 4, java.math.RoundingMode.HALF_UP)
        } else {
            null
        }

        val isElectric = fuelType.contains("Electricity", ignoreCase = true)

        val mtv = when {
            isElectric -> {
                notes += t(
                    lang,
                    "Elektrikli araçlar için MTV, ayrı kW bazlı bir tarifeye tabi — henüz transcribe edilmedi.",
                    "Electric vehicles are taxed under a separate kW-based MTV schedule — not yet transcribed.",
                )
                null
            }
            avgCc == null -> null
            else -> computeMtv(avgCc, registrationYear, asOfYear, trValueTl).also {
                if (it == null) {
                    notes += t(
                        lang,
                        "$fuelType ($testingScheme, ${avgCc}cc) için uyan MTV tarife satırı bulunamadı.",
                        "No matching MTV tariff row found for $fuelType ($testingScheme, ${avgCc}cc).",
                    )
                }
            }
        }

        val tariffFuelType = vcaFuelTypeToTariffFuelType[fuelType]
        val fuel = when {
            isElectric -> {
                notes += t(
                    lang,
                    "Elektrikli araçlar için yakıt (şarj) maliyeti henüz hesaplanmıyor.",
                    "Fuel (charging) cost is not yet computed for electric vehicles.",
                )
                null
            }
            avgL100 == null -> null
            tariffFuelType == null -> {
                notes += t(
                    lang,
                    "'$fuelType' yakıt tipi için maliyet eşlemesi tanımlı değil.",
                    "No cost mapping defined for fuel type '$fuelType'.",
                )
                null
            }
            else -> {
                val price = fuelPriceCache.getPrice(tariffFuelType)
                if (price == null) {
                    notes += t(
                        lang,
                        "'$tariffFuelType' için güncel yakıt fiyatı bulunamadı.",
                        "No current fuel price found for '$tariffFuelType'.",
                    )
                    null
                } else {
                    FuelCostInfo(
                        pricePerLiterTl = price.first,
                        observedAt = price.second,
                        annualCostTl = computeFuelCostPerYear(avgL100, annualKm, price.first),
                    )
                }
            }
        }

        val total = if (mtv != null && fuel != null) mtv.annualTl + fuel.annualCostTl else null

        return CostOption(
            fuelType = fuelType,
            testingScheme = testingScheme,
            engineCc = avgCc,
            literPer100km = avgL100,
            fuel = fuel,
            mtv = mtv,
            totalAnnualTl = total,
            note = null,
        )
    }

    /** Kotlin port of cost_of_ownership.py's get_kasko_deger(): ILIKE substring match on make/trim
     *  plus an exact model year, against the latest landed TSB snapshot. Returns every match
     *  (0, 1, or several — a generation covers many trims) rather than picking one, same
     *  disclosed-list philosophy as the VCA-derived `options`. */
    private fun lookupKaskoDeger(make: String, tipSearchTerm: String, modelYear: Int): List<KaskoDegerOption> {
        val maxSnapshot = KaskoDegerTable.snapshotMonth.max()
        val latestMonth = KaskoDegerTable.select(maxSnapshot).singleOrNull()?.get(maxSnapshot)
            ?: return emptyList()

        val makePattern = "%${make.trim().lowercase()}%"
        val tipPattern = "%${tipSearchTerm.trim().lowercase()}%"

        return KaskoDegerTable.selectAll()
            .where {
                (KaskoDegerTable.snapshotMonth eq latestMonth) and
                    (KaskoDegerTable.markaAdi.lowerCase() like makePattern) and
                    (KaskoDegerTable.tipAdi.lowerCase() like tipPattern) and
                    (KaskoDegerTable.modelYear eq modelYear)
            }
            .map { row ->
                KaskoDegerOption(
                    tipKodu = row[KaskoDegerTable.tipKodu],
                    make = row[KaskoDegerTable.markaAdi],
                    trim = row[KaskoDegerTable.tipAdi],
                    modelYear = row[KaskoDegerTable.modelYear],
                    valueTl = row[KaskoDegerTable.valueTl],
                    snapshotMonth = row[KaskoDegerTable.snapshotMonth],
                    source = row[KaskoDegerTable.sourceLabel],
                )
            }
    }

    /** For each distinct trim that matched at registrationYear, pulls that EXACT (marka_adi,
     *  tip_adi, model_year) — all three fixed — across every ingested snapshot_month, i.e. how
     *  this specific model-year car's TSB value moved over real calendar time. This is the
     *  longitudinal view; see ValueHistoryTrend doc comment for the nominal-TL caveat.
     *
     *  Earlier version of this function compared different model_year rows within one snapshot
     *  instead (a cross-sectional proxy) — dropped after review because it conflated real aging
     *  with generation/facelift changes and, worse, was joined on tip_kodu, which TSB reissues
     *  independently per model_year (verified against the live DB: tip_kodu 1087 was a 2012
     *  Megane, a 2016 tractor, and a 2018 Duster — pure coincidence, not the same trim tracked
     *  over time). Now that multiple snapshot_months are ingested (2020-08 onward), the direct
     *  longitudinal question can be answered instead.
     *
     *  Only uses snapshots from ANCHOR_MONTH (the loader's yearly historical backfill month) —
     *  not "every ingested snapshot" or "latest per year". "Current value" freshness
     *  (kaskoDegerOptions/estimatedKaskoAnnualTl, which reads the single latest snapshot overall)
     *  is expected to be re-ingested more often than yearly going forward; if this function
     *  didn't pin to one fixed month, an extra mid-year freshness re-ingest (e.g. a September
     *  snapshot landing alongside that same year's August one) would either double-count that
     *  year or silently shift the trend's spacing away from a clean 12-month cadence. Trades off
     *  "no data until next ANCHOR_MONTH backfill" for guaranteed even spacing — deliberate, see
     *  README.
     *
     *  Also joins `exchange_rate` (TCMB USD buying rate) on the same date so each point carries a
     *  USD-denominated value alongside the nominal TL one — see ValueHistoryPoint doc comment for
     *  why USD is the more honest "did this car actually gain or lose value" signal. */
    private fun computeValueHistory(matchedTrims: List<KaskoDegerOption>): List<ValueHistoryTrend> {
        val usdBuyingRateByDate = ExchangeRateTable.selectAll()
            .where { ExchangeRateTable.currency eq "USD" }
            .associate { it[ExchangeRateTable.requestedDate] to it[ExchangeRateTable.forexBuying] }

        return matchedTrims.distinctBy { Triple(it.make, it.trim, it.modelYear) }.mapNotNull { trim ->
            val rows = KaskoDegerTable.selectAll()
                .where {
                    (KaskoDegerTable.markaAdi eq trim.make) and
                        (KaskoDegerTable.tipAdi eq trim.trim) and
                        (KaskoDegerTable.modelYear eq trim.modelYear)
                }
                .orderBy(KaskoDegerTable.snapshotMonth to SortOrder.ASC)
                .toList()
                .filter { it[KaskoDegerTable.snapshotMonth].monthValue == VALUE_HISTORY_ANCHOR_MONTH }
            if (rows.size < 2) return@mapNotNull null

            val points = rows.map { row ->
                val snapshotMonth = row[KaskoDegerTable.snapshotMonth]
                val valueTl = row[KaskoDegerTable.valueTl]
                val usdRate = usdBuyingRateByDate[snapshotMonth]
                val valueUsd = usdRate?.let { rate -> valueTl.divide(rate, 2, java.math.RoundingMode.HALF_UP) }
                ValueHistoryPoint(snapshotMonth.year, valueTl, valueUsd)
            }
            val oldest = points.first()
            val newest = points.last()

            var totalPct: BigDecimal? = null
            var annualPct: BigDecimal? = null
            if (oldest.valueTl.signum() != 0 && newest.year > oldest.year) {
                totalPct = ((newest.valueTl - oldest.valueTl) * BigDecimal(100))
                    .divide(oldest.valueTl, 4, java.math.RoundingMode.HALF_UP)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                annualPct = totalPct.divide(
                    BigDecimal(newest.year - oldest.year),
                    2,
                    java.math.RoundingMode.HALF_UP,
                )
            }

            var usdTotalPct: BigDecimal? = null
            var usdAnnualPct: BigDecimal? = null
            val oldestUsd = oldest.valueUsd
            val newestUsd = newest.valueUsd
            if (points.all { it.valueUsd != null } && oldestUsd != null && newestUsd != null &&
                oldestUsd.signum() != 0 && newest.year > oldest.year
            ) {
                usdTotalPct = ((newestUsd - oldestUsd) * BigDecimal(100))
                    .divide(oldestUsd, 4, java.math.RoundingMode.HALF_UP)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                usdAnnualPct = usdTotalPct.divide(
                    BigDecimal(newest.year - oldest.year),
                    2,
                    java.math.RoundingMode.HALF_UP,
                )
            }

            ValueHistoryTrend(
                make = trim.make,
                trim = trim.trim,
                modelYear = trim.modelYear,
                points = points,
                nominalChangePct = totalPct,
                nominalChangePerYearPct = annualPct,
                usdChangePct = usdTotalPct,
                usdChangePerYearPct = usdAnnualPct,
            )
        }
    }

    /** Returns null if engine_cc is null — electric vehicles are taxed under a separate, kW-based
     *  MTV schedule not yet transcribed into mtv_tariff; a cc-based guess would be actively wrong. */
    private fun computeMtv(engineCc: Int, registrationYear: Int, asOfYear: Int, trValueTl: BigDecimal?): MtvInfo? {
        val ageYears = asOfYear - registrationYear
        val (ageColumn, ageLabel) = ageColumnAndLabel(ageYears)
        val tarife = if (registrationYear >= 2018) "I" else "I/A"

        val row = (if (tarife == "I/A") {
            MtvTariffTable.selectAll()
                .where {
                    (MtvTariffTable.tarife eq "I/A") and
                        (MtvTariffTable.engineCcMin lessEq engineCc) and
                        (MtvTariffTable.engineCcMax.isNull() or (MtvTariffTable.engineCcMax greaterEq engineCc))
                }
                .orderBy(MtvTariffTable.effectiveYear to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        } else if (trValueTl == null) {
            // No assessed value given — default to the top value band, where every currently
            // tracked car lands in practice (see cost_of_ownership.py's compute_mtv docstring).
            MtvTariffTable.selectAll()
                .where {
                    (MtvTariffTable.tarife eq "I") and
                        (MtvTariffTable.engineCcMin lessEq engineCc) and
                        (MtvTariffTable.engineCcMax.isNull() or (MtvTariffTable.engineCcMax greaterEq engineCc)) and
                        MtvTariffTable.valueBandMax.isNull()
                }
                .orderBy(MtvTariffTable.effectiveYear to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        } else {
            MtvTariffTable.selectAll()
                .where {
                    (MtvTariffTable.tarife eq "I") and
                        (MtvTariffTable.engineCcMin lessEq engineCc) and
                        (MtvTariffTable.engineCcMax.isNull() or (MtvTariffTable.engineCcMax greaterEq engineCc)) and
                        (MtvTariffTable.valueBandMin lessEq trValueTl) and
                        (MtvTariffTable.valueBandMax.isNull() or (MtvTariffTable.valueBandMax greater trValueTl))
                }
                .orderBy(MtvTariffTable.effectiveYear to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        }) ?: return null

        return MtvInfo(
            tarife = tarife,
            ageBracket = ageLabel,
            annualTl = row[ageColumn],
            effectiveYear = row[MtvTariffTable.effectiveYear],
        )
    }

    private fun ageColumnAndLabel(ageYears: Int): Pair<Column<BigDecimal>, String> = when {
        ageYears in 1..3 -> MtvTariffTable.age1To3 to "age_1_3"
        ageYears in 4..6 -> MtvTariffTable.age4To6 to "age_4_6"
        ageYears in 7..11 -> MtvTariffTable.age7To11 to "age_7_11"
        ageYears in 12..15 -> MtvTariffTable.age12To15 to "age_12_15"
        ageYears >= 16 -> MtvTariffTable.age16Plus to "age_16_plus"
        else -> MtvTariffTable.age1To3 to "age_1_3" // age 0 (registered this year) — same bracket as 1-3
    }

    /** (l_per_100km / 100) * annual_km * price_per_liter_tl — the published test figure, no
     *  adjustment for real-world vs. lab-test driving (same "official value, not a guess" stance
     *  as the rest of the scoring pipeline). */
    private fun computeFuelCostPerYear(literPer100km: BigDecimal, annualKm: Int, pricePerLiterTl: BigDecimal): BigDecimal =
        literPer100km.divide(BigDecimal(100), 8, java.math.RoundingMode.HALF_UP) * BigDecimal(annualKm) * pricePerLiterTl
}
