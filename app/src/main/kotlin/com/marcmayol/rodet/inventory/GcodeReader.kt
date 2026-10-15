package com.marcmayol.rodet.inventory

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

data class OpenedPrint(val name: String, val print: SlicedPrint?)

/**
 * Abre lo que la persona comparte o elige: un .gcode suelto o un .gcode.3mf
 * (un zip con el gcode dentro y, en Bambu/Orca, un resumen en slice_info.config).
 */
object GcodeReader {

    fun read(context: Context, uri: Uri): OpenedPrint {
        val name = displayName(context, uri)
        val print = runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(4)
                val magic = ByteArray(2).also { input.read(it) }
                input.reset()
                if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) fromZip(input) else fromGcode(input)
            }
        }.getOrNull()
        return OpenedPrint(cleanName(name), print)
    }

    private fun fromGcode(input: java.io.InputStream): SlicedPrint? =
        input.bufferedReader().useLines { GcodeInfo.parse(it.filter { l -> l.startsWith(";") }) }

    private fun fromZip(input: java.io.InputStream): SlicedPrint? {
        var fromGcode: SlicedPrint? = null
        var fromInfo: SlicedPrint? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val n = entry.name.lowercase()
                when {
                    n.endsWith("slice_info.config") -> fromInfo = GcodeInfo.parseSliceInfo(zip.readBytes().toString(Charsets.UTF_8))
                    n.endsWith(".gcode") && fromGcode == null ->
                        // No se cierra: el reader de una entrada cerraria el zip entero.
                        fromGcode = GcodeInfo.parse(zip.bufferedReader().lineSequence().filter { it.startsWith(";") })
                }
            }
        }
        // El gcode trae colores y tipos; el resumen, el tiempo de todas las placas.
        val g = fromGcode
        val i = fromInfo
        return when {
            g == null -> i
            i == null -> g
            else -> g.copy(durationS = g.durationS ?: i.durationS, filaments = g.filaments.ifEmpty { i.filaments })
        }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    /** "llavero_k2.3mf_PLA_52m40s.gcode" -> "llavero_k2" */
    fun cleanName(file: String): String {
        var n = file.substringAfterLast('/')
        listOf(".gcode.3mf", ".3mf", ".gcode", ".bgcode").forEach { ext -> if (n.endsWith(ext, ignoreCase = true)) n = n.dropLast(ext.length) }
        n = n.replace(Regex("\\.3mf_.*$", RegexOption.IGNORE_CASE), "")
        return n.replace('_', ' ').trim()
    }
}
