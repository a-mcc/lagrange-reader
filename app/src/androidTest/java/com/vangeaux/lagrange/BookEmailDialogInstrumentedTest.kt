package com.vangeaux.lagrange

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookEmailDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val book = BookSummary(
        libraryId = "1",
        id = "42",
        fileId = "56",
        title = "An ebook",
        format = "epub",
        mediaKind = MediaKind.EPUB
    )
    private val recipient = BookEmailRecipient(7, "My Kindle", "reader@example.com", true)
    private val provider = BookEmailProvider(9, "Proton", true)
    private val template = BookEmailTemplate(5, "Standard", true)
    private val options = BookEmailOptions(
        recipients = listOf(recipient),
        providers = listOf(provider),
        defaultProviderId = provider.id,
        templates = listOf(template),
        defaultTemplateId = template.id
    )

    @Test
    fun singleChoicesHideDropdownsAndSendProviderWithoutTemplateOverride() {
        var submitted: Triple<Set<Long>, Long, Long?>? = null
        showDialog(options) { ids, providerId, templateId ->
            submitted = Triple(ids, providerId, templateId)
        }
        composeRule.onNodeWithTag("book-email-choice-EMAIL PROVIDER").assertDoesNotExist()
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE").assertDoesNotExist()
        composeRule.onNodeWithTag("book-email-recipient-7").assertIsOn()
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle {
            val selection = requireNotNull(submitted)
            assertEquals(setOf(7L), selection.first)
            assertEquals(9L, selection.second)
            assertNull(selection.third)
        }
    }

    @Test
    fun noTemplatesStillAllowsSendingWithoutAnOverride() {
        var submitted = false
        showDialog(options.copy(templates = emptyList(), defaultTemplateId = null)) { _, _, templateId ->
            submitted = true
            assertNull(templateId)
        }
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE").assertDoesNotExist()
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle { assertTrue(submitted) }
    }

    @Test
    fun recipientsPutDefaultsFirstThenSortIgnoringCaseAndSendOnlySelections() {
        var submitted: Set<Long>? = null
        val recipients = listOf(
            BookEmailRecipient(8, "beta", "beta@example.com"),
            recipient.copy(name = "Zulu"),
            BookEmailRecipient(6, "Alpha", "alpha@example.com")
        )
        showDialog(options.copy(recipients = recipients)) { ids, _, _ -> submitted = ids }
        assertAbove("book-email-recipient-7", "book-email-recipient-6")
        assertAbove("book-email-recipient-6", "book-email-recipient-8")
        composeRule.onNodeWithTag("book-email-recipient-7").assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithTag("book-email-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("book-email-recipient-6").performClick().assertIsOn()
        composeRule.onNodeWithTag("book-email-recipient-8").performClick().assertIsOn()
        assertAbove("book-email-recipient-7", "book-email-recipient-6")
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle { assertEquals(setOf(6L, 8L), submitted) }
    }

    @Test
    fun providerDefaultIsSelectedAndBadgedButMenuRemainsAlphabetical() {
        var submitted: Long? = null
        val providers = listOf(
            BookEmailProvider(10, "zulu"),
            provider,
            BookEmailProvider(3, "Alpha")
        )
        showDialog(options.copy(providers = providers)) { _, providerId, _ -> submitted = providerId }
        composeRule.onNodeWithTag("book-email-choice-EMAIL PROVIDER")
            .assertTextContains("Proton").assertTextContains("Default").performClick()
        assertAbove("book-email-option-EMAIL PROVIDER-3", "book-email-option-EMAIL PROVIDER-9")
        assertAbove("book-email-option-EMAIL PROVIDER-9", "book-email-option-EMAIL PROVIDER-10")
        composeRule.onNodeWithTag("book-email-option-EMAIL PROVIDER-9").assertTextContains("Default")
        composeRule.onNodeWithTag("book-email-option-EMAIL PROVIDER-3").performClick()
        composeRule.onNodeWithTag("book-email-option-EMAIL PROVIDER-9").assertDoesNotExist()
        composeRule.onNodeWithTag("book-email-choice-EMAIL PROVIDER").assertTextContains("Alpha").performClick()
        composeRule.onNodeWithTag("book-email-option-EMAIL PROVIDER-9").assertTextContains("Default")
        composeRule.onNodeWithTag("book-email-option-EMAIL PROVIDER-3").performClick()
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle { assertEquals(3L, submitted) }
    }

    @Test
    fun namedTemplateSelectionSendsOverrideAndRetainsDefaultBadge() {
        var submitted: Long? = null
        showDialog(multipleTemplates()) { _, _, templateId -> submitted = templateId }
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE")
            .assertTextContains("Use recipient defaults").performClick()
        assertAbove("book-email-option-TEMPLATE-recipient-defaults", "book-email-option-TEMPLATE-2")
        assertAbove("book-email-option-TEMPLATE-2", "book-email-option-TEMPLATE-5")
        composeRule.onNodeWithTag("book-email-option-TEMPLATE-5").assertTextContains("Default").performClick()
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE")
            .assertTextContains("Standard").assertTextContains("Default")
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle { assertEquals(5L, submitted) }
    }

    @Test
    fun returningToRecipientDefaultsClearsTheTemplateOverride() {
        var submitted = false
        showDialog(multipleTemplates()) { _, _, templateId ->
            submitted = true
            assertNull(templateId)
        }
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE").performClick()
        composeRule.onNodeWithTag("book-email-option-TEMPLATE-2").performClick()
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE").assertTextContains("Alpha").performClick()
        composeRule.onNodeWithTag("book-email-option-TEMPLATE-5").assertTextContains("Default")
        composeRule.onNodeWithTag("book-email-option-TEMPLATE-recipient-defaults").performClick()
        composeRule.onNodeWithTag("book-email-choice-TEMPLATE").assertTextContains("Use recipient defaults")
        composeRule.onNodeWithTag("book-email-confirm").performClick()
        composeRule.runOnIdle { assertTrue(submitted) }
    }

    private fun multipleTemplates() = options.copy(templates = listOf(template, BookEmailTemplate(2, "Alpha")))

    private fun showDialog(
        emailOptions: BookEmailOptions,
        onSend: (Set<Long>, Long, Long?) -> Unit
    ) {
        val state = BookEmailActionState(mutableStateOf(emailOptions), eligible = true).apply { openPicker() }
        composeRule.setContent {
            MaterialTheme {
                BookEmailFeedback(book, state) { _, ids, providerId, templateId ->
                    onSend(ids, providerId, templateId)
                    ids.size
                }
            }
        }
    }

    private fun assertAbove(firstTag: String, secondTag: String) {
        val first = composeRule.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$firstTag must appear above $secondTag", first.top < second.top)
    }
}
