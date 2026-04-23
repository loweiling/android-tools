package com.wllo.bsmaintain.camera

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * Local mapping of photo DISPLAY_NAME → siteName. MediaStore.DESCRIPTION was deprecated in
 * API 29 and on many devices silently drops/mangles non-ASCII values (the 「???? 」bug).
 * EXIF TAG_IMAGE_DESCRIPTION is ASCII-only too — androidx.exifinterface replaces CJK
 * characters with "?" on write. XMP dc:title (written by [XmpWriter]) is UTF-8-safe and
 * is the canonical source for siteName. See [backfillFromXmp].
 */
object PhotoSiteNameStore {
    private const val PREFS = "photo_site_names"
    private const val META_PREFS = "photo_site_names_meta"
    private const val KEY_BACKFILL_VERSION = "backfill_version"
    // Bump when the recovery strategy changes. v1 read EXIF (garbage for CJK); v2 reads XMP;
    // v3 reads EXIF GPS and matches against site_info.json (100% reliable — GPS is numeric,
    // no encoding issues, and every STATION_ photo has coords written at capture time).
    // v4 same as v3 but with diagnostic Toast + detailed breakdown for debugging legacy
    // photos that silently failed GPS/XMP writes.
    // v5 force re-run — v4 finished with stats=null-masking-zero-matches on many devices
    // and marked done, leaving SharedPreferences empty and all photos grouped as 其他.
    // v6 widened scan scope from STATION_/ANNOTATED_ prefix only → all DCIM/Camera photos,
    // so python-tools-processed IMG_* photos (EXIF GPS written on desktop) also get tagged.
    // v7 prior runs used a buggy nameKey detector that could pick 「台名」 (verbose address
    // string) over 「siteName」. Prefs wiped at backfill start so wrong tags get replaced;
    // StationRepository.pickNameKey fixed.
    // v8 XMP/EXIF fallback values now validated against the station list — older bsmaintain
    // builds wrote buggy siteName into XMP dc:title (containing 「台名」 verbose strings),
    // which leaked through as 亂碼-looking group headers. Untrusted values are dropped;
    // their photos end up in 其他 instead of in a bogus group.
    // v9 rerun + sample-log first 10 rejected XMP/EXIF values so we can see why validation
    // drops them (v8 unexpectedly rejected ALL ~1484 XMP entries).
    private const val CURRENT_BACKFILL_VERSION = 9
    private const val TAG = "PhotoSiteNameStore"
    private const val STATION_RADIUS_M = 50f

    fun save(context: Context, displayName: String, siteName: String) {
        // Reject obvious garbage (all "?" is the classic CJK-mangled ASCII symptom).
        if (siteName.isBlank() || siteName.all { it == '?' }) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(displayName, siteName)
            .apply()
    }

