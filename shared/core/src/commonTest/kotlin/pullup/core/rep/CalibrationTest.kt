package pullup.core.rep

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D2 §2.1 coordinates, §4 calibration and S normalization, §5.2/§5.4 EMA and hold. */
class CalibrationTest {

    private fun near(expected: Double, actual: Double?, tol: Double = 1e-3, what: String = "") {
        assertTrue(actual != null && abs(expected - actual) <= tol, "$what expected $expected, got $actual")
    }

    private fun calibrate(
        meta: CaptureMeta? = Synth.meta(),
        countMode: CountMode = CountMode.STRICT,
        source: SourceKind = SourceKind.REPLAY,
        frames: Int = 31, // ts 0..1000: exactly the T_cal window
        frame: (Long) -> PoseFrame,
    ): Pair<RepDetector, List<RepEvent>> {
        val d = RepDetector(CaptureMode.FRONT_MEASURE, countMode, meta, source)
        d.start()
        val events = (0 until frames).flatMap { d.process(frame(Synth.ts(it))) }
        return d to events
    }

    private fun hangFrame(t: Long, body: Body = Body(theta = 170.0)) = PoseFrame(t, body.landmarks(), Synth.W, Synth.H)

    @Test
    fun calibration_values_are_medians_in_pixel_space() {
        val (d, events) = calibrate { hangFrame(it) }
        val c = d.calibration!!
        near(Synth.S_PX, c.s, what = "S")
        near(Synth.S_PX, c.s2d, what = "S_2d")
        near(0.0, c.psiDeg, what = "psi")
        near(Synth.Y_BAR, c.yBarPx, what = "y_bar px")
        near(0.3, c.yBarNorm, what = "y_bar norm")
        near(170.0, c.thetaHangDeg, what = "theta_hang")
        near(-1.2, c.hHang, what = "h_hang")
        near(1.5, c.aHang, what = "a_hang")
        near(Synth.CX, c.pxRefPx, what = "px_ref")
        near(10.0, c.betaBarDeg, tol = 1e-3, what = "beta_bar")
        assertEquals(RepState.HANG_WAIT, d.state)
        assertEquals(1000, events.filterIsInstance<RepEvent.CalibrationOk>().single().tsMs)
    }

    @Test
    fun s_uses_pixel_space_regardless_of_aspect_ratio() {
        // Same body on a square 1280x1280 frame: normalized x differs, pixel S does not.
        val body = Body(theta = 170.0)
        val (d, _) = calibrate { t ->
            val lms = body.landmarks().map { it.copy(x = (it.x * Synth.W / 1280f)) }
            PoseFrame(t, lms, 1280, Synth.H)
        }
        near(Synth.S_PX, d.calibration!!.s, tol = 1e-2, what = "S on square frame")
    }

    @Test
    fun s_is_yaw_corrected_and_floored() {
        val (d15, _) = calibrate { hangFrame(it, Body(theta = 170.0, psiDeg = 15.0)) }
        near(15.0, d15.calibration!!.psiDeg, tol = 0.05, what = "psi")
        near(Synth.S_PX * cos(15.0 * kotlin.math.PI / 180), d15.calibration!!.s2d, tol = 1e-2, what = "S_2d")
        near(Synth.S_PX, d15.calibration!!.s, tol = 1e-2, what = "S = S_2d / cos psi")

        // psi 70 deg: cos < 0.5, so S = S_2d / 0.5 (G1 floor); practice so SIDE_VIEW only warns.
        val (d70, ev) = calibrate(countMode = CountMode.PRACTICE) { hangFrame(it, Body(theta = 170.0, psiDeg = 70.0)) }
        val c = d70.calibration!!
        near(c.s2d / 0.5, c.s, tol = 1e-6, what = "floored S")
        assertTrue(ev.any { it is RepEvent.Warning && it.type == WarningType.SIDE_VIEW })
    }

    @Test
    fun world_landmarks_take_precedence_for_yaw() {
        val (d, _) = calibrate(countMode = CountMode.PRACTICE) { t ->
            val lms = Body(theta = 170.0).landmarks()
            val world = lms.mapIndexed { i, l ->
                when (i) {
                    Lm.L_SHOULDER -> Landmark(0.2f, 0f, 0.2f)
                    Lm.R_SHOULDER -> Landmark(-0.2f, 0f, 0f)
                    else -> l
                }
            }
            PoseFrame(t, lms, Synth.W, Synth.H, worldLandmarks = world)
        }
        near(26.565, d.calibration!!.psiDeg, tol = 0.01, what = "psi from world (atan(0.2/0.4))")
        assertTrue(d.calibration!!.psiFromWorld)
    }

