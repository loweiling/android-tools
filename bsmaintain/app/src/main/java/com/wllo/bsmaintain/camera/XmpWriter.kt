package com.wllo.bsmaintain.camera

/**
 * 在 JPEG bytes 中注入/取代 XMP APP1 segment,寫入 dc:title / dc:subject / dc:description。
 * 與 photo_tools/verify_photos.py 使用的 XMP:Title 欄位相容。
 */
object XmpWriter {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun buildXmpXml(title: String): String {
        val t = esc(title)
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$t</rdf:li></rdf:Alt></dc:title>
   <dc:subject><rdf:Bag><rdf:li>$t</rdf:li></rdf:Bag></dc:subject>
   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">基地台: $t</rdf:li></rdf:Alt></dc:description>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    fun injectXmp(jpeg: ByteArray, title: String): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
        val nsBytes = XMP_NS.toByteArray(Charsets.ISO_8859_1)
        val xmlBytes = buildXmpXml(title).toByteArray(Charsets.UTF_8)
        val payload = nsBytes + xmlBytes
        val segSize = payload.size + 2
        if (segSize > 0xFFFF) return jpeg
        val seg = ByteArray(4 + payload.size).apply {
            this[0] = 0xFF.toByte(); this[1] = 0xE1.toByte()
            this[2] = ((segSize shr 8) and 0xFF).toByte()
            this[3] = (segSize and 0xFF).toByte()
            System.arraycopy(payload, 0, this, 4, payload.size)
        }

        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker !in 0xE0..0xEF) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val next = pos + 2 + len
            if (marker == 0xE1) {
                val headerStart = pos + 4
                val isXmp = headerStart + nsBytes.size <= jpeg.size &&
                    jpeg.copyOfRange(headerStart, headerStart + nsBytes.size).contentEquals(nsBytes)
                if (isXmp) {
                    val out = ByteArray(pos + seg.size + (jpeg.size - next))
                    System.arraycopy(jpeg, 0, out, 0, pos)
                    System.arraycopy(seg, 0, out, pos, seg.size)
                    System.arraycopy(jpeg, next, out, pos + seg.size, jpeg.size - next)
                    return out
                }
            }
            pos = next
        }
        val out = ByteArray(pos + seg.size + (jpeg.size - pos))
        System.arraycopy(jpeg, 0, out, 0, pos)
        System.arraycopy(seg, 0, out, pos, seg.size)
        System.arraycopy(jpeg, pos, out, pos + seg.size, jpeg.size - pos)
        return out
    }
}
