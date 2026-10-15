package pullup.core.rep

/**
 * One MediaPipe landmark in normalized image coordinates (D2 §2.1): x, y in [0, 1] of the
 * "portrait, un-mirrored" frame (D4 P7). z is MediaPipe's relative depth (roughly the scale of x).
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f,
    val presence: Float = 1f,
)

/** One detected person: 33 normalized landmarks plus optional world landmarks (meters, D2 §4.3). */
data class Pose(
    val landmarks: List<Landmark>,
    val worldLandmarks: List<Landmark>? = null,
)

/**
 * Input to [RepDetector]: one analyzed frame.
 *
 * @param timestampMs sensor timestamp or decoded PTS in ms; must increase strictly (D4 P4).
 * @param landmarks the primary pose (null = no person detected in this frame).
 * @param otherPoses additional people when the engine runs with numPoses > 1 (D2 §6).
 */
data class PoseFrame(
    val timestampMs: Long,
    val landmarks: List<Landmark>?,
    val imageWidth: Int,
    val imageHeight: Int,
    val worldLandmarks: List<Landmark>? = null,
    val otherPoses: List<Pose> = emptyList(),
) {
    internal val poses: List<Pose>
        get() = listOfNotNull(landmarks?.let { Pose(it, worldLandmarks) }) + otherPoses
}

/** User-selected capture mode (D2 §3.6). The selected mode always decides the rule set. */
enum class CaptureMode { FRONT_MEASURE, BACK_POSTURE }

/** Judging mode inside FRONT_MEASURE (D2 §1). BACK_POSTURE ignores it and behaves like practice (§5.6). */
enum class CountMode { STRICT, PRACTICE }

/** Where frames come from (D2 §2.2). Only affects the missing-captureMeta rule (§3.3). */
enum class SourceKind { CAMERA, REPLAY }

/** Recording sidecar (D2 §2.2): camera pitch (up = +) and vertical field of view, degrees. */
data class CaptureMeta(val phiCamDeg: Double, val vfovDeg: Double)

/** MediaPipe 33-point indices used by D2 (§2.1). */
internal object Lm {
    const val NOSE = 0
    const val L_EAR = 7
    const val R_EAR = 8
    const val MOUTH_L = 9
    const val MOUTH_R = 10
    const val L_SHOULDER = 11
    const val R_SHOULDER = 12
    const val L_ELBOW = 13
    const val R_ELBOW = 14
    const val L_WRIST = 15
    const val R_WRIST = 16
    const val L_HIP = 23
    const val R_HIP = 24
    const val COUNT = 33
}
