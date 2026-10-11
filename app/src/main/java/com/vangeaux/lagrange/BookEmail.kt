package com.vangeaux.lagrange

data class BookEmailRecipient(
    val id: Long,
    val name: String,
    val email: String,
    val isDefault: Boolean = false
)

data class BookEmailProvider(val id: Long, val name: String)

enum class BookEmailFailure {
    UNAVAILABLE,
    SIGN_IN_REQUIRED,
    INVALID_BOOK,
    INVALID_SELECTION,
    NONE_QUEUED
}

/** Carries a failure reason to the email dialog. */
class BookEmailException(val reason: BookEmailFailure) : IllegalArgumentException(reason.name)

data class BookEmailOptions(
    val recipients: List<BookEmailRecipient> = emptyList(),
    val providers: List<BookEmailProvider> = emptyList(),
    val defaultProviderId: Long? = null
) {
    val isAvailable: Boolean get() = recipients.isNotEmpty() && providers.isNotEmpty()
}

internal fun canEmailBook(book: BookSummary): Boolean =
    !book.isLocalOnly && !book.isServerMissing &&
        book.id.toLongOrNull()?.let { it > 0 } == true &&
        book.fileId?.toLongOrNull()?.let { it > 0 } == true &&
        book.mediaKind in setOf(MediaKind.EPUB, MediaKind.PDF)
