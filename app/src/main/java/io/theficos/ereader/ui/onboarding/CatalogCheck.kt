package io.theficos.ereader.ui.onboarding

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.opds.CatalogFailure
import io.theficos.ereader.data.opds.ProbeError
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.data.opds.ServerProbeResult
import io.theficos.ereader.data.opds.cleartextBlockedMessage
import io.theficos.ereader.data.opds.isAcquisitionRel
import io.theficos.ereader.data.opds.isEpubMediaType
import io.theficos.ereader.data.opds.parseXmlOrNull
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The connection test for a catalog before it is saved, shared by onboarding and Settings. The
 * probe tells Calibre-Web from any other OPDS catalog, so the reader never picks a type. Calls are
 * blocking: run them off the main thread.
 *
 * The client carries no account interceptor, so no stored login can leak into a test request.
 * The address may embed an API key, so no failure message quotes it.
 */
class CatalogCheck(
    private val probe: ServerProbe = ServerProbe(),
    private val client: OkHttpClient = ConnectServerViewModel.defaultOnboardingClient(),
) {
    sealed interface Result {
        data class Found(val catalog: AccountCredentials.OpdsOnly) : Result
        data class Failed(val message: UiText) : Result
    }

    /** Probe [address] and test the catalog it leads to with the optional login. */
    suspend fun check(address: String, username: String?, password: String?): Result =
        verify(probe.probe(address), username, password)

    /** Test the catalog a probe found. A Quire server alone is not a catalog. */
    fun verify(found: ServerProbeResult, username: String?, password: String?): Result {
        val user = username?.trim()?.takeIf { it.isNotEmpty() }
        val pass = password?.takeIf { it.isNotEmpty() }
        if ((user == null) != (pass == null)) return Result.Failed(uiText(R.string.catalogs_failed_login_incomplete))
        return when (found) {
            is ServerProbeResult.Calibre -> calibre(found.canonicalBaseUrl, user, pass)
            is ServerProbeResult.Both -> calibre(found.canonicalBaseUrl, user, pass)
            is ServerProbeResult.GenericOpds -> feed(AccountCredentials.OpdsOnly(found.canonicalCatalogUrl, user, pass))
            is ServerProbeResult.NativeAuth -> Result.Failed(uiText(R.string.catalogs_quire_server))
            is ServerProbeResult.Unknown -> Result.Failed(uiText(R.string.onboarding_check_the_url_including_any_subpath_and))
            is ServerProbeResult.Error -> Result.Failed(probeErrorMessage(found.reason, found.message))
        }
    }

    /**
     * Test [catalog] at its address exactly as saved. Accepts an Atom feed carrying at least one
     * navigation or EPUB acquisition link: Kavita's root is pure navigation, so requiring a book
     * there would reject the very server this was built for.
     */
    fun feed(catalog: AccountCredentials.OpdsOnly): Result {
        val builder = Request.Builder()
            .url(catalog.baseUrl)
            .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
            .header("User-Agent", ServerProbe.USER_AGENT)
            .get()
        basic(catalog.username, catalog.password)?.let { builder.header("Authorization", it) }
        return request(builder.build()) { code, body ->
            when (code) {
                200 -> if (isUsableOpdsFeed(body())) null else uiText(R.string.onboarding_that_url_answered_but_it_isn_t)
                401 -> if (catalog.username == null) uiText(R.string.onboarding_this_catalog_asked_for_a_username_and)
                else uiText(R.string.onboarding_the_catalog_rejected_those_credentials)
                in 500..599 -> uiText(R.string.onboarding_server_error_try_again_later, code)
                else -> uiText(R.string.onboarding_unexpected_response_from_the_catalog, code)
            }
        }.toResult(catalog)
    }

    private fun calibre(base: String, user: String?, pass: String?): Result {
        val catalog = AccountCredentials.OpdsOnly("$base/opds", user, pass, calibre = true)
        val request = Request.Builder()
            .url(catalog.baseUrl)
            .header("Accept", "application/atom+xml")
            .header("User-Agent", ServerProbe.USER_AGENT)
            .apply { basic(user, pass)?.let { header("Authorization", it) } }
            .get()
            .build()
        return request(request) { code, _ ->
            when (code) {
                200 -> null
                401 -> if (user == null) uiText(R.string.onboarding_this_catalog_asked_for_a_username_and)
                else uiText(R.string.onboarding_username_or_password_rejected_by_the_server)
                in 500..599 -> uiText(R.string.onboarding_server_error_try_again_later, code)
                // A server that demands Basic auth everywhere (Komga does) satisfies the Calibre
                // probe's 401-with-challenge test, then 404s here because its catalog lives elsewhere.
                404 -> uiText(R.string.onboarding_this_server_asked_for_a_username_and)
                else -> uiText(R.string.onboarding_unexpected_response_from_opds, code)
            }
        }.toResult(catalog)
    }

    /** Null when the catalog answered as it should, else why not. */
    private fun request(request: Request, judge: (Int, () -> String) -> UiText?): UiText? = try {
        client.newCall(request).execute().use { resp -> judge(resp.code) { resp.body?.string().orEmpty() } }
    } catch (e: IOException) {
        // Worded as the server probe words it, never the raw exception.
        val reason = when {
            cleartextBlockedMessage(e) != null -> ProbeError.Cleartext
            e is SSLException -> ProbeError.Tls
            e is UnknownHostException -> ProbeError.Dns
            e is ConnectException -> ProbeError.ConnectionRefused
            e is InterruptedIOException -> ProbeError.Timeout
            else -> ProbeError.Unexpected
        }
        probeErrorMessage(reason, e.localizedMessage.orEmpty())
    }

    private fun UiText?.toResult(catalog: AccountCredentials.OpdsOnly): Result =
        this?.let(Result::Failed) ?: Result.Found(catalog)

    private fun basic(user: String?, pass: String?): String? =
        if (user == null || pass == null) null
        else "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    private fun isUsableOpdsFeed(body: String): Boolean = runCatching {
        // Shared hardened parser. An inline DocumentBuilderFactory with Apache feature names
        // throws on Android and would make every catalog look invalid on device while the JVM
        // tests stayed green (issue #101).
        val doc = parseXmlOrNull(body.toByteArray()) ?: return false
        val root = doc.documentElement ?: return false
        if (root.localName != "feed" || root.namespaceURI != ConnectServerViewModel.ATOM_NS) return false
        val links = doc.getElementsByTagNameNS(ConnectServerViewModel.ATOM_NS, "link")
        for (i in 0 until links.length) {
            val el = links.item(i) as org.w3c.dom.Element
            val rel = el.getAttribute("rel")
            val type = el.getAttribute("type")
            // The browser's own rules: a stricter test here would reject a feed the catalog screen
            // then renders fine. Flibusta emits bare `application/epub` on some entries (issue #101).
            val isAcquisition = isAcquisitionRel(rel) && isEpubMediaType(type)
            // An entry linking to another Atom feed by a navigation relation is navigation too, as
            // Quire Web reads it: Ebooks libres et gratuits types its home's entries as bare
            // `application/atom+xml`. A blog feed's `replies` link is not.
            val inEntry = (el.parentNode as? org.w3c.dom.Element)?.localName == "entry"
            val isNavigation = rel == "subsection" || type.startsWith("application/atom+xml;profile=opds-catalog") ||
                inEntry && type.startsWith("application/atom+xml") && isNavigationRel(rel)
            if (isAcquisition || isNavigation) return true
        }
        false
    }.getOrDefault(false)
}

