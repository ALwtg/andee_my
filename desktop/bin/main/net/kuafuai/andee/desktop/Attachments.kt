package net.kuafuai.andee.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.kuafuai.andee.util.Log
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import java.io.File
import java.util.Base64

/**
 * What the user attaches to a message. A picture goes to the model as pixels;
 * a file goes as a path, because the model cannot read bytes it is not shown and
 * `run_command` already reads anything outside the credentials.
 */
sealed class Attachment {
    abstract val label: String

    /** A picture ready to send: [jpegBase64] is what the brain gets, [thumb] is what the strip shows. */
    class Photo(override val label: String, val jpegBase64: String, val thumb: ImageBitmap) : Attachment()

    /** A file the model is pointed at. Nothing is copied: the original is where `run_command` can read it. */
    class FileRef(val file: File) : Attachment() {
        override val label: String get() = file.name
        val bytes: Long get() = file.length()
    }
}

object Attachments {

    private const val TAG = "Attachments"

    /** Same ceiling as the phone's picker. */
    const val MAX = 6
    private const val LONG_EDGE = 1568
    private const val JPEG_QUALITY = 85
    private const val THUMB_EDGE = 112

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "tif", "tiff")

    fun isImage(f: File) = f.extension.lowercase() in IMAGE_EXT

    /** A picked or dropped file becomes a photo if it decodes as one, else a file reference. Null if unreadable. */
    fun fromFile(f: File): Attachment? {
        if (!f.isFile || !f.canRead()) return null
        if (isImage(f)) fromBytes(f.name, runCatching { f.readBytes() }.getOrNull() ?: return null)?.let { return it }
        return Attachment.FileRef(f)
    }

    /**
     * Decode (upright — a phone photo is stored sideways with a flag, and the model must not be
     * shown it rotated), shrink, and re-encode as JPEG.
     */
    fun fromBytes(label: String, bytes: ByteArray): Attachment.Photo? = runCatching {
        // The decoder applies the EXIF orientation itself (a portrait phone photo arrives 200 wide, 400
        // tall). Applying the tag again on top is a second rotation that cancels the first, which is
        // exactly what this did until a test caught it.
        val image = Image.makeFromEncoded(bytes)
        val upright = Bitmap().also {
            it.allocN32Pixels(image.width, image.height)
            check(image.readPixels(it)) { "the decoder returned no pixels" }
        }
        val scaled = shrink(upright, LONG_EDGE)
        val jpeg = Image.makeFromBitmap(scaled).encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)
            ?: return null
        val thumb = Image.makeFromBitmap(shrink(scaled, THUMB_EDGE)).toComposeImageBitmap()
        Attachment.Photo(label, Base64.getEncoder().encodeToString(jpeg.bytes), thumb)
    }.onFailure { Log.w(TAG, "could not read $label as a picture: ${it.message}") }.getOrNull()

    private fun shrink(src: Bitmap, edge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= edge) return src
        val k = edge.toFloat() / long
        val w = maxOf(1, (src.width * k).toInt())
        val h = maxOf(1, (src.height * k).toInt())
        val out = Bitmap().also { it.allocN32Pixels(w, h) }
        Canvas(out).drawImageRect(
            Image.makeFromBitmap(src),
            org.jetbrains.skia.Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
            org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.LINEAR, Paint(), true,
        )
        return out
    }

    /** The picture on the system clipboard, if there is one — a screenshot copied with ⌃⌘⇧4, or an image copied from a page. */
    fun fromClipboard(): Attachment.Photo? = runCatching {
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return null
        val img = cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val w = img.getWidth(null); val h = img.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buf = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buf.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(buf, "png", out)
        fromBytes("pasted", out.toByteArray())
    }.getOrNull()

    /** Files copied in Finder (⌘C on a file) arrive as a list, not as data. */
    fun filesFromClipboard(): List<File> = runCatching {
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return emptyList()
        @Suppress("UNCHECKED_CAST")
        (cb.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>)
    }.getOrDefault(emptyList())

    /** The native open panel. Blocking: call off the UI thread. */
    fun choose(): List<File> {
        val d = java.awt.FileDialog(null as java.awt.Frame?, if (Strings.lang() == "zh") "选择图片或文件" else "Choose pictures or files", java.awt.FileDialog.LOAD)
        d.isMultipleMode = true
        d.isVisible = true
        return d.files.toList()
    }

    fun size(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /**
     * What the brain is told about the files. English like every device-composed prompt, so its own
     * language rule decides the reply from the user's words. The path is the capability; the
     * sentence says what it is, so "这个文件" resolves without a guess.
     */
    fun fileNote(files: List<Attachment.FileRef>): String = buildString {
        append("\n\n(The user attached ").append(if (files.size == 1) "a file" else "${files.size} files")
        append(" to the message above. You cannot see inside ").append(if (files.size == 1) "it" else "them")
        append(" from here: read ").append(if (files.size == 1) "it" else "them")
        append(" with run_command if the request needs the contents — cat or head for text, file/mdls for a type, ")
        append("textutil -convert txt for a document. \"这个文件 / this file\" means:\n")
        for (f in files) append("- ").append(f.file.absolutePath).append(" (").append(size(f.bytes)).append(")\n")
        append(")")
    }
}
