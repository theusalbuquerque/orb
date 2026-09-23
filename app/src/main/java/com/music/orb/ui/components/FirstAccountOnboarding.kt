package com.music.orb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.orb.R
import androidx.compose.ui.res.stringResource

@Composable
fun YouTubeMusicFirstAccountDialog(
    connecting: Boolean,
    onConnect: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!connecting) onLater()
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.first_account_step_youtube),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.first_account_youtube_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        text = {
            Text(
                text = stringResource(R.string.first_account_youtube_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConnect,
                enabled = !connecting,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(18.dp),
                    )
                } else {
                    Text(stringResource(R.string.first_account_youtube_connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                enabled = !connecting,
            ) {
                Text(stringResource(R.string.first_account_later))
            }
        },
    )
}

@Composable
fun UsernameFirstAccountDialog(
    username: String,
    saving: Boolean,
    errorMessage: String?,
    onUsernameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!saving) onLater()
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.first_account_step_username),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.first_account_username_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.first_account_username_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = { Text(stringResource(R.string.first_account_username_field)) },
                    prefix = { Text("@") },
                    supportingText = {
                        Text(
                            text = errorMessage
                                ?: stringResource(R.string.first_account_username_rules),
                            color = if (errorMessage != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = errorMessage != null,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCreate,
                enabled = !saving && username.length in 3..30,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(18.dp),
                    )
                } else {
                    Text(stringResource(R.string.first_account_username_create))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                enabled = !saving,
            ) {
                Text(stringResource(R.string.first_account_later))
            }
        },
    )
}
