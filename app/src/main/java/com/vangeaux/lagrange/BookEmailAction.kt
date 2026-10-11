package com.vangeaux.lagrange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class BookEmailActionState(
    private val options: State<BookEmailOptions>,
    private val eligible: Boolean
) {
    val isAvailable: Boolean get() = eligible && options.value.isAvailable
    var showPicker by mutableStateOf(false)
    var queuedCount by mutableStateOf<Int?>(null)
    val emailOptions: BookEmailOptions get() = options.value

    fun openPicker() {
        showPicker = true
        queuedCount = null
    }
}

@Composable
internal fun rememberBookEmailAction(
    book: BookSummary,
    serverUrl: String,
    offline: Boolean,
    reloadKey: Int,
    optionsLoader: suspend () -> BookEmailOptions
): BookEmailActionState {
    val eligible = !offline && canEmailBook(book)
    return key(serverUrl, book.id, book.fileId, eligible) {
        val options = produceState(BookEmailOptions(), reloadKey) {
            value = BookEmailOptions()
            if (eligible) {
                value = try {
                    optionsLoader()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    BookEmailOptions()
                }
            }
        }
        remember { BookEmailActionState(options, eligible) }
    }
}

@Composable
internal fun BookEmailFeedback(
    book: BookSummary,
    state: BookEmailActionState,
    onSend: suspend (BookSummary, Set<Long>, Long) -> Int
) {
    state.queuedCount?.let { count ->
        Text(
            text = if (count == 1) "1 email queued by BookOrbit." else "$count emails queued by BookOrbit.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("book-email-result")
        )
    }
    if (state.showPicker && state.isAvailable) {
        BookEmailDialog(
            book = book,
            options = state.emailOptions,
            onSend = onSend,
            onDismiss = { state.showPicker = false },
            onQueued = { count ->
                state.showPicker = false
                state.queuedCount = count
            }
        )
    }
}

@Composable
private fun BookEmailDialog(
    book: BookSummary,
    options: BookEmailOptions,
    onSend: suspend (BookSummary, Set<Long>, Long) -> Int,
    onDismiss: () -> Unit,
    onQueued: (Int) -> Unit
) {
    var selectedIds by remember {
        mutableStateOf(options.recipients.filter { it.isDefault }.map { it.id }.toSet())
    }
    var providerId by remember { mutableStateOf(options.defaultProviderId ?: options.providers.first().id) }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send by email") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(book.title, style = MaterialTheme.typography.titleSmall)
                Text("File: ${book.filename ?: book.format?.uppercase() ?: "Selected ebook"}")
                Text("Recipients", style = MaterialTheme.typography.labelLarge)
                options.recipients.forEach { recipient ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = recipient.id in selectedIds,
                                enabled = !sending,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    selectedIds = if (checked) selectedIds + recipient.id else selectedIds - recipient.id
                                }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = recipient.id in selectedIds,
                            onCheckedChange = null,
                            enabled = !sending
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(recipient.name)
                            if (recipient.name != recipient.email) {
                                Text(recipient.email, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (options.providers.size > 1) {
                    Text("Email provider", style = MaterialTheme.typography.labelLarge)
                    options.providers.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = provider.id == providerId,
                                    enabled = !sending,
                                    role = Role.RadioButton,
                                    onClick = { providerId = provider.id }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = provider.id == providerId,
                                onClick = null,
                                enabled = !sending
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(provider.name.ifBlank { "Email provider ${provider.id}" })
                        }
                    }
                }
                Text(
                    text = "BookOrbit sends the selected file using your saved email settings. Check its email history for delivery status.",
                    style = MaterialTheme.typography.bodySmall
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && selectedIds.isNotEmpty(),
                modifier = Modifier.testTag("book-email-confirm"),
                onClick = {
                    sending = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val count = onSend(book, selectedIds, providerId)
                            if (count > 0) {
                                onQueued(count)
                            } else {
                                errorMessage = "BookOrbit did not queue any emails."
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: BookEmailException) {
                            errorMessage = when (error.reason) {
                                BookEmailFailure.UNAVAILABLE -> "Email delivery is not available on this server."
                                BookEmailFailure.SIGN_IN_REQUIRED -> "Sign in again before sending this ebook."
                                BookEmailFailure.INVALID_BOOK -> "This ebook cannot be sent by email."
                                BookEmailFailure.INVALID_SELECTION -> "Check the recipients and email provider settings in BookOrbit."
                                BookEmailFailure.NONE_QUEUED -> "BookOrbit did not queue any emails."
                            }
                        } catch (error: HttpRequestException) {
                            errorMessage = when (error.code) {
                                403 -> "Your account does not have permission to send email."
                                404 -> "This ebook, recipient, or email provider is no longer available."
                                400 -> "Check the recipients and email provider settings in BookOrbit."
                                429 -> "BookOrbit is limiting requests. Try again later."
                                else -> "Could not confirm email queueing. Check BookOrbit’s email history before trying again."
                            }
                        } catch (_: Exception) {
                            errorMessage = "Could not confirm email queueing. Check BookOrbit’s email history before trying again."
                        } finally {
                            sending = false
                        }
                    }
                }
            ) {
                Text(if (sending) "Queueing…" else "Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text("Cancel")
            }
        }
    )
}
