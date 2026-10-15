package pullup.core.eval

import pullup.core.rep.CaptureMode
import pullup.core.rep.CountMode
import pullup.core.rep.DetectorOptions
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
 * blocked calibration is retried automatically. Prints only; writes nothing.
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

    for (file in files) {
        val lf = LandmarkJson.read(file)
        val metaFile = File(metaDir, "${lf.video}.json")
        val meta = if (metaFile.exists()) LandmarkJson.readMeta(metaFile) else null
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
            lf.frames.forEach { detector.process(it) }
            val r = detector.finish()
            val label = if (capture == CaptureMode.BACK_POSTURE) "BACK_POSTURE" else "FRONT_${countMode.name}"
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
            append(" cal@%dms S=%.0fpx psi=%.0f° thetaHang=%.0f°".format(cal.calibratedAtMs, cal.s, cal.psiDeg, cal.thetaHangDeg))
            append(" cues=").append(listOf(cal.viewCues.vz, cal.viewCues.vx, cal.viewCues.vn).joinToString("/") { it.name.take(1) })
        } else {
            append(" NOT_CALIBRATED")
        }
        if (pauses > 0) append(" paused=").append(pauses)
        if (stop != null) append(" stop=").append(stop.cause).append("@").append(stop.tsMs)
    }
}
