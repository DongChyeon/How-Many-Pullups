package pullup.core.rep

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Synthetic landmark generator for the D2 §13 golden cases.
 *
 * D2 §13.1 specifies feature-level tracks (theta, h, a, px, e, q). This builds a 33-point pose whose
 * extracted features reproduce those tracks exactly, so the whole landmark -> feature -> state machine
 * path is exercised.
 */
internal object Synth {
    const val W = 1080
    const val H = 1280
    const val S_PX = 160.0
    const val CX = 400.0
    const val Y_BAR = 384.0 // 0.3 * H: wrists in the upper half (§6.1)
    const val VFOV = 60.0

    fun ts(n: Int): Long = n * 1000L / 30

    /** Capture meta giving β_bar = [betaDeg] for the synthetic bar height (§3.3). */
    fun meta(betaDeg: Double = 10.0): CaptureMeta {
        val off = atan((0.5 - Y_BAR / H) * 2.0 * tan(VFOV / 2.0 * kotlin.math.PI / 180.0)) * 180.0 / kotlin.math.PI
        return CaptureMeta(phiCamDeg = betaDeg - off, vfovDeg = VFOV)
    }

    /** a derived from h on the R template's line (h -1.2 <-> a 1.5, h 0.2 <-> a 0.3). */
    fun aFromH(h: Double): Double = 1.5 - (h + 1.2) * (1.2 / 1.4)
}

/** Piecewise-linear keyframes over relative ms. */
internal class Keys(vararg val pts: Pair<Int, Double>) {
    val start get() = pts.first().first
    val end get() = pts.last().first

    fun at(rel: Double): Double {
        if (rel <= pts.first().first) return pts.first().second
        for (i in 1 until pts.size) {
            val (t1, v1) = pts[i]
            val (t0, v0) = pts[i - 1]
            if (rel <= t1) return if (t1 == t0) v1 else v0 + (v1 - v0) * (rel - t0) / (t1 - t0)
        }
        return pts.last().second
    }
}

/** A scalar track: base value plus keyframe segments anchored at absolute t0. */
internal class Track(private val base: Double) {
    private val segs = mutableListOf<Pair<Long, Keys>>()
    fun add(t0: Long, keys: Keys) = apply { segs += t0 to keys }
    fun value(t: Long): Double {
        for ((t0, k) in segs.asReversed()) {
            val rel = (t - t0).toDouble()
            if (rel >= k.start && rel <= k.end) return k.at(rel)
        }
        return base
    }
}

internal enum class Group { FACE, EAR, SHOULDER, ARM, HIP }

internal enum class Cue { FRONT, BACK }

/** Pixel-space body description for one frame. */
internal data class Body(
    val theta: Double,
    val h: Double = -1.2,
    val a: Double = 1.5,
    val px: Double = 0.0,
    val e: Double = -0.9,
    val q: Double = 0.30,
    /** true: shoulders placed from e and q (back geometry, §13.1 G14-G16); false: from a (front). */
    val backGeometry: Boolean = false,
    val shoulderOrder: Cue = Cue.FRONT,
    val noseDepth: Cue = Cue.FRONT,
    val psiDeg: Double = 0.0,
    val vis: Map<Group, Double> = emptyMap(),
    val cx: Double = Synth.CX,
    val s: Double = Synth.S_PX,
)

