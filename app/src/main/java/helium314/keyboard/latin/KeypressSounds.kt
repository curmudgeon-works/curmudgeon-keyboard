// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import java.io.File

/**
 * The key-press sounds to choose from: Android's own key clicks (quiet, and only with the phone's Touch sounds on),
 * or one of the short sounds every phone already has in its system UI folder, or a generated beep. Nothing is added
 * to the app. The chosen sound plays at full strength of the ring / system volume, times the key sound volume.
 */
object KeypressSounds {
    const val ANDROID = "android"
    const val BEEP = "beep"

    // choice -> file in the system UI sounds folder
    private val files = linkedMapOf(
        "click" to "KeypressStandard.ogg",
        "tick" to "Effect_Tick.ogg",
        "lock" to "Lock.ogg",
        "unlock" to "Unlock.ogg",
        "camera" to "camera_click.ogg",
    )
    private val folders = listOf("/system/media/audio/ui/", "/product/media/audio/ui/")

    private fun file(name: String) = folders.map { File(it + name) }.firstOrNull { it.canRead() }

    /** The choices this phone can play, in list order. */
    fun available(): List<String> = listOf(ANDROID) + files.filterValues { file(it) != null }.keys + BEEP

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var pool: SoundPool? = null
    private var loadedChoice: String? = null
    private var soundId = 0
    private var ready = false
    private var playWhenReady = -1f

    private var tone: ToneGenerator? = null
    private var toneVolume = -1

    /** Plays [choice] at [volume] (0..1, negative = full). False for Android's own clicks, which the caller plays. */
    fun play(choice: String, volume: Float): Boolean {
        if (choice == ANDROID) return false
        val v = if (volume < 0) 1f else volume
        if (choice == BEEP) {
            val percent = (v * 100).toInt().coerceIn(0, 100)
            if (tone == null || toneVolume != percent) {
                tone?.release()
                tone = runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, percent) }.getOrNull()
                toneVolume = percent
            }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            return true
        }
        if (choice in boosted && canBoost) { playBoosted(choice, v); return true }
        load(choice)
        if (ready) pool?.play(soundId, v, v, 1, 0, 1f) else playWhenReady = v // the first press after a change
        return true
    }

    // choices played louder than the file allows: decoded once, the samples multiplied (peaks limited), played from memory
    private val boosted = mapOf("click" to 3f)
    private val canBoost = android.os.Build.VERSION.SDK_INT >= 23 // AudioTrack.Builder; older phones get the file as it is
    private var boostedChoice: String? = null
    private var track: android.media.AudioTrack? = null

    private fun prepareBoosted(choice: String) {
        if (boostedChoice == choice) return
        track?.release()
        track = files[choice]?.let { file(it) }?.let { decode(it, boosted[choice]!!) }
        boostedChoice = choice
    }

    private fun playBoosted(choice: String, volume: Float) {
        prepareBoosted(choice)
        val t = track ?: return
        runCatching {
            t.setVolume(volume)
            if (t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    /** The file as 16-bit PCM times [gain] (soft-limited near full scale), ready to play from memory. */
    private fun decode(file: File, gain: Float): android.media.AudioTrack? = runCatching {
        val extractor = android.media.MediaExtractor().apply { setDataSource(file.path) }
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = java.io.ByteArrayOutputStream()
        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false
        var sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sampleRate = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size)
                buf.position(info.offset); buf.get(bytes)
                out.write(bytes)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); extractor.release()
        val pcm = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining()).also { pcm.get(it) }
        for (i in samples.indices) {
            // gain, then a soft limit (tanh) so the loudest parts round off instead of clipping into a crackle
            val x = samples[i] / 32768f * gain
            samples[i] = (kotlin.math.tanh(x) * 32767f).toInt().toShort()
        }
        val channelMask = if (channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
        android.media.AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(channelMask).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build().also { it.write(samples, 0, samples.size) }
    }.getOrNull()

    /** Loads [choice] ahead of the first key press. */
    fun load(choice: String) {
        if (choice in boosted && canBoost) { prepareBoosted(choice); return }
        if (choice == loadedChoice) return
        val path = files[choice]?.let { file(it) } ?: return
        val p = pool ?: SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build().also { sp ->
            pool = sp
            sp.setOnLoadCompleteListener { _, id, status ->
                if (id != soundId || status != 0) return@setOnLoadCompleteListener
                ready = true
                if (playWhenReady >= 0) sp.play(id, playWhenReady, playWhenReady, 1, 0, 1f)
                playWhenReady = -1f
            }
        }
        if (soundId != 0) p.unload(soundId)
        ready = false
        playWhenReady = -1f
        loadedChoice = choice
        soundId = p.load(path.path, 1)
    }
}
