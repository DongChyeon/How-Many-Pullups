package pullup.core.rep

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** D2 §13.2 golden cases G1-G16, driven by synthetic landmarks (see [Synth]). */
class GoldenCasesTest {

    private val SessionResult.counts get() = events.filterIsInstance<RepEvent.Count>()
    private val SessionResult.noCounts get() = events.filterIsInstance<RepEvent.NoCount>()
    private fun SessionResult.transitionsTo(s: RepState) = events.filterIsInstance<RepEvent.StateChanged>().filter { it.to == s }

    private fun SessionResult.debug(): String =
        events.filter { it !is RepEvent.StateChanged || it.to != RepState.CALIBRATING }.joinToString("\n  ", prefix = "\n  ")

    private fun SessionResult.assertWithin(lo: Long, hi: Long, v: Long, what: String) {
        if (v !in lo..hi) fail("$what = $v not in [$lo, $hi]${debug()}")
    }

    private fun SessionResult.assertCount(expected: Int) {
        if (count != expected) fail("count = $count, expected $expected${debug()}")
    }

    /** §13.2 note: n equals reason-0 rows without NO_END, timestamps are monotonic, codes match. */
    private fun SessionResult.assertTimeline() {
        val tl = timeline ?: fail("no timeline")
        assertEquals(reps.count { it.reason == ReasonCode.COUNTED && !it.has(RepFlags.NO_END) }, tl.n)
        assertEquals(validCount, tl.n)
        assertEquals(reps.size, tl.rows.size)
        tl.rows.zip(reps).forEach { (row, rep) ->
            assertTrue(row[0] >= 0, "dS must be >= 0: $row")
            assertTrue(row[1] >= 0, "dT must be >= 0: $row")
            assertTrue(row[2] >= -1, "dE must be >= -1: $row")
            assertEquals(rep.code.toLong(), row.last())
        }
        assertTrue(tl.json.startsWith("{\"v\":1"))
        assertTrue(tl.sizeBytes <= 16 * 1024)
    }

    @Test
    fun g1_normal_three_reps() {
        val r = Scenario(8500).rep(2000).rep(4000).rep(6000).run()
        r.assertCount(3)
        assertEquals(listOf(1, 2, 3), r.counts.map { it.count })
        assertTrue(r.counts.all { it.voice == Voice.NUMBER && it.flags == 0 && it.latencyPath == LatencyPath.NORMAL })
        r.counts.zip(listOf(2000L, 4000L, 6000L)).forEach { (c, t0) -> r.assertWithin(t0 + 630, t0 + 720, c.tsMs, "COUNT") }
        r.reps.forEach { r.assertWithin(1350, 1480, it.durationMs!!, "rep duration") }
        assertTrue(r.noCounts.isEmpty())
        assertEquals(3, r.validCount)
        assertTrue(r.leaderboardEligible, r.ineligibleReasons.toString())
        r.assertTimeline()
    }

    private fun g2(): Scenario {
        val sc = Scenario(8500).rep(5200)
        sc.theta.add(2000, Keys(0 to 170.0, 600 to 40.0, 900 to 40.0, 1300 to 130.0, 1500 to 130.0, 1800 to 40.0, 2100 to 40.0, 2700 to 170.0))
        val hKeys = listOf(0 to -1.2, 600 to 0.2, 900 to 0.2, 1300 to -0.8, 1500 to -0.8, 1800 to 0.2, 2100 to 0.2, 2700 to -1.2)
        sc.h.add(2000, Keys(*hKeys.toTypedArray()))
        sc.a.add(2000, Keys(*hKeys.map { it.first to Synth.aFromH(it.second) }.toTypedArray()))
        return sc
    }

    @Test
    fun g2_half_rep() {
        val strict = g2().run()
        strict.assertCount(2)
        assertEquals(1, strict.noCounts.size, strict.debug())
        val nc = strict.noCounts.single()
        assertEquals(ReasonCode.INCOMPLETE_EXTENSION, nc.reason)
        assertEquals(Voice.NO_COUNT, nc.voice)
        // D2 says ~t0+1833 [1790, 1880]; raw h first reaches 0 at t0+1740, so DWELL_OK cannot precede
        // t0+1840. The EMA puts it at t0+1900 (see report: D2 window inconsistency).
        strict.assertWithin(2000 + 1840, 2000 + 1934, nc.tsMs, "N1")
        strict.assertTimeline()

        val practice = g2().run(countMode = CountMode.PRACTICE)
        practice.assertCount(3)
        assertTrue(practice.noCounts.isEmpty(), practice.debug())
        practice.assertTimeline()
    }

