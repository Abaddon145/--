package com.luca.classmanager.domain.logic

data class PackagePricing(
    val purchasedCreditUnits: Int?,
    val giftCreditUnits: Int,
    val amountCents: Long?,
    val unitPriceCents: Long?,
    val createdAtEpochMs: Long,
)

data class MoneyTotal(
    val knownCents: Long,
    val complete: Boolean,
) {
    companion object {
        val UNKNOWN = MoneyTotal(knownCents = 0, complete = false)

        fun known(cents: Long) = MoneyTotal(knownCents = cents, complete = true)
    }
}

data class CourseMetrics(
    val totalCreditUnits: Int?,
    val consumedCreditUnits: Int,
    val remainingCreditUnits: Int?,
    val paid: MoneyTotal,
    val consumedMoney: MoneyTotal,
    val remainingValue: MoneyTotal,
    val unrecordedSessionCount: Int,
    val needsRenewal: Boolean,
)

data class FeeRollup(
    val paid: MoneyTotal,
    val consumed: MoneyTotal,
    val remainingValue: MoneyTotal,
)

enum class GiftValuation {
    /** Gift lessons add hours and cost nothing. */
    ZERO,

    /**
     * Gift lessons use the purchased unit price of the same package.
     * If that price is unknown, the gift stays unknown instead of becoming 0.
     */
    MATCH_PURCHASED,
}

data class ConsumptionSlice(
    val key: String,
    val units: Int,
)

data class SliceCost(
    val key: String,
    val money: MoneyTotal,
)

/**
 * Money is integer cents. Unknown amounts stay unknown and are never treated as zero.
 * Consumed value uses FIFO. Gift units are worth 0 unless [GiftValuation.MATCH_PURCHASED]
 * and the package already has a known purchased price.
 */
object LessonCalculator {
    private data class Bucket(val units: Int?, val pricePerLessonCents: Long?)

    fun derivedUnitPrice(amountCents: Long?, purchasedUnits: Int?): Long? {
        if (amountCents == null || purchasedUnits == null || purchasedUnits <= 0) return null
        return amountCents * CreditUnits.PER_LESSON / purchasedUnits
    }

    fun calculate(
        packages: List<PackagePricing>,
        adjustmentCreditUnits: Int,
        attendanceCreditUnits: Int,
        historicalUnrecordedUnits: Int,
        historicalUnrecordedCount: Int,
        renewalThresholdCreditUnits: Int = CreditUnits.DEFAULT_RENEWAL_UNITS,
        giftValuation: GiftValuation = GiftValuation.ZERO,
    ): CourseMetrics {
        val ordered = packages.sortedBy { it.createdAtEpochMs }
        val purchasesKnown = ordered.all { it.purchasedCreditUnits != null }
        val packageCreditUnits = if (ordered.isNotEmpty() && purchasesKnown) {
            ordered.sumOf { (it.purchasedCreditUnits ?: 0) + it.giftCreditUnits }
        } else {
            null
        }
        val totalCreditUnits = packageCreditUnits?.plus(adjustmentCreditUnits)
        val consumedCreditUnits = attendanceCreditUnits + historicalUnrecordedUnits
        val remainingCreditUnits = totalCreditUnits?.minus(consumedCreditUnits)
        val paid = when {
            ordered.isEmpty() -> MoneyTotal.UNKNOWN
            ordered.all { it.amountCents != null } -> MoneyTotal.known(ordered.sumOf { it.amountCents ?: 0L })
            else -> MoneyTotal(
                knownCents = ordered.sumOf { it.amountCents ?: 0L },
                complete = false,
            )
        }
        val buckets = bucketsFor(ordered, giftValuation)
        val consumedMoney = consumeMoney(buckets, consumedCreditUnits)
        val remainingValue = remainingValue(buckets, consumedCreditUnits, packageCreditUnits, remainingCreditUnits)
        val needsRenewal = remainingCreditUnits != null &&
            remainingCreditUnits < renewalThresholdCreditUnits
        return CourseMetrics(
            totalCreditUnits = totalCreditUnits,
            consumedCreditUnits = consumedCreditUnits,
            remainingCreditUnits = remainingCreditUnits,
            paid = paid,
            consumedMoney = consumedMoney,
            remainingValue = remainingValue,
            unrecordedSessionCount = historicalUnrecordedCount,
            needsRenewal = needsRenewal,
        )
    }

