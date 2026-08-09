package com.softwaremine.dps.data.android.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.contact.ContactRepository
import kotlinx.coroutines.withContext

/**
 * Reads contacts through `ContactsContract`.
 *
 * ## Purpose
 * The only class that touches the Contacts Provider. Keeps URIs, column names
 * and cursor handling in one place so a second contacts consumer cannot grow a
 * subtly different query.
 *
 * ## APIs used — verified against official documentation
 * | API | Purpose |
 * |---|---|
 * | `ContactsContract.Contacts.CONTENT_FILTER_URI` | broad name search |
 * | `Uri.withAppendedPath(base, Uri.encode(query))` | documented way to build a filter URI |
 * | `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` | phone numbers per contact |
 * | `ContactsContract.CommonDataKinds.Email.CONTENT_URI` | email addresses per contact |
 * | `ContactsContract.PhoneLookup.CONTENT_FILTER_URI` | reverse lookup by number |
 * | `READ_CONTACTS` | required permission |
 *
 * `Uri.encode` is applied to the query rather than string concatenation:
 * contact names contain spaces, slashes and `#`, each of which changes the
 * meaning of a URI path if left raw.
 *
 * ## Threading
 * Provider reads are disk-backed IO and run on
 * [DispatcherProvider.io]. Documentation recommends `CursorLoader` for the same
 * reason; coroutines on an IO dispatcher achieve it without tying the query to
 * a Loader lifecycle the tool layer does not have.
 *
 * ## Data minimisation
 * Only id, display name, phone numbers and email addresses are read. Postal
 * addresses, photos, notes and organisations are deliberately never queried —
 * the cheapest way to keep personal data safe is not to read it.
 *
 * ## Dependencies
 * `android.provider.ContactsContract`. Android layer only.
 */
class AndroidContactRepository(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) : ContactRepository {

    override suspend fun searchByName(query: String, limit: Int): ContactRepository.Outcome =
        withContext(dispatchers.io) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext ContactRepository.Outcome.Found(emptyList())

            // Documented construction: append the encoded query to the filter URI.
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(trimmed),
            )

            readContacts(uri, limit)
        }

    override suspend fun searchByPhone(phoneNumber: String, limit: Int): ContactRepository.Outcome =
        withContext(dispatchers.io) {
            val trimmed = phoneNumber.trim()
            if (trimmed.isEmpty()) return@withContext ContactRepository.Outcome.Found(emptyList())

            // PhoneLookup is the provider's own reverse lookup and already
            // handles formatting differences between stored and queried
            // numbers, which a LIKE query over the raw NUMBER column does not.
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(trimmed),
            )

            readContacts(uri, limit, idColumn = ContactsContract.PhoneLookup.CONTACT_ID)
        }

    /**
     * Reads contact identities from [uri], then enriches each with its numbers
     * and addresses.
     *
     * Two stages because contact identity and contact data live in different
     * tables; the filter URI answers "who matches", and the data tables answer
     * "how do I reach them".
     */
    private fun readContacts(
        uri: Uri,
        limit: Int,
        idColumn: String = ContactsContract.Contacts._ID,
    ): ContactRepository.Outcome {
        val projection = arrayOf(idColumn, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

        return try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                ?: return ContactRepository.Outcome.NoProvider

            val identities = mutableListOf<Pair<String, String>>()
            cursor.use {
                val idIndex = it.getColumnIndex(idColumn)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (idIndex < 0 || nameIndex < 0) {
                    return ContactRepository.Outcome.Failed("Contacts provider returned no usable columns.")
                }

                while (it.moveToNext() && identities.size < limit) {
                    val id = it.getString(idIndex) ?: continue
                    val name = it.getString(nameIndex) ?: continue
                    // The same person can appear on several rows when multiple
                    // data fields match the filter.
                    if (identities.none { existing -> existing.first == id }) {
                        identities += id to name
                    }
                }
            }

            val contacts = identities.map { (id, name) ->
                Contact(
                    id = id,
                    displayName = name,
                    phoneNumbers = readPhones(id),
                    emailAddresses = readEmails(id),
                )
            }

            logger.d(TAG, "Contact query returned ${contacts.size} result(s)")
            ContactRepository.Outcome.Found(contacts)
        } catch (security: SecurityException) {
            // Should not occur — the executor gates on READ_CONTACTS first —
            // but the provider may still refuse, and this must not propagate.
            logger.w(TAG, "Contacts query denied", security)
            ContactRepository.Outcome.Failed("Contacts access was denied.")
        } catch (throwable: Throwable) {
            logger.e(TAG, "Contacts query failed", throwable)
            ContactRepository.Outcome.Failed(throwable.message ?: "Could not read contacts.")
        }
    }

    private fun readPhones(contactId: String): List<String> = readDataColumn(
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        column = ContactsContract.CommonDataKinds.Phone.NUMBER,
        selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        contactId = contactId,
    )

    private fun readEmails(contactId: String): List<String> = readDataColumn(
        uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        column = ContactsContract.CommonDataKinds.Email.ADDRESS,
        selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
        contactId = contactId,
    )

    /** Reads one column of a contact's data rows, de-duplicated and order-preserving. */
    private fun readDataColumn(
        uri: Uri,
        column: String,
        selection: String,
        contactId: String,
    ): List<String> = try {
        context.contentResolver.query(uri, arrayOf(column), selection, arrayOf(contactId), null)
            ?.use { cursor ->
                val values = LinkedHashSet<String>()
                val index = cursor.getColumnIndex(column)
                if (index >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let(values::add)
                    }
                }
                values.toList()
            }
            ?: emptyList()
    } catch (throwable: Throwable) {
        // A contact without reachable data is still a valid contact; failing
        // the whole search because one data read failed would be worse.
        logger.w(TAG, "Could not read data column $column for contact $contactId", throwable)
        emptyList()
    }

    private companion object {
        const val TAG = "ContactRepository"
    }
}
