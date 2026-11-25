package com.example.llm

import com.example.data.BuiltInPrompts
import com.example.model.AnimationStyle
import com.example.model.SceneScript
import com.example.model.StudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class DurationBudget(
    val targetDurationSeconds: Int,
    val targetSceneCount: Int,
    val targetSpokenChars: Int,
    val minChars: Int,
    val maxChars: Int
)

data class LlmScriptResult(
    val scenes: List<SceneScript>,
    val providerUsed: String
)

class LlmDirectorClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateStoryScript(
        topic: String,
        durationSeconds: Int,
        style: AnimationStyle,
        settings: StudioSettings,
        onStatus: (String) -> Unit
    ): LlmScriptResult = withContext(Dispatchers.IO) {
        val activeCreativePrompt = settings.activeTemplate?.content
            ?: BuiltInPrompts.VIRAL_PINOY_EXPLAINER.content
        val systemPrompt = BuiltInPrompts.composeSystemPrompt(
            activeCreativePrompt = activeCreativePrompt,
            durationSeconds = durationSeconds,
            style = style
        )
        val durationBudget = calculateDurationBudget(durationSeconds)
        val userPrompt = buildString {
            append("Topic: $topic\n")
            append("Visual Rendering Style (aesthetic & camera feel ONLY—do NOT override story topic/characters): ${style.displayName}\n")
            append("Target Duration: ${durationSeconds} seconds\n")
            append("Use natural conversational Filipino/Tagalog (no literal English idiom translations, no gibberish).\n")
            append("TOPIC-FIRST RULE: Derive 100% of characters (character_gender, character_age, character_outfit, outfit_color_hex, hair_color_hex, hand_prop), environment_theme (matching time of day), cutaway_diagram_prompt/flow_direction/pointer_callouts, and dynamic diagram_type labels strictly from this Topic and Master Prompt. Never insert unrelated skin wounds, protein chains, chickens, or top-hat men.\n\n")
            append(buildDurationContractBlock(durationBudget))
            append("\n\nWrite the Tagalog scenes JSON array now.")
        }

        val errors = mutableListOf<String>()
        val primaryBaseUrl = settings.effectiveLlmBaseUrl
        val primaryApiKey = settings.effectiveLlmApiKey
        val primaryModel = settings.effectiveSelectedModelId

        // 1. Primary Universal LLM Endpoint (Gemini, Groq, OpenRouter, Custom Proxy, or AI Horde)
        if (primaryBaseUrl.isNotBlank()) {
            if (UniversalModelDetector.isHordeUrl(primaryBaseUrl)) {
                val hordeResult = tryAiHordeTextWithRetry(
                    apiKey = primaryApiKey.ifBlank { settings.aiHordeKey }.ifBlank { "0000000000" },
                    preferredModel = primaryModel,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    durationBudget = durationBudget,
                    onStatus = onStatus
                )
                if (hordeResult.isSuccess) return@withContext hordeResult.getOrThrow()
                errors.add("AI Horde: ${hordeResult.exceptionOrNull()?.message}")
            } else {
                val resolvedModel = primaryModel.ifBlank {
                    UniversalModelDetector.matchPresetForUrl(primaryBaseUrl).defaultModelHint
                }
                if (resolvedModel.isNotBlank()) {
                    val candidateUrls = UniversalModelDetector.buildCandidateChatUrls(
                        baseUrl = primaryBaseUrl,
                        detectedChatBaseUrl = settings.detectedChatBaseUrl
                    )
                    val presetLabel = UniversalModelDetector.matchPresetForUrl(primaryBaseUrl).label
                    val extraHeaders = if (primaryBaseUrl.contains("openrouter.ai", ignoreCase = true)) {
                        mapOf(
                            "HTTP-Referer" to "https://toonforge.tagalog.app",
                            "X-Title" to "ToonForge Tagalog"
                        )
                    } else {
                        emptyMap()
                    }
                    var lastCustomErr: String? = null
                    for (url in candidateUrls) {
                        val result = tryOpenAiWithRetry(
                            providerName = "$presetLabel ($resolvedModel)",
                            url = url,
                            model = resolvedModel,
                            apiKey = primaryApiKey,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            durationBudget = durationBudget,
                            extraHeaders = extraHeaders,
                            onStatus = onStatus
                        )
                        if (result.isSuccess) return@withContext result.getOrThrow()
                        val errMsg = result.exceptionOrNull()?.message.orEmpty()
                        lastCustomErr = errMsg
                        // Only try next candidate URL path if this was a 404/405 path mismatch
                        if (!errMsg.contains("HTTP 404") && !errMsg.contains("HTTP 405")) {
                            break
                        }
                    }
                    errors.add("$presetLabel: $lastCustomErr")
                }
            }
        }

        // 2. Legacy Groq Free API fallback (if configured separately)
        if (settings.groqApiKey.isNotBlank() && settings.groqApiKey != primaryApiKey) {
            val groqModels = listOf("qwen/qwen3-32b", "openai/gpt-oss-120b")
            for (model in groqModels) {
                val result = tryOpenAiWithRetry(
                    providerName = "Groq ($model)",
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    model = model,
                    apiKey = settings.groqApiKey,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    durationBudget = durationBudget,
                    onStatus = onStatus
                )
                if (result.isSuccess) return@withContext result.getOrThrow()
                errors.add("Groq[$model]: ${result.exceptionOrNull()?.message}")
            }
        }

        // 3. Legacy OpenRouter Free API fallback (if configured separately)
        if (settings.openRouterApiKey.isNotBlank() && settings.openRouterApiKey != primaryApiKey) {
            val openRouterModels = listOf("qwen/qwen3-235b-a22b:free", "deepseek/deepseek-r1:free")
            for (model in openRouterModels) {
                val result = tryOpenAiWithRetry(
                    providerName = "OpenRouter ($model)",
                    url = "https://openrouter.ai/api/v1/chat/completions",
                    model = model,
                    apiKey = settings.openRouterApiKey,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    durationBudget = durationBudget,
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://toonforge.tagalog.app",
                        "X-Title" to "ToonForge Tagalog"
                    ),
                    onStatus = onStatus
                )
                if (result.isSuccess) return@withContext result.getOrThrow()
                errors.add("OpenRouter[$model]: ${result.exceptionOrNull()?.message}")
            }
        }

        // 4. Keyless Unlimited Default: AI Horde Text API
        if (!UniversalModelDetector.isHordeUrl(primaryBaseUrl)) {
            val hordeResult = tryAiHordeTextWithRetry(
                apiKey = settings.aiHordeKey.ifBlank { "0000000000" },
                preferredModel = "",
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                durationBudget = durationBudget,
                onStatus = onStatus
            )
            if (hordeResult.isSuccess) return@withContext hordeResult.getOrThrow()
            errors.add("AI Horde: ${hordeResult.exceptionOrNull()?.message}")
        }

        throw IOException("All LLM providers failed: ${errors.joinToString(" | ")}")
    }

    private suspend fun tryOpenAiWithRetry(
        providerName: String,
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        durationBudget: DurationBudget,
        extraHeaders: Map<String, String> = emptyMap(),
        onStatus: (String) -> Unit
    ): Result<LlmScriptResult> {
        var lastError: Throwable? = null
        for (attempt in 1..3) { // initial + up to 2 retries on malformed output
            try {
                onStatus("Writing Tagalog script via $providerName (try $attempt/3)...")
                val retryHint = if (attempt > 1) {
                    "\nCRITICAL: Previous output had malformed JSON or unnatural/gibberish Tagalog (${lastError?.message ?: "error"}). Output ONLY a raw JSON array starting with '[' and ending with ']' with natural everyday conversational Tagalog."
                } else ""

                val rawText = executeOpenAiChatCompletion(
                    url = url,
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = systemPrompt + retryHint,
                    userPrompt = userPrompt,
                    extraHeaders = extraHeaders
                )

                val initialScenes = JsonSanitizer.parseAndValidateScenes(rawText)
                val guardedScenes = applyDurationLengthGuard(
                    scenes = initialScenes,
                    budget = durationBudget,
                    onStatus = onStatus
                ) { expansionUserPrompt ->
                    executeOpenAiChatCompletion(
                        url = url,
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        userPrompt = expansionUserPrompt,
                        extraHeaders = extraHeaders
                    )
                }
                return Result.success(LlmScriptResult(scenes = guardedScenes, providerUsed = providerName))
            } catch (e: MalformedSceneJsonException) {
                lastError = e
                delay(400)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(lastError ?: IOException("Malformed JSON after retries"))
    }

    private fun executeOpenAiChatCompletion(
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        extraHeaders: Map<String, String>
    ): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.7)
            put("max_tokens", 4096)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        return httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${bodyStr.take(240)}")
            }
            val root = JSONObject(bodyStr)
            root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private suspend fun tryAiHordeTextWithRetry(
        apiKey: String,
        preferredModel: String = "",
        systemPrompt: String,
        userPrompt: String,
        durationBudget: DurationBudget,
        onStatus: (String) -> Unit
    ): Result<LlmScriptResult> {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                onStatus("Writing Tagalog script via AI Horde (free keyless, try $attempt/3)...")
                val retryHint = if (attempt > 1) {
                    "\nOutput ONLY a valid JSON array starting with '[' and ending with ']' using natural conversational Tagalog (no gibberish or literal English idioms)."
                } else ""

                val text = executeAiHordeTextCompletion(
                    apiKey = apiKey,
                    preferredModel = preferredModel,
                    systemPrompt = systemPrompt + retryHint,
                    userPrompt = userPrompt,
                    onStatus = onStatus
                )
                val initialScenes = JsonSanitizer.parseAndValidateScenes(text)
                val guardedScenes = applyDurationLengthGuard(
                    scenes = initialScenes,
                    budget = durationBudget,
                    onStatus = onStatus
                ) { expansionUserPrompt ->
                    executeAiHordeTextCompletion(
                        apiKey = apiKey,
                        preferredModel = preferredModel,
                        systemPrompt = systemPrompt,
                        userPrompt = expansionUserPrompt,
                        onStatus = onStatus
                    )
                }
                return Result.success(LlmScriptResult(scenes = guardedScenes, providerUsed = "AI Horde Text"))
            } catch (e: MalformedSceneJsonException) {
                lastError = e
                delay(500)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(lastError ?: IOException("AI Horde malformed JSON after retries"))
    }

    private suspend fun executeAiHordeTextCompletion(
        apiKey: String,
        preferredModel: String,
        systemPrompt: String,
        userPrompt: String,
        onStatus: (String) -> Unit
    ): String {
        val combinedPrompt = buildString {
            append("<|system|>\n")
            append(systemPrompt)
            append("\n<|user|>\n")
            append(userPrompt)
            append("\n<|assistant|>\n[")
        }

        val payload = JSONObject().apply {
            put("prompt", combinedPrompt)
            if (preferredModel.isNotBlank()) {
                put("models", JSONArray().apply { put(preferredModel) })
            }
            put("params", JSONObject().apply {
                put("max_context_length", 4096)
                put("max_length", 2500)
                put("temperature", 0.65)
                put("top_p", 0.9)
            })
        }

        val submitReq = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/text/async")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("apikey", apiKey)
            .header("Client-Agent", "ToonForgeTagalog:1.0:android")
            .header("Content-Type", "application/json")
            .build()

        val jobId = httpClient.newCall(submitReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("AI Horde submit HTTP ${resp.code}: ${bodyStr.take(200)}")
            }
            JSONObject(bodyStr).getString("id")
        }

        var generatedText: String? = null
        val startWaitMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWaitMs < 90_000L) {
            delay(2_500L)
            val statusReq = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/text/status/$jobId")
                .get()
                .header("Client-Agent", "ToonForgeTagalog:1.0:android")
                .build()

            val statusJson = httpClient.newCall(statusReq).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("AI Horde status HTTP ${resp.code}")
                }
                JSONObject(bodyStr)
            }

            val done = statusJson.optBoolean("done", false)
            val faulted = statusJson.optBoolean("faulted", false)
            if (faulted) {
                throw IOException("AI Horde text job faulted")
            }
            val waitTime = statusJson.optInt("wait_time", 0)
            if (!done) {
                onStatus("AI Horde queued (~${waitTime}s left)...")
                continue
            }

            val gens = statusJson.optJSONArray("generations")
            if (gens != null && gens.length() > 0) {
                val rawGen = gens.getJSONObject(0).optString("text", "")
                generatedText = if (rawGen.trimStart().startsWith("[")) rawGen else "[$rawGen"
                break
            } else {
                throw IOException("AI Horde completed with empty generations")
            }
        }

        return generatedText ?: throw IOException("AI Horde text generation timed out")
    }

    private suspend fun applyDurationLengthGuard(
        scenes: List<SceneScript>,
        budget: DurationBudget,
        onStatus: (String) -> Unit,
        requestExpansion: suspend (expansionUserPrompt: String) -> String
    ): List<SceneScript> {
        var working = scenes
        val actualChars = totalSpokenChars(working)

        if (actualChars < budget.minChars) {
            val missingScenes = (budget.targetSceneCount - working.size).coerceAtLeast(1)
            onStatus("Expanding Tagalog script ($actualChars/${budget.targetSpokenChars} chars, +$missingScenes scenes)...")
            val expansionUserPrompt = buildString {
                append("Existing scenes JSON:\n")
                append(serializeScenesForExpansion(working))
                append("\n\nExpand the existing scenes with richer Tagalog storytelling details and add $missingScenes scenes so total tagalog_narration reaches ${budget.targetSpokenChars} characters while keeping the exact JSON schema.\n\n")
                append(buildDurationContractBlock(budget))
            }
            val expandedResult = runCatching {
                val rawExpanded = requestExpansion(expansionUserPrompt)
                JsonSanitizer.parseAndValidateScenes(rawExpanded)
            }.getOrNull()
            if (!expandedResult.isNullOrEmpty()) {
                working = mergeExpandedScenes(original = working, expanded = expandedResult, budget = budget)
            }
        }

        val trimThreshold = (budget.maxChars * OVERSHOOT_TRIM_MULTIPLIER).roundToInt()
        if (totalSpokenChars(working) > trimThreshold) {
            onStatus("Trimming excess scenes to lock ${budget.targetDurationSeconds}s pacing...")
            working = trimExcessScenesPreservingEnding(working, budget)
        }
        return working
    }

    companion object {
        const val TAGALOG_CHARS_PER_SECOND = 15.5
        const val SCENE_PAUSE_SEC = 0.45
        const val OVERSHOOT_TRIM_MULTIPLIER = 1.15

        fun calculateDurationBudget(targetDurationSeconds: Int): DurationBudget {
            val safeDuration = targetDurationSeconds.coerceAtLeast(10)
            val targetSceneCount = (safeDuration / 4.8).roundToInt().coerceIn(5, 22)
            val speechWindowSec = (safeDuration - (targetSceneCount * SCENE_PAUSE_SEC)).coerceAtLeast(4.0)
            val targetSpokenChars = (speechWindowSec * TAGALOG_CHARS_PER_SECOND).roundToInt()
            val minChars = (targetSpokenChars * 0.90).roundToInt()
            val maxChars = (targetSpokenChars * 1.08).roundToInt()
            return DurationBudget(
                targetDurationSeconds = safeDuration,
                targetSceneCount = targetSceneCount,
                targetSpokenChars = targetSpokenChars,
                minChars = minChars,
                maxChars = maxChars
            )
        }

        fun buildDurationContractBlock(budget: DurationBudget): String {
            return """
MANDATORY DURATION & LENGTH CONTRACT:
- Target Duration: ${budget.targetDurationSeconds} seconds (calibrated at 15.5 Tagalog chars/sec + 0.45s breathing pause per scene cut).
- Produce EXACTLY ${budget.targetSceneCount} scenes in the JSON array.
- Combined "tagalog_narration" character count across ALL ${budget.targetSceneCount} scenes MUST be strictly between ${budget.minChars} and ${budget.maxChars} characters (target: ${budget.targetSpokenChars} characters, ~11–15 Tagalog words per scene).
- Preserve the voice, tone, and style of the master prompt while strictly obeying this scene count and spoken length.
            """.trimIndent()
        }

        fun totalSpokenChars(scenes: List<SceneScript>): Int =
            scenes.sumOf { it.tagalogNarration.length }

        fun trimExcessScenesPreservingEnding(
            scenes: List<SceneScript>,
            budget: DurationBudget
        ): List<SceneScript> {
            val trimThreshold = (budget.maxChars * OVERSHOOT_TRIM_MULTIPLIER).roundToInt()
            if (scenes.size <= 2 || totalSpokenChars(scenes) <= trimThreshold) {
                return scenes
            }
            val closingScene = scenes.last()
            val closingLen = closingScene.tagalogNarration.length
            val kept = mutableListOf(scenes.first())
            var runningChars = scenes.first().tagalogNarration.length

            for (i in 1 until scenes.lastIndex) {
                val candidate = scenes[i]
                val candLen = candidate.tagalogNarration.length
                if (runningChars + candLen + closingLen <= budget.maxChars || kept.size < 2 && runningChars + candLen + closingLen <= trimThreshold) {
                    kept.add(candidate)
                    runningChars += candLen
                } else {
                    break
                }
            }
            kept.add(closingScene)
            return kept.mapIndexed { idx, scene -> scene.copy(sceneId = idx + 1) }
        }

        fun mergeExpandedScenes(
            original: List<SceneScript>,
            expanded: List<SceneScript>,
            budget: DurationBudget
        ): List<SceneScript> {
            if (expanded.isEmpty()) return original
            val origChars = totalSpokenChars(original)
            val expChars = totalSpokenChars(expanded)
            val existingNarrations = original.map { it.tagalogNarration.trim().lowercase() }.toSet()
            val newOnly = expanded.filterNot { it.tagalogNarration.trim().lowercase() in existingNarrations }

            val isFullRewrite = (expanded.size > original.size && expChars > origChars) ||
                (expanded.size >= budget.targetSceneCount && expChars >= origChars)

            val merged = when {
                isFullRewrite -> expanded
                newOnly.isNotEmpty() && original.size < budget.targetSceneCount -> original + newOnly
                expChars > origChars -> expanded
                else -> original
            }
            return merged.mapIndexed { idx, scene -> scene.copy(sceneId = idx + 1) }
        }

        fun serializeScenesForExpansion(scenes: List<SceneScript>): String {
            val arr = JSONArray()
            scenes.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("scene_id", s.sceneId)
                        put("tagalog_narration", s.tagalogNarration)
                        put("background_prompt", s.backgroundPrompt)
                        put("camera_motion", s.cameraMotion.name)
                        put("actor_role", s.actorRole.name)
                        put("actor_action", s.actorAction.name)
                        put("world_fx", s.worldFx.name)
                        if (s.environmentTheme != null) put("environment_theme", s.environmentTheme.name)
                        if (s.characterGender != null) put("character_gender", s.characterGender.name)
                        if (s.characterAge != null) put("character_age", s.characterAge.name)
                        if (s.characterOutfit != null) put("character_outfit", s.characterOutfit.name)
                        if (s.outfitColorHex.isNotBlank()) put("outfit_color_hex", s.outfitColorHex)
                        if (s.hairColorHex.isNotBlank()) put("hair_color_hex", s.hairColorHex)
                        if (s.shotFraming.name != "AUTO") put("shot_framing", s.shotFraming.name)
                        if (s.characterPose.name != "AUTO") put("character_pose", s.characterPose.name)
                        if (s.handProp.name != "NONE") put("hand_prop", s.handProp.name)
                        if (s.metaphorVisual.name != "NONE") put("metaphor_visual", s.metaphorVisual.name)
                        if (s.diagramType.name != "NONE") put("diagram_type", s.diagramType.name)
                        if (s.leftLabel.isNotBlank()) put("left_label", s.leftLabel)
                        if (s.rightLabel.isNotBlank()) put("right_label", s.rightLabel)
                        if (s.step1.isNotBlank()) put("step_1", s.step1)
                        if (s.step2.isNotBlank()) put("step_2", s.step2)
                        if (s.step3.isNotBlank()) put("step_3", s.step3)
                        if (s.statusPill.name != "NONE") put("status_pill", s.statusPill.name)
                        if (s.calloutBanner.isNotBlank()) put("callout_banner", s.calloutBanner)
                        if (s.cutawayDiagramPrompt.isNotBlank()) put("cutaway_diagram_prompt", s.cutawayDiagramPrompt)
                        if (s.flowDirection.name != "LEFT_TO_RIGHT") put("flow_direction", s.flowDirection.name)
                        if (s.cutawaySubject != null) put("cutaway_subject", s.cutawaySubject.name)
                        if (s.cutawayStage != null) put("cutaway_stage", s.cutawayStage.name)
                    }
                )
            }
            return arr.toString()
        }
    }
}