    private fun g3(): Scenario {
        val sc = Scenario(4500)
        sc.theta.add(2000, Keys(0 to 170.0, 600 to 50.0, 900 to 50.0, 1500 to 170.0))
        sc.h.add(2000, Keys(0 to -1.2, 600 to -0.15, 900 to -0.15, 1500 to -1.2))
        sc.a.add(2000, Keys(0 to 1.5, 600 to Synth.aFromH(-0.15), 900 to Synth.aFromH(-0.15), 1500 to 1.5))
        return sc
    }

    @Test
    fun g3_chin_below_bar() {
        val strict = g3().run()
        strict.assertCount(0)
        val nc = strict.noCounts.single()
        assertEquals(ReasonCode.CHIN_BELOW_BAR, nc.reason)
        assertEquals(Voice.NO_COUNT, nc.voice)
        strict.assertWithin(2000 + 1480, 2000 + 1600, nc.tsMs, "A5 at EXT return (~t0+1537)")
        strict.assertTimeline()

        val practice = g3().run(countMode = CountMode.PRACTICE)
        practice.assertCount(1)
        practice.assertWithin(2000 + 500, 2000 + 600, practice.counts.single().tsMs, "practice COUNT (~t0+545)")
    }

    private fun g4() = Scenario(4500).rep(2000).also {
        it.px.add(2000, Keys(0 to 0.0, 200 to 0.4, 400 to -0.4, 600 to 0.0))
    }

    @Test
    fun g4_kipping() {
        val strict = g4().run()
        strict.assertCount(0)
        val nc = strict.noCounts.single()
        assertEquals(ReasonCode.KIPPING, nc.reason)
        assertEquals(Voice.NO_COUNT, nc.voice)
        strict.assertWithin(2630, 2720, nc.tsMs, "KIPPING at DWELL_OK")
        assertTrue(strict.reps.single().kipH >= 0.30, "kipH=${strict.reps.single().kipH}")
        strict.assertTimeline()

        val practice = g4().run(countMode = CountMode.PRACTICE)
        practice.assertCount(1)
        assertTrue(practice.noCounts.isEmpty())
        assertTrue(practice.reps.single().kipH >= 0.30)
    }

    @Test
    fun g5_full_body_loss() {
        val r = Scenario(8500).rep(1500).rep(6000)
            .occlude(3500, 5000, Group.entries.toSet(), v = 0.0)
            .run()
        r.assertCount(2)
        assertTrue(r.noCounts.isEmpty(), r.debug())
        val paused = r.transitionsTo(RepState.PAUSED)
        assertEquals(1, paused.size)
        r.assertWithin(4480, 4540, paused.single().tsMs, "LOST")
        val found = r.events.filterIsInstance<RepEvent.StateChanged>().single { it.from == RepState.PAUSED }
        assertEquals(RepEventId.FOUND, found.trigger)
        r.assertWithin(5280, 5340, found.tsMs, "FOUND")
        val bottom = r.events.filterIsInstance<RepEvent.StateChanged>().first { it.tsMs > found.tsMs && it.to == RepState.BOTTOM }
        r.assertWithin(5300, 5380, bottom.tsMs, "BOTTOM after FOUND")
        r.assertWithin(6630, 6720, r.counts[1].tsMs, "COUNT2")
        r.assertTimeline()
    }

    @Test
    fun g6_face_occlusion_200ms_hold() {
        val r = Scenario(4500).rep(2000, p = 600).occlude(2480, 2680, setOf(Group.FACE)).run()
        r.assertCount(1)
        val c = r.counts.single()
        assertEquals(LatencyPath.HOLD, c.latencyPath)
        assertEquals(0, c.flags and RepFlags.AUX)
        r.assertWithin(2770, 2840, c.tsMs, "COUNT")
        r.assertTimeline()
    }

