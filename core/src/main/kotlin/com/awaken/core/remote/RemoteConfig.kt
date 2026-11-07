package com.awaken.core.remote

import com.awaken.core.ScheduleKind
import com.awaken.core.update.Hashing
import com.awaken.core.update.SecureUrl
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Remote configuration is DATA, never code. Every field maps onto a capability that already ships in the
 * installed APK; anything the app does not recognise is dropped. New *behaviour* needs a signed APK update.
 */
enum class FeatureFlag(val key: String, val default: Boolean) {
    VISUALIZER("visualizer", true),
    PLAYLISTS("playlists", true),
    HISTORY("history", true),
    ANNOUNCEMENTS("announcements", true),
    FEATURED_CONTENT("featured_content", true),
    SUGGESTED_SCHEDULES("suggested_schedules", true),
    EXPERIMENTAL_DASHBOARD("experimental_dashboard", false);

    companion object { fun fromKey(k: String) = entries.firstOrNull { it.key == k } }
}

enum class DashboardWidget(val key: String) {
    CLOCK("clock"), NEXT_PLAYBACK("next_playback"), COUNTDOWN("countdown"), HOURLY_STATUS("hourly_status"),
    CURRENT_MEDIA("current_media"), QUICK_CONTROLS("quick_controls"), HISTORY("history"), ANNOUNCEMENT("announcement");

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k }
        val DEFAULT_ORDER = listOf(ANNOUNCEMENT, CLOCK, COUNTDOWN, NEXT_PLAYBACK, HOURLY_STATUS, CURRENT_MEDIA, QUICK_CONTROLS, HISTORY)
    }
}

/** Settings sections a remote config may hide. Core safety sections (update, reset) can never be hidden. */
enum class SettingsSection(val key: String, val hideable: Boolean) {
    PLAYBACK("playback", true), QUIET_HOURS("quiet_hours", true), APPEARANCE("appearance", true),
    BACKUP("backup", true), UPDATES("updates", false), RESET("reset", false);

    companion object { fun fromKey(k: String) = entries.firstOrNull { it.key == k } }
}

data class ThemePreset(val id: String, val name: String, val primary: Long, val secondary: Long, val background: Long)
data class Branding(val tagline: String?, val primary: Long?, val secondary: Long?)
data class Announcement(val id: String, val title: String, val body: String, val severity: String)
data class FeaturedMedia(val id: String, val title: String, val url: String, val sha256: String, val sizeBytes: Long, val isVideo: Boolean)
data class SuggestedSchedule(
    val id: String, val name: String, val kind: ScheduleKind, val start: LocalTime, val end: LocalTime?,
    val intervalMinutes: Int, val days: Set<DayOfWeek>,
)

data class RemoteConfig(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val branding: Branding? = null,
    val themes: List<ThemePreset> = emptyList(),
    val dashboardLayout: List<DashboardWidget> = DashboardWidget.DEFAULT_ORDER,
    val featureFlags: Map<FeatureFlag, Boolean> = emptyMap(),
    val announcements: List<Announcement> = emptyList(),
    val featuredMedia: List<FeaturedMedia> = emptyList(),
    val suggestedSchedules: List<SuggestedSchedule> = emptyList(),
    val hiddenSettings: Set<SettingsSection> = emptySet(),
    val texts: Map<String, String> = emptyMap(),
) {
    fun flag(f: FeatureFlag) = featureFlags[f] ?: f.default

    companion object { val EMPTY = RemoteConfig() }
}

object RemoteConfigParser {
    /** Keys the UI knows how to render; unknown text keys are dropped. */
    val KNOWN_TEXT_KEYS = setOf("home.greeting", "home.subtitle", "library.empty", "schedules.empty", "about.blurb")

    private const val MAX_TEXT = 280
    private const val MAX_ITEMS = 20
    private const val MAX_FEATURED_BYTES = 100L * 1024 * 1024
    private val ID = Regex("^[A-Za-z0-9_.-]{1,40}$")
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val AV_EXT = setOf("mp3", "wav", "m4a", "aac", "mp4", "ogg", "flac", "3gp", "mkv", "webm")

