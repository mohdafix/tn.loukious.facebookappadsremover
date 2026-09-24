package tn.loukious.facebookappadsremover.videotracker

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject
import org.json.JSONArray
import java.util.regex.Pattern

data class VideoData(
    val id: String,
    val hdUrl: String?,
    val sdUrl: String?,
    val thumbnailUrl: String?
)

object RecentVideosBottomSheet {
    private const val TAG = "FBAdsBlockerX"
    private val uiHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, html: String, onDownloadClick: (url: String, isHd: Boolean) -> Unit): Boolean {
        val videos = extractVideosFromHtml(html)
        if (videos.isEmpty()) {
            Log.e(TAG, "No videos found in HTML!")
            return false
        }
        
        uiHandler.post {
            showDialog(context, videos, onDownloadClick)
        }
        return true
    }

    private fun extractVideosFromHtml(rawHtml: String): List<VideoData> {
        // Unescape escaped quotes and slashes so our regex matches easily
        val html = rawHtml.replace("\\\"", "\"").replace("\\/", "/")
        val videos = mutableMapOf<String, VideoData>()
        
        // Facebook stores Relay cache in script tags, usually inside require("RelayPrefetchedStreamCache")
        // We will do a robust regex to find Video nodes.
        try {
            // First, find all video blocks by locating `"__typename":"Video"` or `"video_id":"`
            // Since regex with nested braces is hard, we will just use targeted regexes
            val hdMatcher = Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
            val sdMatcher = Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
            
            // To correlate them, we can try to find blocks of JSON
            val blockMatcher = Pattern.compile("\\{\"__typename\"\\s*:\\s*\"Video\".*?(?=\\}\\,\\{|\\]\\})").matcher(html)
            while (blockMatcher.find()) {
                val block = blockMatcher.group() + "}"
                try {
                    // Try to fix truncated JSON for parsing
                    val id = Pattern.compile("\"(video_id|id)\"\\s*:\\s*\"([^\"]+)\"").matcher(block).let { if (it.find()) it.group(2) else "" }
                    if (id.isEmpty()) continue
                    
                    val hdUrl = Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\"").matcher(block).let { if (it.find()) it.group(1)?.replace("\\\\/", "/") else "" }
                    val sdUrl = Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\"").matcher(block).let { if (it.find()) it.group(1)?.replace("\\\\/", "/") else "" }
                    val thumbUrl = Pattern.compile("\"thumbnailImage\"\\s*:\\s*\\{\"uri\"\\s*:\\s*\"([^\"]+)\"").matcher(block).let { if (it.find()) it.group(1)?.replace("\\\\/", "/") else "" }
                    
                    if (hdUrl?.isNotEmpty() == true || sdUrl?.isNotEmpty() == true) {
                        videos[id] = VideoData(id, hdUrl.takeIf { it?.isNotEmpty() == true }, sdUrl.takeIf { it?.isNotEmpty() == true }, thumbUrl)
                    }
                } catch (e: Exception) { }
            }
            
            // Fallback: If no blocks matched, just collect all HD urls
            if (videos.isEmpty()) {
                hdMatcher.reset()
                var count = 0
                while (hdMatcher.find()) {
                    val hdUrl = hdMatcher.group(1)?.replace("\\\\/", "/") ?: ""
                    videos["unknown_hd_$count"] = VideoData("unknown_$count", hdUrl, null, null)
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing videos: ${e.message}")
        }
        
        return videos.values.toList()
    }

    fun showDialog(context: Context, videos: List<VideoData>, onDownloadClick: (url: String, isHd: Boolean) -> Unit) {
        try {
            val moduleContext = context.createPackageContext("tn.loukious.facebookappadsremover", Context.CONTEXT_IGNORE_SECURITY)
            val inflater = LayoutInflater.from(moduleContext)
            
            val layoutId = moduleContext.resources.getIdentifier("bottom_sheet_recent_videos", "layout", "tn.loukious.facebookappadsremover")
            val view = inflater.inflate(layoutId, null)
            
            val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.setContentView(view)
            
            // The container
            val containerId = moduleContext.resources.getIdentifier("videoListContainer", "id", "tn.loukious.facebookappadsremover")
            val container = view.findViewById<LinearLayout>(containerId)
            
            val itemLayoutId = moduleContext.resources.getIdentifier("item_recent_video", "layout", "tn.loukious.facebookappadsremover")
            
            for (video in videos) {
                val itemView = inflater.inflate(itemLayoutId, container, false)
                
                // Bind data
                val tvId = itemView.findViewById<TextView>(moduleContext.resources.getIdentifier("tvVideoId", "id", "tn.loukious.facebookappadsremover"))
                tvId.text = "VideoId: ${video.id}"
                
                val btnHd = itemView.findViewById<Button>(moduleContext.resources.getIdentifier("btnDownloadHd", "id", "tn.loukious.facebookappadsremover"))
                if (video.hdUrl == null) {
                    btnHd.isEnabled = false
                    btnHd.text = "HD Unavailable"
                } else {
                    btnHd.setOnClickListener {
                        dialog.dismiss()
                        onDownloadClick(video.hdUrl, true)
                    }
                }
                
                val btnSd = itemView.findViewById<Button>(moduleContext.resources.getIdentifier("btnDownloadSd", "id", "tn.loukious.facebookappadsremover"))
                if (video.sdUrl == null) {
                    btnSd.isEnabled = false
                } else {
                    btnSd.setOnClickListener {
                        dialog.dismiss()
                        onDownloadClick(video.sdUrl, false)
                    }
                }
                
                val ivThumb = itemView.findViewById<ImageView>(moduleContext.resources.getIdentifier("ivThumbnail", "id", "tn.loukious.facebookappadsremover"))
                video.thumbnailUrl?.let { url ->
                    thread {
                        try {
                            val conn = URL(url).openConnection()
                            conn.connect()
                            val bitmap = BitmapFactory.decodeStream(conn.getInputStream())
                            uiHandler.post { ivThumb.setImageBitmap(bitmap) }
                        } catch (e: Exception) {}
                    }
                }
                
                container.addView(itemView)
            }
            
            val btnCancel = view.findViewById<Button>(moduleContext.resources.getIdentifier("btnCancel", "id", "tn.loukious.facebookappadsremover"))
            btnCancel.setOnClickListener { dialog.dismiss() }
            
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show BottomSheet: ${e.message}")
        }
    }
}
