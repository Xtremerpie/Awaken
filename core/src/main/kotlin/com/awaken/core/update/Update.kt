package com.awaken.core.update

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** What the update server publishes. Parsed from JSON by the app, validated here. */
data class UpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** Installs older than this are locked out until updated (mandatory update). */
    val minSupportedVersionCode: Long,
    val mandatory: Boolean,
    val releaseDate: String,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    /** Optional pinned signing-certificate SHA-256 fingerprints (lowercase hex, no colons). */
    val signerSha256: List<String>,
    val changelog: List<String>,
)

sealed interface ManifestParse {
    data class Ok(val manifest: UpdateManifest) : ManifestParse
    data class Bad(val reason: String) : ManifestParse
}

object UpdateManifestParser {
    const val SUPPORTED_SCHEMA = 1
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    /** [raw] is a JSON object already converted to plain Kotlin maps/lists/strings/numbers/booleans. */
    fun parse(raw: Map<String, Any?>): ManifestParse {
        fun str(k: String) = (raw[k] as? String)?.trim()
        fun num(k: String) = (raw[k] as? Number)?.toLong()

        val schema = num("schemaVersion")?.toInt() ?: return ManifestParse.Bad("Missing schemaVersion")
        if (schema > SUPPORTED_SCHEMA) return ManifestParse.Bad("Manifest schema $schema is newer than this app understands")
        val pkg = str("packageName") ?: return ManifestParse.Bad("Missing packageName")
        val vc = num("versionCode") ?: return ManifestParse.Bad("Missing versionCode")
        val vn = str("versionName") ?: return ManifestParse.Bad("Missing versionName")
        val size = num("sizeBytes") ?: return ManifestParse.Bad("Missing sizeBytes")
        val sha = str("sha256")?.lowercase() ?: return ManifestParse.Bad("Missing sha256")
        val url = str("apkUrl") ?: return ManifestParse.Bad("Missing apkUrl")

        if (vc <= 0) return ManifestParse.Bad("versionCode must be positive")
        if (size <= 0 || size > MAX_APK_BYTES) return ManifestParse.Bad("sizeBytes out of range")
        if (!Hashing.isSha256Hex(sha)) return ManifestParse.Bad("sha256 must be 64 hex characters")
        if (!SecureUrl.isAllowed(url)) return ManifestParse.Bad("apkUrl must be a public HTTPS URL")

        val signers = (raw["signerSha256"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(Hashing::normalizeFingerprint) }
        if (signers.any { !Hashing.isSha256Hex(it) }) return ManifestParse.Bad("signerSha256 entries must be SHA-256 fingerprints")

        return ManifestParse.Ok(
            UpdateManifest(
                schemaVersion = schema,
                packageName = pkg,
                versionCode = vc,
                versionName = vn,
                minSupportedVersionCode = num("minSupportedVersionCode") ?: 0L,
                mandatory = raw["mandatory"] as? Boolean ?: false,
                releaseDate = str("releaseDate").orEmpty(),
                sizeBytes = size,
                sha256 = sha,
                apkUrl = url,
                signerSha256 = signers,
                changelog = (raw["changelog"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.take(50),
            )
        )
    }
}

sealed interface UpdateState {
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest, val mandatory: Boolean) : UpdateState
    data class Invalid(val reason: String) : UpdateState
}

object UpdateDecision {
    fun evaluate(installedPackage: String, installedVersionCode: Long, m: UpdateManifest): UpdateState {
        if (m.packageName != installedPackage) {
            return UpdateState.Invalid("Manifest is for ${m.packageName}, not $installedPackage")
        }
        val mandatory = m.mandatory || installedVersionCode < m.minSupportedVersionCode
        if (m.versionCode <= installedVersionCode) {
            return if (installedVersionCode < m.minSupportedVersionCode) {
                UpdateState.Invalid("Manifest requires version ${m.minSupportedVersionCode} but offers ${m.versionCode}")
            } else UpdateState.UpToDate
        }
        if (m.minSupportedVersionCode > m.versionCode) {
            return UpdateState.Invalid("minSupportedVersionCode is higher than the offered versionCode")
        }
        return UpdateState.Available(m, mandatory)
    }

    /** The lock persists (even offline) until Android reports an installed version that satisfies it. */
    fun isLocked(installedVersionCode: Long, requiredVersionCode: Long): Boolean =
        requiredVersionCode > 0 && installedVersionCode < requiredVersionCode

    /** The version code that must be installed to lift a lock, or 0 if this update is optional. */
    fun requiredVersionCode(state: UpdateState.Available): Long =
        if (state.mandatory) maxOf(state.manifest.minSupportedVersionCode, state.manifest.versionCode) else 0L
}

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Fail(val reason: String) : VerifyResult
}

/** Facts read from the downloaded archive by the Android layer (PackageManager.getPackageArchiveInfo). */
data class ArchiveFacts(
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    /** SHA-256 fingerprints of the archive's signing certificates. */
    val signerSha256: Set<String>,
)

object ApkVerifier {
    /**
     * All checks must pass before the system installer is opened:
     * size + SHA-256 integrity, package identity, strictly newer version, and a signing certificate
     * that matches the one that signed the installed app (and the manifest pin, if the manifest has one).
     */
    fun verify(
        manifest: UpdateManifest,
        archive: ArchiveFacts,
        installedPackage: String,
        installedVersionCode: Long,
        installedSignerSha256: Set<String>,
    ): VerifyResult {
        if (archive.sizeBytes != manifest.sizeBytes) {
            return VerifyResult.Fail("Downloaded size ${archive.sizeBytes} does not match the manifest (${manifest.sizeBytes}).")
        }
        if (!archive.sha256.equals(manifest.sha256, ignoreCase = true)) {
            return VerifyResult.Fail("Checksum mismatch: the file is corrupted or was tampered with.")
        }
        val pkg = archive.packageName ?: return VerifyResult.Fail("The file is not a readable Android package.")
        if (pkg != installedPackage) return VerifyResult.Fail("Package identity mismatch ($pkg).")
        val vc = archive.versionCode ?: return VerifyResult.Fail("Package has no version.")
        if (vc != manifest.versionCode) return VerifyResult.Fail("Package version $vc does not match the manifest (${manifest.versionCode}).")
        if (vc <= installedVersionCode) return VerifyResult.Fail("Package is not newer than the installed version.")

        val signers = archive.signerSha256.map(Hashing::normalizeFingerprint).toSet()
        if (signers.isEmpty()) return VerifyResult.Fail("Package is not signed.")
        val installed = installedSignerSha256.map(Hashing::normalizeFingerprint).toSet()
        if (installed.isEmpty() || signers != installed) {
            return VerifyResult.Fail("Signing certificate does not match the installed app; Android would reject this update.")
        }
        if (manifest.signerSha256.isNotEmpty() && signers.none { it in manifest.signerSha256 }) {
            return VerifyResult.Fail("Signing certificate is not on the manifest's pinned list.")
        }
        return VerifyResult.Ok
    }
}

object SecureUrl {
    /** HTTPS only, no credentials in the URL, and not a loopback/private/IP-literal host. */
    fun isAllowed(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.contains(':')) return false // IPv6 literal
        if (host.all { it.isDigit() || it == '.' }) return false // IPv4 literal
        return host.contains('.')
    }
}

object Hashing {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun isSha256Hex(s: String) = HEX64.matches(s)

    /** Accepts "AA:BB:.." or "aabb.." and returns lowercase hex without separators. */
    fun normalizeFingerprint(s: String) = s.replace(":", "").trim().lowercase()

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
