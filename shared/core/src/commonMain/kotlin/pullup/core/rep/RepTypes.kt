package pullup.core.rep

/** D2 §7.1 states. */
enum class RepState { IDLE, CALIBRATING, HANG_WAIT, BOTTOM, PULLING, TOP_DONE, DESCENDING, TOP_NOCOUNT, PAUSED, ENDED }

/** D2 §7.2 events, declared in evaluation priority order. */
enum class RepEventId {
    STOP, LOST, FOUND, START, CAL_OK, CAL_BLOCK, UNDECIDABLE, DWELL_OK, FAST_TOP, TOP_OFF, EXT, LEAVE_BOTTOM,
}

/** D2 §12.1 reason codes (low 3 bits of `code`). */
enum class ReasonCode(val code: Int) {
    COUNTED(0),
    INCOMPLETE_EXTENSION(1),
    CHIN_BELOW_BAR(2),
    KIPPING(3),
    UNDETERMINED_OCCLUSION(4),
    ABORTED(5),
}

/** D2 §12.1 flag bits. */
object RepFlags {
    const val FAST_TOP = 8
    const val AUX = 16
    const val FAST_REP = 32
    const val TOO_FAST = 64
    const val NO_END = 128
    const val EST = 256
}

/** D2 §12.2 latency path. */
enum class LatencyPath { NORMAL, FAST_TOP, HOLD, AUX }

/** What the voice output should play for an event (D2 §5.4, §7.3, §8). */
enum class Voice { NUMBER, NO_COUNT, NEUTRAL_TONE, NONE }

/** D2 §3.3 / §3.4 / §4.1 calibration block reasons. */
enum class CalBlockReason { LOW_ANGLE, HIGH_ANGLE, SIDE_VIEW, NO_FULL_HANG, ANGLE_UNMEASURABLE }

enum class WarningType {
    LOW_ANGLE,
    HIGH_ANGLE,
    SIDE_VIEW,
    NO_FULL_HANG,
    VIEW_MISMATCH_SUSPECTED,
    ANGLE_CHECK_SKIPPED,
    NO_FACE_AT_CALIBRATION,
}

enum class AngleCheck { PASSED, WARNED, BLOCKED, SKIPPED }

enum class ViewVote { FRONT, BACK, NA }

/** D2 §3.4 view-suspect cue votes: V-z depth sign, V-x shoulder order, V-n nose offset. */
data class ViewCues(val vz: ViewVote, val vx: ViewVote, val vn: ViewVote)

/** D2 §5.7 TOP source of a back-view rep. */
enum class TopSource { EAR, SHOULDER_WRIST }

enum class StopCause { USER, RELEASE, PAUSE_TIMEOUT }

/**
 * Values fixed at calibration (D2 §4). Pixel quantities are in image pixels; S-normalized ones are
 * multiples of [s].
 */
data class Calibration(
    val calibratedAtMs: Long,
    val s: Double,
    val s2d: Double,
    val psiDeg: Double,
    /** True when ψ and the V-z cue came from world landmarks; false = normalized-z fallback (unreliable, warn only). */
    val psiFromWorld: Boolean,
    val yBarPx: Double,
    val yBarNorm: Double,
    val thetaHangDeg: Double,
    /** Effective extension threshold θ_ext = max(θ_hang − Δ_ext, θ_floor_abs) (§11 A1). */
    val thetaExtDeg: Double,
    val hHang: Double?,
    val aHang: Double,
    val pxRefPx: Double,
    val eHang: Double?,
    val qCal: Double?,
    val earVisibility: Double?,
    val betaBarDeg: Double?,
    val viewCues: ViewCues,
    val viewSuspect: Boolean,
)

/** D2 §12.2 per-rep local record. Times are input timestamps (ms). */
data class RepMetrics(
    val index: Int,
    val startMs: Long,
    val topMs: Long,
    val endMs: Long?,
    val reason: ReasonCode,
    val flags: Int,
    val thetaBottomL: Double?,
    val thetaBottomR: Double?,
    val thetaTopL: Double?,
    val thetaTopR: Double?,
    val hPeak: Double?,
    val kipH: Double,
    val durationMs: Long?,
    val latencyPath: LatencyPath,
) {
    val code: Int get() = reason.code + flags
    fun has(flag: Int): Boolean = flags and flag != 0
}

/** D2 §5.7 back-view posture metrics (proxies; all UNVERIFIED in D2). Null = not measurable. */
data class PostureMetrics(
    val repIndex: Int,
    val topSource: TopSource,
    val qBottom: Double?,
    val qTop: Double?,
    val dqBottom: Double?,
    val dqTop: Double?,
    val depressOnsetMs: Long?,
    val wElTop: Double?,
    val wElMax: Double?,
    val tElMaxMs: Long?,
    val oL: Double?,
    val oR: Double?,
    val rho: Double?,
    val dPsiDeg: Double?,
    val dThetaTop: Double?,
    val dThetaBottom: Double?,
    val shoulderTilt: Double?,
    val elbowHeightDiff: Double?,
    val dtLRMs: Long?,
)

/** Output events of [RepDetector.process]. */
sealed interface RepEvent {
    val tsMs: Long

    data class StateChanged(override val tsMs: Long, val from: RepState, val to: RepState, val trigger: RepEventId) : RepEvent

    data class CalibrationOk(override val tsMs: Long, val calibration: Calibration, val angleCheck: AngleCheck) : RepEvent

    data class CalibrationBlocked(override val tsMs: Long, val reasons: List<CalBlockReason>) : RepEvent

    /** The single COUNT event (D2 §8). */
    data class Count(
        override val tsMs: Long,
        val count: Int,
        val repIndex: Int,
        val flags: Int,
        val latencyPath: LatencyPath,
        val estimated: Boolean,
        val voice: Voice = Voice.NUMBER,
    ) : RepEvent

    data class NoCount(override val tsMs: Long, val reason: ReasonCode, val repIndex: Int, val voice: Voice) : RepEvent

    data class RepClosed(override val tsMs: Long, val repIndex: Int, val durationMs: Long?, val flags: Int) : RepEvent

    data class Warning(override val tsMs: Long, val type: WarningType) : RepEvent

    data class Stopped(override val tsMs: Long, val cause: StopCause) : RepEvent
}

/** Result of [RepDetector.finish]. */
data class SessionResult(
    val captureMode: CaptureMode,
    val countMode: CountMode,
    val finalState: RepState,
    /** Live COUNT events (what was spoken). */
    val count: Int,
    /** Session record count n: COUNTED reps without NO_END (D2 §12.3). */
    val validCount: Int,
    val estimated: Boolean,
    val reps: List<RepMetrics>,
    val posture: List<PostureMetrics>,
    val calibration: Calibration?,
    val angleCheck: AngleCheck?,
    val viewSuspect: Boolean,
    val warnings: List<WarningType>,
    val leaderboardEligible: Boolean,
    val ineligibleReasons: List<String>,
    val events: List<RepEvent>,
    val timeline: Timeline?,
    val nonMonotonicDropped: Int,
)
