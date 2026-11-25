package com.example.llm

import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorHeadgear
import com.example.model.ActorRole
import com.example.model.CameraMotion
import com.example.model.CharacterPose
import com.example.model.CutawayStage
import com.example.model.HandProp
import com.example.model.HudWidget
import com.example.model.MetaphorVisual
import com.example.model.PointerCallout
import com.example.model.SceneScript
import com.example.model.ShotFraming
import com.example.model.ShotType
import com.example.model.StatusPill
import com.example.model.WorldFx
import org.json.JSONArray
import org.json.JSONObject

class MalformedSceneJsonException(message: String, cause: Throwable? = null) : Exception(message, cause)

object JsonSanitizer {

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val UNCLOSED_THINK_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_START = Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_END = Regex("```")

    private val REQUIRED_KEYS = listOf(
        "scene_id",
        "tagalog_narration",
        "background_prompt",
        "camera_motion",
        "actor_role",
        "actor_action",
        "world_fx"
    )

    fun extractJsonArrayText(rawOutput: String): String {
        val cleaned = rawOutput
            .replace(THINK_BLOCK_REGEX, "")
            .replace(UNCLOSED_THINK_REGEX, "")
            .replace(CODE_FENCE_START, "")
            .replace(CODE_FENCE_END, "")
            .trim()

        val firstBracket = cleaned.indexOf('[')
        if (firstBracket == -1) {
            throw MalformedSceneJsonException("No JSON array brackets found in LLM response")
        }

        return repairOrExtractArray(cleaned, firstBracket)
    }

    /**
     * Scans from the opening `[` while respecting JSON strings/escapes.
     * - If the outer `[...]` is cleanly closed (`bracketDepth == 0`), returns that exact slice.
     * - If the stream was cut off mid-object before closing `]`, locates the last cleanly
     *   closed top-level scene object (`}` where `braceDepth == 0` and `bracketDepth == 1`),
     *   trims any incomplete trailing partial object and trailing commas, and closes with `]`.
     */
    private fun repairOrExtractArray(cleaned: String, firstBracket: Int): String {
        var inString = false
        var escaped = false
        var braceDepth = 0
        var bracketDepth = 0
        var lastCompleteSceneObjectEnd = -1

        for (i in firstBracket until cleaned.length) {
            val ch = cleaned[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> braceDepth++
                '}' -> {
                    if (braceDepth > 0) {
                        braceDepth--
                        if (braceDepth == 0 && bracketDepth == 1) {
                            lastCompleteSceneObjectEnd = i
                        }
                    }
                }
                '[' -> bracketDepth++
                ']' -> {
                    if (bracketDepth > 0) {
                        bracketDepth--
                        if (bracketDepth == 0) {
                            val slice = cleaned.substring(firstBracket, i + 1)
                            return stripTrailingCommasBeforeArrayEnd(slice)
                        }
                    }
                }
            }
        }

        // Truncated before closing `]` — repair at last cleanly closed scene object at depth 1
        if (lastCompleteSceneObjectEnd > firstBracket) {
            val repairedPrefix = cleaned.substring(firstBracket, lastCompleteSceneObjectEnd + 1)
                .trimEnd()
                .trimEnd(',')
                .trimEnd()
            return "$repairedPrefix]"
        }

        throw MalformedSceneJsonException("No complete scene objects or closing bracket found in LLM response")
    }

    private fun stripTrailingCommasBeforeArrayEnd(jsonArrayText: String): String {
        // Handle e.g. `[{"scene_id":1}, ]` -> `[{"scene_id":1}]`
        return jsonArrayText.replace(Regex(",\\s*\\]$"), "]")
    }

