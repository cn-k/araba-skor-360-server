package com.arabaskor360.cars

import com.arabaskor360.common.Lang
import com.arabaskor360.cost.CostOfOwnershipRepository
import com.arabaskor360.cost.DEFAULT_ANNUAL_KM
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

private val REFRESH_INTERVAL: Duration = Duration.ofHours(1)

/**
 * Denormalized in-memory cache of the full Car response — score, confidence, ncapRating,
 * bestFuelConsumptionL100km, communityScore/communityCategoryScores/communityReviewCount, AND
 * costOfOwnership — keyed by (model_variant id, response language). `GET /api/cars/{id}` and
 * `GET /api/cars` are the same underlying data (one element vs. a filtered/sorted/paginated
 * list of them), so both read from here instead of hitting the DB per request; filtering,
 * sorting, and pagination for the list endpoint happen in [list] over already-assembled
 * responses, not via SQL (see CarRepository's doc comment — it went from doing that itself to
 * being a single-row builder this class calls).
 *
 * Rebuilt wholesale once an hour: every field here only changes via the loader's periodic
 * ingests (score, NCAP, MTV/kasko/fuel-price/exchange-rate tables) or a monthly cron, so an hour
 * of staleness is a non-issue for all of them EXCEPT communityScore/communityCategoryScores/
 * communityReviewCount, which change the instant someone submits or deletes a review through
 * this app itself. Those three are kept correct via [patchCommunityStats], called by
 * ReviewController right after a successful DB write — an O(1) single-entry patch, not a
 * cache-wide refresh, and not a source of truth on its own (the DB write already happened; this
 * just stops the cache from serving a stale count for up to an hour).
 *
 * Stored as an immutable `Map` behind a `@Volatile` reference (copy-on-write), not a
 * `ConcurrentHashMap` — readers never block, and a bulk refresh replaces the whole snapshot in
 * one atomic reference swap instead of a clear()-then-refill() window where concurrent readers
 * could see a half-empty cache. Same pattern as this app's other caches (FuelPriceCache).
 *
 * A car missing from the current snapshot (added to model_variant since the last sweep) is
 * computed live on first access and folded in — same lazy-fill approach as elsewhere in this
 * app. Only `Lang.TR` (this app's primary market) is eagerly precomputed during a sweep; `EN`
 * entries are filled lazily on first English request via [get]'s miss fallback — computing both
 * up front would double every DB round-trip in the sweep (measured: ~65s cold for ~60 cars x 2
 * langs) for a language most requests won't need immediately.
 */
