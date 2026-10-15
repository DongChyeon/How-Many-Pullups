package pullup.core.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import pullup.core.rep.Landmark
import pullup.core.rep.PoseFrame
import java.io.File

/**
 * One landmark dump: `{"video", "width", "height", "fps", "model", "mediapipe", "frames": [{"t", "lm", "wlm"?}]}`.
 * `wlm` (world landmarks, meters, hip-centered) is optional so older dumps still parse.
 */
data class LandmarkFile(
    val video: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val model: String,
    val mediapipe: String,
    val frames: List<PoseFrame>,
)

/** Ground truth from `meta/<videoId>.json`. */
data class VideoMeta(
    val groundTruthReps: Int?,
    val view: String?,
    val intendedMode: String?,
    val excludeFromEval: Boolean = false,
    val excludeReason: String? = null,
)

object LandmarkJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): LandmarkFile {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val width = root.getValue("width").jsonPrimitive.int
        val height = root.getValue("height").jsonPrimitive.int
        val frames = root.getValue("frames").jsonArray.map { el ->
            val f = el.jsonObject
            val landmarks = points(f["lm"])
            PoseFrame(f.getValue("t").jsonPrimitive.long, landmarks, width, height, worldLandmarks = landmarks?.let { points(f["wlm"]) })
        }
        return LandmarkFile(
            video = root.getValue("video").jsonPrimitive.content,
            width = width,
            height = height,
            fps = root["fps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            model = root["model"]?.jsonPrimitive?.content ?: "unknown",
            mediapipe = root["mediapipe"]?.jsonPrimitive?.content ?: "unknown",
            frames = frames,
        )
    }

    private fun points(el: JsonElement?): List<Landmark>? =
        if (el == null || el is JsonNull) {
            null
        } else {
            (el as JsonArray).map { p ->
                val v = p.jsonArray
                Landmark(
                    x = v[0].jsonPrimitive.float,
                    y = v[1].jsonPrimitive.float,
                    z = v[2].jsonPrimitive.float,
                    visibility = v[3].jsonPrimitive.float,
                    presence = v[4].jsonPrimitive.float,
                )
            }
        }

    fun readMeta(file: File): VideoMeta {
        val root: JsonObject = json.parseToJsonElement(file.readText()).jsonObject
        fun str(key: String) = root[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        return VideoMeta(
            groundTruthReps = str("groundTruthReps")?.toIntOrNull(),
            view = str("view"),
            intendedMode = str("intendedMode"),
            excludeFromEval = str("excludeFromEval") == "true",
            excludeReason = str("excludeReason"),
        )
    }
}
