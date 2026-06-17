package com.pda.app

import com.pda.app.ui.dockreceiving.calculateInSampleSize
import com.pda.app.ui.dockreceiving.scaledSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScalingTest {

    @Test
    fun `inSampleSize is 1 when image already within maxEdge`() {
        assertEquals(1, calculateInSampleSize(1600, 1200, 1800))
    }

    @Test
    fun `inSampleSize grows as powers of two for oversized images`() {
        assertEquals(2, calculateInSampleSize(4000, 3000, 1800))
        assertEquals(4, calculateInSampleSize(8000, 6000, 1800))
    }

    @Test
    fun `scaledSize keeps longest edge at maxEdge and preserves aspect ratio`() {
        val (w, h) = scaledSize(3600, 1800, 1800)
        assertEquals(1800, w)
        assertEquals(900, h)
    }

    @Test
    fun `scaledSize is unchanged when within maxEdge`() {
        val (w, h) = scaledSize(1000, 800, 1800)
        assertEquals(1000, w)
        assertEquals(800, h)
    }

    @Test
    fun `scaledSize handles portrait orientation`() {
        val (w, h) = scaledSize(1200, 3600, 1800)
        assertEquals(600, w)
        assertEquals(1800, h)
    }

    @Test
    fun `scaledSize never returns zero for tiny inputs`() {
        val (w, h) = scaledSize(1, 1, 1800)
        assertTrue(w >= 1 && h >= 1)
    }
}
