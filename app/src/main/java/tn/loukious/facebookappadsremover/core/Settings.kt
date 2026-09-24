package tn.loukious.facebookappadsremover.core

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Single source of truth for feature toggles.
 *
 * Storage = the framework's remote-preferences group "fbar_settings"
 * (Vector/LSPosed daemon, SQLite configs table). The settings UI in the
 * module app writes through the libxposed service library
 * (ui.MainActivity); the hooks run inside the Facebook process, where the
 * libxposed API exposes the same group read-only through
 * [XposedInterface.getRemotePreferences]. Plain SharedPreferences files in
 * either app's storage are NOT part of this channel — verified the hard way
 * (2026-09-06): the daemon never reads them, so writes must go over the
 * service binder.
 *
 * The remote group may also push live updates to hooked processes, but
 * several hooks read their toggle only once at install — treat changes as
 * apply-on-next-Facebook-restart (the settings UI says the same).
 *
 * Until the settings app has written at least one toggle (group empty), we
 * fall back to the legacy FB-process-local "fbar_prefs" file that earlier
 * builds read — preserving state set via root during on-device verification.
 */
object Settings {

    private const val TAG = "FBAR.Settings"

    /** Prefs file name — must match the group name the settings UI writes. */
    const val NAME = "fbar_settings"

    /** Legacy FB-process-local file from earlier builds (root-editable). */
    const val LEGACY_NAME = "fbar_prefs"

    // Toggle keys + defaults, mirroring the original mod's switches:
    //   mod pref                              module key                      default
    //   app.telegram.bemai3012_swHOME_ADS   → ads.enabled                     TRUE
    //   swHOME_THREADS                       → feed.threads                    FALSE
    //   swHOME_REELS                         → feed.reels                      FALSE
    //   swHOME_GOIY                          → feed.suggestions                FALSE
    //   swNhungNguoiBanCoTheBiet             → feed.pymk                       FALSE
    //   Story24hInNewsFeed                   → feed.stories                    FALSE
    //   hideSeenStory                        → stories.hideSeen                FALSE
    //   privacy.disable_flag_secure          → privacy.allowCapture            FALSE
    //   privacy.capture_detection            → privacy.blockCaptureDetection   FALSE
    //   (download "use browser" setting)     → download.useBrowser             FALSE
    //   swVIDEO_RESUME                       → media.video.resume              TRUE
    //   swBACKGROUND_PLAYBACK                → media.video.background          FALSE

    /** Master ad-block switch — gates AdFilterHook, the sponsored feed
     *  category and the ad-free-session spoof (mod: one swHOME_ADS switch). */
    const val ADS_ENABLED = "ads.enabled"

    /** Per-family sub-switches for the ported ad guards. Each rides the
     *  master [ADS_ENABLED] switch but can be turned off separately — e.g.
     *  to disable one family without losing the rest of the ad blocking. */
    const val ADS_MARKETPLACE = "ads.marketplace"
    const val ADS_GAME_ADS = "ads.gameAds"
    const val ADS_FEED_GUARD = "ads.feedGuard"

    /** Reels "Shop now" shopping cards — the small shoppable product-card
     *  banner overlaying a promotional reel (rendered by dedicated Litho
     *  components, blocked at render time). */
    const val ADS_REELS_SHOPPING = "ads.reelsShopping"

    const val FEED_THREADS = "feed.threads"
    const val FEED_REELS = "feed.reels"
    const val FEED_SUGGESTIONS = "feed.suggestions"
    const val FEED_PYMK = "feed.pymk"
    const val FEED_STORIES = "feed.stories"

    /** AI-content filter — drops stories whose gen-AI transparency info
     *  carries the was_self_disclosed_as_ai_generated flag (our own feature;
     *  the marker is TreeJNI hash -1133610173 on the transparency model that
     *  GraphQLStory.A0Y()/C44g.A0u() expose). */
    const val FEED_AI_CONTENT = "feed.aiContent"

    /** Keyword filter master switch; the list itself is [FEED_KEYWORDS]. */
    const val FEED_KEYWORDS_ENABLED = "feed.keywords.enabled"

    /** Comma/semicolon/newline-separated keyword list — a story whose TreeJNI
     *  dump contains any entry (case-insensitive) is dropped from the feed. */
    const val FEED_KEYWORDS = "feed.keywords"
    const val STORIES_HIDE_SEEN = "stories.hideSeen"

