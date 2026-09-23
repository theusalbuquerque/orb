package com.music.orb.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.feedback.FeedbackAttachmentException
import com.music.orb.data.feedback.FeedbackAttachmentFailure
import com.music.orb.data.feedback.FeedbackRepository
import com.music.orb.data.feedback.PreparedFeedbackImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(
    version: String,
    username: String?,
    email: String?,
    signedIn: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var preparingImages by remember { mutableStateOf(false) }
    val images = remember { mutableStateListOf<PreparedFeedbackImage>() }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val remaining = (FeedbackRepository.MAX_IMAGES - images.size).coerceAtLeast(0)
        if (remaining <= 0) {
            Toast.makeText(
                context,
                context.getString(R.string.feedback_max_images),
                Toast.LENGTH_SHORT,
            ).show()
            return@rememberLauncherForActivityResult
        }

        if (uris.size > remaining) {
            Toast.makeText(
                context,
                context.getString(R.string.feedback_max_images),
                Toast.LENGTH_SHORT,
            ).show()
        }

        preparingImages = true
        scope.launch {
            try {
                uris.take(remaining).forEach { uri ->
                    runCatching {
                        FeedbackRepository.prepareImage(context, uri)
                    }.onSuccess { prepared ->
                        images += prepared
                    }.onFailure { error ->
                        val messageRes = when ((error as? FeedbackAttachmentException)?.reason) {
                            FeedbackAttachmentFailure.TOO_LARGE -> R.string.feedback_image_too_large
                            FeedbackAttachmentFailure.NOT_IMAGE -> R.string.feedback_image_not_image
                            FeedbackAttachmentFailure.READ_FAILED, null -> R.string.feedback_image_read_failed
                        }
                        Toast.makeText(
                            context,
                            context.getString(messageRes),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } finally {
                preparingImages = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!sending) onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.feedback_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.feedback_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(R.string.feedback_version, version),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(
                        R.string.feedback_username,
                        username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "—",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(
                        R.string.feedback_email,
                        email?.takeIf { it.isNotBlank() } ?: "—",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(5000) },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 9,
                label = { Text(stringResource(R.string.feedback_message_label)) },
                placeholder = { Text(stringResource(R.string.feedback_message_hint)) },
                supportingText = {
                    Text("${message.length}/5000")
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !sending &&
                        !preparingImages &&
                        images.size < FeedbackRepository.MAX_IMAGES,
                ) {
                    if (preparingImages) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(
                            R.string.feedback_attach_images,
                            images.size,
                            FeedbackRepository.MAX_IMAGES,
                        ),
                    )
                }
            }
            Text(
                text = stringResource(R.string.feedback_attachment_limit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    images.forEach { image ->
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            AsyncImage(
                                model = image.sourceUri,
                                contentDescription = image.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                            IconButton(
                                onClick = { images.remove(image) },
                                enabled = !sending,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.feedback_remove_image),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            if (!signedIn) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.feedback_sign_in_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !sending,
                ) {
                    Text(stringResource(R.string.feedback_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (sending) return@Button
                        sending = true
                        scope.launch {
                            FeedbackRepository.submit(
                                message = message,
                                version = version,
                                images = images.toList(),
                            ).onSuccess {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.feedback_sent),
                                    Toast.LENGTH_LONG,
                                ).show()
                                onDismiss()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.feedback_send_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            sending = false
                        }
                    },
                    enabled = signedIn &&
                        message.isNotBlank() &&
                        !sending &&
                        !preparingImages,
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.feedback_send))
                    }
                }
            }
        }
    }
}