private val OPDS_NAVIGATION_REL = Regex("^http://opds-spec\\.org/(sort/|featured|recommended|shelf|subscriptions)")

/** The relations Quire Web follows as navigation, a missing one counting as `alternate`. */
private fun isNavigationRel(rel: String): Boolean =
    rel.isBlank() || rel in setOf("alternate", "subsection", "related") || OPDS_NAVIGATION_REL.containsMatchIn(rel)

/** What the reader is told when a change to the catalog list did not go through. */
fun catalogFailureMessage(reason: CatalogFailure): UiText = uiText(
    when (reason) {
        CatalogFailure.LIMIT_REACHED -> R.string.catalogs_failed_limit
        CatalogFailure.NEEDS_LOGIN -> R.string.catalogs_needs_login
        CatalogFailure.INVALID_NAME -> R.string.catalogs_failed_name
        CatalogFailure.INVALID_URL -> R.string.catalogs_failed_url
        CatalogFailure.HTTPS_REQUIRED -> R.string.catalogs_failed_https
        CatalogFailure.CREDENTIALS_IN_URL -> R.string.catalogs_failed_credentials_in_url
        CatalogFailure.LOGIN_INCOMPLETE -> R.string.catalogs_failed_login_incomplete
        CatalogFailure.ORDER_CHANGED -> R.string.catalogs_failed_order
        CatalogFailure.REMOVED_ELSEWHERE -> R.string.catalogs_failed_removed
        CatalogFailure.NOT_SUPPORTED -> R.string.catalogs_status_not_supported
        CatalogFailure.SIGN_IN_REQUIRED -> R.string.catalogs_failed_sign_in
        CatalogFailure.OFFLINE -> R.string.catalogs_failed_offline
        CatalogFailure.REJECTED -> R.string.catalogs_failed_rejected
    },
)
