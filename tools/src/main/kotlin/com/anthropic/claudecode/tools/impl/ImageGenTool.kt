package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * ImageGenTool - generates images via external API
 * ImageGenTool - 通过外部 API 生成图片
 *
 * Maps from TypeScript tools/ImageGenTool.
 * Supports configurable image generation endpoints.
 * Downloads generated images to local filesystem.
 * 映射自 TypeScript tools/ImageGenTool。
 * 支持可配置的图片生成端点。
 * 将生成的图片下载到本地文件系统。
 */
class ImageGenTool : Tool<ImageGenInput, ImageGenOutput> {
    override val name = "ImageGenTool"
    override val aliases = listOf("ImageGen", "image_gen")

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Generate a high-fidelity image based on a text prompt. The image will be exported to a specified file path.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): ImageGenInput {
        return ImageGenInput(
            name = rawInput["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: name"),
            prompt = rawInput["prompt"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: prompt"),
            size = rawInput["size"]?.jsonPrimitive?.content ?: "1024x1024"
        )
    }

    override suspend fun call(
        input: ImageGenInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<ImageGenOutput> {
        logger.info { "Image generation: ${input.name}, size=${input.size}" }

        if (input.size !in VALID_SIZES) {
            return ToolResult(
                data = ImageGenOutput(error = "Invalid size: ${input.size}. Valid: ${VALID_SIZES.joinToString()}"),
                output = ImageGenOutput(error = "Invalid size"),
                isError = true
            )
        }

        val apiKey = System.getenv("IMAGE_GEN_API_KEY") ?: System.getenv("OPENAI_API_KEY")
        val endpoint = System.getenv("IMAGE_GEN_ENDPOINT") ?: "https://api.openai.com/v1/images/generations"

        if (apiKey.isNullOrBlank()) {
            val output = ImageGenOutput(
                name = input.name, size = input.size,
                status = "error",
                error = "No image generation API key. Set IMAGE_GEN_API_KEY or OPENAI_API_KEY."
            )
            return ToolResult(data = output, output = output, isError = true)
        }

        onProgress?.invoke(ToolProgressData.SkillToolProgress(
            skillName = "ImageGen", status = "Generating image: ${input.name}"
        ))

        return try {
            // Build request body / 构建请求体
            val requestBody = buildJsonObject {
                put("prompt", input.prompt)
                put("size", input.size)
                put("n", 1)
                put("response_format", "url")
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                throw RuntimeException("API error ${response.statusCode()}: ${response.body().take(500)}")
            }

            // Parse response and download image / 解析响应并下载图片
            val responseJson = Json.parseToJsonElement(response.body()).jsonObject
            val imageUrl = responseJson["data"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: throw RuntimeException("No image URL in response")

            // Download image to local file / 下载图片到本地文件
            val sanitizedName = input.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val outputDir = File(System.getProperty("java.io.tmpdir"), "claude-images")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "${sanitizedName}_${UUID.randomUUID().toString().take(8)}.png")

            onProgress?.invoke(ToolProgressData.SkillToolProgress(
                skillName = "ImageGen", status = "Downloading image..."
            ))

            val imageRequest = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            val imageResponse = httpClient.send(imageRequest, HttpResponse.BodyHandlers.ofByteArray())
            outputFile.writeBytes(imageResponse.body())

            logger.info { "Image saved to ${outputFile.absolutePath}" }

            val output = ImageGenOutput(
                name = input.name, size = input.size,
                filePath = outputFile.absolutePath,
                status = "completed",
                message = "Image generated and saved to ${outputFile.absolutePath}"
            )
            ToolResult(data = output, output = output)

        } catch (e: Exception) {
            logger.error(e) { "Image generation failed" }
            val output = ImageGenOutput(
                name = input.name, size = input.size,
                status = "error",
                error = "Image generation failed: ${e.message}"
            )
            ToolResult(data = output, output = output, isError = true)
        }
    }

    override suspend fun description(input: ImageGenInput, options: DescriptionOptions): String {
        return "Generate image: ${input.name}"
    }

    override suspend fun checkPermissions(input: ImageGenInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Ask(message = "Generate image '${input.name}'?")
    }

    companion object {
        val VALID_SIZES = setOf(
            "1024x1024", "1536x1024", "1024x1536", "768x1024", "1024x768",
            "1024x1280", "1280x1024", "1024x1792", "1792x1024", "2560x1080"
        )
    }
}

@Serializable data class ImageGenInput(val name: String, val prompt: String, val size: String = "1024x1024")
@Serializable data class ImageGenOutput(
    val name: String? = null, val size: String? = null,
    val filePath: String? = null, val status: String = "pending",
    val message: String? = null, val error: String? = null
)
