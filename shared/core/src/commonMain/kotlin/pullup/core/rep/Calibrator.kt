package pullup.core.rep

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.tan

/** Chin proxy y_chin (D2 §5.3): mouth midpoint + c_chin*S, else nose + (c_chin + c_nm)*S. Pixels. */
internal fun chinY(p: PosePx, s: Double, cfg: RepConfig): Double? {
    val mouth = p.meanY(Lm.MOUTH_L, Lm.MOUTH_R)
    if (mouth != null) return mouth + cfg.cChin * s
    if (p.vis(Lm.NOSE)) return p.y(Lm.NOSE) + (cfg.cChin + cfg.cNm) * s
    return null
}

internal fun hipMidX(p: PosePx): Double? =
    if (p.vis(Lm.L_HIP) && p.vis(Lm.R_HIP)) (p.x(Lm.L_HIP) + p.x(Lm.R_HIP)) / 2.0 else null

/** a = (shoulder-mid y - wrist-mid y) / S (D2 §5.3). */
internal fun auxA(p: PosePx, s: Double): Double? {
    val sh = p.meanY(Lm.L_SHOULDER, Lm.R_SHOULDER) ?: return null
    val wr = p.meanY(Lm.L_WRIST, Lm.R_WRIST) ?: return null
    return (sh - wr) / s
}

internal fun meanTheta(p: PosePx): Double? {
    val l = p.elbowAngle(Lm.L_SHOULDER, Lm.L_ELBOW, Lm.L_WRIST)
    val r = p.elbowAngle(Lm.R_SHOULDER, Lm.R_ELBOW, Lm.R_WRIST)
    return when {
        l != null && r != null -> (l + r) / 2.0
        else -> l ?: r
    }
}

/**
 * "Overlaps the bar" (D2 §6.1): both wrists observed, |dy| < 0.3 S, wrists in the upper half of the
 * frame. Wrists above the shoulders is added so a standing person with hands down never calibrates.
 */
internal fun overlapsBar(p: PosePx, s: Double, cfg: RepConfig): Boolean {
    if (!p.vis(Lm.L_WRIST) || !p.vis(Lm.R_WRIST)) return false
    if (abs(p.y(Lm.L_WRIST) - p.y(Lm.R_WRIST)) >= cfg.barOverlapWristDy * s) return false
    if (p.yNorm(Lm.L_WRIST) >= 0.5 || p.yNorm(Lm.R_WRIST) >= 0.5) return false
    val sh = p.meanY(Lm.L_SHOULDER, Lm.R_SHOULDER) ?: return true
    return (p.y(Lm.L_WRIST) + p.y(Lm.R_WRIST)) / 2.0 < sh
}

internal class CalSample(val ts: Long, val pose: PosePx)

internal class CalibrationOutcome(
    val calibration: Calibration,
    val blocks: List<CalBlockReason>,
    val warnings: List<WarningType>,
    val angleCheck: AngleCheck,
    val centerX: Double,
    val centerY: Double,
    /** Back view: true when label-left (11) is on the image left, i.e. labels already match image order. */
    val labelLeftIsImageLeft: Boolean,
)

/** Finds the T_cal stable-hang window (D2 §4.1 step 3). */
internal class Calibrator(private val cfg: RepConfig) {
    private val window = ArrayDeque<CalSample>()

    fun reset() = window.clear()

    /** Adds one frame; returns the stable window once found, else null. */
    fun offer(frame: PoseFrame): List<CalSample>? {
        val target = frame.poses
            .map { PosePx(it, frame.imageWidth, frame.imageHeight, cfg.visibilityMin) }
            .filter { isHangSample(it) }
            .maxByOrNull { it.trunkArea() }
        if (target == null) {
            window.clear()
            return null
        }
        val last = window.lastOrNull()
        if (last != null && frame.timestampMs - last.ts > cfg.tGapMs) window.clear()
        window.addLast(CalSample(frame.timestampMs, target))
        while (window.size > 1 && window.last().ts - window.first().ts >= cfg.tCalMs) {
            if (isStable()) return window.toList()
            window.removeFirst()
        }
        return null
    }

