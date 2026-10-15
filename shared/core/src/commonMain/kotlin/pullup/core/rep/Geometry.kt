package pullup.core.rep

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

internal fun Double.toDeg(): Double = this * 180.0 / PI
internal fun Double.toRad(): Double = this * PI / 180.0

/** Angle at [b] formed by a-b-c, in degrees; null when a side has zero length. */
internal fun angleDeg(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double? {
    val ux = ax - bx
    val uy = ay - by
    val vx = cx - bx
    val vy = cy - by
    val nu = hypot(ux, uy)
    val nv = hypot(vx, vy)
    if (nu == 0.0 || nv == 0.0) return null
    val c = ((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1.0, 1.0)
    return acos(c).toDeg()
}

internal fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}

internal fun stdDev(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.sum() / values.size
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/** Time-based EMA weight (D2 §5.2): alpha = 1 - exp(-dt / tau). */
internal fun emaAlpha(dtMs: Long, tauMs: Double): Double = 1.0 - exp(-dtMs.toDouble() / tauMs)

/**
 * A pose in pixel space. D2 §2.1 uses x' = x * W/H, y' = y (frame-height units); pixel space is the same
 * up to the constant factor H, so every ratio "distance / S" is identical.
 */
internal class PosePx(val pose: Pose, val width: Int, val height: Int, private val visMin: Double) {
    private val lm = pose.landmarks

    fun vis(i: Int): Boolean = i < lm.size && lm[i].visibility >= visMin
    fun x(i: Int): Double = lm[i].x.toDouble() * width
    fun y(i: Int): Double = lm[i].y.toDouble() * height
    fun yNorm(i: Int): Double = lm[i].y.toDouble()
    fun visibility(i: Int): Double = if (i < lm.size) lm[i].visibility.toDouble() else 0.0

    /** Depth for yaw / V-z: world z when available, else normalized z (same scale as normalized x). */
    fun depthPair(i: Int): Pair<Double, Double>? {
        val world = pose.worldLandmarks
        if (world != null && i < world.size) return world[i].x.toDouble() to world[i].z.toDouble()
        if (i >= lm.size) return null
        return lm[i].x.toDouble() to lm[i].z.toDouble()
    }

    /** Mean y of the visible landmarks among [a] and [b] (one-side fallback). */
    fun meanY(a: Int, b: Int): Double? = when {
        vis(a) && vis(b) -> (y(a) + y(b)) / 2.0
        vis(a) -> y(a)
        vis(b) -> y(b)
        else -> null
    }

    fun meanX(a: Int, b: Int): Double? = when {
        vis(a) && vis(b) -> (x(a) + x(b)) / 2.0
        vis(a) -> x(a)
        vis(b) -> x(b)
        else -> null
    }

    fun allVisible(vararg idx: Int): Boolean = idx.all { vis(it) }

    /** Apparent shoulder width S_2d in pixels (both shoulders required). */
    fun shoulderWidth(): Double? =
        if (vis(Lm.L_SHOULDER) && vis(Lm.R_SHOULDER)) {
            hypot(x(Lm.L_SHOULDER) - x(Lm.R_SHOULDER), y(Lm.L_SHOULDER) - y(Lm.R_SHOULDER))
        } else {
            null
        }

    /** Trunk center c: mean of the visible points among 11, 12, 23, 24 (D2 §6.1). */
    fun center(): Pair<Double, Double>? {
        val idx = intArrayOf(Lm.L_SHOULDER, Lm.R_SHOULDER, Lm.L_HIP, Lm.R_HIP).filter { vis(it) }
        if (idx.isEmpty()) return null
        return idx.sumOf { x(it) } / idx.size to idx.sumOf { y(it) } / idx.size
    }

    /** Bounding-box area of the SHOULDER and HIP points (D2 §6.1 "size"). */
    fun trunkArea(): Double {
        val idx = intArrayOf(Lm.L_SHOULDER, Lm.R_SHOULDER, Lm.L_HIP, Lm.R_HIP).filter { vis(it) }
        if (idx.isEmpty()) return 0.0
        val xs = idx.map { x(it) }
        val ys = idx.map { y(it) }
        return (xs.max() - xs.min()) * (ys.max() - ys.min())
    }

    /** Elbow angle of one arm (shoulder-elbow-wrist), degrees. */
    fun elbowAngle(shoulder: Int, elbow: Int, wrist: Int): Double? =
        if (allVisible(shoulder, elbow, wrist)) {
            angleDeg(x(shoulder), y(shoulder), x(elbow), y(elbow), x(wrist), y(wrist))
        } else {
            null
        }

    /** Body yaw psi (D2 §4.3), degrees. */
    fun yawDeg(): Double? {
        if (!vis(Lm.L_SHOULDER) || !vis(Lm.R_SHOULDER)) return null
        val l = depthPair(Lm.L_SHOULDER) ?: return null
        val r = depthPair(Lm.R_SHOULDER) ?: return null
        return kotlin.math.atan2(kotlin.math.abs(r.second - l.second), kotlin.math.abs(r.first - l.first)).toDeg()
    }
}
