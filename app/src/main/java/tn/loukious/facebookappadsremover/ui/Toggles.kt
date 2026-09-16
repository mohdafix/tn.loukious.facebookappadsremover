package tn.loukious.facebookappadsremover.ui

import tn.loukious.facebookappadsremover.core.Settings

/**
 * The full toggle surface, in display order. Keys and defaults must stay in
 * sync with core.Settings (which documents the original-mod pref each one
 * mirrors). Subtitles record the hook-level effect, so the cost of each
 * switch is visible where it's flipped.
 */
data class ToggleSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val default: Boolean,
)

data class ToggleSection(val title: String, val toggles: List<ToggleSpec>)

val TOGGLE_SECTIONS: List<ToggleSection> = listOf(
    ToggleSection("Ads", listOf(
        ToggleSpec(
            Settings.ADS_ENABLED,
            "Block ads",
            "Home-feed sponsored stories, video ads, banners and the ad-free-session spoof — one switch",
            true,
        ),
        ToggleSpec(
            Settings.ADS_MARKETPLACE,
            "Block marketplace ads",
            "Marketplace sponsored tiles, boosted listings and video ads — drops the ad queries and flips the server's ad-skip flags",
            true,
        ),
        ToggleSpec(
            Settings.ADS_GAME_ADS,
            "Block game ads",
            "In-app game ad requests (interstitial/rewarded/banner) are rejected; rewarded ads resolve as success so rewards are still granted",
            true,
        ),
        ToggleSpec(
            Settings.ADS_FEED_GUARD,
            "Feed ad guard (CSR experiment)",
            "Second feed pipeline used by FB's CSR experiment cohort — filters sponsored units the classic feed filter never sees",
            true,
        ),
        ToggleSpec(
            Settings.ADS_REELS_SHOPPING,
            "Block reels shopping cards",
            "The small \"Shop now\" product-card banner overlaying promotional reels — blocked at render time",
            true,
        ),
    )),
    ToggleSection("Feed filters", listOf(
        ToggleSpec(Settings.FEED_THREADS, "Hide Threads posts", "PROMOTION stories", false),
        ToggleSpec(Settings.FEED_REELS, "Hide Reels", "FB_SHORTS stories", false),
        ToggleSpec(
            Settings.FEED_SUGGESTIONS,
            "Hide suggestions",
            "ENGAGEMENT stories — can starve the feed pager when whole batches are suggestions",
            false,
        ),
        ToggleSpec(Settings.FEED_PYMK, "Hide People You May Know", "ENGAGEMENT_QP stories", false),
        ToggleSpec(Settings.FEED_STORIES, "Hide Stories in feed", "Stories tray + story viewer render", false),
        ToggleSpec(
            Settings.FEED_AI_CONTENT,
            "Hide AI-generated content",
            "Drops stories tagged with the gen-AI transparency flag (\"AI info\") — self-disclosed AI posts",
            false,
        ),
        ToggleSpec(
            Settings.FEED_KEYWORDS_ENABLED,
            "Keyword filter",
            "Hides feed stories containing any keyword from the list below (case-insensitive, comma-separated)",
            false,
        ),
        ToggleSpec(
            Settings.FEED_AUTO_REFRESH_BLOCK,
            "Block auto-refresh",
            "Kills revisit/warm-start/foreground/force refresh on the News Feed (Beta)",
            false,
        ),
    )),
    ToggleSection("Stories", listOf(
        ToggleSpec(Settings.STORIES_HIDE_SEEN, "View stories without marking seen", "Blocks the seen-reporting controller so the server never learns you watched", false),
        ToggleSpec(
            Settings.STORIES_HIDE_TRAY,
            "Hide Stories tray",
            "Keeps the Stories card out of the feed's adapter list",
            false,
        ),
    )),
    ToggleSection("Appearance", listOf(
        ToggleSpec(Settings.APPEARANCE_DARK, "Force dark mode", "ThemePreferences + FbFragmentActivity isDarkMode → true", false),
    )),
    ToggleSection("Privacy", listOf(
        ToggleSpec(Settings.PRIVACY_ALLOW_CAPTURE, "Allow screenshots & recording", "Clears FLAG_SECURE on every activity", false),
        ToggleSpec(Settings.PRIVACY_BLOCK_DETECTION, "Block capture detection", "FB's screenshot/recording watchers", false),
    )),
    ToggleSection("Navigation", listOf(
        ToggleSpec(
            Settings.NAVIGATION_ACTIVITY_LIST,
            "Activity list (Intent dump)",
            "Logs every hidden activity FB starts — action, URI, extras",
            false,
        ),
    )),
    ToggleSection("Links", listOf(
        ToggleSpec(
            Settings.LINKS_CLEAN_URL,
            "Unwrap l.php redirect links",
            "Rewrites facebook.com/l.php?u=…&fbclid=… URLs to the real destination before the browser opens them",
            false,
        ),
    )),
    ToggleSection("Downloader", listOf(
        ToggleSpec(Settings.DOWNLOAD_USE_BROWSER, "Download via browser", "Hand media URLs to the browser instead of the in-module downloader", true),
        ToggleSpec(
            Settings.DOWNLOAD_SHOW_ICON,
            "Show download icon",
            "The floating download bubble that appears once a video is captured — hide it if you only download via copied links",
            true,
        ),
        ToggleSpec(
            Settings.DOWNLOAD_CLIPBOARD,
            "Quick download from copied link",
            "Copying an http(s) link fires the qualities fetch + quick-download dialog",
            false,
        ),
    )),
    ToggleSection("Video", listOf(
        ToggleSpec(Settings.VIDEO_RESUME, "Resume video position", "Seek back to the saved position when a video is re-opened", true),
        ToggleSpec(Settings.VIDEO_BACKGROUND, "Background playback", "Keep the tracked video playing while the app is backgrounded (no floating window)", false),
    )),
)