    private fun isHangSample(p: PosePx): Boolean {
        if (!p.allVisible(Lm.L_SHOULDER, Lm.R_SHOULDER, Lm.L_ELBOW, Lm.R_ELBOW, Lm.L_WRIST, Lm.R_WRIST, Lm.L_HIP, Lm.R_HIP)) {
            return false
        }
        val s2d = p.shoulderWidth() ?: return false
        return s2d > 0.0 && overlapsBar(p, s2d, cfg)
    }

    private fun isStable(): Boolean {
        val s = median(window.mapNotNull { it.pose.shoulderWidth() }) ?: return false
        val wristY = window.map { (it.pose.y(Lm.L_WRIST) + it.pose.y(Lm.R_WRIST)) / 2.0 }
        return stdDev(wristY) < cfg.calWristStd * s
    }
}

/** D2 §4.1 steps 4-5: medians over the window, then block/warn decisions. */
internal fun computeCalibration(
    samples: List<CalSample>,
    ts: Long,
    captureMode: CaptureMode,
    countMode: CountMode,
    meta: CaptureMeta?,
    source: SourceKind,
    cfg: RepConfig,
): CalibrationOutcome {
    val poses = samples.map { it.pose }
    val s2d = median(poses.mapNotNull { it.shoulderWidth() })!!
    val psi = median(poses.mapNotNull { it.yawDeg() }) ?: 0.0
    val s = s2d / max(cos(psi.toRad()), cfg.cosPsiFloor)
    val yBar = median(poses.flatMap { listOf(it.y(Lm.L_WRIST), it.y(Lm.R_WRIST)) })!!
    val yBarNorm = median(poses.flatMap { listOf(it.yNorm(Lm.L_WRIST), it.yNorm(Lm.R_WRIST)) })!!

    val thetaHang = median(poses.mapNotNull { meanTheta(it) }) ?: 0.0
    val hHang = median(poses.mapNotNull { p -> chinY(p, s, cfg)?.let { (yBar - it) / s } })
    val aHang = median(poses.mapNotNull { auxA(it, s) })!!
    val pxRef = median(poses.mapNotNull { hipMidX(it) })!!
    val eHang = median(poses.mapNotNull { p -> p.meanY(Lm.L_EAR, Lm.R_EAR)?.let { (yBar - it) / s } })
    val qCal = median(
        poses.mapNotNull { p ->
            val sh = p.meanY(Lm.L_SHOULDER, Lm.R_SHOULDER)
            val ear = p.meanY(Lm.L_EAR, Lm.R_EAR)
            if (sh != null && ear != null) (sh - ear) / s else null
        },
    )
    val earVis = median(poses.map { (it.visibility(Lm.L_EAR) + it.visibility(Lm.R_EAR)) / 2.0 })

    val beta = meta?.let { it.phiCamDeg + atan((0.5 - yBarNorm) * 2.0 * tan((it.vfovDeg / 2.0).toRad())).toDeg() }

    val cues = viewCues(poses, s, cfg)
    val expected = if (captureMode == CaptureMode.FRONT_MEASURE) ViewVote.FRONT else ViewVote.BACK
    val votes = listOf(cues.vz, cues.vx, cues.vn).filter { it != ViewVote.NA }
    val opposite = votes.count { it != expected }
    val viewSuspect = votes.size >= 2 && opposite >= cfg.viewSuspectMinVotes

    val strict = captureMode == CaptureMode.FRONT_MEASURE && countMode == CountMode.STRICT
    val blocks = mutableListOf<CalBlockReason>()
    val warnings = mutableListOf<WarningType>()
    fun blockOrWarn(block: CalBlockReason, warn: WarningType) {
        if (strict) blocks += block else warnings += warn
    }

    val angleCheck: AngleCheck
    if (beta == null) {
        warnings += WarningType.ANGLE_CHECK_SKIPPED
        angleCheck = AngleCheck.SKIPPED
        if (strict && source == SourceKind.CAMERA) blocks += CalBlockReason.ANGLE_UNMEASURABLE
    } else if (beta > cfg.betaMaxDeg || beta < cfg.betaMinDeg) {
        if (beta > cfg.betaMaxDeg) blockOrWarn(CalBlockReason.LOW_ANGLE, WarningType.LOW_ANGLE)
        else blockOrWarn(CalBlockReason.HIGH_ANGLE, WarningType.HIGH_ANGLE)
        angleCheck = if (strict) AngleCheck.BLOCKED else AngleCheck.WARNED
    } else {
        angleCheck = AngleCheck.PASSED
    }
    if (abs(psi) > cfg.sideViewPsiDeg) blockOrWarn(CalBlockReason.SIDE_VIEW, WarningType.SIDE_VIEW)
    if (thetaHang < cfg.thetaExtDeg - cfg.noFullHangMarginDeg) blockOrWarn(CalBlockReason.NO_FULL_HANG, WarningType.NO_FULL_HANG)
    if (viewSuspect) warnings += WarningType.VIEW_MISMATCH_SUSPECTED
    if (captureMode == CaptureMode.FRONT_MEASURE && hHang == null) warnings += WarningType.NO_FACE_AT_CALIBRATION

    val last = poses.last()
    val center = last.center()!!
    val labelLeftIsImageLeft = (median(poses.map { it.x(Lm.L_SHOULDER) - it.x(Lm.R_SHOULDER) }) ?: 0.0) < 0.0

    return CalibrationOutcome(
        calibration = Calibration(
            calibratedAtMs = ts,
            s = s,
            s2d = s2d,
            psiDeg = psi,
            yBarPx = yBar,
            yBarNorm = yBarNorm,
            thetaHangDeg = thetaHang,
            hHang = hHang,
            aHang = aHang,
            pxRefPx = pxRef,
            eHang = eHang,
            qCal = qCal,
            earVisibility = earVis,
            betaBarDeg = beta,
            viewCues = cues,
            viewSuspect = viewSuspect,
        ),
        blocks = blocks,
        warnings = warnings,
        angleCheck = angleCheck,
        centerX = center.first,
        centerY = center.second,
        labelLeftIsImageLeft = labelLeftIsImageLeft,
    )
}