    /** Stories tray hide — the "Stories" card at the top of the News Feed
     *  (mod: hideTagStory, X.2dd.addStoriesAdapter null-out). */
    const val STORIES_HIDE_TRAY = "stories.hideTray"
    const val PRIVACY_ALLOW_CAPTURE = "privacy.allowCapture"
    const val PRIVACY_BLOCK_DETECTION = "privacy.blockCaptureDetection"
    const val DOWNLOAD_USE_BROWSER = "download.useBrowser"
    //   (download "use browser" setting) → download.useBrowser  FALSE — the
    //   in-module downloader is the default (mod's DownloadWithBrowser toggle
    //   defaulted OFF; browser hand-off is opt-in).

    /** Show the floating quick-download bubble when media is captured. The
     *  downloader itself stays armed when this is off — only the overlay
     *  icon is hidden (downloads then work via the copied-link trigger). */
    const val DOWNLOAD_SHOW_ICON = "download.showIcon"

    /** Quick download via copied link — the clipboard trigger arm
     *  (mod: taiNhanh, `l6gJTzHczDDiahs77aMq` on setPrimaryClip, encrypted
     *  pref `app.telegram.bemai3012_taiNhanh`). Default FALSE: the mod's
     *  encrypted-pref read also defaulted FALSE. */
    const val DOWNLOAD_CLIPBOARD = "download.clipboardTrigger"

    const val APPEARANCE_DARK = "appearance.dark"

    /** Clean URL — unwrap facebook.com/l.php?u=…&fbclid=… redirect links to
     *  the real destination when FB hands them to the browser (mod:
     *  swFbclid, X.O1EMPB7OWX4fymeZ5Qom). Default FALSE: the mod's
     *  encrypted-pref read also defaulted FALSE. */
    const val LINKS_CLEAN_URL = "links.cleanUrl"

    /** News Feed auto-refresh block (mod: swNewsFeedAutoReload). */
    const val FEED_AUTO_REFRESH_BLOCK = "feed.autoRefreshBlock"

    /** Activity list — Intent dump on every startActivityForResult
     *  (mod: navigation.activity_list.*). */
    const val NAVIGATION_ACTIVITY_LIST = "navigation.activityList"

    /** Video resume — seek back to the saved position when a video is
     *  re-opened (mod: swVIDEO_RESUME). Default TRUE: the mod's v879
     *  per-video gate read (videoId, 1) — enabled unless turned off. */
    const val VIDEO_RESUME = "media.video.resume"

    /** Background video playback — keep the tracked video playing while the
     *  app is backgrounded (mod: swBACKGROUND_PLAYBACK). Default FALSE:
     *  the mod's default for this switch was not decoded, and the port's
     *  no-overlay variant changes audible behaviour, so it ships off. */
    const val VIDEO_BACKGROUND = "media.video.background"

    private var prefs: SharedPreferences? = null

    /**
     * Picks the prefs source for the Facebook process. Call once from
     * ModuleMain.onAppCreated, before any hook reads a toggle.
     */
    fun init(module: XposedInterface?, context: Context) {
        val remote = module?.let {
            runCatching { it.getRemotePreferences(NAME) }
                .onFailure { L.w(TAG, "getRemotePreferences($NAME) threw", it) }
                .getOrNull()
        }
        val legacy = runCatching {
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        // Empty remote file = the settings app was never opened → keep
        // honoring whatever the legacy file holds until the UI takes over.
        val source = if (remote != null && remote.all.isNotEmpty()) remote else legacy
        prefs = source
        L.i(TAG, "toggle source: " + when {
            source === remote -> "remote preferences ($NAME)"
            source === legacy -> "legacy local prefs ($LEGACY_NAME)"
            else -> "none (defaults)"
        })
    }

    /** Reads a toggle; safe on hot paths and before [init]. */
    fun getBoolean(key: String, default: Boolean): Boolean =
        prefs?.let { runCatching { it.getBoolean(key, default) }.getOrDefault(default) } ?: default

    /** Reads a string setting (e.g. the keyword list); safe before [init]. */
    fun getString(key: String, default: String): String =
        prefs?.let { runCatching { it.getString(key, default) }.getOrDefault(default) } ?: default
}
