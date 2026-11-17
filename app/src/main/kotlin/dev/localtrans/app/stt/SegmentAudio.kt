package dev.localtrans.app.stt

/**
 * 지금 듣고 있는 구간(마지막으로 끊긴 뒤부터)의 음성을 모아 둔다.
 * 구간이 끝나면 통째로 꺼내 2단계 인식에 넘긴다. [maxSamples] 를 넘으면 오래된 쪽부터 버린다.
 */
internal class SegmentAudio(private val maxSamples: Int) {
    private var data = FloatArray(minOf(maxSamples, INITIAL_SAMPLES))
    private var size = 0

    fun append(samples: FloatArray) {
        if (samples.size >= maxSamples) {
            if (data.size < maxSamples) data = FloatArray(maxSamples)
            samples.copyInto(data, 0, samples.size - maxSamples, samples.size)
            size = maxSamples
            return
        }
        val overflow = size + samples.size - maxSamples
        if (overflow > 0) {
            data.copyInto(data, 0, overflow, size)
            size -= overflow
        }
        if (size + samples.size > data.size) {
            data = data.copyOf(minOf(maxSamples, maxOf(data.size * 2, size + samples.size)))
        }
        samples.copyInto(data, size)
        size += samples.size
    }

    /** 모아 둔 음성을 돌려주고 비운다. */
    fun take(): FloatArray = data.copyOf(size).also { size = 0 }

    /** 마지막 [samples] 개만 남기고 앞쪽을 버린다. */
    fun keepLast(samples: Int) {
        if (size <= samples) return
        data.copyInto(data, 0, size - samples, size)
        size = samples
    }

    fun clear() {
        size = 0
    }

    private companion object {
        const val INITIAL_SAMPLES = 16_000 * 4
    }
}