/** D2 §3.4 cue signals, each the median over the calibration window. Missing inputs vote NA. */
internal fun viewCues(poses: List<PosePx>, s: Double, cfg: RepConfig): ViewCues {
    val vzValues = poses.mapNotNull { p ->
        if (!p.vis(Lm.NOSE) || !p.vis(Lm.L_EAR) || !p.vis(Lm.R_EAR)) return@mapNotNull null
        val n = p.depthPair(Lm.NOSE) ?: return@mapNotNull null
        val l = p.depthPair(Lm.L_EAR) ?: return@mapNotNull null
        val r = p.depthPair(Lm.R_EAR) ?: return@mapNotNull null
        n.second - (l.second + r.second) / 2.0
    }
    val vz = median(vzValues)?.let { if (it < 0) ViewVote.FRONT else ViewVote.BACK } ?: ViewVote.NA

    val vxValues = poses.mapNotNull { p ->
        if (p.vis(Lm.L_SHOULDER) && p.vis(Lm.R_SHOULDER)) p.x(Lm.L_SHOULDER) - p.x(Lm.R_SHOULDER) else null
    }
    val vx = median(vxValues)?.let { if (it > 0) ViewVote.FRONT else ViewVote.BACK } ?: ViewVote.NA

    val vnValues = poses.mapNotNull { p ->
        if (!p.vis(Lm.NOSE)) return@mapNotNull null
        val earX = p.meanX(Lm.L_EAR, Lm.R_EAR) ?: return@mapNotNull null
        (p.x(Lm.NOSE) - earX) / s
    }
    val vn = median(vnValues)?.let { if (abs(it) <= cfg.viewNoseOffsetMax) ViewVote.FRONT else ViewVote.BACK } ?: ViewVote.NA
    return ViewCues(vz, vx, vn)
}