    @Test
    fun chin_falls_back_to_nose_when_mouth_missing() {
        val (d, _) = calibrate { t ->
            val lms = Body(theta = 170.0).landmarks().mapIndexed { i, l ->
                if (i == Lm.MOUTH_L || i == Lm.MOUTH_R) l.copy(visibility = 0f) else l
            }
            PoseFrame(t, lms, Synth.W, Synth.H)
        }
        near(-1.2, d.calibration!!.hHang, tol = 1e-3, what = "h_hang via nose + (c_chin + c_nm) S")
    }

    @Test
    fun missing_capture_meta_skips_angle_check() {
        val (replay, ev) = calibrate(meta = null) { hangFrame(it) }
        assertEquals(RepState.HANG_WAIT, replay.state)
        assertNull(replay.calibration!!.betaBarDeg)
        assertTrue(ev.any { it is RepEvent.CalibrationOk && it.angleCheck == AngleCheck.SKIPPED })
        assertTrue(ev.any { it is RepEvent.Warning && it.type == WarningType.ANGLE_CHECK_SKIPPED })
        val result = replay.finish()
        assertTrue("ANGLE_CHECK_SKIPPED" in result.ineligibleReasons)
        assertTrue(result.timeline!!.json.contains("\"beta\":null"))

        val (camera, camEv) = calibrate(meta = null, source = SourceKind.CAMERA) { hangFrame(it) }
        assertEquals(RepState.IDLE, camera.state)
        assertEquals(
            listOf(CalBlockReason.ANGLE_UNMEASURABLE),
            camEv.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons,
        )
    }

    @Test
    fun no_full_hang_blocks_strict_and_warns_practice() {
        // θ_hang 125° < θ_floor_abs 120° + margin 10°: a bent-arm calibration.
        val (strict, ev) = calibrate { hangFrame(it, Body(theta = 125.0)) }
        assertEquals(RepState.IDLE, strict.state)
        assertTrue(CalBlockReason.NO_FULL_HANG in ev.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons)

        val (practice, pev) = calibrate(countMode = CountMode.PRACTICE) { hangFrame(it, Body(theta = 125.0)) }
        assertEquals(RepState.HANG_WAIT, practice.state)
        assertTrue(pev.any { it is RepEvent.Warning && it.type == WarningType.NO_FULL_HANG })
        near(120.0, practice.calibration!!.thetaExtDeg, what = "θ_ext falls back to the absolute floor")
    }

    @Test
    fun low_natural_hang_calibrates_with_relative_theta_ext() {
        // 150° used to block (absolute θ_ext 160° − 5°); now θ_ext = 150° − Δ_ext 10° = 140°.
        val (d, ev) = calibrate { hangFrame(it, Body(theta = 150.0)) }
        assertEquals(RepState.HANG_WAIT, d.state)
        assertTrue(ev.none { it is RepEvent.Warning && it.type == WarningType.NO_FULL_HANG })
        near(150.0, d.calibration!!.thetaHangDeg, what = "θ_hang")
        near(140.0, d.calibration!!.thetaExtDeg, what = "θ_ext")
        assertTrue(d.finish().timeline!!.json.contains("\"te\":140"))
    }

    @Test
    fun theta_ext_is_relative_with_absolute_floor() {
        val cfg = RepConfig()
        near(160.0, cfg.thetaExtFor(170.0), what = "170 - 10")
        near(135.0, cfg.thetaExtFor(145.0), what = "145 - 10")
        near(120.0, cfg.thetaExtFor(125.0), what = "floor")
        near(150.0, RepConfig(deltaExtDeg = 20.0).thetaExtFor(170.0), what = "tunable Δ_ext")
    }

    @Test
    fun side_view_from_normalized_z_only_warns_in_strict() {
        // No world landmarks: ψ comes from normalized z (unreliable), so strict warns instead of blocking.
        val (fallback, ev) = calibrate { hangFrame(it, Body(theta = 170.0, psiDeg = 40.0)) }
        assertEquals(RepState.HANG_WAIT, fallback.state)
        assertTrue(!fallback.calibration!!.psiFromWorld)
        assertTrue(ev.any { it is RepEvent.Warning && it.type == WarningType.SIDE_VIEW })
        assertTrue(ev.none { it is RepEvent.CalibrationBlocked })

        // Same body with world landmarks: ψ is trusted and strict blocks.
        val (world, wev) = calibrate { t ->
            val b = Body(theta = 170.0, psiDeg = 40.0)
            PoseFrame(t, b.landmarks(), Synth.W, Synth.H, worldLandmarks = b.worldLandmarks())
        }
        assertEquals(RepState.IDLE, world.state)
        assertTrue(CalBlockReason.SIDE_VIEW in wev.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons)
    }