    fun parseAndValidateScenes(rawOutput: String): List<SceneScript> {
        val jsonText = extractJsonArrayText(rawOutput)
        val array = try {
            JSONArray(jsonText)
        } catch (e: Exception) {
            throw MalformedSceneJsonException("Invalid JSONArray syntax: ${e.message}", e)
        }

        if (array.length() == 0) {
            throw MalformedSceneJsonException("Scene JSONArray is empty")
        }

        val scenes = mutableListOf<SceneScript>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw MalformedSceneJsonException("Element at index $i is not a JSONObject")
            scenes.add(parseSceneObject(obj, i))
        }
        return scenes
    }

    private fun parseSceneObject(obj: JSONObject, index: Int): SceneScript {
        for (key in REQUIRED_KEYS) {
            if (!obj.has(key) || obj.isNull(key)) {
                throw MalformedSceneJsonException("Scene[$index] missing required key '$key'")
            }
        }

        val sceneId = obj.optInt("scene_id", index + 1)
        val narration = TagalogNarrationValidator.cleanForTts(obj.optString("tagalog_narration", ""))
        val bgPrompt = obj.optString("background_prompt", "").trim()
        val camRaw = obj.optString("camera_motion", "").trim().uppercase()
        val roleRaw = obj.optString("actor_role", "").trim().uppercase()
        val actionRaw = obj.optString("actor_action", "").trim().uppercase()
        val fxRaw = obj.optString("world_fx", "").trim().uppercase()

        if (narration.isEmpty()) {
            throw MalformedSceneJsonException("Scene[$index] has empty tagalog_narration")
        }
        TagalogNarrationValidator.validateOrThrow(narration)
        if (bgPrompt.isEmpty()) {
            throw MalformedSceneJsonException("Scene[$index] has empty background_prompt")
        }

        // Backward-compatible optional HUD & kinetic motion-graphics fields
        val statusPill = StatusPill.fromRaw(obj.optString("status_pill", "NONE"))
        val calloutBanner = obj.optString("callout_banner", "").trim()
        val hudWidget = HudWidget.fromRaw(obj.optString("hud_widget", "NONE"))
        val hudLabel = obj.optString("hud_label", "").trim()
        val hudFrom = obj.optInt("hud_from_value", 0).coerceIn(0, 100)
        val hudTo = obj.optInt("hud_to_value", 100).coerceIn(0, 100)

        val checklistArray = obj.optJSONArray("checklist_items")
        val checklistItems = if (checklistArray != null) {
            buildList {
                for (k in 0 until minOf(4, checklistArray.length())) {
                    val item = checklistArray.optString(k, "").trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        } else {
            emptyList()
        }

        val shotFraming = ShotFraming.fromRaw(obj.optString("shot_framing", "AUTO"))
        val characterPose = CharacterPose.fromRaw(obj.optString("character_pose", "AUTO"))
        val handProp = HandProp.fromRaw(obj.optString("hand_prop", "NONE"))
        val shotType = ShotType.fromRaw(obj.optString("shot_type", "AUTO"))
        val secondaryRoleRaw = obj.optString("secondary_actor_role", "").trim()
        val secondaryActorRole = if (secondaryRoleRaw.isNotEmpty()) ActorRole.fromRaw(secondaryRoleRaw) else null
        val expression = ActorExpression.fromRaw(obj.optString("expression", "HAPPY_TALK"))
        val headgear = ActorHeadgear.fromRaw(obj.optString("headgear", "NONE"))
        val metaphorVisual = MetaphorVisual.fromRaw(obj.optString("metaphor_visual", "NONE"))
        val topicPillLabel = obj.optString("topic_pill", "").trim()
        val cutawayStage = CutawayStage.fromRaw(obj.optString("cutaway_stage", ""))

        val pointerCalloutsArray = obj.optJSONArray("pointer_callouts")
        val pointerCallouts = if (pointerCalloutsArray != null) {
            buildList {
                for (k in 0 until minOf(2, pointerCalloutsArray.length())) {
                    val pObj = pointerCalloutsArray.optJSONObject(k) ?: continue
                    val label = pObj.optString("label", "").trim().uppercase()
                    if (label.isNotEmpty()) {
                        val px = pObj.optDouble("x", 135.0).toFloat()
                        val py = pObj.optDouble("y", 170.0).toFloat()
                        add(PointerCallout(label = label, x = px, y = py))
                    }
                }
            }
        } else {
            emptyList()
        }

        return SceneScript(
            sceneId = sceneId,
            tagalogNarration = narration,
            backgroundPrompt = bgPrompt,
            cameraMotion = CameraMotion.fromRaw(camRaw),
            actorRole = ActorRole.fromRaw(roleRaw),
            actorAction = ActorAction.fromRaw(actionRaw),
            worldFx = WorldFx.fromRaw(fxRaw),
            statusPill = statusPill,
            calloutBanner = calloutBanner,
            hudWidget = hudWidget,
            hudLabel = hudLabel,
            hudFromValue = hudFrom,
            hudToValue = hudTo,
            checklistItems = checklistItems,
            shotFraming = shotFraming,
            characterPose = characterPose,
            handProp = handProp,
            shotType = shotType,
            secondaryActorRole = secondaryActorRole,
            expression = expression,
            headgear = headgear,
            metaphorVisual = metaphorVisual,
            topicPillLabel = topicPillLabel,
            cutawayStage = cutawayStage,
            pointerCallouts = pointerCallouts
        )
    }
}