    @Test
    fun g7_face_occlusion_400ms_aux() {
        val r = Scenario(4500).rep(2000, p = 600).occlude(2480, 2880, setOf(Group.FACE)).run()
        r.assertCount(1)
        val c = r.counts.single()
        assertEquals(LatencyPath.AUX, c.latencyPath)
        assertTrue(c.flags and RepFlags.AUX != 0)
        r.assertWithin(2870, 2940, c.tsMs, "COUNT")
        assertEquals(16, r.reps.single().code and 16)
        r.assertTimeline()
    }

    /** Person B: 2.5 S to the right, 1.3x bounding box, theta 60-170 with 800 ms period. */
    private fun personB(t: Long): Pose {
        val phase = (t % 800) / 800.0
        val tri = if (phase < 0.5) phase * 2 else (1 - phase) * 2
        val body = Body(
            theta = 170.0 - 110.0 * tri,
            h = -1.2 + 1.4 * tri,
            a = Synth.aFromH(-1.2 + 1.4 * tri),
            cx = Synth.CX + 2.5 * Synth.S_PX,
            s = Synth.S_PX * 1.14,
            vis = Group.entries.associateWith { 0.95 },
        )
        return Pose(body.landmarks())
    }

    private fun g8(endMs: Long, bUntil: Long): Scenario = Scenario(endMs).rep(1500).rep(3500).also { sc ->
        sc.others = { t -> if (t in 3000 until bUntil) listOf(personB(t)) else emptyList() }
    }

    @Test
    fun g8_second_person() {
        val r = g8(6000, 5500).run()
        r.assertCount(2)
        r.assertWithin(2130, 2220, r.counts[0].tsMs, "COUNT1")
        r.assertWithin(4130, 4220, r.counts[1].tsMs, "COUNT2")
        assertTrue(r.transitionsTo(RepState.PAUSED).isEmpty())
        assertTrue(r.noCounts.isEmpty())
    }

    @Test
    fun g8b_second_person_target_leaves() {
        val r = g8(8000, Long.MAX_VALUE).occlude(5000, 99_999, Group.entries.toSet(), v = 0.0).run()
        r.assertCount(2)
        val paused = r.transitionsTo(RepState.PAUSED)
        assertEquals(1, paused.size)
        r.assertWithin(5980, 6040, paused.single().tsMs, "LOST")
        assertTrue(r.events.filterIsInstance<RepEvent.StateChanged>().none { it.trigger == RepEventId.FOUND }, r.debug())
        assertEquals(RepState.ENDED, r.finalState)
    }

    private fun g9(): Scenario {
        val sc = Scenario(4000)
        sc.theta.add(2000, Keys(0 to 170.0, 350 to 40.0, 700 to 170.0))
        val hk = listOf(0 to -1.2, 300 to -0.20, 333 to 0.12, 366 to 0.12, 400 to -0.20, 700 to -1.2)
        sc.h.add(2000, Keys(*hk.toTypedArray()))
        sc.a.add(2000, Keys(*hk.map { it.first to Synth.aFromH(it.second) }.toTypedArray()))
        return sc
    }

    @Test
    fun g9_fast_rep_fast_top() {
        val r = g9().run()
        r.assertCount(1)
        val c = r.counts.single()
        assertTrue(c.flags and RepFlags.FAST_TOP != 0)
        assertEquals(LatencyPath.FAST_TOP, c.latencyPath)
        assertEquals(2400, c.tsMs, r.debug())
        val rep = r.reps.single()
        r.assertWithin(620, 740, rep.durationMs!!, "rep duration")
        assertTrue(rep.has(RepFlags.FAST_REP))
        assertFalse(rep.has(RepFlags.TOO_FAST))
        r.assertTimeline()
    }

    @Test
    fun g10_single_frame_spike() {
        val sc = Scenario(4000)
        sc.theta.add(2000, Keys(0 to 170.0, 350 to 40.0, 700 to 170.0))
        val hk = listOf(0 to -1.2, 300 to -0.20, 333 to 0.12, 366 to -0.20, 700 to -1.2)
        sc.h.add(2000, Keys(*hk.toTypedArray()))
        sc.a.add(2000, Keys(*hk.map { it.first to Synth.aFromH(it.second) }.toTypedArray()))
        val r = sc.run()
        r.assertCount(0)
        val nc = r.noCounts.single()
        assertEquals(ReasonCode.CHIN_BELOW_BAR, nc.reason)
        assertEquals(Voice.NO_COUNT, nc.voice)
    }