    /** Returns null when the document is unusable (wrong schema). Individual bad entries are dropped, not fatal. */
    fun parse(raw: Map<String, Any?>, supportedSchema: Int): RemoteConfig? {
        val schema = (raw["schemaVersion"] as? Number)?.toInt() ?: return null
        if (schema > supportedSchema || schema < 1) return null

        val branding = (raw["branding"] as? Map<*, *>)?.let {
            Branding(
                tagline = text(it["tagline"]),
                primary = color(it["primary"]),
                secondary = color(it["secondary"]),
            )
        }

        val themes = list(raw["themes"]).mapNotNull { t ->
            val m = t as? Map<*, *> ?: return@mapNotNull null
            val id = id(m["id"]) ?: return@mapNotNull null
            ThemePreset(id, text(m["name"]) ?: id, color(m["primary"]) ?: return@mapNotNull null,
                color(m["secondary"]) ?: return@mapNotNull null, color(m["background"]) ?: 0xFF050505)
        }

        val layout = list(raw["dashboardLayout"]).mapNotNull { (it as? String)?.let(DashboardWidget::fromKey) }.distinct()

        val flags = (raw["featureFlags"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val f = (k as? String)?.let(FeatureFlag::fromKey) ?: return@mapNotNull null
            val b = v as? Boolean ?: return@mapNotNull null
            f to b
        }.toMap()

        val announcements = list(raw["announcements"]).mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val sev = (m["severity"] as? String)?.takeIf { it in setOf("info", "warning") } ?: "info"
            Announcement(id(m["id"]) ?: return@mapNotNull null, text(m["title"]) ?: return@mapNotNull null, text(m["body"]).orEmpty(), sev)
        }

        val featured = list(raw["featuredMedia"]).mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"] as? String ?: return@mapNotNull null
            val sha = (m["sha256"] as? String)?.lowercase() ?: return@mapNotNull null
            val size = (m["sizeBytes"] as? Number)?.toLong() ?: return@mapNotNull null
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            if (!SecureUrl.isAllowed(url) || !Hashing.isSha256Hex(sha) || size !in 1..MAX_FEATURED_BYTES || ext !in AV_EXT) return@mapNotNull null
            FeaturedMedia(id(m["id"]) ?: return@mapNotNull null, text(m["title"]) ?: return@mapNotNull null, url, sha, size,
                isVideo = ext in setOf("mp4", "mkv", "webm", "3gp"))
        }

        val suggested = list(raw["suggestedSchedules"]).mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val kind = (m["kind"] as? String)?.let { k -> ScheduleKind.entries.firstOrNull { it.name.equals(k, true) } } ?: return@mapNotNull null
            val start = time(m["start"]) ?: return@mapNotNull null
            val interval = (m["intervalMinutes"] as? Number)?.toInt() ?: 60
            if (kind == ScheduleKind.RECURRING && interval !in 1..1440) return@mapNotNull null
            val days = list(m["days"]).mapNotNull { d -> (d as? String)?.let { n -> DayOfWeek.entries.firstOrNull { it.name.startsWith(n.uppercase().take(3)) } } }.toSet()
            SuggestedSchedule(id(m["id"]) ?: return@mapNotNull null, text(m["name"]) ?: return@mapNotNull null, kind, start, time(m["end"]), interval, days)
        }

        val hidden = list(raw["hiddenSettings"]).mapNotNull { (it as? String)?.let(SettingsSection::fromKey) }.filter { it.hideable }.toSet()

        val texts = (raw["texts"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            if (key !in KNOWN_TEXT_KEYS) return@mapNotNull null
            key to (text(v) ?: return@mapNotNull null)
        }.toMap()

        return RemoteConfig(
            schemaVersion = schema,
            revision = (raw["revision"] as? Number)?.toLong() ?: 0,
            branding = branding,
            themes = themes.take(MAX_ITEMS),
            dashboardLayout = layout.ifEmpty { DashboardWidget.DEFAULT_ORDER },
            featureFlags = flags,
            announcements = announcements.take(MAX_ITEMS),
            featuredMedia = featured.take(MAX_ITEMS),
            suggestedSchedules = suggested.take(MAX_ITEMS),
            hiddenSettings = hidden,
            texts = texts,
        )
    }

    private fun list(v: Any?): List<Any?> = (v as? List<*>).orEmpty()
    private fun text(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    private fun id(v: Any?): String? = (v as? String)?.takeIf { ID.matches(it) }
    private fun color(v: Any?): Long? = (v as? String)?.takeIf { COLOR.matches(it) }?.let { 0xFF000000 or it.substring(1).toLong(16) }
    private fun time(v: Any?): LocalTime? = (v as? String)?.let { try { LocalTime.parse(it) } catch (_: Exception) { null } }
}
