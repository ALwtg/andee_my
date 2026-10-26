package net.kuafuai.andee.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

class AttachmentsTest {

    private fun png(w: Int, h: Int, fill: Color = Color.RED): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = fill; g.fillRect(0, 0, w, h); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun decode(b64: String): BufferedImage = ImageIO.read(Base64.getDecoder().decode(b64).inputStream())

    @Test
    fun `a small picture is kept at its size and sent as a JPEG`() {
        val p = Attachments.fromBytes("a.png", png(300, 200))!!
        val out = decode(p.jpegBase64)
        assertEquals(300, out.width); assertEquals(200, out.height)
    }

    @Test
    fun `a large picture is shrunk to the long edge, proportions kept`() {
        val p = Attachments.fromBytes("big.png", png(4000, 2000))!!
        val out = decode(p.jpegBase64)
        assertEquals(1568, out.width)
        assertEquals(784, out.height)
    }

    @Test
    fun `the thumbnail is small`() {
        val p = Attachments.fromBytes("big.png", png(4000, 2000))!!
        assertTrue(maxOf(p.thumb.width, p.thumb.height) <= 112)
    }

    @Test
    fun `bytes that are not a picture are refused rather than thrown`() {
        assertNull(Attachments.fromBytes("x.png", "not an image".toByteArray()))
    }

    @Test
    fun `a sideways phone photo is turned upright`() {
        // A 400x200 JPEG with EXIF orientation 6 (rotate 90° clockwise) is a portrait picture.
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val withExif = withOrientation(jpeg, 6)
        val out = decode(Attachments.fromBytes("p.jpg", withExif)!!.jpegBase64)
        assertEquals("width and height swap", 200, out.width)
        assertEquals(400, out.height)
    }

    @Test
    fun `an image file becomes a photo and any other file a reference`() {
        val dir = File.createTempFile("att", "").also { it.delete(); it.mkdirs() }
        val pic = File(dir, "p.png").also { it.writeBytes(png(50, 50)) }
        val doc = File(dir, "notes.pdf").also { it.writeText("%PDF-1.4") }
        assertTrue(Attachments.fromFile(pic) is Attachment.Photo)
        val ref = Attachments.fromFile(doc)
        assertTrue(ref is Attachment.FileRef)
        assertEquals("notes.pdf", ref!!.label)
        assertNull(Attachments.fromFile(File(dir, "missing.txt")))
    }

    @Test
    fun `a file named like a picture that is not one still reaches the model as a file`() {
        val f = File.createTempFile("fake", ".png").also { it.writeText("hello") }
        assertTrue(Attachments.fromFile(f) is Attachment.FileRef)
    }

    @Test
    fun `the note names every path so this file means something`() {
        val a = File.createTempFile("note", ".txt").also { it.writeText("x") }
        val note = Attachments.fileNote(listOf(Attachment.FileRef(a)))
        assertTrue(note.contains(a.absolutePath))
        assertTrue(note.contains("run_command"))
    }

    /** Insert a minimal EXIF APP1 segment carrying only the Orientation tag. */
    private fun withOrientation(jpeg: ByteArray, value: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,           // big-endian TIFF header, IFD at 8
            0x00, 0x01,                                                // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,            // tag 0x0112 (Orientation), SHORT, count 1
            0x00, value.toByte(), 0x00, 0x00,                          // the value
            0x00, 0x00, 0x00, 0x00,                                    // no next IFD
        )
        val body = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}
