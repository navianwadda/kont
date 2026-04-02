package com.lagradost.cloudstream3.utils

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StreamLogger — transparent session logger for CloudStream.
 *
 * Logs:
 *  - Repo manifest + plugin list fetches (URL, response name, pluginLists)
 *  - Plugin downloads (url, file path)
 *  - Plugin load events (name, version, mainUrl, credentials, internal name)
 *  - API registration (name, mainUrl, storedCredentials, sourcePlugin)
 *  - API loadLinks calls (api name, mainUrl, data string)
 *  - ExtractorLink callbacks (url, referer, headers, type, quality)
 *  - Stream play events (url + full DRM info: licenseUrl, kid, key, uuid, kty, keyRequestParams)
 *
 * Auto-saves to /storage/emulated/0/Download/CloudStream_Logs/session_<timestamp>.txt
 * on app exit (MainActivity.onDestroy).
 */
object StreamLogger {

    private const val TAG = "StreamLogger"
    private val sessionStart = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    private val logLines = mutableListOf<String>()
    private val lock = Any()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun append(section: String, lines: List<Pair<String, String?>>) {
        val sb = StringBuilder()
        sb.appendLine("┌─── $section [${timestamp()}]")
        for ((k, v) in lines) {
            if (!v.isNullOrBlank()) sb.appendLine("│  $k: $v")
        }
        sb.append("└" + "─".repeat(60))
        val entry = sb.toString()
        Log.d(TAG, entry)
        synchronized(lock) { logLines.add(entry) }
    }

    // ── REPO FETCHING ─────────────────────────────────────────────────────────

    /** Called from RepositoryManager.parseRepository() — logs the manifest URL being fetched. */
    fun logRepoFetch(url: String?, repoName: String? = null, pluginLists: List<String>? = null) {
        val fields = mutableListOf(
            "URL"  to url,
            "Name" to repoName,
        )
        pluginLists?.forEachIndexed { i, u -> fields.add("PluginList[$i]" to u) }
        append("REPO FETCH", fields)
    }

    /** Called from RepositoryManager.parsePlugins() — logs each plugin list URL fetched. */
    fun logPluginListFetch(pluginListUrl: String?, repoUrl: String? = null, pluginCount: Int? = null) {
        append("PLUGIN LIST FETCH", listOf(
            "PluginListURL" to pluginListUrl,
            "RepoURL"       to repoUrl,
            "PluginCount"   to pluginCount?.toString(),
        ))
    }

    /** Called from RepositoryManager.downloadPluginToFile() — logs the .cs3 download URL. */
    fun logPluginDownload(pluginUrl: String?, filePath: String? = null) {
        append("PLUGIN DOWNLOAD", listOf(
            "URL"      to pluginUrl,
            "SavePath" to filePath,
        ))
    }

    // ── PLUGIN LOADING ────────────────────────────────────────────────────────

    /**
     * Called from PluginManager.loadPlugin() after the plugin is instantiated and load() is called.
     * Logs plugin manifest info + any APIs it registered.
     */
    fun logPluginLoaded(
        internalName: String?,
        manifestName: String?,
        version: Int?,
        filePath: String?,
        pluginUrl: String?,
    ) {
        append("PLUGIN LOADED", listOf(
            "InternalName" to internalName,
            "ManifestName" to manifestName,
            "Version"      to version?.toString(),
            "FilePath"     to filePath,
            "PluginURL"    to pluginUrl,
        ))
    }

    // ── API REGISTRATION ──────────────────────────────────────────────────────

    /**
     * Called from APIHolder.addPluginMapping() — logs every MainAPI the plugin registers.
     * This is where mainUrl, name, and storedCredentials are captured.
     */
    fun logApiRegistered(
        name: String?,
        mainUrl: String?,
        storedCredentials: String?,
        lang: String?,
        sourcePlugin: String?,
    ) {
        append("API REGISTERED", listOf(
            "Name"              to name,
            "MainURL"           to mainUrl,
            "StoredCredentials" to storedCredentials,
            "Lang"              to lang,
            "SourcePlugin"      to sourcePlugin,
        ))
    }

    // ── LINK LOADING ──────────────────────────────────────────────────────────