    @Test
    fun world_landmarks_drive_view_depth_cue() {
        // Normalized z says "back" (nose behind ears) but world z says "front": world wins.
        val (d, _) = calibrate(countMode = CountMode.PRACTICE) { t ->
            val b = Body(theta = 170.0)
            val lms = b.landmarks().mapIndexed { i, l -> if (i == Lm.NOSE) l.copy(z = 0.1f) else l }
            PoseFrame(t, lms, Synth.W, Synth.H, worldLandmarks = b.worldLandmarks())
        }
        assertTrue(d.calibration!!.psiFromWorld)
        assertEquals(ViewVote.FRONT, d.calibration!!.viewCues.vz)
    }

    @Test
    fun unstable_wrists_do_not_calibrate() {
        // Whole body bobbing 0.2 S every other frame: wrist y std 0.1 S >= 0.05 S.
        val (d, _) = calibrate(frames = 90) { t ->
            val lms = Body(theta = 170.0).landmarks()
            val dy = if ((t / 33) % 2 == 0L) 0f else (0.2 * Synth.S_PX / Synth.H).toFloat()
            PoseFrame(t, lms.map { it.copy(y = it.y + dy) }, Synth.W, Synth.H)
        }
        assertEquals(RepState.CALIBRATING, d.state)
    }

    @Test
    fun hands_below_shoulders_do_not_calibrate() {
        // Standing with hands down: wrists below shoulders and in the lower half.
        val (d, _) = calibrate(frames = 90) { t ->
            val lms = Body(theta = 170.0, a = -1.5).landmarks().map { it.copy(y = it.y + 0.3f) }
            PoseFrame(t, lms, Synth.W, Synth.H)
        }
        assertEquals(RepState.CALIBRATING, d.state)
    }

    @Test
    fun non_monotonic_timestamps_are_dropped() {
        val d = RepDetector(CaptureMode.FRONT_MEASURE)
        d.start()
        d.process(hangFrame(100))
        assertTrue(d.process(hangFrame(100)).isEmpty())
        assertTrue(d.process(hangFrame(50)).isEmpty())
        assertEquals(2, d.finish().nonMonotonicDropped)
    }

    @Test
    fun ema_alpha_is_time_based() {
        near(0.5, emaAlpha(33, 48.0), tol = 0.01, what = "alpha at 30 fps")
        near(0.646, emaAlpha(50, 48.0), tol = 0.01, what = "alpha at 20 fps")
    }

    @Test
    fun held_ema_holds_then_expires_then_reinitializes() {
        val f = HeldEma(48.0, 300)
        f.update(0, 0, 1.0)
        assertEquals(FeatureStatus.OBSERVED, f.status)
        f.update(33, 33, null) // first missing frame
        assertEquals(FeatureStatus.HELD, f.status)
        f.update(300, 33, null) // 267 ms since first missing: still held
        assertEquals(FeatureStatus.HELD, f.status)
        near(1.0, f.usable, what = "held value keeps the EMA at the last raw value")
        f.update(333, 33, null) // 300 ms: cap reached
        assertEquals(FeatureStatus.UNAVAILABLE, f.status)
        assertNull(f.usable)
        f.update(366, 33, 5.0) // return after the cap: re-initialize, no lag from the old value
        assertEquals(5.0, f.usable)
    }

    @Test
    fun held_ema_continues_when_back_within_cap() {
        val f = HeldEma(48.0, 300)
        f.update(0, 0, 0.0)
        f.update(33, 33, null)
        f.update(66, 33, 1.0)
        val v = f.usable!!
        assertTrue(v > 0.0 && v < 1.0, "EMA continues (no re-init): $v")
    }

    @Test
    fun angle_helper() {
        near(90.0, angleDeg(1.0, 0.0, 0.0, 0.0, 0.0, 1.0), what = "right angle")
        near(180.0, angleDeg(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0), what = "straight")
        assertNull(angleDeg(0.0, 0.0, 0.0, 0.0, 1.0, 1.0))
    }
}
