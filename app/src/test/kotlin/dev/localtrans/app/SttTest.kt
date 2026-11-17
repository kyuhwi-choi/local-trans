package dev.localtrans.app

import dev.localtrans.app.audio.WavSource
import dev.localtrans.app.audio.WavWriter
import dev.localtrans.app.stt.SegmentAudio
import dev.localtrans.app.stt.SilenceTracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun segmentAudioPeekKeepsAudio() {
        val segment = SegmentAudio(maxSamples = 10)
        segment.append(samples(1, 2))
        assertArrayEquals(samples(1, 2), segment.peek(), 0f)
        segment.append(samples(3))
        assertArrayEquals(samples(1, 2, 3), segment.take(), 0f)
    }

    @Test
    fun silenceCountsOnlyAfterSpeech() {
        val tracker = SilenceTracker(minSilenceSec = 0.1f)
        // 말소리가 없었으면 아무리 조용해도 침묵으로 세지 않는다.
        repeat(5) { tracker.update(speaking = false, chunkSec = 0.1f) }
        assertFalse(tracker.heard)
        assertEquals(0f, tracker.silentSec, 0f)

        tracker.update(speaking = true, chunkSec = 0.1f)
        assertTrue(tracker.heard)
        assertEquals(0f, tracker.silentSec, 0f)

        // 말이 끝났다고 알려 온 첫 조각에서 이미 최소 침묵만큼 지났다.
        tracker.update(speaking = false, chunkSec = 0.1f)
        assertEquals(0.1f, tracker.silentSec, 1e-6f)
        tracker.update(speaking = false, chunkSec = 0.1f)
        tracker.update(speaking = false, chunkSec = 0.1f)
        assertEquals(0.3f, tracker.silentSec, 1e-6f)

        // 다시 말하면 처음부터 센다.
        tracker.update(speaking = true, chunkSec = 0.1f)
        assertEquals(0f, tracker.silentSec, 0f)
    }

    @Test
    fun silenceStartsOverInNewSegment() {
        val tracker = SilenceTracker(minSilenceSec = 0.1f)
        tracker.update(speaking = true, chunkSec = 0.1f)
        tracker.update(speaking = false, chunkSec = 0.1f)
        tracker.newSegment()
        assertFalse(tracker.heard)
        tracker.update(speaking = false, chunkSec = 0.1f)
        assertEquals(0f, tracker.silentSec, 0f)
    }

    @Test
    fun wavHeaderDescribesMono16k() {
        val header = WavWriter.header(dataBytes = 3200)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVEfmt ", String(header, 8, 8, Charsets.US_ASCII))
        fun int(at: Int, bytes: Int) = (0 until bytes).sumOf { (header[at + it].toInt() and 0xff) shl (8 * it) }
        assertEquals(36 + 3200, int(4, 4))
        assertEquals(1, int(22, 2)) // 채널 수
        assertEquals(16_000, int(24, 4))
        assertEquals(16, int(34, 2)) // 비트 수
        assertEquals(3200, int(40, 4))
        // 파일 입력으로 다시 읽을 때 음성이 시작되는 위치
        assertEquals(44, WavSource.dataOffset(header))
    }

    @Test
    fun wavSamplesAreLittleEndianAndClipped() {
        val bytes = WavWriter.pcm16(floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f))
        fun sample(i: Int) = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(listOf(0, 16384, -16384, 32767, -32768), (0 until 5).map { sample(it) })
    }
}
