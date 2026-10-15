package pullup.core.eval

import pullup.core.rep.CaptureMode
import pullup.core.rep.CountMode
import pullup.core.rep.DetectorOptions
import pullup.core.rep.Pose
import pullup.core.rep.PoseFrame
import pullup.core.rep.PosePx
import pullup.core.rep.chinY
import pullup.core.rep.meanTheta
import pullup.core.rep.RepDetector
import pullup.core.rep.RepEvent
import pullup.core.rep.RepState
import pullup.core.rep.SessionResult
import pullup.core.rep.SourceKind
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Replays landmark dumps through [RepDetector] and compares counts with ground truth.
 *
 * Usage: EvalLandmarks <landmarksDir> [metaDir]
 * metaDir defaults to `<landmarksDir>/../meta`. Front videos run FRONT_MEASURE STRICT and PRACTICE,
 * back videos run BACK_POSTURE. Replays have no captureMeta sidecar, so the angle check is SKIPPED, and a
 * blocked calibration is retried automatically. Videos whose meta has `excludeFromEval: true` are skipped and
 * listed. System property `eval.trace=<substring>` prints a per-frame trace (raw elbow angle, clearance h, state)
 * for matching files. Prints only; writes nothing.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: EvalLandmarks <landmarksDir> [metaDir]")
        exitProcess(2)
    }
    val dir = File(args[0])
    val metaDir = File(args.getOrNull(1) ?: File(dir, "../meta").path)
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
    if (files.isEmpty()) {
        System.err.println("no landmark files in ${dir.path}")
        exitProcess(1)
    }

    data class Row(val config: String, val model: String, val truth: Int, val count: Int)
    val rows = mutableListOf<Row>()
    val excluded = sortedMapOf<String, String?>()
    val trace = System.getProperty("eval.trace")?.takeIf { it.isNotBlank() }

    for (file in files) {
        val videoId = file.name.substringBefore('.')
        val metaFile = File(metaDir, "$videoId.json")
        val meta = if (metaFile.exists()) LandmarkJson.readMeta(metaFile) else null
        if (meta?.excludeFromEval == true) {
            excluded[videoId] = meta.excludeReason
            continue
        }
        val lf = LandmarkJson.read(file)
        val tracing = trace != null && file.name.contains(trace)
        val view = meta?.view ?: if (lf.video.contains("_back_")) "back" else "front"
        val configs = if (view == "back") {
            listOf(CaptureMode.BACK_POSTURE to CountMode.PRACTICE)
        } else {
            listOf(CaptureMode.FRONT_MEASURE to CountMode.STRICT, CaptureMode.FRONT_MEASURE to CountMode.PRACTICE)
        }
        val model = lf.model.removePrefix("pose_landmarker_")
        println("== ${lf.video} [$model, ${lf.width}x${lf.height}, ${lf.frames.size} frames, view=$view, truth=${meta?.groundTruthReps ?: "?"}]")
        for ((capture, countMode) in configs) {
            val detector = RepDetector(
                captureMode = capture,
                countMode = countMode,
                captureMeta = null,
                source = SourceKind.REPLAY,
                options = DetectorOptions(restartAfterCalBlock = true, engine = "mediapipe-${lf.model}/${lf.mediapipe}"),
            )
            detector.start()
            lf.frames.forEach {
                detector.process(it)
                if (tracing) println("    " + traceLine(label(capture, countMode), it, detector))
            }
            val r = detector.finish()
            val label = label(capture, countMode)
            println("  " + describe(label, r, meta?.groundTruthReps))
            meta?.groundTruthReps?.let { rows += Row(label, model, it, r.count) }
        }
    }

    println()
    println("== ±1 hit rate (count vs groundTruthReps)")
    for ((key, group) in rows.groupBy { it.config to it.model }.toSortedMap(compareBy({ it.first }, { it.second }))) {
        val hits = group.count { abs(it.count - it.truth) <= 1 }
        val mae = group.sumOf { abs(it.count - it.truth) }.toDouble() / group.size
        println("  %-16s %-5s %d/%d = %5.1f%%  MAE %.2f".format(key.first, key.second, hits, group.size, 100.0 * hits / group.size, mae))
    }
    val hits = rows.count { abs(it.count - it.truth) <= 1 }
    if (rows.isNotEmpty()) println("  overall              %d/%d = %5.1f%%".format(hits, rows.size, 100.0 * hits / rows.size))
    if (excluded.isNotEmpty()) {
        println()
        println("== excluded (excludeFromEval)")
        excluded.forEach { (id, reason) -> println("  $id: ${reason ?: "-"}") }
    }
}

private fun label(capture: CaptureMode, countMode: CountMode) =
    if (capture == CaptureMode.BACK_POSTURE) "BACK_POSTURE" else "FRONT_${countMode.name}"

/** Raw (unsmoothed) per-frame values for debugging: elbow angle, clearance h = (y_bar - y_chin) / S, state. */
private fun traceLine(label: String, frame: PoseFrame, d: RepDetector): String {
    val p = frame.landmarks?.let { PosePx(Pose(it, frame.worldLandmarks), frame.imageWidth, frame.imageHeight, d.config.visibilityMin) }
    val c = d.calibration
    val theta = p?.let { meanTheta(it) }
    val h = if (p != null && c != null) chinY(p, c.s, d.config)?.let { (c.yBarPx - it) / c.s } else null
    fun f(v: Double?, fmt: String) = v?.let { fmt.format(it) } ?: "-"
    return "%s t=%6d theta=%6s h=%6s ext=%5s state=%s".format(
        label.take(7), frame.timestampMs, f(theta, "%.1f"), f(h, "%.2f"), f(c?.thetaExtDeg, "%.0f"), d.state,
    )
}

private fun describe(label: String, r: SessionResult, truth: Int?): String {
    val err = truth?.let { r.count - it }
    val nc = r.events.filterIsInstance<RepEvent.NoCount>().groupingBy { it.reason.name }.eachCount()
    val blocks = r.events.filterIsInstance<RepEvent.CalibrationBlocked>().flatMap { it.reasons }.groupingBy { it.name }.eachCount()
    val pauses = r.events.count { it is RepEvent.StateChanged && it.to == RepState.PAUSED }
    val stop = r.events.filterIsInstance<RepEvent.Stopped>().lastOrNull()
    val cal = r.calibration
    return buildString {
        append("%-14s count=%-3d n=%-3d".format(label, r.count, r.validCount))
        append(" err=").append(err?.let { if (it > 0) "+$it" else "$it" } ?: "?")
        append(" nc=").append(nc)
        append(" warn=").append(r.warnings.distinct().map { it.name })
        if (blocks.isNotEmpty()) append(" calBlocks=").append(blocks)
        if (cal != null) {
            append(
                " cal@%dms S=%.0fpx psi=%.0f°(%s) thetaHang=%.0f° thetaExt=%.0f°".format(
                    cal.calibratedAtMs, cal.s, cal.psiDeg, if (cal.psiFromWorld) "world" else "norm", cal.thetaHangDeg, cal.thetaExtDeg,
                ),
            )
            append(" cues=").append(listOf(cal.viewCues.vz, cal.viewCues.vx, cal.viewCues.vn).joinToString("/") { it.name.take(1) })
        } else {
            append(" NOT_CALIBRATED")
        }
        if (pauses > 0) append(" paused=").append(pauses)
        if (stop != null) append(" stop=").append(stop.cause).append("@").append(stop.tsMs)
    }
}
