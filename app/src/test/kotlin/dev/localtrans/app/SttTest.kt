package dev.localtrans.app

import dev.localtrans.app.stt.SegmentAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SttTest {
    private fun samples(vararg values: Int) = FloatArray(values.size) { values[it].toFloat() }

    @Test
    fun segmentAudioReturnsEverythingSinceLastTake() {
        val segment = SegmentAudio(maxSamples = 100)
        segment.append(samples(1, 2))
        segment.append(samples(3))
        assertArrayEquals(samples(1, 2, 3), segment.take(), 0f)
        assertEquals(0, segment.take().size)

        segment.append(samples(4))
        assertArrayEquals(samples(4), segment.take(), 0f)
    }

    @Test
    fun segmentAudioDropsOldestWhenFull() {
        val segment = SegmentAudio(maxSamples = 4)
        segment.append(samples(1, 2, 3))
        segment.append(samples(4, 5))
        assertArrayEquals(samples(2, 3, 4, 5), segment.take(), 0f)

        // 한 조각이 한도보다 길면 뒤쪽만 남긴다.
        segment.append(samples(1, 2, 3, 4, 5, 6))
        assertArrayEquals(samples(3, 4, 5, 6), segment.take(), 0f)
    }

    @Test
    fun segmentAudioGrowsPastInitialCapacity() {
        val segment = SegmentAudio(maxSamples = 200_000)
        val chunk = FloatArray(1_600) { 1f }
        repeat(100) { segment.append(chunk) }
        assertEquals(160_000, segment.take().size)
    }

    @Test
    fun segmentAudioKeepLastDropsOlderAudio() {
        val segment = SegmentAudio(maxSamples = 10)
        segment.append(samples(1, 2, 3, 4, 5))
        segment.keepLast(2)
        segment.append(samples(6))
        assertArrayEquals(samples(4, 5, 6), segment.take(), 0f)

        // 남길 양보다 적게 모여 있으면 그대로 둔다.
        segment.append(samples(7))
        segment.keepLast(3)
        assertArrayEquals(samples(7), segment.take(), 0f)
    }

    @Test
    fun segmentAudioClearForgetsAudio() {
        val segment = SegmentAudio(maxSamples = 10)
        segment.append(samples(1, 2))
        segment.clear()
        segment.append(samples(3))
        assertArrayEquals(samples(3), segment.take(), 0f)
    }
}
