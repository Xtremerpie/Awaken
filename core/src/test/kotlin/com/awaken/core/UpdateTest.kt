package com.awaken.core

import com.awaken.core.remote.DashboardWidget
import com.awaken.core.remote.FeatureFlag
import com.awaken.core.remote.RemoteConfigParser
import com.awaken.core.remote.SettingsSection
import com.awaken.core.update.ApkVerifier
import com.awaken.core.update.ArchiveFacts
import com.awaken.core.update.Hashing
import com.awaken.core.update.ManifestParse
import com.awaken.core.update.SecureUrl
import com.awaken.core.update.UpdateDecision
import com.awaken.core.update.UpdateManifest
import com.awaken.core.update.UpdateManifestParser
import com.awaken.core.update.UpdateState
import com.awaken.core.update.VerifyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private val sha = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val pkg = "com.awaken.app"

    private fun rawManifest(over: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "packageName" to pkg, "versionCode" to 5, "versionName" to "2.0.0",
        "minSupportedVersionCode" to 1, "mandatory" to false, "releaseDate" to "2026-12-01",
        "sizeBytes" to 1000, "sha256" to sha, "apkUrl" to "https://updates.example.com/awaken-2.0.0.apk",
        "signerSha256" to listOf(signer), "changelog" to listOf("New thing", " "),
    ) + over

    private fun manifest(over: Map<String, Any?> = emptyMap()) = (UpdateManifestParser.parse(rawManifest(over)) as ManifestParse.Ok).manifest

    @Test fun parsesValidManifest() {
        val m = manifest()
        assertEquals(5L, m.versionCode)
        assertEquals(listOf("New thing"), m.changelog)
    }

    @Test fun rejectsInsecureOrMalformedManifests() {
        fun bad(over: Map<String, Any?>) = assertTrue(UpdateManifestParser.parse(rawManifest(over)) is ManifestParse.Bad)
        bad(mapOf("apkUrl" to "http://updates.example.com/a.apk"))
        bad(mapOf("apkUrl" to "https://127.0.0.1/a.apk"))
        bad(mapOf("apkUrl" to "https://user:pw@updates.example.com/a.apk"))
        bad(mapOf("sha256" to "xyz"))
        bad(mapOf("sizeBytes" to 0))
        bad(mapOf("schemaVersion" to 99))
        bad(mapOf("versionCode" to null))
        bad(mapOf("signerSha256" to listOf("nothex")))
    }

    @Test fun secureUrlRules() {
        assertTrue(SecureUrl.isAllowed("https://example.com/a.apk"))
        assertFalse(SecureUrl.isAllowed("https://localhost/a.apk"))
        assertFalse(SecureUrl.isAllowed("https://192.168.1.4/a.apk"))
        assertFalse(SecureUrl.isAllowed("ftp://example.com/a.apk"))
        assertFalse(SecureUrl.isAllowed("not a url"))
    }

    @Test fun updateAvailability() {
        assertEquals(UpdateState.UpToDate, UpdateDecision.evaluate(pkg, 5, manifest()))
        val optional = UpdateDecision.evaluate(pkg, 4, manifest()) as UpdateState.Available
        assertFalse(optional.mandatory)
        assertEquals(0L, UpdateDecision.requiredVersionCode(optional))
        assertTrue(UpdateDecision.evaluate("other.app", 1, manifest()) is UpdateState.Invalid)
    }

    @Test fun mandatoryUpdateViaFlagOrMinimumVersion() {
        val byFlag = UpdateDecision.evaluate(pkg, 4, manifest(mapOf("mandatory" to true))) as UpdateState.Available
        assertTrue(byFlag.mandatory)
        assertEquals(5L, UpdateDecision.requiredVersionCode(byFlag))
        val byMin = UpdateDecision.evaluate(pkg, 2, manifest(mapOf("minSupportedVersionCode" to 3))) as UpdateState.Available
        assertTrue(byMin.mandatory)
    }

    @Test fun inconsistentMandatoryManifestIsInvalidNotALockout() {
        assertTrue(UpdateDecision.evaluate(pkg, 2, manifest(mapOf("versionCode" to 2, "minSupportedVersionCode" to 3))) is UpdateState.Invalid)
    }

    @Test fun lockLiftsOnlyWhenAndroidReportsTheRequiredVersion() {
        assertTrue(UpdateDecision.isLocked(4, 5))
        assertFalse(UpdateDecision.isLocked(5, 5))
        assertFalse(UpdateDecision.isLocked(1, 0))
    }

    private fun facts(
        size: Long = 1000, hash: String = sha, p: String? = pkg, vc: Long? = 5, signers: Set<String> = setOf(signer),
    ) = ArchiveFacts(size, hash, p, vc, signers)

    private fun verify(f: ArchiveFacts, m: UpdateManifest = manifest(), installedSigners: Set<String> = setOf(signer)) =
        ApkVerifier.verify(m, f, pkg, 4, installedSigners)

    @Test fun verifierAcceptsAGoodApk() = assertEquals(VerifyResult.Ok, verify(facts()))

    @Test fun verifierRejectsEveryTamperingCase() {
        assertTrue(verify(facts(size = 999)) is VerifyResult.Fail)
        assertTrue(verify(facts(hash = "c".repeat(64))) is VerifyResult.Fail)
        assertTrue(verify(facts(p = "evil.app")) is VerifyResult.Fail)
        assertTrue(verify(facts(p = null)) is VerifyResult.Fail)
        assertTrue(verify(facts(vc = 6)) is VerifyResult.Fail)
        assertTrue(verify(facts(signers = emptySet())) is VerifyResult.Fail)
        assertTrue(verify(facts(signers = setOf("d".repeat(64)))) is VerifyResult.Fail)
        assertTrue(verify(facts(), installedSigners = setOf("d".repeat(64))) is VerifyResult.Fail)
        // downgrade
        assertTrue(ApkVerifier.verify(manifest(), facts(), pkg, 5, setOf(signer)) is VerifyResult.Fail)
    }

    @Test fun verifierHonoursManifestPinAndColonFingerprints() {
        val pinnedElsewhere = manifest(mapOf("signerSha256" to listOf("e".repeat(64))))
        assertTrue(verify(facts(), pinnedElsewhere) is VerifyResult.Fail)
        val colons = signer.chunked(2).joinToString(":").uppercase()
        assertEquals(VerifyResult.Ok, verify(facts(signers = setOf(colons)), installedSigners = setOf(colons)))
    }

    @Test fun sha256IsCorrect() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc".toByteArray()))
        assertEquals(Hashing.sha256Hex("abc".toByteArray()), Hashing.sha256Hex("abc".byteInputStream()))
    }

    // ---- remote config ----
    @Test fun remoteConfigDropsUnknownAndUnsafeContent() {
        val raw = mapOf(
            "schemaVersion" to 1, "revision" to 7,
            "dashboardLayout" to listOf("clock", "evil_widget", "history", "clock"),
            "featureFlags" to mapOf("visualizer" to false, "made_up" to true, "history" to "yes"),
            "hiddenSettings" to listOf("backup", "updates", "reset", "nonsense"),
            "texts" to mapOf("home.greeting" to "Rise.", "script" to "alert(1)"),
            "branding" to mapOf("primary" to "#FFAA00", "secondary" to "red"),
            "featuredMedia" to listOf(
                mapOf("id" to "ok", "title" to "Chime", "url" to "https://cdn.example.com/chime.mp3", "sha256" to sha, "sizeBytes" to 5000),
                mapOf("id" to "http", "title" to "Bad", "url" to "http://cdn.example.com/chime.mp3", "sha256" to sha, "sizeBytes" to 5000),
                mapOf("id" to "exe", "title" to "Bad", "url" to "https://cdn.example.com/run.sh", "sha256" to sha, "sizeBytes" to 5000),
            ),
            "suggestedSchedules" to listOf(
                mapOf("id" to "wake", "name" to "Wake", "kind" to "alarm_clock", "start" to "06:30", "days" to listOf("mon", "tue")),
                mapOf("id" to "bad", "name" to "Bad", "kind" to "recurring", "start" to "06:30", "intervalMinutes" to 0),
            ),
        )
        val c = RemoteConfigParser.parse(raw, supportedSchema = 1)!!
        assertEquals(listOf(DashboardWidget.CLOCK, DashboardWidget.HISTORY), c.dashboardLayout)
        assertEquals(mapOf(FeatureFlag.VISUALIZER to false), c.featureFlags)
        assertFalse(c.flag(FeatureFlag.VISUALIZER))
        assertTrue(c.flag(FeatureFlag.HISTORY))
        assertEquals(setOf(SettingsSection.BACKUP), c.hiddenSettings)
        assertEquals(mapOf("home.greeting" to "Rise."), c.texts)
        assertEquals(0xFFFFAA00, c.branding!!.primary)
        assertNull(c.branding!!.secondary)
        assertEquals(listOf("ok"), c.featuredMedia.map { it.id })
        assertEquals(listOf("wake"), c.suggestedSchedules.map { it.id })
        assertEquals(2, c.suggestedSchedules[0].days.size)
    }

    @Test fun remoteConfigFromNewerSchemaIsIgnored() {
        assertNull(RemoteConfigParser.parse(mapOf("schemaVersion" to 2), supportedSchema = 1))
        assertNull(RemoteConfigParser.parse(emptyMap(), supportedSchema = 1))
    }
}
