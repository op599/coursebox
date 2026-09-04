package com.wangxiuwen.coursebox.ui.nce

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.wangxiuwen.coursebox.core.cx.CxDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.ceil

@Serializable
data class SpeechSegment(val startMs: Long, val endMs: Long)

enum class SentenceAnalysisState { IDLE, ANALYZING, READY, FAILED }

/**
 * Fully-offline speech segmentation for course audio.
 *
 * Text is deliberately not used here: imported lesson text is sometimes a
 * different edition from the recording. Silero VAD supplies speech
 * probabilities, then [VadPostProcessor] turns pauses into replayable chunks.
 * Results are cached by media URI so analysis happens only once per lesson.
 */
class VoiceActivityAnalyzer(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(context.filesDir, "sentence_boundaries/v2")

    suspend fun analyze(mediaPath: String): List<SpeechSegment> = withContext(Dispatchers.Default) {
        val checkActive = { ensureActive() }
        val cache = File(cacheDir, cacheKey(mediaPath) + ".json")
        runCatching {
            if (cache.isFile) json.decodeFromString<List<SpeechSegment>>(cache.readText()) else null
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val pcm = decodeMono(mediaPath, checkActive)
        if (pcm.samples.isEmpty()) return@withContext emptyList()
        val mono16k = resample(pcm.samples, pcm.sampleRate, MODEL_SAMPLE_RATE)
        val probabilities = infer(mono16k, checkActive)
        val segments = VadPostProcessor.toSegments(
            probabilities = probabilities,
            audioDurationMs = mono16k.size * 1000L / MODEL_SAMPLE_RATE,
        )
        if (segments.isNotEmpty()) {
            cacheDir.mkdirs()
            cache.writeText(json.encodeToString(segments))
        }
        segments
    }

    private data class DecodedPcm(val samples: FloatArray, val sampleRate: Int)

    private fun decodeMono(mediaPath: String, checkActive: () -> Unit): DecodedPcm {
        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        try {
            val uri = Uri.parse(mediaPath)
            when (uri.scheme) {
                CxDataSource.SCHEME -> {
                    val (file, entry) = CxDataSource.resolveEntry(uri)
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    extractor.setDataSource(pfd.fileDescriptor, entry.dataOffset, entry.size)
                }
                "http", "https" -> extractor.setDataSource(mediaPath, emptyMap())
                "file" -> extractor.setDataSource(uri.path ?: error("音频路径无效"))
                else -> extractor.setDataSource(mediaPath)
            }

            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    inputFormat = format
                    break
                }
            }
            require(track >= 0 && inputFormat != null) { "媒体中没有音轨" }
            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("音轨格式缺失")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                return drainDecoder(codec, extractor, inputFormat, checkActive)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
            pfd?.close()
        }
    }

    private fun drainDecoder(
        codec: MediaCodec,
        extractor: MediaExtractor,
        initialFormat: MediaFormat,
        checkActive: () -> Unit,
    ): DecodedPcm {
        var sampleRate = initialFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, MODEL_SAMPLE_RATE)
        var channels = initialFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val out = FloatCollector(sampleRate * 60)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false

        while (!outputEnded && out.size < sampleRate * MAX_ANALYSIS_SECONDS) {
            checkActive()
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: error("解码输入缓冲区缺失")
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    pcmEncoding = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    codec.getOutputBuffer(index)?.let { buffer ->
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendPcm(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding, out)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        }
        return DecodedPcm(out.toArray(), sampleRate)
    }

    private fun appendPcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        out: FloatCollector,
    ) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            while (floats.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += floats.get() }
                out.add((sum / channels).coerceIn(-1f, 1f))
            }
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += shorts.get() / 32768f }
                out.add((sum / channels).coerceIn(-1f, 1f))
            }
        }
    }

    private fun infer(samples: FloatArray, checkActive: () -> Unit): FloatArray {
        val frameCount = ceil(samples.size / FRAME_SAMPLES.toDouble()).toInt()
        val probabilities = FloatArray(frameCount)
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        context.assets.open(MODEL_ASSET).use { model ->
            env.createSession(model.readBytes(), options).use { session ->
                var state = FloatArray(2 * 1 * 128)
                var contextSamples = FloatArray(CONTEXT_SAMPLES)
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(MODEL_SAMPLE_RATE.toLong())), longArrayOf()).use { sr ->
                    repeat(frameCount) { frameIndex ->
                        if (frameIndex % 32 == 0) checkActive()
                        val frame = FloatArray(FRAME_SAMPLES)
                        val from = frameIndex * FRAME_SAMPLES
                        val count = minOf(FRAME_SAMPLES, samples.size - from)
                        if (count > 0) samples.copyInto(frame, 0, from, from + count)
                        // Silero's public ONNX graph expects the previous
                        // 64 samples prepended to each 512-sample frame.
                        // Its Python wrapper performs this concatenation;
                        // mobile callers must do it explicitly as well.
                        val modelInput = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
                        contextSamples.copyInto(modelInput, 0)
                        frame.copyInto(modelInput, CONTEXT_SAMPLES)
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(modelInput),
                            longArrayOf(1, modelInput.size.toLong()),
                        ).use { input ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                                session.run(mapOf("input" to input, "state" to stateTensor, "sr" to sr)).use { result ->
                                    @Suppress("UNCHECKED_CAST")
                                    val output = result[0].value as Array<FloatArray>
                                    probabilities[frameIndex] = output[0][0]
                                    @Suppress("UNCHECKED_CAST")
                                    val next = result[1].value as Array<Array<FloatArray>>
                                    state = FloatArray(256).also { flattened ->
                                        var p = 0
                                        next.forEach { batch -> batch.forEach { row -> row.forEach { flattened[p++] = it } } }
                                    }
                                    contextSamples = frame.copyOfRange(
                                        FRAME_SAMPLES - CONTEXT_SAMPLES,
                                        FRAME_SAMPLES,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        options.close()
        return probabilities
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outputSize = (input.size.toLong() * toRate / fromRate).toInt()
        return FloatArray(outputSize) { i ->
            val source = i.toDouble() * fromRate / toRate
            val left = source.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (source - left).toFloat()
            input[left] + (input[right] - input[left]) * fraction
        }
    }

    private fun cacheKey(mediaPath: String): String = MessageDigest.getInstance("SHA-256")
        .digest(mediaPath.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private class FloatCollector(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    companion object {
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val MODEL_SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_ANALYSIS_SECONDS = 30 * 60
    }
}

/** Pause-based post-processing kept Android-free so boundary behaviour can be unit-tested. */
internal object VadPostProcessor {
    private const val FRAME_MS = 32L
    private const val START_THRESHOLD = 0.50f
    private const val END_THRESHOLD = 0.35f
    // A short breath inside one sentence is commonly 200-500 ms. Requiring
    // 800 ms avoids turning every clause into a separate "sentence" button,
    // while the 10 s ceiling below still keeps uninterrupted narration easy
    // to replay.
    private const val MIN_SILENCE_MS = 800L
    private const val MIN_SPEECH_MS = 180L
    private const val SPEECH_PAD_MS = 100L
    private const val MAX_SEGMENT_MS = 10_000L

    fun toSegments(probabilities: FloatArray, audioDurationMs: Long): List<SpeechSegment> {
        if (probabilities.isEmpty() || audioDurationMs <= 0) return emptyList()
        val raw = mutableListOf<SpeechSegment>()
        var speechStart = -1
        var silenceStart = -1
        val requiredSilentFrames = ceil(MIN_SILENCE_MS.toDouble() / FRAME_MS).toInt()

        probabilities.forEachIndexed { index, probability ->
            if (speechStart < 0) {
                if (probability >= START_THRESHOLD) speechStart = index
                return@forEachIndexed
            }
            if (probability < END_THRESHOLD) {
                if (silenceStart < 0) silenceStart = index
                if (index - silenceStart + 1 >= requiredSilentFrames) {
                    appendSpeech(raw, speechStart, silenceStart, audioDurationMs)
                    speechStart = -1
                    silenceStart = -1
                }
            } else {
                silenceStart = -1
            }
        }
        if (speechStart >= 0) appendSpeech(raw, speechStart, probabilities.size, audioDurationMs)
        return raw.flatMap { splitLong(it, probabilities) }.fixOverlaps(audioDurationMs)
    }

    private fun appendSpeech(out: MutableList<SpeechSegment>, startFrame: Int, endFrame: Int, durationMs: Long) {
        val rawStart = startFrame * FRAME_MS
        val rawEnd = minOf(durationMs, endFrame * FRAME_MS)
        if (rawEnd - rawStart >= MIN_SPEECH_MS) {
            out += SpeechSegment(
                startMs = (rawStart - SPEECH_PAD_MS).coerceAtLeast(0L),
                endMs = (rawEnd + SPEECH_PAD_MS).coerceAtMost(durationMs),
            )
        }
    }

    private fun splitLong(segment: SpeechSegment, probabilities: FloatArray): List<SpeechSegment> {
        if (segment.endMs - segment.startMs <= MAX_SEGMENT_MS) return listOf(segment)
        val result = mutableListOf<SpeechSegment>()
        var start = segment.startMs
        while (segment.endMs - start > MAX_SEGMENT_MS) {
            val searchFrom = ((start + 5_000L) / FRAME_MS).toInt().coerceAtLeast(0)
            val searchTo = ((start + MAX_SEGMENT_MS) / FRAME_MS).toInt().coerceAtMost(probabilities.lastIndex)
            val splitFrame = (searchFrom..searchTo).minByOrNull { probabilities[it] } ?: searchTo
            val splitMs = (splitFrame * FRAME_MS).coerceAtLeast(start + 1_000L)
            result += SpeechSegment(start, splitMs)
            start = splitMs
        }
        result += SpeechSegment(start, segment.endMs)
        return result
    }

    private fun List<SpeechSegment>.fixOverlaps(durationMs: Long): List<SpeechSegment> {
        if (size < 2) return this
        val out = toMutableList()
        for (i in 0 until out.lastIndex) {
            if (out[i].endMs > out[i + 1].startMs) {
                val midpoint = (out[i].endMs + out[i + 1].startMs) / 2
                out[i] = out[i].copy(endMs = midpoint)
                out[i + 1] = out[i + 1].copy(startMs = midpoint)
            }
        }
        return out.map { it.copy(startMs = it.startMs.coerceAtLeast(0), endMs = it.endMs.coerceAtMost(durationMs)) }
            .filter { it.endMs > it.startMs }
    }
}