class CarCache(
    private val carRepository: CarRepository,
    private val costOfOwnershipRepository: CostOfOwnershipRepository,
) {
    private val log = LoggerFactory.getLogger(CarCache::class.java)
    private val mutationLock = Any()

    @Volatile
    private var refreshedAt: Instant = Instant.EPOCH

    @Volatile
    private var cache: Map<Pair<Int, Lang>, CarResponse> = emptyMap()

    /** Triggers a synchronous full sweep right now — call from a background thread at startup so
     *  the first real request doesn't pay for it. */
    fun warmUp() = ensureFresh()

    fun get(id: Int, lang: Lang): CarResponse? {
        ensureFresh()
        cache[id to lang]?.let { return it }

        val computed = buildOne(id, lang) ?: return null
        synchronized(mutationLock) { cache = cache + ((id to lang) to computed) }
        return computed
    }

    fun list(
        query: String?,
        makes: List<String>?,
        minYear: Int?,
        maxYear: Int?,
        sortBy: CarSortBy?,
        descending: Boolean,
        limit: Int,
        offset: Int,
        lang: Lang,
    ): List<CarResponse> {
        ensureFresh()
        ensureLangPopulated(lang)
        val queryLower = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val makesLower = makes?.map { it.trim().lowercase() }?.takeIf { it.isNotEmpty() }

        val filtered = cache.entries.asSequence()
            .filter { (key, _) -> key.second == lang }
            .map { it.value }
            .filter { car ->
                (queryLower == null || car.make.lowercase().contains(queryLower) || car.model.lowercase().contains(queryLower)) &&
                    (makesLower == null || car.make.lowercase() in makesLower) &&
                    // Interval-overlap: a generation matches [minYear, maxYear] if its own span
                    // overlaps that range at all, not just if it started within it.
                    (minYear == null || car.yearEnd >= minYear) &&
                    (maxYear == null || car.yearStart <= maxYear)
            }

        val sorted = if (sortBy == null) {
            filtered.sortedWith(compareBy({ it.make }, { it.model }))
        } else {
            val comparator: Comparator<CarResponse> = when (sortBy) {
                CarSortBy.SCORE -> compareBy(rankingComparator<BigDecimal>(descending)) { it.score }
                CarSortBy.NCAP_STARS -> compareBy(rankingComparator<BigDecimal>(descending)) { it.ncapRating?.stars }
                CarSortBy.COMMUNITY_SCORE -> compareBy(rankingComparator<BigDecimal>(descending)) { it.communityScore }
                CarSortBy.FUEL_CONSUMPTION -> compareBy(rankingComparator<BigDecimal>(descending)) { it.bestFuelConsumptionL100km }
            }
            filtered.sortedWith(comparator)
        }

        return sorted.drop(offset).take(limit).toList()
    }

    /** Recomputes just [carId]'s community stats (cheap — a single GROUP BY) and patches both
     *  language variants in the cache in place; everything else in the cached response (score,
     *  costOfOwnership, ...) is left untouched since reviews can't affect them. No-op if the car
     *  isn't cached yet — a later [get]/[list] will build it fresh, community stats included. */
    fun patchCommunityStats(carId: Int) {
        synchronized(mutationLock) {
            var updated = cache
            for (lang in Lang.entries) {
                val existing = updated[carId to lang] ?: continue
                val refreshed = carRepository.getCar(carId, lang) ?: continue
                updated = updated + (
                    (carId to lang) to existing.copy(
                        communityScore = refreshed.communityScore,
                        communityReviewCount = refreshed.communityReviewCount,
                        communityCategoryScores = refreshed.communityCategoryScores,
                    )
                    )
            }
            cache = updated
        }
    }

    /** Cars with no value for the sort field (no score/NCAP/community reviews/VCA data yet)
     *  always sort last, in both directions — "unranked" isn't the same as "worst". */
    private fun <T : Comparable<T>> rankingComparator(descending: Boolean): Comparator<T?> =
        if (descending) nullsLast(Comparator.reverseOrder()) else nullsLast()

    private fun buildOne(id: Int, lang: Lang): CarResponse? {
        val car = carRepository.getCar(id, lang) ?: return null
        val costOfOwnership = costOfOwnershipRepository.getCostOfOwnership(
            modelVariantId = id,
            make = car.make,
            kaskoTipSearchTerm = car.trName ?: car.model,
            registrationYear = car.yearStart,
            annualKm = DEFAULT_ANNUAL_KM,
            trValueTl = null,
            lang = lang,
        )
        return car.copy(costOfOwnership = costOfOwnership)
    }

    /** [list] can't rely on [get]'s per-id miss fallback — it reads directly off the cache's
     *  current contents to filter/sort/paginate, so a language with zero (or partially) built
     *  entries would just silently return fewer results, not fall back to computing them. Since
     *  only Lang.TR is eagerly swept, the first EN list call pays for building every EN entry
     *  once (same cost as a second, EN-only sweep); every EN call after that is a normal cache
     *  read. Also self-heals any language for cars added since the last full sweep. */
    private fun ensureLangPopulated(lang: Lang) {
        val ids = carRepository.allVariantIds()
        if (ids.all { (it to lang) in cache }) return
        synchronized(mutationLock) {
            val stillMissing = ids.filter { (it to lang) !in cache }
            if (stillMissing.isEmpty()) return
            var updated = cache
            for (id in stillMissing) {
                try {
                    val built = buildOne(id, lang) ?: continue
                    updated = updated + ((id to lang) to built)
                } catch (e: Exception) {
                    log.warn("Failed to precompute car $id ($lang) while populating a list request", e)
                }
            }
            cache = updated
        }
    }

    private fun ensureFresh() {
        if (Duration.between(refreshedAt, Instant.now()) < REFRESH_INTERVAL) return
        synchronized(mutationLock) {
            // Another thread may have already refreshed while we were waiting for the lock.
            if (Duration.between(refreshedAt, Instant.now()) < REFRESH_INTERVAL) return

            val ids = carRepository.allVariantIds()
            val fresh = mutableMapOf<Pair<Int, Lang>, CarResponse>()
            var failures = 0
            for (id in ids) {
                try {
                    fresh[id to Lang.TR] = buildOne(id, Lang.TR) ?: continue
                } catch (e: Exception) {
                    failures++
                    log.warn("Failed to precompute car $id (TR)", e)
                }
            }

            if (fresh.isEmpty() && ids.isNotEmpty()) {
                // Whole sweep failed (e.g. DB hiccup) — keep serving the last good snapshot
                // rather than wiping it out with an empty one.
                log.warn("Car cache refresh produced no results, keeping previous snapshot")
                return
            }
            if (failures > 0) {
                log.warn("Car cache refresh completed with $failures failure(s) out of ${ids.size}")
            }

            cache = fresh
            refreshedAt = Instant.now()
        }
    }
}