    fun get(context: Context, displayName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(displayName, null)

    fun remove(context: Context, displayName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(displayName).apply()
    }

    @Suppress("UNCHECKED_CAST")
    fun getAll(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all as Map<String, String>

    fun isBackfillDone(context: Context): Boolean =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BACKFILL_VERSION, 0) >= CURRENT_BACKFILL_VERSION

    fun markBackfillDone(context: Context) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BACKFILL_VERSION, CURRENT_BACKFILL_VERSION).apply()
    }

    /** Dump the full DISPLAY_NAME → siteName map as JSON so the desktop
     *  sync_phone_to_stations.py can file phone photos into 基地台資料/{siteName}/照片/
     *  without re-running GPS matching. Quotes escaped; keys are JPEG filenames (ASCII-safe
     *  from MediaStore) but values may include UTF-8 CJK — JSON spec handles that. */
    fun exportToJson(context: Context): String {
        val map = getAll(context).toSortedMap()
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"entry_count\": ${map.size},\n")
        sb.append("  \"entries\": {\n")
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val (k, v) = iter.next()
            sb.append("    ")
            sb.append(jsonQuote(k))
            sb.append(": ")
            sb.append(jsonQuote(v))
            if (iter.hasNext()) sb.append(",")
            sb.append("\n")
        }
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun jsonQuote(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Scan STATION_* (and ANNOTATED_*) photos in MediaStore, recover siteName via:
     *   1. EXIF GPS coords + site_info.json nearest-station match (primary, 100% reliable)
     *   2. XMP dc:title written by [XmpWriter] (fallback if GPS missing)
     *   3. EXIF ImageDescription, rejecting "?"-mangled CJK (last resort)
     * Safe to call on a background thread; returns the number of entries written this run.
     */
    data class BackfillStats(
        val scanned: Int,
        val byGps: Int,
        val byXmp: Int,
        val unmatched: Int,
        val noGpsReadable: Int,
        val stationCount: Int
    ) {
        val totalMatched: Int get() = byGps + byXmp
        fun toHumanString(): String =
            "回填 $totalMatched/$scanned 張 " +
                "(GPS=$byGps / XMP=$byXmp / 無GPS=$noGpsReadable / 站台清單=$stationCount)"
    }

    fun backfillFromGps(
        context: Context,
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null
    ): BackfillStats? {
        val stations = StationRepository.load(context)
        if (stations.isEmpty()) {
            // Cache not populated yet — bail without marking done so we retry on the next
            // gallery open (after camera or StationSyncWorker has pulled the list).
            Log.w(TAG, "站台清單還沒下載，先開一次站台相機 (skipped backfill)")
            return null
        }

        // v7: wipe prior tags — they may have been written with a buggy nameKey detector
        // that could pick 「台名」 (verbose address) over 「siteName」.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        // Scan ALL photos in DCIM/Camera, not just STATION_/ANNOTATED_. python-tools
        // writes EXIF GPS + XMP dc:title to 照片 but keeps their original IMG_* names,
        // so the prefix-only filter was missing ~1000 of those.
        val selection: String?
        val selectionArgs: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf("DCIM/Camera/")
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            selectionArgs = arrayOf("Camera")
        }
        val resolver = context.contentResolver
        // Sort siteNames longest-first so contains() picks the most specific match —
        // e.g. "彰化一心S 彰化市一心路..." matches siteName "彰化一心S" before "彰化一心".
        val knownNames = stations.map { it.siteName }.distinct().sortedByDescending { it.length }

        var scanned = 0
        var byGps = 0
        var byXmp = 0
        var noGpsReadable = 0
        // Sample first few rejected XMP/EXIF values so we can see what they actually look
        // like when nothing matches knownNames (e.g. is python-tools writing a different
        // siteName variant than what's in site_info.json now?).
        var rejectedSamplesLogged = 0

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val total = cursor.count
            onProgress?.invoke(0, total)
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                scanned++
                // Report every 20 photos to keep UI refresh bounded (~1% on a 2000-photo run).
                if (scanned % 20 == 0) onProgress?.invoke(scanned, total)
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)

                val gps = readExifGps(resolver, uri)
                if (gps == null) noGpsReadable++
                val match = gps?.let {
                    StationRepository.findNearest(it.first, it.second, STATION_RADIUS_M, stations)?.siteName
                }
                if (match != null) {
                    save(context, name, match); byGps++
                    continue
                }
                val xmpRaw = readXmpTitle(resolver, uri)
                val xmp = validateAgainstStations(xmpRaw, knownNames)
                if (xmp != null) {
                    save(context, name, xmp); byXmp++
                    continue
                }
                if (rejectedSamplesLogged < 10 && xmpRaw != null) {
                    Log.i(TAG, "rejected $name: xmp='${xmpRaw.take(120)}'")
                    rejectedSamplesLogged++
                }
            }
        }
        val unmatched = scanned - (byGps + byXmp)
        val stats = BackfillStats(scanned, byGps, byXmp, unmatched, noGpsReadable, stations.size)
        onProgress?.invoke(scanned, scanned)
        Log.i(TAG, stats.toHumanString())
        return stats
    }

    /** Accept only values that reference a real siteName — either exact match, or a
     *  substring hit inside a verbose 台名-style string. [knownNames] must be sorted
     *  longest-first so contains() picks the most specific match. Untrusted input
     *  returns null so the photo falls through to 其他 instead of becoming a 亂碼 header. */
    private fun validateAgainstStations(raw: String?, knownNames: List<String>): String? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return knownNames.firstOrNull { v.contains(it) }
    }

    private fun readExifGps(resolver: ContentResolver, uri: Uri): Pair<Double, Double>? = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.latLong?.takeIf { it.size == 2 && (it[0] != 0.0 || it[1] != 0.0) }
                ?.let { Pair(it[0], it[1]) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GPS read failed for $uri", e)
        null
    }

    private fun readXmpTitle(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { XmpReader.readTitle(it) }
    } catch (e: Exception) {
        Log.w(TAG, "XMP read failed for $uri", e)
        null
    }

}
