package com.arabaskor360.cost

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
    ): CostOfOwnershipResponse = transaction {
        val asOfYear = LocalDate.now().year
        val ageYears = asOfYear - registrationYear
        val notes = mutableListOf<String>()

        val fuelRows = VcaFuelConsumptionTable.selectAll()
            .where { VcaFuelConsumptionTable.modelVariantId eq modelVariantId }
            .toList()

        val options = fuelRows
            .groupBy { row -> (row[VcaFuelConsumptionTable.fuelType] ?: "Unknown") to row[VcaFuelConsumptionTable.testingScheme] }
            .map { (key, rows) -> buildOption(key.first, key.second, rows, registrationYear, asOfYear, annualKm, trValueTl, notes) }
            .sortedWith(compareBy({ it.fuelType }, { it.testingScheme }))

        if (options.isEmpty()) {
            notes += "Bu araç için VCA yakıt tüketimi verisi bulunamadı."
        }

        val kaskoDegerOptions = lookupKaskoDeger(make, kaskoTipSearchTerm, registrationYear)
        if (kaskoDegerOptions.isEmpty()) {
            notes += "'$make $kaskoTipSearchTerm' ($registrationYear model yılı) için TSB kasko değer eşleşmesi bulunamadı."
        }

        val estimatedKasko = if (trValueTl == null) {
            notes += "Kasko tahmini için trValueTl (aracın güncel değeri) parametresi verilmedi."
            null
        } else {
            notes += "Kasko tahmini gerçek bir teklif değildir — aracın değerinin %2-5'i arası kaba bir aralık."
            EstimateRange(
                minTl = (trValueTl * KASKO_RATE_MIN).setScale(2, java.math.RoundingMode.HALF_UP),
                maxTl = (trValueTl * KASKO_RATE_MAX).setScale(2, java.math.RoundingMode.HALF_UP),
            )
        }

        notes += "Trafik sigortası tahmini bu araca özel değildir — hasar basamağı, il ve araç " +
            "sınıfına göre gerçek fiyat bu aralığın dışına da çıkabilir."
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
                notes += "Elektrikli araçlar için MTV, ayrı kW bazlı bir tarifeye tabi — henüz transcribe edilmedi."
                null
            }
            avgCc == null -> null
            else -> computeMtv(avgCc, registrationYear, asOfYear, trValueTl).also {
                if (it == null) notes += "$fuelType ($testingScheme, ${avgCc}cc) için uyan MTV tarife satırı bulunamadı."
            }
        }

        val tariffFuelType = vcaFuelTypeToTariffFuelType[fuelType]
        val fuel = when {
            isElectric -> {
                notes += "Elektrikli araçlar için yakıt (şarj) maliyeti henüz hesaplanmıyor."
                null
            }
            avgL100 == null -> null
            tariffFuelType == null -> {
                notes += "'$fuelType' yakıt tipi için maliyet eşlemesi tanımlı değil."
                null
            }
            else -> {
                val price = fuelPriceCache.getPrice(tariffFuelType)
                if (price == null) {
                    notes += "'$tariffFuelType' için güncel yakıt fiyatı bulunamadı."
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
                    make = row[KaskoDegerTable.markaAdi],
                    trim = row[KaskoDegerTable.tipAdi],
                    modelYear = row[KaskoDegerTable.modelYear],
                    valueTl = row[KaskoDegerTable.valueTl],
                    snapshotMonth = row[KaskoDegerTable.snapshotMonth],
                    source = row[KaskoDegerTable.sourceLabel],
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
