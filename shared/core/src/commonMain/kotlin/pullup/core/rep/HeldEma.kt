package pullup.core.rep

internal enum class FeatureStatus { OBSERVED, HELD, UNAVAILABLE }

/**
 * One scalar feature with the D2 §5.4 hold rule and the §5.2 time-based EMA.
 *
 * - Observed: EMA update (re-initialized when returning after the hold cap).
 * - Missing: the last raw value is fed while `ts - firstMissing < tHold`; afterwards the feature is
 *   unavailable and the EMA is frozen.
 */
internal class HeldEma(private val tauMs: Double, private val tHoldMs: Long) {
    var value: Double? = null
        private set
    var status: FeatureStatus = FeatureStatus.UNAVAILABLE
        private set
    var lastRaw: Double? = null
        private set
    private var missingSince: Long? = null

    val usable: Double? get() = if (status == FeatureStatus.UNAVAILABLE) null else value

    /** A frame gap longer than T_gap counts as missing from [gapStartMs] (D2 §2.3). */
    fun markGap(gapStartMs: Long) {
        if (missingSince == null) missingSince = gapStartMs
    }

    fun update(ts: Long, dtMs: Long, raw: Double?) {
        val since = missingSince
        if (raw != null) {
            val expired = since != null && ts - since >= tHoldMs
            val v = value
            value = if (v == null || expired || status == FeatureStatus.UNAVAILABLE) raw else v + emaAlpha(dtMs, tauMs) * (raw - v)
            lastRaw = raw
            missingSince = null
            status = FeatureStatus.OBSERVED
            return
        }
        val start = since ?: ts
        missingSince = start
        val held = lastRaw
        val v = value
        if (held != null && v != null && status != FeatureStatus.UNAVAILABLE && ts - start < tHoldMs) {
            value = v + emaAlpha(dtMs, tauMs) * (held - v)
            status = FeatureStatus.HELD
        } else {
            status = FeatureStatus.UNAVAILABLE
        }
    }
}