    private fun g11() = Scenario(5000).rep(2000, p = 600).occlude(2480, 3000, setOf(Group.FACE, Group.ARM))

    @Test
    fun g11_undecidable() {
        val strict = g11().run()
        strict.assertCount(0)
        val nc = strict.noCounts.single()
        assertEquals(ReasonCode.UNDETERMINED_OCCLUSION, nc.reason)
        assertEquals(Voice.NEUTRAL_TONE, nc.voice)
        strict.assertWithin(2780, 2840, nc.tsMs, "N4")
        assertTrue(strict.transitionsTo(RepState.PAUSED).isEmpty())
        assertTrue(strict.transitionsTo(RepState.BOTTOM).any { it.tsMs > nc.tsMs }, strict.debug())
        strict.assertTimeline()

        val practice = g11().run(countMode = CountMode.PRACTICE)
        practice.assertCount(1)
        practice.assertWithin(2400, 2480, practice.counts.single().tsMs, "practice COUNT (~t0+440)")
        assertTrue(practice.noCounts.isEmpty(), practice.debug())
    }

    private fun calOnly(meta: CaptureMeta?, countMode: CountMode = CountMode.STRICT, configure: Scenario.() -> Unit = {}) =
        Scenario(1500).apply(configure).run(countMode = countMode, meta = meta)

    @Test
    fun g12_capture_blocks() {
        val a = calOnly(Synth.meta(32.0))
        assertEquals(listOf(CalBlockReason.LOW_ANGLE), a.events.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons)
        assertTrue(a.transitionsTo(RepState.IDLE).single().from == RepState.CALIBRATING)
        assertTrue(a.transitionsTo(RepState.HANG_WAIT).isEmpty())

        val b = calOnly(Synth.meta(-15.0))
        assertEquals(listOf(CalBlockReason.HIGH_ANGLE), b.events.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons)

        val c = calOnly(Synth.meta()) {
            shoulderOrder = Cue.BACK
            noseDepth = Cue.BACK
        }
        assertEquals(ViewCues(ViewVote.BACK, ViewVote.BACK, ViewVote.FRONT), c.calibration!!.viewCues)
        assertTrue(c.viewSuspect)
        assertTrue(WarningType.VIEW_MISMATCH_SUSPECTED in c.warnings)
        assertEquals(1, c.transitionsTo(RepState.HANG_WAIT).size)
        assertTrue(c.timeline!!.json.contains("\"vs\":1"))

        val d = calOnly(Synth.meta(20.0))
        assertEquals(1, d.transitionsTo(RepState.HANG_WAIT).size)
        assertEquals(AngleCheck.PASSED, d.angleCheck)
        val cal = d.events.filterIsInstance<RepEvent.CalibrationOk>().single()
        assertTrue(cal.tsMs in 980..1040, "CAL_OK at ${cal.tsMs}")

        // Practice: (a)(b) pass with a warning; (c) same as strict.
        val pa = calOnly(Synth.meta(32.0), CountMode.PRACTICE)
        assertEquals(1, pa.transitionsTo(RepState.HANG_WAIT).size)
        assertTrue(WarningType.LOW_ANGLE in pa.warnings)
        val pb = calOnly(Synth.meta(-15.0), CountMode.PRACTICE)
        assertTrue(WarningType.HIGH_ANGLE in pb.warnings)
        assertEquals(AngleCheck.WARNED, pb.angleCheck)
        val pc = calOnly(Synth.meta(), CountMode.PRACTICE) {
            shoulderOrder = Cue.BACK
            noseDepth = Cue.BACK
        }
        assertTrue(pc.viewSuspect)
    }

