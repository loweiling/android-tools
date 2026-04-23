package com.wllo.bsmaintain.camera

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Writes siteName into XMP dc:title so desktop tools (python-tools photo_station_tagger.py,
 * exiftool, Lightroom) see the association without relying on the app's local
 * PhotoSiteNameStore map. GPS is intentionally NOT modified — overwriting a photo's real
 * capture location with the station's coords would falsify where the photo was actually
 * taken; siteName alone is enough for cross-tool sync.
 *
 * Foreign-owned URIs (system camera, MTP-copied photos) need a one-shot user consent via
 * [android.provider.MediaStore.createWriteRequest] before writeXmpTitle() can succeed —
 * call site handles that launcher flow and only invokes this after RESULT_OK.
 */
object PhotoMetadataWriter {
    private const val TAG = "PhotoMetadataWriter"

    fun writeXmpTitle(context: Context, uri: Uri, siteName: String): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            val patched = XmpWriter.injectXmp(bytes, siteName)
            val wrote = context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(patched); true
            } ?: false
            wrote
        } catch (e: Exception) {
            Log.w(TAG, "writeXmpTitle failed for $uri ($siteName)", e)
            false
        }
    }
}