    fun rollup(metrics: List<CourseMetrics>): FeeRollup {
        if (metrics.isEmpty()) {
            return FeeRollup(
                paid = MoneyTotal.known(0),
                consumed = MoneyTotal.known(0),
                remainingValue = MoneyTotal.known(0),
            )
        }
        return FeeRollup(
            paid = combine(metrics.map { it.paid }),
            consumed = combine(metrics.map { it.consumedMoney }),
            remainingValue = combine(metrics.map { it.remainingValue }),
        )
    }

    private fun combine(parts: List<MoneyTotal>): MoneyTotal = MoneyTotal(
        knownCents = parts.sumOf { it.knownCents },
        complete = parts.all { it.complete },
    )

    fun allocate(
        packages: List<PackagePricing>,
        slices: List<ConsumptionSlice>,
        giftValuation: GiftValuation = GiftValuation.ZERO,
    ): List<SliceCost> {
        val buckets = bucketsFor(packages.sortedBy { it.createdAtEpochMs }, giftValuation)
        val cursor = FifoCursor(buckets)
        return slices.map { slice -> SliceCost(slice.key, cursor.take(slice.units)) }
    }

    private fun bucketsFor(packages: List<PackagePricing>, giftValuation: GiftValuation): List<Bucket> {
        val buckets = mutableListOf<Bucket>()
        for (pkg in packages) {
            val price = pkg.unitPriceCents ?: derivedUnitPrice(pkg.amountCents, pkg.purchasedCreditUnits)
            buckets += Bucket(units = pkg.purchasedCreditUnits, pricePerLessonCents = price)
            if (pkg.giftCreditUnits > 0) {
                val giftPrice = when (giftValuation) {
                    GiftValuation.ZERO -> 0L
                    GiftValuation.MATCH_PURCHASED -> price
                }
                buckets += Bucket(units = pkg.giftCreditUnits, pricePerLessonCents = giftPrice)
            }
        }
        return buckets
    }

    private fun priceUnits(units: Int, pricePerLessonCents: Long): Long {
        return units.toLong() * pricePerLessonCents / CreditUnits.PER_LESSON
    }

    private fun consumeMoney(buckets: List<Bucket>, consumedUnits: Int): MoneyTotal {
        return FifoCursor(buckets).take(consumedUnits)
    }

    private class FifoCursor(private val buckets: List<Bucket>) {
        private var index = 0
        private var usedInBucket = 0

        fun take(units: Int): MoneyTotal {
            if (units < 0) return MoneyTotal.UNKNOWN
            if (units == 0) return MoneyTotal.known(0)
            if (buckets.isEmpty()) return MoneyTotal.UNKNOWN
            var left = units
            var cents = 0L
            var complete = true
            while (left > 0 && index <= buckets.lastIndex) {
                val bucket = buckets[index]
                val size = bucket.units
                if (size == null) {
                    val hasLaterBuckets = index < buckets.lastIndex
                    val price = bucket.pricePerLessonCents
                    if (hasLaterBuckets || price == null) {
                        complete = false
                        break
                    }
                    cents += priceUnits(left, price)
                    left = 0
                } else if (size - usedInBucket <= 0) {
                    index += 1
                    usedInBucket = 0
                } else {
                    val take = minOf(left, size - usedInBucket)
                    val price = bucket.pricePerLessonCents
                    if (price == null) {
                        complete = false
                        break
                    }
                    cents += priceUnits(take, price)
                    left -= take
                    usedInBucket += take
                    if (usedInBucket == size) {
                        index += 1
                        usedInBucket = 0
                    }
                }
            }
            if (left > 0) complete = false
            return MoneyTotal(knownCents = cents, complete = complete)
        }
    }

    private fun remainingValue(
        buckets: List<Bucket>,
        consumedUnits: Int,
        packageCreditUnits: Int?,
        remainingCreditUnits: Int?,
    ): MoneyTotal {
        if (remainingCreditUnits == null || packageCreditUnits == null) return MoneyTotal.UNKNOWN
        if (remainingCreditUnits <= 0) return MoneyTotal.known(0)
        var left = consumedUnits.coerceAtLeast(0)
        var cents = 0L
        for (bucket in buckets) {
            val size = bucket.units ?: return MoneyTotal.UNKNOWN
            val consumeHere = minOf(left, size)
            val remainHere = size - consumeHere
            left -= consumeHere
            if (remainHere > 0) {
                val price = bucket.pricePerLessonCents ?: return MoneyTotal(cents, complete = false)
                cents += priceUnits(remainHere, price)
            }
        }
        val packageRemaining = (packageCreditUnits - consumedUnits).coerceAtLeast(0)
        return if (packageRemaining == remainingCreditUnits) {
            MoneyTotal.known(cents)
        } else {
            MoneyTotal(knownCents = cents, complete = false)
        }
    }
}
