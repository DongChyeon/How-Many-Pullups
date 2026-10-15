package pullup.core.rep

/**
 * All D2 thresholds in one place, initialized to the D2 §11 initial values.
 * Distances are multiples of S, times are ms, angles are degrees (D2 §1 unit rule).
 */
data class RepConfig(
    // §11.1 judging
    // A1: extension is relative to the calibrated hang. theta_ext = max(theta_hang - deltaExtDeg, thetaFloorAbsDeg).
    val deltaExtDeg: Double = 10.0, // A1
    val thetaFloorAbsDeg: Double = 120.0, // A1b
    val thetaHystDeg: Double = 10.0, // A2
    val thetaAttemptDeg: Double = 110.0, // A3
    val k: Double = 0.0, // B1
    val hTop: Double = 0.05, // B2
    val deltaFast: Double = 0.10, // B3
    val dAux: Double = 0.45, // B4
    val hAux: Double = 0.05, // B5
    val kappaKip: Double = 0.30, // B6
    val practiceTopRatio: Double = 0.6, // B7 (TOP)
    val practiceRearmRatio: Double = 0.4, // B7 (re-arm)
    val practiceLeaveRatio: Double = 0.45, // B8
    val tDwellMs: Long = 100, // T1
    val tFastMinMs: Long = 50, // T2
    val tExtHoldMs: Long = 50, // T3
    val tFloorMs: Long = 500, // T4
    val tRepMinMs: Long = 800, // T5
    // §11.2 signal processing, occlusion, tracking
    val visibilityMin: Double = 0.5, // V1
    val tauEmaMs: Double = 48.0, // E1
    val tHoldMs: Long = 300, // O1
    val tLostMs: Long = 1000, // O2
    val tFoundMs: Long = 300, // O3
    val tReleaseMs: Long = 2000, // O4
    val tPauseEndMs: Long = 30_000, // O5
    val tGapMs: Long = 200, // O6
    val tCalMs: Long = 1000, // O7
    val gTrack: Double = 1.0, // O8
    val refindRadius: Double = 1.5, // O9
    val widthTolerance: Double = 0.20, // O9
    val calWristStd: Double = 0.05, // O10
    val releaseDrop: Double = 0.5, // O11
    // §11.3 geometry and capture
    val betaMaxDeg: Double = 25.0, // C1
    val betaMinDeg: Double = -10.0, // C2
    val sideViewPsiDeg: Double = 20.0, // C3 (blocks in strict only when psi comes from world landmarks)
    val mEar: Double = 0.30, // P1
    val viewSuspectMinVotes: Int = 2, // C4
    val cosPsiFloor: Double = 0.5, // G1
    val noFullHangMarginDeg: Double = 10.0, // G2: NO_FULL_HANG when theta_hang < thetaFloorAbsDeg + this
    val cChin: Double = 0.12, // G3
    val cNm: Double = 0.10, // G4
    // Values D2 states in prose but not in §11
    val barOverlapWristDy: Double = 0.3, // §6.1 wrist y difference for "overlaps the bar"
    val depressOnsetDelta: Double = 0.05, // §5.7 (a)
    // Implementation choice (D2 gives no number): V-n votes FRONT when |nose x - ear-mid x| / S <= this
    val viewNoseOffsetMax: Double = 0.25,
) {
    /** Effective extension threshold theta_ext for a calibrated hang angle (D2 §4.1, §11 A1). */
    fun thetaExtFor(thetaHangDeg: Double): Double = maxOf(thetaHangDeg - deltaExtDeg, thetaFloorAbsDeg)
}
