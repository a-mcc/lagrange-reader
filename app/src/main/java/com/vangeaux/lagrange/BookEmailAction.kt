package com.vangeaux.lagrange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    onSend: suspend (BookSummary, Set<Long>, Long, Long?) -> Int
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
    onSend: suspend (BookSummary, Set<Long>, Long, Long?) -> Int,
    onDismiss: () -> Unit,
    onQueued: (Int) -> Unit
) {
    val recipients = remember(options.recipients) {
        options.recipients.sortedWith(
            compareBy<BookEmailRecipient> { !it.isDefault }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.id }
        )
    }
    var selectedIds by remember {
        mutableStateOf(options.recipients.filter { it.isDefault }.map { it.id }.toSet())
    }
    var providerId by remember { mutableLongStateOf(options.defaultProviderId ?: options.providers.first().id) }
    var templateId by remember { mutableStateOf<Long?>(null) }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    Dialog(onDismissRequest = { if (!sending) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Send via Email", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !sending) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "RECIPIENTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    recipients.forEach { recipient ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("book-email-recipient-${recipient.id}")
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
                                Text(recipient.name, style = MaterialTheme.typography.bodyMedium)
                                if (recipient.name != recipient.email) {
                                    Text(
                                        recipient.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (recipient.isDefault) {
                                Spacer(Modifier.width(8.dp))
                                BookEmailDefaultBadge()
                            }
                        }
                    }
                    if (options.providers.size > 1) {
                        BookEmailChoiceDropdown(
                            title = "EMAIL PROVIDER",
                            choices = options.providers.map { it.id to it.name.ifBlank { "Email provider ${it.id}" } },
                            selectedId = providerId,
                            defaultId = options.defaultProviderId,
                            enabled = !sending,
                            onSelect = { id -> if (id != null) providerId = id }
                        )
                    }
                    if (options.templates.size > 1) {
                        BookEmailChoiceDropdown(
                            title = "TEMPLATE",
                            choices = options.templates.map { it.id to it.name.ifBlank { "Template ${it.id}" } },
                            selectedId = templateId,
                            defaultId = options.defaultTemplateId,
                            defaultChoiceLabel = "Use recipient defaults",
                            enabled = !sending,
                            onSelect = { templateId = it }
                        )
                    }
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss, enabled = !sending) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = !sending && selectedIds.isNotEmpty(),
                        modifier = Modifier.testTag("book-email-confirm"),
                        onClick = {
                            sending = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val count = onSend(book, selectedIds, providerId, templateId)
                                    if (count > 0) {
                                        onQueued(count)
                                    } else {
                                        errorMessage = "BookOrbit did not queue any emails."
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    errorMessage = bookEmailErrorMessage(error)
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (sending) "Queueing…" else "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun BookEmailChoiceDropdown(
    title: String,
    choices: List<Pair<Long, String>>,
    selectedId: Long?,
    defaultId: Long?,
    enabled: Boolean,
    defaultChoiceLabel: String? = null,
    onSelect: (Long?) -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val sortedChoices = remember(choices) {
        choices.sortedWith(
            compareBy<Pair<Long, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.second }
                .thenBy { it.first }
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val selectedName = if (selectedId == null) defaultChoiceLabel.orEmpty() else
        choices.firstOrNull { it.first == selectedId }?.second.orEmpty()
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("book-email-choice-$title")
                .onSizeChanged { anchorWidth = it.width }
        ) {
            Text(
                selectedName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            if (selectedId != null && selectedId == defaultId) {
                Spacer(Modifier.width(8.dp))
                BookEmailDefaultBadge()
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { anchorWidth.toDp() })
        ) {
            defaultChoiceLabel?.let { label ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("book-email-option-$title-recipient-defaults"),
                    text = { Text(label) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
            }
            sortedChoices.forEach { (id, name) ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("book-email-option-$title-$id"),
                    text = { Text(name) },
                    trailingIcon = {
                        if (id == defaultId) BookEmailDefaultBadge()
                    },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BookEmailDefaultBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Text(
            "Default",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun bookEmailErrorMessage(error: Exception): String = when (error) {
    is BookEmailException -> when (error.reason) {
        BookEmailFailure.UNAVAILABLE -> "Email delivery is not available on this server."
        BookEmailFailure.SIGN_IN_REQUIRED -> "Sign in again before sending this ebook."
        BookEmailFailure.INVALID_BOOK -> "This ebook cannot be sent by email."
        BookEmailFailure.INVALID_SELECTION -> "Check the recipients, email provider, and template in BookOrbit’s email settings."
        BookEmailFailure.NONE_QUEUED -> "BookOrbit did not queue any emails."
    }
    is HttpRequestException -> when (error.code) {
        403 -> "Your account does not have permission to send email."
        404 -> "This ebook, recipient, email provider, or template is no longer available."
        400 -> "Check the recipients, email provider, and template in BookOrbit’s email settings."
        429 -> "BookOrbit is limiting requests. Try again later."
        else -> "Could not confirm email queueing. Check BookOrbit’s email history before trying again."
    }
    else -> "Could not confirm email queueing. Check BookOrbit’s email history before trying again."
}
