package com.music.orb.data.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.music.orb.data.Http
import com.music.orb.data.social.OrbSupabase
import io.github.jan.supabase.auth.auth
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PreparedFeedbackImage(
    val sourceUri: Uri,
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

enum class FeedbackAttachmentFailure {
    NOT_IMAGE,
    TOO_LARGE,
    READ_FAILED,
}

class FeedbackAttachmentException(
    val reason: FeedbackAttachmentFailure,
) : IllegalArgumentException(reason.name)

object FeedbackRepository {
    const val MAX_IMAGES = 2
    const val MAX_SOURCE_IMAGE_BYTES = 10L * 1024L * 1024L

    // Input files may be 10 MB each. Delivery copies are compressed before
    // upload so two images stay comfortably below common email limits.
    private const val MAX_DELIVERY_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_LONG_EDGE = 2200
    private const val FEEDBACK_BUCKET = "feedback-attachments"
    private const val FEEDBACK_FUNCTION = "orb-feedback"

    suspend fun prepareImage(
        context: Context,
        uri: Uri,
    ): PreparedFeedbackImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: throw FeedbackAttachmentException(FeedbackAttachmentFailure.NOT_IMAGE)

        var displayName = "image"
        var declaredSize: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: displayName
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex)
                }
            }
        }

        if ((declaredSize ?: 0L) > MAX_SOURCE_IMAGE_BYTES) {
            throw FeedbackAttachmentException(FeedbackAttachmentFailure.TOO_LARGE)
        }

        val original = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_SOURCE_IMAGE_BYTES) {
                    throw FeedbackAttachmentException(FeedbackAttachmentFailure.TOO_LARGE)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw FeedbackAttachmentException(FeedbackAttachmentFailure.READ_FAILED)

        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
            ?: throw FeedbackAttachmentException(FeedbackAttachmentFailure.READ_FAILED)

        val preparedBytes = try {
            compressForEmail(bitmap)
        } finally {
            bitmap.recycle()
        }

        PreparedFeedbackImage(
            sourceUri = uri,
            displayName = displayName,
            mimeType = "image/jpeg",
            bytes = preparedBytes,
        )
    }

    suspend fun submit(
        message: String,
        version: String,
        images: List<PreparedFeedbackImage>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(message.isNotBlank()) { "Feedback message is empty." }
        require(images.size <= MAX_IMAGES) { "Too many feedback images." }
        check(OrbSupabase.configured) { "Supabase is not configured." }

        val user = requireNotNull(OrbSupabase.client.auth.currentUserOrNull()) {
            "Sign in to your Orb account before sending feedback."
        }
        val accessToken = requireNotNull(OrbSupabase.client.auth.currentSessionOrNull()?.accessToken) {
            "The Orb session has expired."
        }

        val submissionId = UUID.randomUUID().toString()
        val uploadedPaths = mutableListOf<String>()

        try {
            images.forEachIndexed { index, image ->
                val path = "${user.id}/$submissionId/image-${index + 1}.jpg"
                uploadFeedbackImage(
                    path = path,
                    image = image,
                    accessToken = accessToken,
                )
                uploadedPaths += path
            }

            val payload = JSONObject()
                .put("message", message.trim())
                .put("version", version.trim())
                .put("attachmentPaths", JSONArray(uploadedPaths))

            val request = Request.Builder()
                .url("${OrbSupabase.baseUrl}/functions/v1/$FEEDBACK_FUNCTION")
                .header("Authorization", "Bearer $accessToken")
                .header("apikey", OrbSupabase.publishableKey)
                .header("Content-Type", "application/json")
                .post(
                    payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .build()

            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Feedback delivery failed (${response.code}): $body")
                }
            }
        } catch (error: Throwable) {
            uploadedPaths.forEach { path ->
                runCatching { deleteFeedbackImage(path, accessToken) }
            }
            throw error
        }
        }
    }

    private fun uploadFeedbackImage(
        path: String,
        image: PreparedFeedbackImage,
        accessToken: String,
    ) {
        // Supabase Storage REST semantics:
        // POST /object/{bucket}/{path} creates a new object.
        // PUT is reserved for updating an object that already exists.
        val request = Request.Builder()
            .url("${OrbSupabase.baseUrl}/storage/v1/object/$FEEDBACK_BUCKET/$path")
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", OrbSupabase.publishableKey)
            .header("x-upsert", "false")
            .post(image.bytes.toRequestBody(image.mimeType.toMediaType()))
            .build()

        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Feedback image upload failed (${response.code}): $body")
            }
        }
    }

    private fun deleteFeedbackImage(
        path: String,
        accessToken: String,
    ) {
        val payload = JSONObject()
            .put("prefixes", JSONArray().put(path))

        val request = Request.Builder()
            .url("${OrbSupabase.baseUrl}/storage/v1/object/$FEEDBACK_BUCKET")
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", OrbSupabase.publishableKey)
            .header("Content-Type", "application/json")
            .delete(
                payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        Http.client.newCall(request).execute().close()
    }

    private fun compressForEmail(source: Bitmap): ByteArray {
        var working = source
        var ownsWorking = false

        val longest = maxOf(source.width, source.height)
        if (longest > MAX_LONG_EDGE) {
            val scale = MAX_LONG_EDGE.toFloat() / longest.toFloat()
            working = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            ownsWorking = true
        }

        try {
            var quality = 92
            var bytes = encodeJpeg(working, quality)
            while (bytes.size > MAX_DELIVERY_IMAGE_BYTES && quality > 58) {
                quality -= 8
                bytes = encodeJpeg(working, quality)
            }

            if (bytes.size <= MAX_DELIVERY_IMAGE_BYTES) return bytes

            // Very detailed 10 MB screenshots/photos can still be large at
            // moderate JPEG quality. Scale once more rather than rejecting an
            // otherwise valid attachment.
            val secondScale = 0.78f
            val smaller = Bitmap.createScaledBitmap(
                working,
                (working.width * secondScale).toInt().coerceAtLeast(1),
                (working.height * secondScale).toInt().coerceAtLeast(1),
                true,
            )
            return try {
                var secondQuality = 82
                var secondBytes = encodeJpeg(smaller, secondQuality)
                while (secondBytes.size > MAX_DELIVERY_IMAGE_BYTES && secondQuality > 50) {
                    secondQuality -= 8
                    secondBytes = encodeJpeg(smaller, secondQuality)
                }
                secondBytes
            } finally {
                smaller.recycle()
            }
        } finally {
            if (ownsWorking) working.recycle()
        }
    }

    private fun encodeJpeg(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }
}
