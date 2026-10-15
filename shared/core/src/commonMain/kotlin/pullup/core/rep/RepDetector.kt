package pullup.core.rep

import kotlin.math.abs
import kotlin.math.hypot

/** Non-threshold detector options. */
data class DetectorOptions(
    /** Re-issue START on the next frame after CAL_BLOCK (replay evaluation; the app uses a retry button). */
    val restartAfterCalBlock: Boolean = false,
    val appVersion: String = "dev",
    val engine: String = "unknown",
)

/**
 * D2 rep detector: calibration (§4), features (§5), target tracking (§6), the §7.3 transition table,
 * the single COUNT event (§8), rep time checks (§9) and the timeline (§12).
 *
 * Deterministic: time comes only from [PoseFrame.timestampMs]. Not thread-safe; feed frames in order (D4 P1).
 */
class RepDetector(
    val captureMode: CaptureMode,
    val countMode: CountMode = CountMode.STRICT,
    val captureMeta: CaptureMeta? = null,
    val source: SourceKind = SourceKind.REPLAY,
    val config: RepConfig = RepConfig(),
    val options: DetectorOptions = DetectorOptions(),
) {
    private val cfg = config
    private val back = captureMode == CaptureMode.BACK_POSTURE
    private val strictFront = !back && countMode == CountMode.STRICT
    private val practiceFront = !back && countMode == CountMode.PRACTICE

    var state: RepState = RepState.IDLE
        private set
    var count: Int = 0
        private set
    var calibration: Calibration? = null
        private set

    private var startPending = false
    private var lastTs: Long? = null
    private var nonMonotonic = 0
    private val events = mutableListOf<RepEvent>()
    private val warnings = mutableListOf<WarningType>()
    private val dts = mutableListOf<Long>()
    private var angleCheck: AngleCheck? = null

    private val calibrator = Calibrator(cfg)
    private var labelLeftIsImageLeft = true
    private var hLine = 0.0
    private var auxLine = 0.0
    private var hHang = 0.0

    // Smoothed features (D2 §5.2-5.4). Theta/geometry ones use label sides; posture metrics remap to image sides.
    private fun ema() = HeldEma(cfg.tauEmaMs, cfg.tHoldMs)
    private val thetaL = ema()
    private val thetaR = ema()
    private val fH = ema()
    private val fA = ema()
    private val fPx = ema()
    private val fE = ema()
    private val fQ = ema()
    private val fWEl = ema()
    private val fOL = ema()
    private val fOR = ema()
    private val fS2d = ema()
    private val fTilt = ema()
    private val fElbowDiff = ema()
    private val fPsi = ema()
    private val allFeatures = listOf(thetaL, thetaR, fH, fA, fPx, fE, fQ, fWEl, fOL, fOR, fS2d, fTilt, fElbowDiff, fPsi)

    // Target tracking (§6)
    private var centerX = 0.0
    private var centerY = 0.0
    private var bodyMissingSince: Long? = null
    private var releaseSince: Long? = null
    private var pausedSince: Long? = null
    private var refindSince: Long? = null

    // Condition timers (§7.2)
    private var extRunStart: Long? = null
    private var dwellRunStart: Long? = null
    private var dwellRunAux = false
    private var fastRunStart: Long? = null
    private var fastRunSawDwell = false

    // Rep bookkeeping
    private val reps = mutableListOf<RepBuilder>()
    private var candidate: RepBuilder? = null
    private val openReps = mutableListOf<RepBuilder>()
    private var bottom = BottomSnap()
    private val posture = mutableListOf<PostureMetrics>()

    /** Injects START (E4) on the next processed frame. */
    fun start() {
        startPending = true
    }

    /** Processes one frame and returns the events it produced. */
    fun process(frame: PoseFrame): List<RepEvent> {
        if (state == RepState.ENDED) return emptyList()
        val ts = frame.timestampMs
        val prev = lastTs
        if (prev != null && ts <= prev) {
            nonMonotonic++
            return emptyList()
        }
        val dt = if (prev == null) 0L else ts - prev
        if (prev != null) dts += dt
        lastTs = ts
        val out = mutableListOf<RepEvent>()
        when (state) {
            RepState.IDLE -> if (startPending) {
                startPending = false
                calibrator.reset()
                transition(ts, RepState.CALIBRATING, RepEventId.START, out) // A0
                calibrator.offer(frame)
            }
            RepState.CALIBRATING -> calibrate(frame, out)
            else -> track(frame, ts, dt, prev, out)
        }
        events += out
        return out
    }

    /** User STOP (E1) and final result. */
    fun finish(): SessionResult {
        if (state != RepState.ENDED) {
            val out = mutableListOf<RepEvent>()
            stop(lastTs ?: 0L, StopCause.USER, out)
            events += out
        }
        return result()
    }

    // ---------------------------------------------------------------- calibration (§4)

    private fun calibrate(frame: PoseFrame, out: MutableList<RepEvent>) {
        val ts = frame.timestampMs
        val window = calibrator.offer(frame) ?: return
        val o = computeCalibration(window, ts, captureMode, countMode, captureMeta, source, cfg)
        o.warnings.forEach { w ->
            warnings += w
            out += RepEvent.Warning(ts, w)
        }
        angleCheck = o.angleCheck
        if (o.blocks.isNotEmpty()) {
            out += RepEvent.CalibrationBlocked(ts, o.blocks)
            transition(ts, RepState.IDLE, RepEventId.CAL_BLOCK, out) // A2
            if (options.restartAfterCalBlock) startPending = true
            return
        }
        // A1: freeze calibration values.
        val c = o.calibration
        calibration = c
        labelLeftIsImageLeft = o.labelLeftIsImageLeft
        centerX = o.centerX
        centerY = o.centerY
        hHang = c.hHang ?: DEFAULT_H_HANG
        hLine = when {
            back -> cfg.mEar
            practiceFront -> hHang + cfg.practiceTopRatio * (cfg.k - hHang)
            else -> cfg.k
        }
        auxLine = if (practiceFront) c.aHang - cfg.practiceTopRatio * (c.aHang - cfg.dAux) else cfg.dAux
        out += RepEvent.CalibrationOk(ts, c, o.angleCheck)
        transition(ts, RepState.HANG_WAIT, RepEventId.CAL_OK, out)
    }

    // ---------------------------------------------------------------- per-frame tracking

    private fun track(frame: PoseFrame, ts: Long, dt: Long, prev: Long?, out: MutableList<RepEvent>) {
        val c = calibration!!
        val s = c.s
        if (prev != null && dt > cfg.tGapMs) {
            allFeatures.forEach { it.markGap(prev) }
            if (bodyMissingSince == null) bodyMissingSince = prev
            extRunStart = null
            dwellRunStart = null
            fastRunStart = null
            releaseSince = null
            refindSince = null
        }

        val poses = frame.poses.map { PosePx(it, frame.imageWidth, frame.imageHeight, cfg.visibilityMin) }
        val target = if (state == RepState.PAUSED) refindCandidate(poses, ts) else nearestCandidate(poses)
        target?.center()?.let {
            centerX = it.first
            centerY = it.second
        }
        updateFeatures(target, ts, dt, c)

        // LOST timer: SHOULDER+HIP four points all missing (§5.5).
        val bodyVisible = target != null && intArrayOf(Lm.L_SHOULDER, Lm.R_SHOULDER, Lm.L_HIP, Lm.R_HIP).any { target.vis(it) }
        if (bodyVisible) bodyMissingSince = null else if (bodyMissingSince == null) bodyMissingSince = ts

        // Release timer: both wrists more than 0.5 S below the bar (§5.5).
        val released = target != null && target.vis(Lm.L_WRIST) && target.vis(Lm.R_WRIST) &&
            (target.y(Lm.L_WRIST) - c.yBarPx) / s > cfg.releaseDrop &&
            (target.y(Lm.R_WRIST) - c.yBarPx) / s > cfg.releaseDrop
        releaseSince = if (released) releaseSince ?: ts else null

        val theta = smoothedTheta()
        val top = evaluateTop()

        // EXT run (E11)
        val extCond = if (practiceFront) ratio()?.let { it <= cfg.practiceRearmRatio } == true else theta?.let { it >= cfg.thetaExtDeg } == true
        extRunStart = if (extCond) extRunStart ?: ts else null
        val ext = extRunStart?.let { ts - it >= cfg.tExtHoldMs } == true
        val leave = if (practiceFront) ratio()?.let { it > cfg.practiceLeaveRatio } == true else theta?.let { it < cfg.thetaExtDeg - cfg.thetaHystDeg } == true

        // DWELL run (E8): timer from the first frame the TOP condition holds, regardless of state.
        if (top.cond == true) {
            if (dwellRunStart == null) {
                dwellRunStart = ts
                dwellRunAux = false
            }
            if (top.aux) dwellRunAux = true
        } else {
            dwellRunStart = null
            dwellRunAux = false
        }
        val dwellOk = dwellRunStart?.let { ts - it >= cfg.tDwellMs } == true

        // FAST_TOP (E9): observed raw primary only.
        var fastTop = false
        val primary = if (back) fE else fH
        val rawPrimary = if (primary.status == FeatureStatus.OBSERVED) primary.lastRaw else null
        if (rawPrimary != null) {
            if (rawPrimary >= hLine + cfg.deltaFast) {
                if (fastRunStart == null) {
                    fastRunStart = ts
                    fastRunSawDwell = false
                }
                if (dwellOk) fastRunSawDwell = true
            } else {
                val start = fastRunStart
                if (start != null) fastTop = ts - start >= cfg.tFastMinMs && !fastRunSawDwell && !dwellOk
                fastRunStart = null
            }
        } else {
            fastRunStart = null
        }

        if (state == RepState.PULLING) candidate?.let { updatePulling(it, ts, theta, top) }
        openReps.forEach { r -> primary.usable?.let { v -> r.peak = maxOf(r.peak ?: v, v) } }

        val conds = mapOf(
            RepEventId.STOP to (stopCause(ts) != null),
            RepEventId.LOST to (bodyMissingSince?.let { ts - it >= cfg.tLostMs } == true),
            RepEventId.FOUND to (refindSince?.let { ts - it >= cfg.tFoundMs } == true),
            RepEventId.UNDECIDABLE to (top.cond == null),
            RepEventId.DWELL_OK to dwellOk,
            RepEventId.FAST_TOP to fastTop,
            RepEventId.TOP_OFF to top.off,
            RepEventId.EXT to ext,
            RepEventId.LEAVE_BOTTOM to leave,
        )
        for (ev in RepEventId.entries) {
            if (conds[ev] != true) continue
            if (applyTransition(ev, ts, top, out)) break
        }
    }

    private fun nearestCandidate(poses: List<PosePx>): PosePx? {
        val c = calibration!!
        return poses
            .filter { widthOk(it) }
            .mapNotNull { p -> p.center()?.let { p to hypot(it.first - centerX, it.second - centerY) } }
            .filter { it.second <= cfg.gTrack * c.s }
            .minByOrNull { it.second }
            ?.first
    }

    /** §6.3: within 1.5 S of the last center, width within tolerance, overlapping the bar, for T_found. */
    private fun refindCandidate(poses: List<PosePx>, ts: Long): PosePx? {
        val c = calibration!!
        val p = poses
            .filter { widthOk(it) && it.shoulderWidth() != null && overlapsBar(it, c.s, cfg) }
            .mapNotNull { p -> p.center()?.let { p to hypot(it.first - centerX, it.second - centerY) } }
            .filter { it.second <= cfg.refindRadius * c.s }
            .minByOrNull { it.second }
            ?.first
        refindSince = if (p != null) refindSince ?: ts else null
        return p
    }

    private fun widthOk(p: PosePx): Boolean {
        val w = p.shoulderWidth() ?: return true
        val ref = calibration!!.s2d
        return w >= ref * (1 - cfg.widthTolerance) && w <= ref * (1 + cfg.widthTolerance)
    }

    private fun updateFeatures(p: PosePx?, ts: Long, dt: Long, c: Calibration) {
        val s = c.s
        thetaL.update(ts, dt, p?.elbowAngle(Lm.L_SHOULDER, Lm.L_ELBOW, Lm.L_WRIST))
        thetaR.update(ts, dt, p?.elbowAngle(Lm.R_SHOULDER, Lm.R_ELBOW, Lm.R_WRIST))
        fH.update(ts, dt, p?.let { chinY(it, s, cfg) }?.let { (c.yBarPx - it) / s })
        fA.update(ts, dt, p?.let { auxA(it, s) })
        fPx.update(ts, dt, p?.let { hipMidX(it) }?.let { (it - c.pxRefPx) / s })
        val earY = p?.meanY(Lm.L_EAR, Lm.R_EAR)
        fE.update(ts, dt, earY?.let { (c.yBarPx - it) / s })
        val shY = p?.meanY(Lm.L_SHOULDER, Lm.R_SHOULDER)
        fQ.update(ts, dt, if (shY != null && earY != null) (shY - earY) / s else null)
        if (!back) return
        // §5.7 posture inputs, image-left / image-right sides.
        val sides = p?.let { imageSides() }
        fun both(a: Int, b: Int) = p != null && p.vis(a) && p.vis(b)
        fWEl.update(ts, dt, if (both(Lm.L_ELBOW, Lm.R_ELBOW)) abs(p!!.x(Lm.L_ELBOW) - p.x(Lm.R_ELBOW)) / s else null)
        fOL.update(ts, dt, sides?.let { sd -> if (both(sd.elbowL, sd.wristL)) (p.x(sd.wristL) - p.x(sd.elbowL)) / s else null })
        fOR.update(ts, dt, sides?.let { sd -> if (both(sd.elbowR, sd.wristR)) (p.x(sd.elbowR) - p.x(sd.wristR)) / s else null })
        fS2d.update(ts, dt, p?.shoulderWidth()?.let { it / s })
        fTilt.update(ts, dt, sides?.let { sd -> if (both(sd.shoulderL, sd.shoulderR)) (p.y(sd.shoulderL) - p.y(sd.shoulderR)) / s else null })
        fElbowDiff.update(ts, dt, sides?.let { sd -> if (both(sd.elbowL, sd.elbowR)) (p.y(sd.elbowL) - p.y(sd.elbowR)) / s else null })
        fPsi.update(ts, dt, p?.yawDeg())
    }

    private class Sides(val shoulderL: Int, val shoulderR: Int, val elbowL: Int, val elbowR: Int, val wristL: Int, val wristR: Int)

    /** §5.7: left/right by image x order, not by label. */
    private fun imageSides(): Sides =
        if (labelLeftIsImageLeft) {
            Sides(Lm.L_SHOULDER, Lm.R_SHOULDER, Lm.L_ELBOW, Lm.R_ELBOW, Lm.L_WRIST, Lm.R_WRIST)
        } else {
            Sides(Lm.R_SHOULDER, Lm.L_SHOULDER, Lm.R_ELBOW, Lm.L_ELBOW, Lm.R_WRIST, Lm.L_WRIST)
        }

    private fun imageThetaL(): Double? = if (labelLeftIsImageLeft) thetaL.usable else thetaR.usable
    private fun imageThetaR(): Double? = if (labelLeftIsImageLeft) thetaR.usable else thetaL.usable

    private fun smoothedTheta(): Double? {
        val l = thetaL.usable
        val r = thetaR.usable
        return when {
            l != null && r != null -> (l + r) / 2.0
            else -> l ?: r
        }
    }

    /** Practice rise ratio r (§5.3). */
    private fun ratio(): Double? {
        val h = fH.usable ?: return null
        val den = cfg.k - hHang
        if (den <= 0.0) return null
        return (h - hHang) / den
    }

    private class TopEval(val cond: Boolean?, val off: Boolean, val aux: Boolean, val held: Boolean)

    /** §5.4 (front) / §5.6 (back): primary metric, else shoulder-wrist a, else undecidable. */
    private fun evaluateTop(): TopEval {
        val primary = if (back) fE else fH
        val pv = primary.usable
        if (pv != null) {
            return TopEval(pv >= hLine, pv < hLine - cfg.hTop, aux = false, held = primary.status == FeatureStatus.HELD)
        }
        val av = fA.usable
        if (av != null) {
            return TopEval(av <= auxLine, av > auxLine + cfg.hAux, aux = true, held = fA.status == FeatureStatus.HELD)
        }
        return TopEval(null, off = false, aux = false, held = false)
    }

    private fun stopCause(ts: Long): StopCause? = when {
        state == RepState.PAUSED -> if (pausedSince?.let { ts - it >= cfg.tPauseEndMs } == true) StopCause.PAUSE_TIMEOUT else null
        releaseSince?.let { ts - it >= cfg.tReleaseMs } == true -> StopCause.RELEASE
        else -> null
    }

    // ---------------------------------------------------------------- transition table (§7.3)

    /** Applies [ev] if the table defines it for the current state; returns false for "hold". */
    private fun applyTransition(ev: RepEventId, ts: Long, top: TopEval, out: MutableList<RepEvent>): Boolean {
        val s = state
        if (ev == RepEventId.STOP) {
            if (s == RepState.ENDED) return false
            stop(ts, stopCause(ts) ?: StopCause.USER, out)
            return true
        }
        when (s) {
            RepState.HANG_WAIT -> when (ev) {
                RepEventId.LOST -> pause(ts, ev, out)
                RepEventId.DWELL_OK, RepEventId.FAST_TOP -> n1(ts, ev, out)
                RepEventId.EXT -> {
                    snapBottom()
                    transition(ts, RepState.BOTTOM, ev, out) // A3
                }
                else -> return false
            }
            RepState.BOTTOM -> when (ev) {
                RepEventId.LOST -> pause(ts, ev, out)
                RepEventId.LEAVE_BOTTOM -> {
                    candidate = RepBuilder(ts, bottom).also { updatePulling(it, ts, smoothedTheta(), top) } // A4
                    transition(ts, RepState.PULLING, ev, out)
                }
                else -> return false
            }
            RepState.PULLING -> when (ev) {
                RepEventId.LOST -> pause(ts, ev, out)
                RepEventId.UNDECIDABLE -> {
                    val r = commitCandidate(ts, ReasonCode.UNDETERMINED_OCCLUSION) // N4
                    openReps += r
                    out += RepEvent.NoCount(ts, r.reason, r.index, Voice.NEUTRAL_TONE)
                    transition(ts, RepState.TOP_NOCOUNT, ev, out)
                }
                RepEventId.DWELL_OK, RepEventId.FAST_TOP -> guard(ts, ev, out)
                RepEventId.EXT -> {
                    val cand = candidate!! // A5
                    if (cand.minTheta?.let { it < cfg.thetaAttemptDeg } == true) {
                        val r = commitCandidate(ts, ReasonCode.CHIN_BELOW_BAR)
                        r.endMs = ts
                        r.durationMs = ts - r.startMs
                        out += RepEvent.NoCount(ts, r.reason, r.index, if (strictFront) Voice.NO_COUNT else Voice.NONE)
                    } else {
                        candidate = null
                    }
                    transition(ts, RepState.BOTTOM, ev, out)
                }
                else -> return false
            }
            RepState.TOP_DONE, RepState.TOP_NOCOUNT -> when (ev) {
                RepEventId.LOST -> pause(ts, ev, out)
                RepEventId.TOP_OFF -> transition(ts, RepState.DESCENDING, ev, out)
                RepEventId.EXT -> a6(ts, ev, out)
                else -> return false
            }
            RepState.DESCENDING -> when (ev) {
                RepEventId.LOST -> pause(ts, ev, out)
                RepEventId.DWELL_OK, RepEventId.FAST_TOP -> n1(ts, ev, out)
                RepEventId.EXT -> a6(ts, ev, out)
                else -> return false
            }
            RepState.PAUSED -> when (ev) {
                RepEventId.FOUND -> { // A8: re-lock, disarmed, calibration kept
                    pausedSince = null
                    refindSince = null
                    transition(ts, RepState.HANG_WAIT, ev, out)
                }
                else -> return false
            }
            else -> return false
        }
        return true
    }

    /** G1 guard then COUNT (§8), or NC(KIPPING) in strict front mode. */
    private fun guard(ts: Long, ev: RepEventId, out: MutableList<RepEvent>) {
        val cand = candidate!!
        if (strictFront && cand.kipH >= cfg.kappaKip) {
            val r = commitCandidate(ts, ReasonCode.KIPPING)
            openReps += r
            out += RepEvent.NoCount(ts, r.reason, r.index, Voice.NO_COUNT)
            transition(ts, RepState.TOP_NOCOUNT, ev, out)
            return
        }
        var flags = 0
        val path: LatencyPath
        when {
            dwellRunAux && !back -> {
                flags = flags or RepFlags.AUX
                path = LatencyPath.AUX
            }
            dwellRunAux -> path = LatencyPath.AUX
            cand.usedHold -> path = LatencyPath.HOLD
            ev == RepEventId.FAST_TOP -> path = LatencyPath.FAST_TOP
            else -> path = LatencyPath.NORMAL
        }
        if (ev == RepEventId.FAST_TOP) flags = flags or RepFlags.FAST_TOP
        val r = commitCandidate(ts, ReasonCode.COUNTED)
        r.flags = r.flags or flags
        r.latencyPath = path
        openReps += r
        count++
        if (back) posture += postureMetrics(r)
        out += RepEvent.Count(ts, count, r.index, r.flags, path, estimated = back)
        transition(ts, RepState.TOP_DONE, ev, out)
    }

    /** N1: TOP reached while not armed. Closes a still-open previous rep at this instant. */
    private fun n1(ts: Long, ev: RepEventId, out: MutableList<RepEvent>) {
        closeOpenReps(ts, out)
        val r = RepBuilder(ts, bottom)
        r.minTheta = smoothedTheta()
        commit(r, ts, ReasonCode.INCOMPLETE_EXTENSION)
        openReps += r
        out += RepEvent.NoCount(ts, r.reason, r.index, if (strictFront) Voice.NO_COUNT else Voice.NONE)
        transition(ts, RepState.TOP_NOCOUNT, ev, out)
    }

    /** A6: close the previous rep, time check (§9), re-arm and refresh the bottom snapshot. */
    private fun a6(ts: Long, ev: RepEventId, out: MutableList<RepEvent>) {
        closeOpenReps(ts, out)
        snapBottom()
        transition(ts, RepState.BOTTOM, ev, out)
    }

    private fun closeOpenReps(ts: Long, out: MutableList<RepEvent>) {
        for (r in openReps) {
            r.endMs = ts
            val d = ts - r.startMs
            r.durationMs = d
            if (r.reason == ReasonCode.COUNTED) {
                if (d < cfg.tFloorMs) r.flags = r.flags or RepFlags.TOO_FAST
                else if (d < cfg.tRepMinMs) r.flags = r.flags or RepFlags.FAST_REP
            }
            out += RepEvent.RepClosed(ts, r.index, d, r.flags)
        }
        openReps.clear()
    }

    /** A7 / A9 / A10: abort a candidate, mark open reps NO_END. */
    private fun abandonReps(ts: Long, out: MutableList<RepEvent>) {
        if (state == RepState.PULLING && candidate != null) {
            val r = commitCandidate(ts, ReasonCode.ABORTED)
            r.endMs = ts
            r.durationMs = ts - r.startMs
        }
        candidate = null
        for (r in openReps) {
            r.endMs = null
            r.durationMs = null
            r.flags = r.flags or RepFlags.NO_END
            out += RepEvent.RepClosed(ts, r.index, null, r.flags)
        }
        openReps.clear()
    }

    private fun pause(ts: Long, ev: RepEventId, out: MutableList<RepEvent>) {
        abandonReps(ts, out)
        pausedSince = ts
        refindSince = null
        transition(ts, RepState.PAUSED, ev, out)
    }

    private fun stop(ts: Long, cause: StopCause, out: MutableList<RepEvent>) {
        if (state != RepState.IDLE) abandonReps(ts, out)
        out += RepEvent.Stopped(ts, cause)
        transition(ts, RepState.ENDED, RepEventId.STOP, out)
    }

    private fun transition(ts: Long, to: RepState, trigger: RepEventId, out: MutableList<RepEvent>) {
        val from = state
        state = to
        out += RepEvent.StateChanged(ts, from, to, trigger)
    }

    // ---------------------------------------------------------------- rep records

    private class BottomSnap(
        val thetaL: Double? = null,
        val thetaR: Double? = null,
        val thetaImgL: Double? = null,
        val thetaImgR: Double? = null,
        val q: Double? = null,
        val s2d: Double? = null,
        val psi: Double? = null,
    )

    private fun snapBottom() {
        bottom = BottomSnap(thetaL.usable, thetaR.usable, imageThetaL(), imageThetaR(), fQ.usable, fS2d.usable, fPsi.usable)
    }

    private class RepBuilder(val startMs: Long, val bottom: BottomSnap) {
        var index = -1
        var topMs = startMs
        var endMs: Long? = null
        var reason = ReasonCode.COUNTED
        var flags = 0
        var thetaTopL: Double? = null
        var thetaTopR: Double? = null
        var peak: Double? = null
        var kipH = 0.0
        var minTheta: Double? = null
        var durationMs: Long? = null
        var latencyPath = LatencyPath.NORMAL
        var usedHold = false
        var wElMax: Double? = null
        var tElMaxMs: Long? = null
        var minThetaImgL: Double? = null
        var tMinThetaImgL: Long? = null
        var minThetaImgR: Double? = null
        var tMinThetaImgR: Long? = null
        var depressOnsetMs: Long? = null

        fun toMetrics() = RepMetrics(
            index = index,
            startMs = startMs,
            topMs = topMs,
            endMs = endMs,
            reason = reason,
            flags = flags,
            thetaBottomL = bottom.thetaL,
            thetaBottomR = bottom.thetaR,
            thetaTopL = thetaTopL,
            thetaTopR = thetaTopR,
            hPeak = peak,
            kipH = kipH,
            durationMs = durationMs,
            latencyPath = latencyPath,
        )
    }

    /** Per-frame PULLING trackers: min theta (A5), H(rep) (G1), hold usage, §5.7 timings. */
    private fun updatePulling(r: RepBuilder, ts: Long, theta: Double?, top: TopEval) {
        theta?.let { r.minTheta = minOf(r.minTheta ?: it, it) }
        fPx.usable?.let { r.kipH = maxOf(r.kipH, abs(it)) }
        (if (back) fE else fH).usable?.let { r.peak = maxOf(r.peak ?: it, it) }
        if (top.held) r.usedHold = true
        if (!back) return
        fWEl.usable?.let { w ->
            if (r.wElMax == null || w > r.wElMax!!) {
                r.wElMax = w
                r.tElMaxMs = ts - r.startMs
            }
        }
        imageThetaL()?.let { t ->
            if (r.minThetaImgL == null || t < r.minThetaImgL!!) {
                r.minThetaImgL = t
                r.tMinThetaImgL = ts
            }
        }
        imageThetaR()?.let { t ->
            if (r.minThetaImgR == null || t < r.minThetaImgR!!) {
                r.minThetaImgR = t
                r.tMinThetaImgR = ts
            }
        }
        val qCal = calibration?.qCal
        if (r.depressOnsetMs == null && qCal != null) {
            fQ.usable?.let { if (it >= qCal + cfg.depressOnsetDelta) r.depressOnsetMs = ts - r.startMs }
        }
    }

    private fun commitCandidate(ts: Long, reason: ReasonCode): RepBuilder {
        val r = candidate!!
        candidate = null
        commit(r, ts, reason)
        return r
    }

    private fun commit(r: RepBuilder, ts: Long, reason: ReasonCode) {
        r.index = reps.size
        r.topMs = ts
        r.reason = reason
        r.thetaTopL = thetaL.usable
        r.thetaTopR = thetaR.usable
        if (back) r.flags = r.flags or RepFlags.EST
        (if (back) fE else fH).usable?.let { v -> r.peak = maxOf(r.peak ?: v, v) }
        reps += r
    }

    private fun postureMetrics(r: RepBuilder): PostureMetrics {
        val qCal = calibration?.qCal
        val qTop = fQ.usable
        val qBottom = r.bottom.q
        val s2dTop = fS2d.usable
        val s2dBottom = r.bottom.s2d
        val tl = imageThetaL()
        val tr = imageThetaR()
        val tMinL = r.tMinThetaImgL
        val tMinR = r.tMinThetaImgR
        return PostureMetrics(
            repIndex = r.index,
            topSource = if (dwellRunAux) TopSource.SHOULDER_WRIST else TopSource.EAR,
            qBottom = qBottom,
            qTop = qTop,
            dqBottom = if (qBottom != null && qCal != null) qBottom - qCal else null,
            dqTop = if (qTop != null && qCal != null) qTop - qCal else null,
            depressOnsetMs = r.depressOnsetMs,
            wElTop = fWEl.usable,
            wElMax = r.wElMax,
            tElMaxMs = r.tElMaxMs,
            oL = fOL.usable,
            oR = fOR.usable,
            rho = if (s2dTop != null && s2dBottom != null && s2dBottom > 0) s2dTop / s2dBottom else null,
            dPsiDeg = fPsi.usable?.let { p -> r.bottom.psi?.let { p - it } },
            dThetaTop = if (tl != null && tr != null) tl - tr else null,
            dThetaBottom = r.bottom.thetaImgL?.let { l -> r.bottom.thetaImgR?.let { l - it } },
            shoulderTilt = fTilt.usable,
            elbowHeightDiff = fElbowDiff.usable,
            dtLRMs = if (tMinL != null && tMinR != null) tMinL - tMinR else null,
        )
    }

    // ---------------------------------------------------------------- result

    private fun result(): SessionResult {
        val repList = reps.map { it.toMetrics() }
        val c = calibration
        val n = repList.count { it.reason == ReasonCode.COUNTED && !it.has(RepFlags.NO_END) }
        val reasons = mutableListOf<String>()
        if (back) reasons += "POSTURE_MODE"
        if (practiceFront) reasons += "PRACTICE_MODE"
        if (c == null) reasons += "NOT_CALIBRATED"
        if (angleCheck == AngleCheck.SKIPPED) reasons += "ANGLE_CHECK_SKIPPED"
        if (repList.any { it.has(RepFlags.TOO_FAST) }) reasons += "REP_TOO_FAST"
        val timeline = c?.let {
            buildTimeline(
                reps = repList,
                sessionStartMs = it.calibratedAtMs,
                endMs = lastTs ?: it.calibratedAtMs,
                fpsP50 = median(dts.filter { d -> d > 0 }.map { d -> 1000.0 / d }) ?: 0.0,
                betaDeg = it.betaBarDeg,
                captureMode = captureMode,
                countMode = countMode,
                source = source,
                viewSuspect = it.viewSuspect,
                cfg = cfg,
                appVersion = options.appVersion,
                engine = options.engine,
            )
        }
        return SessionResult(
            captureMode = captureMode,
            countMode = countMode,
            finalState = state,
            count = count,
            validCount = n,
            estimated = back,
            reps = repList,
            posture = posture.toList(),
            calibration = c,
            angleCheck = angleCheck,
            viewSuspect = c?.viewSuspect == true,
            warnings = warnings.toList(),
            leaderboardEligible = reasons.isEmpty(),
            ineligibleReasons = reasons,
            events = events.toList(),
            timeline = timeline,
            nonMonotonicDropped = nonMonotonic,
        )
    }

    private companion object {
        /** Used only when the front calibration window never saw the face (D2 §13.1 default hang h). */
        const val DEFAULT_H_HANG = -1.2
    }
}