    @Test
    fun g13_rep_below_t_floor() {
        val sc = Scenario(4500)
        sc.theta.add(3000, Keys(0 to 170.0, 150 to 40.0, 300 to 170.0))
        val hk = listOf(0 to -1.2, 100 to -0.2, 133 to 0.15, 166 to 0.15, 200 to -0.2, 300 to -1.2)
        sc.h.add(3000, Keys(*hk.toTypedArray()))
        sc.a.add(3000, Keys(*hk.map { it.first to Synth.aFromH(it.second) }.toTypedArray()))
        val r = sc.run()
        r.assertCount(1)
        val c = r.counts.single()
        assertTrue(c.flags and RepFlags.FAST_TOP != 0)
        r.assertWithin(3180, 3220, c.tsMs, "COUNT (~t0+200)")
        val rep = r.reps.single()
        assertTrue(rep.has(RepFlags.TOO_FAST))
        r.assertWithin(290, 410, rep.durationMs!!, "rep duration")
        assertFalse(r.leaderboardEligible)
        assertTrue("REP_TOO_FAST" in r.ineligibleReasons)
        assertEquals(1, r.validCount) // still counted locally
    }

    @Test
    fun g14_back_normal_three_reps() {
        val r = Scenario(8500, back = true).repBack(2000).repBack(4000).repBack(6000).run()
        r.assertCount(3)
        assertTrue(r.estimated)
        assertTrue(r.counts.all { it.estimated && it.flags and RepFlags.EST != 0 })
        assertTrue(r.reps.all { it.has(RepFlags.EST) && !it.has(RepFlags.AUX) })
        assertTrue(r.noCounts.isEmpty(), r.debug())
        r.counts.zip(listOf(2000L, 4000L, 6000L)).forEach { (c, t0) -> r.assertWithin(t0 + 630, t0 + 720, c.tsMs, "COUNT") }
        assertEquals(3, r.posture.size)
        r.posture.forEach {
            assertEquals(TopSource.EAR, it.topSource)
            assertTrue(abs(it.dqTop!!) <= 0.02, "dqTop=${it.dqTop}")
        }
        assertFalse(r.leaderboardEligible)
        assertTrue(r.timeline!!.json.contains("\"mode\":\"B\""))
    }

    @Test
    fun g15_back_shrug() {
        val sc = Scenario(8500, back = true).repBack(2000).repBack(4000).repBack(6000)
        for (t0 in listOf(2000L, 4000L, 6000L)) sc.q.add(t0, Keys(0 to 0.30, 600 to 0.15, 900 to 0.15, 1500 to 0.30))
        val r = sc.run()
        r.assertCount(3)
        r.counts.zip(listOf(2000L, 4000L, 6000L)).forEach { (c, t0) -> r.assertWithin(t0 + 630, t0 + 720, c.tsMs, "COUNT") }
        r.posture.forEach {
            assertTrue(it.dqTop!! in -0.17..-0.12, "dqTop=${it.dqTop}")
            assertTrue(abs(it.dqBottom!!) <= 0.02, "dqBottom=${it.dqBottom}")
        }
    }

    @Test
    fun g16_mode_view_mismatch() {
        val a = Scenario(4500, back = true).repBack(2000).apply {
            shoulderOrder = Cue.FRONT
            noseDepth = Cue.FRONT
            baseVis = Group.entries.associateWith { 0.95 }
        }.run()
        assertTrue(a.viewSuspect)
        assertTrue(WarningType.VIEW_MISMATCH_SUSPECTED in a.warnings)
        assertEquals(1, a.transitionsTo(RepState.HANG_WAIT).size)
        a.assertCount(1)
        assertTrue(a.counts.single().estimated)

        val b = Scenario(1500, back = true).apply { psiDeg = 40.0 }.run()
        assertTrue(abs(b.calibration!!.psiDeg - 40.0) < 0.5, "psi=${b.calibration.psiDeg}")
        assertTrue(WarningType.SIDE_VIEW in b.warnings)
        assertEquals(1, b.transitionsTo(RepState.HANG_WAIT).size)
    }

    @Test
    fun front_strict_side_view_blocks() {
        val r = Scenario(1500).apply { psiDeg = 40.0 }.run()
        assertTrue(CalBlockReason.SIDE_VIEW in r.events.filterIsInstance<RepEvent.CalibrationBlocked>().single().reasons)
    }

    @Test
    fun deterministic_replay() {
        val a = Scenario(8500).rep(2000).rep(4000).rep(6000).run()
        val b = Scenario(8500).rep(2000).rep(4000).rep(6000).run()
        assertEquals(a.events, b.events)
        assertEquals(a.timeline, b.timeline)
    }
}
