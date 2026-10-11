package com.vangeaux.lagrange

import com.vangeaux.lagrange.provider.bookorbit.BookOrbitEmailApi
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BookOrbitEmailApiTest {
    private val book = BookSummary(
        libraryId = "1",
        id = "42",
        fileId = "56",
        title = "An ebook",
        format = "epub",
        mediaKind = MediaKind.EPUB
    )

    @Test
    fun discoversRecipientsAndAccessibleProvidersWithSavedDefault() = runTest {
        val paths = mutableListOf<String>()
        val api = BookOrbitEmailApi { path, method, body ->
            paths += path
            assertEquals("GET", method)
            assertNull(body)
            when (path) {
                "/api/v1/email/recipients" -> """[{"id":7,"name":"Kindle","email":"reader@example.com","isDefault":true}]"""
                "/api/v1/email/providers" -> """[{"id":3,"name":"Personal"},{"id":9,"name":"Shared"}]"""
                "/api/v1/email/preferences" -> """{"defaultProviderId":9}"""
                else -> error("Unexpected path: $path")
            }
        }
        val options = api.loadOptions()
        assertTrue(options.isAvailable)
        assertEquals(BookEmailRecipient(7, "Kindle", "reader@example.com", true), options.recipients.single())
        assertEquals(9L, options.defaultProviderId)
        assertEquals(3, paths.size)
    }

    @Test
    fun hidesActionWhenRecipientsOrProvidersAreMissing() = runTest {
        for (missingRecipients in listOf(true, false)) {
            val api = BookOrbitEmailApi { path, _, _ ->
                when (path) {
                    "/api/v1/email/recipients" -> if (missingRecipients) "[]" else """[{"id":7,"email":"reader@example.com"}]"""
                    "/api/v1/email/providers" -> "[]"
                    else -> error("Should not request preferences without email setup")
                }
            }
            assertFalse(api.loadOptions().isAvailable)
        }
    }

    @Test
    fun oldServersAndForbiddenAccountsHaveNoEmailAction() = runTest {
        for (status in listOf(403, 404, 405, 501)) {
            val api = BookOrbitEmailApi { _, _, _ -> throw HttpRequestException(status, "load recipients") }
            assertFalse(api.loadOptions().isAvailable)
        }
    }

    @Test
    fun authenticationAndServerFailuresAreNotTreatedAsMissingSetup() = runTest {
        for (error in listOf(AuthenticationRequiredException(), HttpRequestException(500, "load recipients"))) {
            val api = BookOrbitEmailApi { _, _, _ -> throw error }
            try {
                api.loadOptions()
                fail("Expected failure")
            } catch (actual: Exception) {
                assertEquals(error.javaClass, actual.javaClass)
                assertEquals(error.message, actual.message)
                if (actual is HttpRequestException) assertEquals(500, actual.code)
            }
        }
    }

    @Test
    fun queuesSelectedEbookFileForSelectedRecipients() = runTest {
        var submitted: JSONObject? = null
        val api = BookOrbitEmailApi { path, method, body ->
            assertEquals("/api/v1/email/send", path)
            assertEquals("POST", method)
            assertEquals("application/json; charset=utf-8", body!!.contentType().toString())
            submitted = JSONObject(readBody(body))
            """{"queued":2}"""
        }
        assertEquals(2, api.send(book.copy(fileId = "99"), setOf(7, 8), 3))
        val payload = submitted!!
        assertEquals(42L, payload.getJSONArray("bookIds").getLong(0))
        assertEquals(99L, payload.getLong("fileId"))
        assertEquals(listOf(7L, 8L), (0..1).map { payload.getJSONArray("recipientIds").getLong(it) })
        assertEquals(3L, payload.getLong("providerId"))
        assertFalse(payload.has("templateId")) // Server retains recipient/template defaults.
    }

    @Test
    fun rejectsInvalidBooksAndEmptyRecipientsBeforeRequestingDelivery() = runTest {
        val api = BookOrbitEmailApi { _, _, _ -> error("Must not send") }
        val invalidBooks = listOf(
            book.copy(mediaKind = MediaKind.AUDIO),
            book.copy(mediaKind = MediaKind.COMIC),
            book.copy(isServerMissing = true),
            book.copy(libraryId = ""),
            book.copy(id = "local-book"),
            book.copy(fileId = null),
            book.copy(fileId = "-1")
        )
        invalidBooks.forEach { invalid ->
            assertFalse(canEmailBook(invalid))
            try {
                api.send(invalid, setOf(7), 3)
                fail("Expected invalid book rejection")
            } catch (_: IllegalArgumentException) {
                // Invalid books must be rejected before making a request.
            }
        }
        try {
            api.send(book, emptySet(), 3)
            fail("Expected recipient rejection")
        } catch (_: IllegalArgumentException) {
            // Sending requires at least one recipient.
        }
        assertTrue(canEmailBook(book.copy(mediaKind = MediaKind.PDF)))
        assertTrue(canEmailBook(book.copy(localPath = "/downloads/book.epub")))
    }

    @Test
    fun sendFailuresPropagateAndAreNeverAutomaticallyRepeated() = runTest {
        var calls = 0
        val api = BookOrbitEmailApi { _, _, _ ->
            calls++
            throw HttpRequestException(400, "queue ebook")
        }
        try {
            api.send(book, setOf(7), 3)
            fail("Expected server failure")
        } catch (error: HttpRequestException) {
            assertEquals(400, error.code)
        }
        assertEquals(1, calls)
    }

    @Test
    fun zeroQueuedEmailsDoesNotReportSuccess() = runTest {
        val api = BookOrbitEmailApi { _, _, _ -> """{"queued":0}""" }
        try {
            api.send(book, setOf(7), 3)
            fail("Expected no-delivery failure")
        } catch (error: BookEmailException) {
            assertEquals(BookEmailFailure.NONE_QUEUED, error.reason)
        }
    }

    private fun readBody(body: RequestBody): String = Buffer().apply { body.writeTo(this) }.readUtf8()
}
