package pullup.core.rep

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** D2 §12.3 upload timeline. [json] is the serialized document; times in it are session-relative ms. */
data class Timeline(
    val json: String,
    val format: String,
    val n: Int,
    /** Rows `[dS, dT, dE, pk, eb, code]` (compact drops pk and eb). */
    val rows: List<List<Long>>,
    val sizeBytes: Int,
    /** True when even `compact` exceeds the 16 KB cap (`gz` is not implemented). */
    val oversize: Boolean,
)

internal const val TIMELINE_MAX_BYTES = 16 * 1024

internal fun buildTimeline(
    reps: List<RepMetrics>,
    sessionStartMs: Long,
    endMs: Long,
    fpsP50: Double,
    betaDeg: Double?,
    thetaExtDeg: Double,
    captureMode: CaptureMode,
    countMode: CountMode,
    source: SourceKind,
    viewSuspect: Boolean,
    cfg: RepConfig,
    appVersion: String,
    engine: String,
): Timeline {
    val n = reps.count { it.reason == ReasonCode.COUNTED && !it.has(RepFlags.NO_END) }
    val full = mutableListOf<List<Long>>()
    var prevRef = sessionStartMs
    for (r in reps) {
        val dS = r.startMs - prevRef
        val dT = r.topMs - r.startMs
        val dE = r.endMs?.let { it - r.topMs } ?: -1L
        val pk = r.hPeak?.let { (it * 100).roundToLong() } ?: 0L
        val eb = if (r.thetaBottomL != null || r.thetaBottomR != null) {
            listOfNotNull(r.thetaBottomL, r.thetaBottomR).average().roundToLong()
        } else {
            0L
        }
        full += listOf(dS, dT, dE, pk, eb, r.code.toLong())
        prevRef = r.endMs ?: r.topMs
    }
    val mode = when {
        captureMode == CaptureMode.BACK_POSTURE -> "B"
        countMode == CountMode.STRICT -> "S"
        else -> "P"
    }
    val header = buildString {
        append("{\"v\":1")
        append(",\"app\":\"").append(appVersion).append('"')
        append(",\"eng\":\"").append(engine).append('"')
        append(",\"src\":\"").append(if (source == SourceKind.CAMERA) "cam" else "replay").append('"')
        append(",\"mode\":\"").append(mode).append('"')
        append(",\"dur\":").append(endMs - sessionStartMs)
        append(",\"fps\":").append(((fpsP50 * 10).roundToLong() / 10.0).toString())
        append(",\"beta\":").append(betaDeg?.roundToInt()?.toString() ?: "null")
        append(",\"view\":\"").append(if (captureMode == CaptureMode.FRONT_MEASURE) "F" else "B").append('"')
        append(",\"vs\":").append(if (viewSuspect) 1 else 0)
        append(",\"thr\":{\"te\":").append(thetaExtDeg.roundToInt())
        append(",\"k\":").append(cfg.k.toString())
        append(",\"td\":").append(cfg.tDwellMs)
        append(",\"tf\":").append(cfg.tFloorMs)
        append(",\"tr\":").append(cfg.tRepMinMs).append('}')
        append(",\"n\":").append(n)
    }

    fun render(fmt: String, rows: List<List<Long>>): String =
        header + ",\"fmt\":\"" + fmt + "\",\"r\":[" + rows.joinToString(",") { "[" + it.joinToString(",") + "]" } + "]}"

    val fullJson = render("full", full)
    if (fullJson.encodeToByteArray().size <= TIMELINE_MAX_BYTES) {
        return Timeline(fullJson, "full", n, full, fullJson.encodeToByteArray().size, oversize = false)
    }
    val compact = full.map { listOf(it[0], it[1], it[2], it[5]) }
    val compactJson = render("compact", compact)
    val size = compactJson.encodeToByteArray().size
    return Timeline(compactJson, "compact", n, compact, size, oversize = size > TIMELINE_MAX_BYTES)
}
