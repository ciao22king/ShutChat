package dev.shutchat.app.ui.screens.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The camera hands over its grey plane in sensor orientation, with rows padded
 * to the stride; the QR decoder only reads upright, dense images.
 */
class LuminanceRotationTest {

    /** 2 wide, 3 tall, padded to a stride of 4 bytes per row. */
    private val width = 2
    private val height = 3
    private val stride = 4
    private val plane = byteArrayOf(
        0, 1, 9, 9,
        2, 3, 9, 9,
        4, 5, 9, 9,
    )

    @Test
    fun `no rotation drops the padding`() {
        val out = rotateLuminance(plane, width, height, stride, 0)

        assertEquals(width, out.width)
        assertEquals(height, out.height)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), out.data)
    }

    @Test
    fun `ninety degrees turns the image clockwise`() {
        val out = rotateLuminance(plane, width, height, stride, 90)

        assertEquals(height, out.width)
        assertEquals(width, out.height)
        // Rows of the rotated image: [4, 2, 0] then [5, 3, 1].
        assertArrayEquals(byteArrayOf(4, 2, 0, 5, 3, 1), out.data)
    }

    @Test
    fun `two hundred seventy degrees turns the image counter clockwise`() {
        val out = rotateLuminance(plane, width, height, stride, 270)

        assertEquals(height, out.width)
        assertEquals(width, out.height)
        assertArrayEquals(byteArrayOf(1, 3, 5, 0, 2, 4), out.data)
    }

    @Test
    fun `half turn keeps the shape and reverses the pixels`() {
        val out = rotateLuminance(plane, width, height, stride, 180)

        assertEquals(width, out.width)
        assertEquals(height, out.height)
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1, 0), out.data)
    }

    @Test
    fun `a stale rotation value is treated as zero`() {
        val out = rotateLuminance(plane, width, height, stride, 360)

        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), out.data)
    }
}
