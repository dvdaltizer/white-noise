package com.slowpixels.whitenoise.play

import com.slowpixels.whitenoise.engine.Motion
import com.slowpixels.whitenoise.engine.NoiseEngine
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one player, for the life of the process, so the sound outlives the screen. The detached player ([Holder]) says
 * whether we're playing: it's what the lock screen and audio focus talk to, and the engine follows it.
 */
object NoiseHub {
    data class Ui(
        val playing: Boolean = false,
        /** When the sleep timer stops the sound (wall clock), while playing. */
        val sleepAt: Long? = null,
        /** The sleep timer's time left while paused: it only counts down while the sound plays. */
        val sleepLeft: Long? = null,
        val error: String? = null,
    ) {
        val sleepOn get() = sleepAt != null || sleepLeft != null
        fun sleepRemaining(now: Long = System.currentTimeMillis()): Long? = sleepAt?.let { maxOf(0L, it - now) } ?: sleepLeft
    }

    /** The sleep timer's fade: a whole minute, gentle enough not to be noticed. */
    private const val FADE_MS = 60_000L

    val engine by lazy { NoiseEngine() }
    val ui = MutableStateFlow(Ui())
    lateinit var settings: Settings
        private set
    /** The sliders right now: follows a finger while it moves them, and is saved when it lifts ([commit]). */
    val levels = MutableStateFlow(Sounds.soft.levels)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: LightAudioPlayer? = null
    private var owner: Any? = null
    private var watching: Job? = null
    private var ticker: Job? = null
    private var sleepFading = false
    private var nextItem = Holder.QUEUE

    /** A screen is up: load what's saved the first time, and connect to the player (which may already be playing). */
    fun attach(by: Any, audio: LightAudio, filesDir: File) {
        if (!::settings.isInitialized) {
            settings = Settings(File(filesDir, "settings.properties"))
            levels.value = settings.values.value.levels
            engine.levels = levels.value.toFloatArray()
            engine.motion = settings.values.value.motion
            engine.onError = { message -> ui.value = ui.value.copy(error = message); player?.pause() }
            engine.onLeftHeadphones = ::pause
        }
        detach()
        owner = by
        val p = audio.newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        player = p
        watching = scope.launch {
            if (!p.awaitReady()) { ui.value = ui.value.copy(error = "Couldn't start sound"); return@launch }
            val silence = withContext(Dispatchers.IO) { Holder.silence(filesDir) }
            if (p.currentMediaItemIndex.value == NO_MEDIA_ITEM) p.setMediaQueue(List(Holder.QUEUE) { Holder.item(silence, it) })
            launch { p.isPlaying.collectLatest(::follow) }
            // Each ten minutes the item that finished is swapped for a new one at the end, so the queue never runs out.
            launch { p.currentMediaItemIndex.collect { i -> if (i > 0) { p.removeMediaItem(0); p.addMediaItem(Holder.item(silence, nextItem++)) } } }
        }
    }

    private fun detach() {
        watching?.cancel()
        player?.release()
        player = null
    }

    /**
     * Backed out of the tool, which ends the screen the player's connection hangs from: the lock screen could no longer
     * reach the engine, so the sound stops here. Going to the toolbox with Menu keeps the screen, and the sound.
     */
    fun leave(by: Any) {
        if (by !== owner) return
        ticker?.cancel()
        sleepFading = false
        engine.stop(0.3f)
        ui.value = ui.value.copy(playing = false, sleepAt = null, sleepLeft = ui.value.sleepRemaining())
        watching?.cancel()
        player?.stop()
        detach()
    }

    private suspend fun follow(playing: Boolean) {
        if (playing) {
            engine.start()
            val left = ui.value.sleepLeft
            ui.value = ui.value.copy(playing = true, error = null, sleepLeft = null,
                sleepAt = if (left != null) System.currentTimeMillis() + left else ui.value.sleepAt)
            startTicker()
        } else {
            // Not for the moment between one silent item and the next.
            delay(400)
            ticker?.cancel()
            sleepFading = false
            engine.stop(0.3f)
            ui.value = ui.value.copy(playing = false, sleepAt = null, sleepLeft = ui.value.sleepRemaining())
        }
    }

    fun play() {
        if (sleepFading) {
            sleepFading = false
            ui.value = ui.value.copy(sleepAt = null, sleepLeft = null)
            engine.start(0.5f)
            return
        }
        player?.play()
    }

    fun pause() { player?.pause() }

    fun toggle() = if (ui.value.playing && !sleepFading) pause() else play()

    /** A finger moving the sliders: heard at once, saved when it lifts ([commit]). */
    fun preview(new: List<Float>) {
        levels.value = new
        engine.levels = new.toFloatArray()
    }

    /** The sliders as you've left them: your own shape now. */
    fun commit() {
        val l = levels.value
        settings.update { it.copy(levels = l, sound = Settings.MINE, mine = l) }
    }

    /** Every slider up or down a step. */
    fun nudgeAll(delta: Float) {
        preview(levels.value.map { (it + delta).coerceIn(0f, 1f) })
        commit()
    }

    /** Picking a sound means wanting to hear that sound as it is, so Animate goes off (also for Mine). */
    fun choose(sound: Sound) {
        setMotion(Motion.Off)
        preview(sound.levels)
        settings.update { it.copy(levels = sound.levels, sound = sound.id) }
    }

    fun chooseMine() {
        val mine = settings.values.value.mine ?: return
        setMotion(Motion.Off)
        preview(mine)
        settings.update { it.copy(levels = mine, sound = Settings.MINE) }
    }

    fun soundName(id: String): String = Sounds.byId(id)?.name ?: "Mine"

    fun setMotion(m: Motion) {
        engine.motion = m
        settings.update { it.copy(motion = m) }
    }

    fun setSleep(minutes: Int?) {
        if (sleepFading) { sleepFading = false; if (ui.value.playing) engine.start(0.5f) }
        val ms = minutes?.let { it * 60_000L }
        ui.value = when {
            ms == null -> ui.value.copy(sleepAt = null, sleepLeft = null)
            ui.value.playing -> ui.value.copy(sleepAt = System.currentTimeMillis() + ms, sleepLeft = null)
            else -> ui.value.copy(sleepAt = null, sleepLeft = ms)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val at = ui.value.sleepAt
                if (at != null && !sleepFading && at - System.currentTimeMillis() <= FADE_MS) {
                    sleepFading = true
                    engine.stop(maxOf(1f, (at - System.currentTimeMillis()) / 1000f)) {
                        if (sleepFading) {
                            sleepFading = false
                            ui.value = ui.value.copy(sleepAt = null, sleepLeft = null)
                            player?.pause()
                        }
                    }
                }
                delay(5_000)
            }
        }
    }
}
