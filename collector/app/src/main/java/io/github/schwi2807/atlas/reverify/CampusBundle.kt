package io.github.schwi2807.atlas.reverify

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Minimal MessagePack reader (same subset as site/js/msgpack.js: what Python's msgpack.packb writes). */
class MsgPack(private val b: ByteArray) {
    private var p = 0
    private fun u8() = b[p++].toInt() and 0xFF
    private fun u16() = (u8() shl 8) or u8()
    private fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    private fun s(n: Int): String { val v = String(b, p, n, Charsets.UTF_8); p += n; return v }
    private fun arr(n: Int) = List(n) { read() }
    private fun map(n: Int): Map<String, Any?> { val m = LinkedHashMap<String, Any?>(n); repeat(n) { m[read().toString()] = read() }; return m }
    private fun i64(): Long { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }

    fun read(): Any? {
        val t = u8()
        return when {
            t <= 0x7f -> t.toLong()
            t >= 0xe0 -> (t - 0x100).toLong()
            t and 0xf0 == 0x80 -> map(t and 0x0f)
            t and 0xf0 == 0x90 -> arr(t and 0x0f)
            t and 0xe0 == 0xa0 -> s(t and 0x1f)
            else -> when (t) {
                0xc0 -> null; 0xc2 -> false; 0xc3 -> true
                0xc4 -> ByteArray(u8()).also { System.arraycopy(b, p, it, 0, it.size); p += it.size }
                0xca -> java.lang.Float.intBitsToFloat(u32().toInt()).toDouble()
                0xcb -> java.lang.Double.longBitsToDouble(i64())
                0xcc -> u8().toLong(); 0xcd -> u16().toLong(); 0xce -> u32(); 0xcf -> i64()
                0xd0 -> b[p++].toLong()
                0xd1 -> u16().toShort().toLong()
                0xd2 -> u32().toInt().toLong()
                0xd3 -> i64()
                0xd9 -> s(u8()); 0xda -> s(u16()); 0xdb -> s(u32().toInt())
                0xdc -> arr(u16()); 0xdd -> arr(u32().toInt())
                0xde -> map(u16()); 0xdf -> map(u32().toInt())
                else -> error("msgpack: type 0x${t.toString(16)} at ${p - 1}")
            }
        }
    }
}

data class Place(
    val shortId: String, val kind: String, val category: String, val nameEn: String, val nameMs: String?, val code: String?,
    val aliases: List<String>, val lat: Double, val lon: Double, val lastVerified: String?, val verified: Boolean, val floors: Int?,
)

data class Campus(val buildTs: Long, val date: String, val places: List<Place>)

/** Reads site/data/campus.bundle (UATL header + gzip MessagePack; schema/BUNDLE_FORMAT.md). */
object CampusBundle {
    fun parse(bytes: ByteArray): Campus {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic) == "UATL") { "not a campus.bundle" }
        val version = d.readUnsignedShort()
        require(version == 1) { "bundle schema $version not supported" }
        val ts = d.readInt().toLong() and 0xffffffffL
        val body = GZIPInputStream(ByteArrayInputStream(bytes, 10, bytes.size - 10)).readBytes()
        @Suppress("UNCHECKED_CAST")
        val root = MsgPack(body).read() as Map<String, Any?>
        val build = root["build"] as Map<String, Any?>
        val places = (root["places"] as List<Map<String, Any?>>).map { p ->
            val pt = p["p"] as List<Long>
            Place(
                shortId = p["id"] as String, kind = p["k"] as String, category = p["c"] as String, nameEn = p["en"] as String,
                nameMs = p["ms"] as String?, code = p["code"] as String?, aliases = (p["al"] as List<String>?) ?: emptyList(),
                lat = pt[1] / 1e6, lon = pt[0] / 1e6, lastVerified = p["lv"] as String?, verified = p["ok"] as Boolean,
                floors = (p["nf"] as Long?)?.toInt(),
            )
        }
        return Campus(ts, build["date"] as String, places)
    }
}