    /**
     * Called from APIRepository.loadLinks() — logs which API + URL is being called
     * to load links for an episode/movie.
     */
    fun logLoadLinks(
        apiName: String?,
        mainUrl: String?,
        data: String?,
        storedCredentials: String?,
    ) {
        append("LOAD LINKS", listOf(
            "APIName"           to apiName,
            "MainURL"           to mainUrl,
            "Data"              to data,
            "StoredCredentials" to storedCredentials,
        ))
    }

    /**
     * Called from RepoLinkGenerator callback — logs every ExtractorLink returned by the API.
     */
    fun logExtractorLink(
        source: String?,
        name: String?,
        url: String?,
        referer: String?,
        type: String?,
        quality: Int?,
        headers: Map<String, String>?,
        extractorData: String? = null,
        // DRM fields — present when the extension provides a DrmExtractorLink
        licenseUrl: String? = null,
        kid: String? = null,
        key: String? = null,
        uuid: String? = null,
        kty: String? = null,
        keyRequestParameters: Map<String, String>? = null,
    ) {
        val fields = mutableListOf(
            "Source"        to source,
            "Name"          to name,
            "URL"           to url,
            "Referer"       to referer,
            "Type"          to type,
            "Quality"       to quality?.toString(),
            "ExtractorData" to extractorData,
        )
        headers?.forEach { (k, v) -> fields.add("Header[$k]" to v) }
        if (!licenseUrl.isNullOrBlank() || !kid.isNullOrBlank() || !key.isNullOrBlank()) {
            fields.add("── DRM ──"     to "")
            fields.add("LicenseURL"    to licenseUrl)
            fields.add("KID"           to kid)
            fields.add("Key"           to key)
            fields.add("UUID"          to uuid)
            fields.add("KTY"           to kty)
            keyRequestParameters?.forEach { (k, v) -> fields.add("KeyReqParam[$k]" to v) }
        }
        append("EXTRACTOR LINK", fields)
    }

    // ── STREAM PLAY ───────────────────────────────────────────────────────────

    /**
     * Called from CS3IPlayer.loadOnlinePlayer() — logs the final stream URL sent to ExoPlayer,
     * including all DRM fields exposed by the open-source extension.
     */
    fun logStreamPlay(
        source: String?,
        name: String?,
        url: String?,
        referer: String?,
        type: String?,
        quality: Int?,
        headers: Map<String, String>?,
        licenseUrl: String? = null,
        kid: String? = null,
        key: String? = null,
        uuid: String? = null,
        kty: String? = null,
        keyRequestParameters: Map<String, String>? = null,
    ) {
        val fields = mutableListOf(
            "Source"  to source,
            "Name"    to name,
            "URL"     to url,
            "Referer" to referer,
            "Type"    to type,
            "Quality" to quality?.toString(),
        )
        headers?.forEach { (k, v) -> fields.add("Header[$k]" to v) }
        if (!licenseUrl.isNullOrBlank() || !kid.isNullOrBlank() || !key.isNullOrBlank()) {
            fields.add("── DRM ──"     to "")
            fields.add("LicenseURL"    to licenseUrl)
            fields.add("KID"           to kid)
            fields.add("Key"           to key)
            fields.add("UUID"          to uuid)
            fields.add("KTY"           to kty)
            keyRequestParameters?.forEach { (k, v) -> fields.add("KeyReqParam[$k]" to v) }
        }
        append("PLAY STREAM", fields)
    }

    // ── SAVE ─────────────────────────────────────────────────────────────────

    /**
     * Writes the full session log to:
     *   /storage/emulated/0/Download/CloudStream_Logs/session_<timestamp>.txt
     *
     * Called from MainActivity.onDestroy().
     */
    fun saveAndFlush() {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "CloudStream_Logs"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "session_$sessionStart.txt")
            val header = buildString {
                appendLine("═".repeat(64))
                appendLine("  CloudStream Session Log")
                appendLine("  Session started : $sessionStart")
                appendLine("  Saved at        : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("═".repeat(64))
                appendLine()
            }

            synchronized(lock) {
                file.writeText(header + logLines.joinToString("\n\n"))
                Log.i(TAG, "Session log saved → ${file.absolutePath}  (${logLines.size} entries)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save session log", t)
        }
    }
}
