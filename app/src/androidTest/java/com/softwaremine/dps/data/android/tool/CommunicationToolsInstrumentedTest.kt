package com.softwaremine.dps.data.android.tool

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.intent.EmailIntentBuilder
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.data.android.intent.WhatsAppIntentBuilder
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Phase C communication layer.
 *
 * ## What these deliberately do not do
 * **No test sends a message or an email**, and none launches WhatsApp or an
 * email app. Intent *construction* and *resolution* are verified; launching is
 * not, because a passing test suite that leaves apps open and messages half
 * composed on the developer's device is not an acceptable trade.
 *
 * The launch path itself is thin — `IntentLauncher.launch` adds one flag and
 * calls `startActivity` — and its decision-making half, resolution, **is**
 * covered here.
 *
 * ## Grant state is not asserted
 * Whether READ_CONTACTS is granted depends on the device. Both paths are
 * asserted instead: granted leads to execution, missing leads to
 * `PermissionRequired`, and neither crashes.
 */
@RunWith(AndroidJUnit4::class)
class CommunicationToolsInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun container() = AiContainer(context)
    private fun launcher() = IntentLauncher(context, logger)

    // -----------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------

    @Test
    fun phaseCToolsAreRegisteredAsRealImplementations() {
        val registry = container().toolRegistry

        assertTrue(registry.find(ToolId.CONTACTS) is AndroidContactsTool)
        assertTrue(registry.find(ToolId.WHATSAPP) is PrepareWhatsAppMessageTool)
        assertTrue(registry.find(ToolId.GMAIL) is PrepareEmailTool)
    }

    /** No id may be registered twice, and every id must still resolve. */
    @Test
    fun registryStillHoldsEveryToolIdExactlyOnce() {
        val registry = container().toolRegistry

        assertEquals(ToolId.entries.toSet(), registry.registeredIds())
        assertEquals(ToolId.entries.size, registry.all().size)
        assertEquals(registry.all().size, registry.all().distinctBy { it.id }.size)
    }

    /** Phone remains a declaration — Phase C implements three tools, not four. */
    @Test
    fun outOfScopeToolsRemainUnimplemented() {
        assertTrue(container().toolRegistry.find(ToolId.PHONE) is UnimplementedTool)
    }

    // -----------------------------------------------------------------
    // Intent construction
    // -----------------------------------------------------------------

    @Test
    fun whatsAppIntentIsBuiltWithTheDocumentedUrlShape() {
        val intent = WhatsAppIntentBuilder.buildConversationIntent("+92 300 1234567", "Hello there")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        val uri = intent.data!!
        assertEquals("https", uri.scheme)
        assertEquals("wa.me", uri.host)
        // Digits only — punctuation would open WhatsApp to nothing.
        assertEquals("/923001234567", uri.path)
        assertEquals("Hello there", uri.getQueryParameter("text"))
    }

    /**
     * Query encoding must survive characters that would otherwise truncate or
     * corrupt the URL.
     */
    @Test
    fun whatsAppMessageWithSpecialCharactersIsEncoded() {
        val message = "Meet at 5 & bring #notes?"
        val intent = WhatsAppIntentBuilder.buildConversationIntent("923001234567", message)

        // Decoded back through the Uri API, the message must be unchanged.
        assertEquals(message, intent.data!!.getQueryParameter("text"))
    }

    @Test
    fun whatsAppIntentWithBlankMessageOmitsTheTextParameter() {
        val intent = WhatsAppIntentBuilder.buildConversationIntent("923001234567", "")
        assertEquals(null, intent.data!!.getQueryParameter("text"))
    }

    /**
     * `ACTION_SENDTO` with a bare `mailto:` is what restricts resolution to
     * email apps. `ACTION_SEND` would also match messaging and social apps.
     */
    @Test
    fun emailIntentUsesSendToWithMailtoSoOnlyEmailAppsRespond() {
        val intent = EmailIntentBuilder.buildComposeIntent(
            recipients = listOf("abdul@example.com"),
            subject = "Report",
            body = "Attached below.",
        )

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertEquals(
            arrayOf("abdul@example.com").toList(),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList(),
        )
        assertEquals("Report", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Attached below.", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun emailIntentOmitsBlankSubjectAndBody() {
        val intent = EmailIntentBuilder.buildComposeIntent(listOf("a@b.com"), subject = "", body = null)

        assertEquals(null, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(null, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    // -----------------------------------------------------------------
    // Intent resolution (package visibility)
    // -----------------------------------------------------------------

    /**
     * Resolution must not throw regardless of what is installed.
     *
     * With targetSdk 35 this exercises package-visibility filtering: the
     * manifest `<queries>` declaration is what allows these lookups to see
     * anything at all.
     */
    @Test
    fun intentResolutionAnswersWithoutThrowing() {
        val launcher = launcher()

        val whatsApp = WhatsAppIntentBuilder.buildConversationIntent("923001234567", "hi")
        val email = EmailIntentBuilder.buildComposeIntent(listOf("a@b.com"), "s", "b")

        // Either answer is legitimate; not throwing is the requirement.
        val whatsAppHandlers = launcher.resolvedPackages(whatsApp)
        val emailHandlers = launcher.resolvedPackages(email)

        assertNotNull(whatsAppHandlers)
        assertNotNull(emailHandlers)
        android.util.Log.i(
            "DPS/PhaseC",
            "whatsapp handlers=$whatsAppHandlers email handlers=$emailHandlers " +
                "whatsappInstalled=${launcher.isPackageInstalled(WhatsAppIntentBuilder.PACKAGE_NAME)}",
        )
    }

    @Test
    fun packageInstalledCheckIsSafeForAbsentPackages() {
        assertTrue(!launcher().isPackageInstalled("com.example.definitely.not.installed"))
    }

    // -----------------------------------------------------------------
    // Contacts tool
    // -----------------------------------------------------------------

    @Test
    fun contactsToolRequiresAnIdentifier(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CONTACTS)!!

        val result = tool.execute(ToolCall(ToolId.CONTACTS, "find_contact", emptyMap()))

        assertTrue("Expected Failure, got $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("name"))
    }

    @Test
    fun contactsToolRejectsUnknownOperations(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.CONTACTS)!!
        val result = tool.execute(ToolCall(ToolId.CONTACTS, "delete_contact"))
        assertTrue(result is ToolResult.Unsupported)
    }

    /**
     * The executor gate, exercised on both sides.
     *
     * Without READ_CONTACTS the call must never reach the provider; with it,
     * the lookup runs and returns a typed result.
     */
    @Test
    fun contactsLookupRespectsPermissionState(): Unit = runBlocking {
        val container = container()
        val granted = container.permissionManager.state(DpsPermission.READ_CONTACTS).isUsable

        val result = container.toolExecutor.execute(
            ToolCall(ToolId.CONTACTS, "find_contact", mapOf("name" to "Zzzz Unlikely Name")),
        )

        if (granted) {
            assertTrue(
                "With permission the tool should run, got $result",
                result !is ToolResult.PermissionRequired,
            )
            // Nobody is called that, so a clean "no match" is expected.
            assertTrue("Expected Failure or Success, got $result", result is ToolResult.Failure || result is ToolResult.Success)
        } else {
            assertTrue(
                "Without permission the gate must fire, got $result",
                result is ToolResult.PermissionRequired,
            )
            assertEquals(
                listOf(DpsPermission.READ_CONTACTS),
                (result as ToolResult.PermissionRequired).permissions,
            )
        }
    }

    // -----------------------------------------------------------------
    // Confirmation flows — argument validation only, nothing is launched
    // -----------------------------------------------------------------

    @Test
    fun whatsAppRequiresAMessage(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.WHATSAPP)!!

        val result = tool.execute(
            ToolCall(ToolId.WHATSAPP, "prepare_message", mapOf("phone" to "923001234567")),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("message"))
    }

    @Test
    fun whatsAppRequiresARecipient(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.WHATSAPP)!!

        val result = tool.execute(
            ToolCall(ToolId.WHATSAPP, "prepare_message", mapOf("message" to "hello")),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("contact"))
    }

    @Test
    fun whatsAppRejectsAnUnusablePhoneNumber(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.WHATSAPP)!!

        val result = tool.execute(
            ToolCall(
                ToolId.WHATSAPP,
                "prepare_message",
                mapOf("phone" to "123", "message" to "hi"),
            ),
        )

        assertTrue("Expected Failure, got $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("not a usable phone number"))
    }

    /** Both operation names must reach the same preparation. */
    @Test
    fun whatsAppAcceptsBothPrepareAndSendOperationNames() {
        val tool = container().toolRegistry.find(ToolId.WHATSAPP)!!

        assertTrue(tool.supports("prepare_message"))
        assertTrue(tool.supports("send_message"))
    }

    @Test
    fun emailRequiresARecipient(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.GMAIL)!!

        val result = tool.execute(
            ToolCall(ToolId.GMAIL, "compose_email", mapOf("subject" to "Hi")),
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("to"))
    }

    @Test
    fun emailRejectsAnInvalidAddress(): Unit = runBlocking {
        val tool = container().toolRegistry.find(ToolId.GMAIL)!!

        val result = tool.execute(
            ToolCall(ToolId.GMAIL, "compose_email", mapOf("to" to "Abdul", "subject" to "Hi")),
        )

        assertTrue("Expected Failure, got $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("not a valid email address"))
    }

    @Test
    fun emailAcceptsBothComposeAndSendOperationNames() {
        val tool = container().toolRegistry.find(ToolId.GMAIL)!!

        assertTrue(tool.supports("compose_email"))
        assertTrue(tool.supports("send_email"))
    }

    /**
     * The confirmation contract, asserted structurally.
     *
     * Neither tool declares any operation whose name or behaviour delivers a
     * message. The aliases exist so a model's natural vocabulary is accepted,
     * but every path prepares and hands the final action to the user.
     */
    @Test
    fun neitherCommunicationToolCanDeliverWithoutTheUser() {
        val whatsApp = container().toolRegistry.find(ToolId.WHATSAPP)!!
        val email = container().toolRegistry.find(ToolId.GMAIL)!!

        assertEquals(setOf("prepare_message", "send_message"), whatsApp.operations)
        assertEquals(setOf("compose_email", "send_email"), email.operations)

        // Both require contacts access and nothing that would permit sending.
        assertEquals(setOf(DpsPermission.READ_CONTACTS), whatsApp.requiredPermissions)
        assertEquals(setOf(DpsPermission.READ_CONTACTS), email.requiredPermissions)
    }
}
