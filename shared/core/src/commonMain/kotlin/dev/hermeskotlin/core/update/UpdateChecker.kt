package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A release newer than the installed app. [downloadUrl] is the file attached to it for this device (an APK, or a
 * desktop's installer), when there is one.
 */
data class AppUpdate(val version: String, val pageUrl: String, val downloadUrl: String?)

/** The ABIs this device runs, most preferred first (`Build.SUPPORTED_ABIS`). */
internal expect fun deviceAbis(): List<String>

/** On a desktop, the end of its installer's name (".msi", ".dmg", ".deb"); null on a phone, which takes an APK. */
internal expect fun desktopInstaller(): String?

/**
 * Looks for a newer release of the app on GitHub, where it is published (`releases/latest`, which
 * skips drafts and pre-releases). Nothing is sent but the request itself; a failure just means no update.
 * [abis] picks which of the release's APKs to offer.
 */
class UpdateChecker(
    private val client: HttpClient,
    private val store: KeyValueStore,
    private val abis: List<String> = deviceAbis(),
    private val installer: String? = desktopInstaller(),
) {

    /** The latest release of [repo] (`owner/name`) when it is newer than [current], unless it was dismissed. */
    suspend fun check(repo: String, current: String): AppUpdate? {
        if (repo.isBlank() || parseVersion(current) == null) return null
        val release = try {
            // A renamed repo answers at its old name with a redirect; the client doesn't follow those itself.
            var url = "$GITHUB_API/repos/$repo/releases/latest"
            var response = latest(url)
            repeat(MAX_REDIRECTS) {
                if (response.status.value !in REDIRECTS) return@repeat
                url = response.headers[HttpHeaders.Location]?.takeIf { it.startsWith("$GITHUB_API/") } ?: return null
                response = latest(url)
            }
            if (!response.status.isSuccess()) return null
            HermesJson.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val update = parseRelease(release, abis, installer) ?: return null
        if (!isNewer(update.version, current)) return null
        if (store.get(DISMISSED_KEY) == update.version) return null
        return update
    }

    /** Stops offering [version]; a later one is offered again. */
    suspend fun dismiss(version: String) = store.put(DISMISSED_KEY, version)

    private suspend fun latest(url: String) = client.get(url) {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.UserAgent, "herald-android")
    }

    private companion object {
        const val DISMISSED_KEY = "update.dismissed.v1"
        const val GITHUB_API = "https://api.github.com"
        const val MAX_REDIRECTS = 3
        val REDIRECTS = setOf(301, 302, 307, 308)
    }
}

/**
 * A release names one APK per ABI (`kyllene-1.2.3_arm64-v8a.apk`) beside the universal one (`kyllene-1.2.3.apk`):
 * the one for the first of [abis] that has its own wins, then the universal one, then any APK. The watch app's APK
 * (`kyllene-wear-1.2.3.apk`) is never offered: it installs on the watch, not the phone. It is known by `-wear-` after
 * the app's name, whatever that name is, so older `herald-wear-1.2.3.apk` releases are skipped too.
 *
 * A desktop ([installer] set) takes the installer for its system instead (`kyllene-desktop-1.2.3.msi`).
 */
internal fun parseRelease(release: JsonObject, abis: List<String>, installer: String? = null): AppUpdate? {
    val tag = release.text("tag_name") ?: return null
    val page = release.text("html_url") ?: return null
    val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (installer != null) {
        val file = assets.firstOrNull { asset ->
            asset.text("name")?.let { it.startsWith(DESKTOP_PREFIX, ignoreCase = true) && it.endsWith(installer, ignoreCase = true) } == true
        }
        return AppUpdate(tag.removePrefix("v"), page, file?.text("browser_download_url"))
    }
    val apks = assets
        .mapNotNull { asset -> asset.text("name")?.takeIf { it.endsWith(".apk", ignoreCase = true) }?.let { it.dropLast(4) to asset } }
        .filterNot { (name, _) -> WEAR_APK.containsMatchIn(name) }
    val forAbi = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { (name, _) -> name.endsWith("_$abi") } }
    val universal = apks.firstOrNull { (name, _) -> APK_ABIS.none { name.endsWith("_$it") } }
    val apk = (forAbi ?: universal ?: apks.firstOrNull())?.second?.text("browser_download_url")
    return AppUpdate(tag.removePrefix("v"), page, apk)
}

/** The ABIs a release builds an APK of its own for; see androidApp's `splits`. */
private val APK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** How release.yml names the Wear OS app's APK: the app's name, then `-wear-` and the version. */
private val WEAR_APK = Regex("-wear-\\d", RegexOption.IGNORE_CASE)

/** How release.yml names the desktop installers. */
private const val DESKTOP_PREFIX = "kyllene-desktop-"

/** True when [candidate] is a higher `major.minor.patch` than [current]; a leading `v` and any `-suffix` are ignored. */
internal fun isNewer(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun parseVersion(text: String): List<Int>? =
    text.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.')
        .map { it.toIntOrNull() ?: return null }
        .takeIf { it.isNotEmpty() }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
