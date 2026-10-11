package com.vangeaux.lagrange.provider.bookorbit

import com.vangeaux.lagrange.BookEmailException
import com.vangeaux.lagrange.BookEmailFailure
import com.vangeaux.lagrange.BookEmailOptions
import com.vangeaux.lagrange.BookEmailProvider
import com.vangeaux.lagrange.BookEmailRecipient
import com.vangeaux.lagrange.BookEmailTemplate
import com.vangeaux.lagrange.BookSummary
import com.vangeaux.lagrange.HttpRequestException
import com.vangeaux.lagrange.canEmailBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Uses the repository's authenticated transport; SMTP credentials stay on the server. */
internal class BookOrbitEmailApi(
    private val request: (String, String, RequestBody?) -> String
) {
    suspend fun loadOptions(): BookEmailOptions = withContext(Dispatchers.IO) {
        try {
            val recipients = JSONArray(request("/api/v1/email/recipients", "GET", null))
            val parsedRecipients = (0 until recipients.length()).mapNotNull { index ->
                val recipient = recipients.getJSONObject(index)
                val id = recipient.optLong("id")
                val email = recipient.optString("email").trim()
                if (id <= 0 || email.isBlank()) null else BookEmailRecipient(
                    id = id,
                    name = recipient.optString("name").ifBlank { email },
                    email = email,
                    isDefault = recipient.optBoolean("isDefault")
                )
            }
            if (parsedRecipients.isEmpty()) return@withContext BookEmailOptions()
            val providers = JSONArray(request("/api/v1/email/providers", "GET", null))
            val parsedProviders = (0 until providers.length()).mapNotNull { index ->
                val provider = providers.getJSONObject(index)
                val id = provider.optLong("id")
                if (id <= 0) null else BookEmailProvider(
                    id = id,
                    name = provider.optString("name"),
                    isDefault = provider.optBoolean("isDefault")
                )
            }
            if (parsedProviders.isEmpty()) return@withContext BookEmailOptions()
            val templates = JSONArray(request("/api/v1/email/templates", "GET", null))
            val parsedTemplates = (0 until templates.length()).mapNotNull { index ->
                val template = templates.getJSONObject(index)
                val id = template.optLong("id")
                if (id <= 0) null else BookEmailTemplate(
                    id = id,
                    name = template.optString("name"),
                    isDefault = template.optBoolean("isDefault")
                )
            }
            val preferences = JSONObject(request("/api/v1/email/preferences", "GET", null))
            val defaultId = preferences.optLong("defaultProviderId").takeIf { id ->
                parsedProviders.any { it.id == id }
            } ?: parsedProviders.firstOrNull { it.isDefault }?.id
            val defaultTemplateId = preferences.optLong("defaultTemplateId").takeIf { id ->
                parsedTemplates.any { it.id == id }
            } ?: parsedTemplates.firstOrNull { it.isDefault }?.id
            BookEmailOptions(parsedRecipients, parsedProviders, defaultId, parsedTemplates, defaultTemplateId)
        } catch (error: HttpRequestException) {
            // Older servers and accounts without email.send permission have no email action.
            if (error.code in setOf(403, 404, 405, 501)) BookEmailOptions() else throw error
        }
    }

    suspend fun send(book: BookSummary, recipientIds: Set<Long>, providerId: Long, templateId: Long? = null): Int =
        withContext(Dispatchers.IO) {
            if (!canEmailBook(book)) throw BookEmailException(BookEmailFailure.INVALID_BOOK)
            if (recipientIds.isEmpty() || recipientIds.any { it <= 0 } || providerId <= 0 || (templateId != null && templateId <= 0)) {
                throw BookEmailException(BookEmailFailure.INVALID_SELECTION)
            }
            val body = JSONObject()
                .put("bookIds", JSONArray().put(book.id.toLong()))
                .put("recipientIds", JSONArray(recipientIds.toList()))
                .put("providerId", providerId)
                .apply { if (templateId != null) put("templateId", templateId) }
                .toString().toRequestBody("application/json".toMediaType())
            val result = JSONObject(request("/api/v1/email/send", "POST", body))
            val queued = result.getInt("queued")
            if (queued <= 0) throw BookEmailException(BookEmailFailure.NONE_QUEUED)
            queued
        }
}