internal fun Body.landmarks(): List<Landmark> {
    val pts = Array(Lm.COUNT) { doubleArrayOf(cx, Synth.Y_BAR + 2 * s, 0.0) }
    val s2d = s * cos(psiDeg * kotlin.math.PI / 180.0)
    val yBar = Synth.Y_BAR
    val yEar: Double
    val ySh: Double
    if (backGeometry) {
        yEar = yBar - e * s
        ySh = yEar + q * s
    } else {
        ySh = yBar + a * s
        yEar = 0.0 // set below from the face
    }
    // Label-left (11) on the image right in a front view (§3.4 V-x).
    val leftSign = if (shoulderOrder == Cue.FRONT) 1.0 else -1.0
    fun side(label: Int) = if (label == 0) leftSign else -leftSign
    val shoulders = intArrayOf(Lm.L_SHOULDER, Lm.R_SHOULDER)
    val elbows = intArrayOf(Lm.L_ELBOW, Lm.R_ELBOW)
    val wrists = intArrayOf(Lm.L_WRIST, Lm.R_WRIST)
    val hips = intArrayOf(Lm.L_HIP, Lm.R_HIP)
    val zShoulderDelta = (s2d / Synth.W) * tan(psiDeg * kotlin.math.PI / 180.0)
    for (i in 0..1) {
        val sd = side(i)
        val sx = cx + sd * s2d / 2
        val wx = sx + sd * 0.3 * s
        pts[shoulders[i]] = doubleArrayOf(sx, ySh, if (i == 0) zShoulderDelta else 0.0)
        pts[wrists[i]] = doubleArrayOf(wx, yBar, 0.0)
        // Isosceles elbow giving exactly theta at the elbow, bent outward.
        val dx = wx - sx
        val dy = yBar - ySh
        val d = sqrt(dx * dx + dy * dy)
        val half = theta / 2 * kotlin.math.PI / 180.0
        val armLen = d / (2 * sin(half))
        val off = sqrt((armLen * armLen - d * d / 4).coerceAtLeast(0.0))
        var nx = -dy / d
        var ny = dx / d
        if (nx * sd < 0) {
            nx = -nx
            ny = -ny
        }
        pts[elbows[i]] = doubleArrayOf((sx + wx) / 2 + nx * off, (ySh + yBar) / 2 + ny * off, 0.0)
        pts[hips[i]] = doubleArrayOf(cx + px * s + sd * 0.35 * s, ySh + 1.6 * s, 0.0)
    }
    val noseZ = if (noseDepth == Cue.FRONT) -0.1 else 0.1
    if (backGeometry) {
        for (i in intArrayOf(Lm.L_EAR, Lm.R_EAR)) pts[i] = doubleArrayOf(cx + (if (i == Lm.L_EAR) leftSign else -leftSign) * 0.35 * s, yEar, 0.0)
        pts[Lm.NOSE] = doubleArrayOf(cx, yEar + 0.05 * s, noseZ)
        pts[Lm.MOUTH_L] = doubleArrayOf(cx + 0.1 * s, yEar + 0.15 * s, noseZ)
        pts[Lm.MOUTH_R] = doubleArrayOf(cx - 0.1 * s, yEar + 0.15 * s, noseZ)
    } else {
        val yChin = yBar - h * s
        val yMouth = yChin - 0.12 * s
        val yNose = yMouth - 0.10 * s
        pts[Lm.MOUTH_L] = doubleArrayOf(cx + 0.1 * s, yMouth, noseZ)
        pts[Lm.MOUTH_R] = doubleArrayOf(cx - 0.1 * s, yMouth, noseZ)
        pts[Lm.NOSE] = doubleArrayOf(cx, yNose, noseZ)
        for (i in intArrayOf(Lm.L_EAR, Lm.R_EAR)) pts[i] = doubleArrayOf(cx + (if (i == Lm.L_EAR) leftSign else -leftSign) * 0.35 * s, yNose, 0.0)
    }
    for (i in 1..6) pts[i] = doubleArrayOf(pts[Lm.NOSE][0], pts[Lm.NOSE][1] - 0.05 * s, pts[Lm.NOSE][2])

    fun groupOf(i: Int): Group? = when (i) {
        in 0..6, Lm.MOUTH_L, Lm.MOUTH_R -> Group.FACE
        Lm.L_EAR, Lm.R_EAR -> Group.EAR
        Lm.L_SHOULDER, Lm.R_SHOULDER -> Group.SHOULDER
        in Lm.L_ELBOW..Lm.R_WRIST -> Group.ARM
        Lm.L_HIP, Lm.R_HIP -> Group.HIP
        else -> null
    }
    return List(Lm.COUNT) { i ->
        val v = groupOf(i)?.let { vis[it] } ?: 0.95
        Landmark(
            x = (pts[i][0] / Synth.W).toFloat(),
            y = (pts[i][1] / Synth.H).toFloat(),
            z = pts[i][2].toFloat(),
            visibility = v.toFloat(),
            presence = v.toFloat(),
        )
    }
}

/**
 * World landmarks (meters, hip-centered in MediaPipe; here centered on the trunk x / bar y, which only shifts
 * them) for the same body: pixel offsets scaled so the shoulder width S is [SHOULDER_M] m. Depth uses the
 * same pixel-space z the normalized landmarks encode (z_norm * W), so ψ and V-z agree between the two.
 */
internal fun Body.worldLandmarks(): List<Landmark> = landmarks().map {
    val m = SHOULDER_M / s
    it.copy(
        x = ((it.x * Synth.W - cx) * m).toFloat(),
        y = ((it.y * Synth.H - Synth.Y_BAR) * m).toFloat(),
        z = (it.z * Synth.W * m).toFloat(),
    )
}

private const val SHOULDER_M = 0.4

/**
 * A scripted session: tracks + visibility windows + optional second person.
 * [hangTheta] is the natural dead-hang elbow angle (θ_hang); templates start and end there.
 * [world] = false omits world landmarks (normalized-z ψ fallback, §4.3).
 */
internal class Scenario(val endMs: Long, val back: Boolean = false, val hangTheta: Double = 170.0, val world: Boolean = true) {
    val theta = Track(hangTheta)
    val h = Track(-1.2)
    val a = Track(1.5)
    val px = Track(0.0)
    val e = Track(-0.9)
    val q = Track(0.30)
    var shoulderOrder = if (back) Cue.BACK else Cue.FRONT
    var noseDepth = if (back) Cue.BACK else Cue.FRONT
    var psiDeg = 0.0
    var baseVis: Map<Group, Double> =
        if (back) mapOf(Group.FACE to 0.1, Group.EAR to 0.9, Group.SHOULDER to 0.95, Group.ARM to 0.95, Group.HIP to 0.95)
        else Group.entries.associateWith { 0.95 }
    private val visWindows = mutableListOf<Triple<LongRange, Set<Group>, Double>>()
    var others: (Long) -> List<Pose> = { emptyList() }

    /** Visibility [v] for [groups] on [fromMs, untilMs). */
    fun occlude(fromMs: Long, untilMs: Long, groups: Set<Group>, v: Double = 0.1) = apply {
        visWindows += Triple(fromMs until untilMs, groups, v)
    }

    /** Standard front template R(t0, P) (§13.1). */
    fun rep(t0: Long, p: Int = 300) = apply {
        theta.add(t0, Keys(0 to hangTheta, 600 to 40.0, 600 + p to 40.0, 1200 + p to hangTheta))
        h.add(t0, Keys(0 to -1.2, 600 to 0.2, 600 + p to 0.2, 1200 + p to -1.2))
        a.add(t0, Keys(0 to 1.5, 600 to 0.3, 600 + p to 0.3, 1200 + p to 1.5))
    }

    /** Back template R_b(t0, P) (§13.1): h replaced by e. */
    fun repBack(t0: Long, p: Int = 300) = apply {
        theta.add(t0, Keys(0 to hangTheta, 600 to 40.0, 600 + p to 40.0, 1200 + p to hangTheta))
        e.add(t0, Keys(0 to -0.9, 600 to 0.5, 600 + p to 0.5, 1200 + p to -0.9))
    }

    fun body(t: Long): Body {
        val vis = baseVis.toMutableMap()
        for ((range, groups, v) in visWindows) if (t in range) groups.forEach { vis[it] = v }
        return Body(
            theta = theta.value(t),
            h = h.value(t),
            a = a.value(t),
            px = px.value(t),
            e = e.value(t),
            q = q.value(t),
            backGeometry = back,
            shoulderOrder = shoulderOrder,
            noseDepth = noseDepth,
            psiDeg = psiDeg,
            vis = vis,
        )
    }

    fun frames(): List<PoseFrame> {
        val out = mutableListOf<PoseFrame>()
        var n = 0
        while (true) {
            val t = Synth.ts(n++)
            if (t > endMs) break
            val b = body(t)
            out += PoseFrame(t, b.landmarks(), Synth.W, Synth.H, worldLandmarks = if (world) b.worldLandmarks() else null, otherPoses = others(t))
        }
        return out
    }

    fun run(
        mode: CaptureMode = if (back) CaptureMode.BACK_POSTURE else CaptureMode.FRONT_MEASURE,
        countMode: CountMode = CountMode.STRICT,
        meta: CaptureMeta? = Synth.meta(),
    ): SessionResult {
        val d = RepDetector(mode, countMode, meta)
        d.start()
        frames().forEach { d.process(it) }
        return d.finish()
    }
}
