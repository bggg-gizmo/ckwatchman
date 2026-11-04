package com.bggg.watchman.alerts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bggg.watchman.preferences.UserPreferences

/** Process-wide engine survives Activity/service handoff; callers never own separate TTS queues. */
object WatchManSpeech {
    private val main = Handler(Looper.getMainLooper())
    private val owners = HashSet<String>()
    private val queue = SpeechDeliveryQueue()
    private val speechLease = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var selecting = false
    private val initialization = SpeechInitializationGate()
    private val engineGeneration get() = initialization.generation
    private var nextInitAt = 0L
    private var nextSelectionAt = 0L
    private var initTimeout: Runnable? = null
    private val io by lazy {
        check(Looper.myLooper() == Looper.getMainLooper())
        SpeechIo(dispatch = { main.post(it) })
    }
    private var app: Context? = null
    private var preferences: SharedPreferences? = null
    private var diagnostics = ArrayDeque<String>()
    private val pumpTask = Runnable { pump() }

    private fun now() = SystemClock.elapsedRealtime()
    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun acquire(context: Context, owner: String) = dispatch {
        app = context.applicationContext
        preferences = context.getSharedPreferences("watchman", Context.MODE_PRIVATE)
        owners.add(owner)
        initialize()
    }

    fun release(owner: String) = dispatch {
        owners.remove(owner)
        // Allow Activity recreation / service startup to acquire before releasing the shared engine.
        main.postDelayed({ if (owners.isEmpty()) shutdown() }, 2000L)
    }

    fun refresh() = dispatch {
        val prefs = preferences ?: return@dispatch
        if (!prefs.getBoolean("voice", true) || prefs.getInt("alertVolume", 85) == 0) {
            queue.clear()
            speechLease.set(null)
            stopEngine()
        } else {
            if (initialized && !ready) selectVoice(force = true)
            initialize()
            pump()
        }
    }

    fun camera(alert: AlertEngine.Alert, text: String) = dispatch {
        val key = alert.speechKey ?: return@dispatch
        val tracker = ProximityEncounterSession.tracker
        submit(SpeechDeliveryQueue.Request(key, text, SpeechDeliveryQueue.Kind.CAMERA, now() + 30_000L,
            relevant = { tracker.speechPending(alert.camera.id, key) }, delivered = {
                tracker.acknowledgeSpeech(alert.camera.id, key)
                preferences?.edit()?.putString(ProximityEncounterSession.PREF_STATE_KEY, ProximityEncounterSession.encode())?.apply()
            }))
    }

    fun maneuver(key: String, text: String, immediate: Boolean, relevant: () -> Boolean) = dispatch {
        submit(SpeechDeliveryQueue.Request(key, text,
            if (immediate) SpeechDeliveryQueue.Kind.MANEUVER else SpeechDeliveryQueue.Kind.PREVIEW,
            now() + 20_000L, relevant))
    }

    fun testVoice() = dispatch {
        refresh()
        submit(SpeechDeliveryQueue.Request("test-${now()}",
            "WatchMan British English voice is ready. Camera and navigation alerts will use this voice.",
            SpeechDeliveryQueue.Kind.TEST, now() + 30_000L))
    }

    private fun submit(request: SpeechDeliveryQueue.Request) {
        val prefs = preferences ?: return
        if (!prefs.getBoolean("voice", true) || prefs.getInt("alertVolume", 85) == 0) return
        if (queue.offer(request)) record("requested ${request.key} ${request.kind}")
        pump()
    }

    private fun initialize() {
        if (preferences?.getBoolean("voice", true) == false || preferences?.getInt("alertVolume", 85) == 0) return
        if (engine != null || initialization.waiting || now() < nextInitAt || owners.isEmpty()) return
        val context = app ?: return
        val generation = initialization.begin()
        initialized = false
        ready = false
        selecting = false
        status("British English voice initializing")
        nextInitAt = now() + 10_000L
        val timeout = Runnable {
            if (generation == engineGeneration && initialization.waiting) {
                record("engine_init_timeout generation=$generation")
                status("Speech engine initialization timed out; retrying while alerts remain relevant.")
                retireEngine()
                pump()
            }
        }
        initTimeout = timeout
        main.postDelayed(timeout, 10_000L)
        io.submit({
            TextToSpeech(context) { result -> main.post {
                initialization.callback(generation, result, engine != null)?.let { finishInitialization(it) }
            } }
        }) { result ->
            if (generation != engineGeneration) {
                result.getOrNull()?.let { retireInstance(it) }
                return@submit
            }
            result.onSuccess { created ->
                engine = created
                record("engine_constructed_off_main generation=$generation")
                initialization.published(generation)?.let { finishInitialization(it) }
            }.onFailure {
                record("engine_construct_failed ${it.javaClass.simpleName}")
                retireEngine()
                status("Speech engine unavailable: ${it.javaClass.simpleName}")
                pump()
            }
        }
    }

    private fun finishInitialization(result: Int) {
        initTimeout?.let { main.removeCallbacks(it) }
        initTimeout = null
        if (result == TextToSpeech.SUCCESS) {
            initialized = true
            selectVoice(force = true)
        } else {
            record("engine_init_failed result=$result")
            status("Speech engine unavailable. Open voice settings to install British English voice data.")
            retireEngine()
        }
        pump()
    }

    private data class PreparedVoice(val status: String, val ready: Boolean, val diagnostic: String,
                                     val workerThread: String = Thread.currentThread().name)

    private fun selectVoice(force: Boolean = false) {
        val tts = engine ?: return
        if (!initialized || selecting || (!force && now() < nextSelectionAt)) return
        if (owners.isEmpty() || preferences?.getBoolean("voice", true) == false || preferences?.getInt("alertVolume", 85) == 0) return
        val generation = engineGeneration
        val listener = progressListener(generation)
        selecting = true
        ready = false
        nextSelectionAt = now() + 10_000L
        status("Selecting British English voice")
        io.submit({
            check(tts.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS) { "Speech listener unavailable" }
            val voices = tts.voices.orEmpty()
            val candidates = voices.map {
                BritishVoicePolicy.Candidate(it.name, it.locale.language, it.locale.country, it.quality,
                    it.latency, it.isNetworkConnectionRequired, "notInstalled" !in it.features.orEmpty())
            }
            val selected = BritishVoicePolicy.select(candidates)
            val voice = voices.firstOrNull { it.name == selected?.name }
            val setResult = voice?.let { tts.setVoice(it) }
            if (voice == null || setResult != TextToSpeech.SUCCESS) {
                val british = candidates.filter { it.language.equals("en", true) && it.country.equals("GB", true) }
                PreparedVoice("British English voice unavailable. Install en-GB voice data in Android voice settings, then retry Test Voice.", false,
                    "engine=${tts.defaultEngine} voice_inventory=${voices.size} enGB=${british.size} installed_enGB=${british.count { it.installed }} selected=${selected?.name ?: "none"} setVoice=${setResult ?: "not_called"}")
            } else {
                PreparedVoice("British English ready: ${voice.name} (${voice.locale.toLanguageTag()})" +
                    (if (voice.isNetworkConnectionRequired) " · network voice" else " · offline voice"), true,
                    "engine=${tts.defaultEngine} voice=${voice.name} locale=${voice.locale.toLanguageTag()} quality=${voice.quality} network=${voice.isNetworkConnectionRequired}")
            }
        }) { result ->
            if (generation != engineGeneration || engine !== tts) return@submit
            selecting = false
            result.onSuccess { prepared ->
                record("voice_prepared thread=${prepared.workerThread}")
                ready = prepared.ready
                status(prepared.status)
                record(prepared.diagnostic)
            }.onFailure {
                ready = false
                status("British English voice selection failed: ${it.javaClass.simpleName}")
            }
            pump()
        }
    }

    private fun progressListener(generation: Long) = object : UtteranceProgressListener() {
            override fun onStart(id: String) { main.post { if (generation == engineGeneration) record("started $id") } }
            override fun onDone(id: String) { main.post {
                if (generation != engineGeneration) return@post
                speechLease.compareAndSet(id, null)
                if (queue.done(id)) record("delivered $id")
                pump()
            } }
            @Deprecated("Platform compatibility callback")
            override fun onError(id: String) { failed(id, "error", generation) }
            override fun onError(id: String, code: Int) { failed(id, "error $code", generation) }
            override fun onStop(id: String, interrupted: Boolean) { failed(id, "stopped $interrupted", generation) }
        }

    private fun failed(id: String, reason: String, generation: Long) { main.post {
        if (generation != engineGeneration) return@post
        speechLease.compareAndSet(id, null)
        if (queue.failed(id, now())) record("retry $id $reason")
        pump()
    } }

    private fun pump() {
        main.removeCallbacks(pumpTask)
        val prefs = preferences ?: return
        if (!prefs.getBoolean("voice", true) || prefs.getInt("alertVolume", 85) == 0 || owners.isEmpty()) {
            queue.clear(); speechLease.set(null); stopEngine(); return
        }
        initialize()
        if (initialized && !ready) selectVoice()
        queue.cancelExpired(now())?.let {
            speechLease.compareAndSet(it, null)
            record("cancelled_by_policy $it"); stopEngine()
        }
        queue.active?.takeIf { now() - it.queuedAt > 15_000L }?.let {
            queue.failed(it.utteranceId, now())
            speechLease.compareAndSet(it.utteranceId, null)
            stopEngine()
            record("watchdog_retry ${it.utteranceId}")
        }
        queue.next(now(), ready)?.let { attempt ->
            // The shared queue sends one utterance at a time. Navigation never flushes camera speech.
            val tts = engine
            val generation = engineGeneration
            val parameters = UserPreferences.ttsBundle(prefs)
            speechLease.set(attempt.utteranceId)
            io.submit({
                if (speechLease.get() != attempt.utteranceId || now() >= attempt.request.expiresAt)
                    TextToSpeech.ERROR
                else tts?.speak(attempt.request.text, TextToSpeech.QUEUE_ADD, parameters, attempt.utteranceId)
            }) { result ->
                if (generation != engineGeneration) return@submit
                if (result.getOrNull() != TextToSpeech.SUCCESS) {
                    speechLease.compareAndSet(attempt.utteranceId, null)
                    if (queue.failed(attempt.utteranceId, now())) record("queue_failed ${attempt.utteranceId}")
                } else record("queued ${attempt.utteranceId} ${attempt.request.kind}")
                pump()
            }
        }
        if (queue.hasWork()) main.postDelayed(pumpTask, 250L)
    }

    private fun status(text: String) { preferences?.edit()?.putString("britishVoiceStatus", text)?.apply() }
    private fun record(message: String) {
        diagnostics.addLast("${now()} $message")
        while (diagnostics.size > 100) diagnostics.removeFirst()
        preferences?.edit()?.putString("speechDiagnostics", diagnostics.joinToString("\n"))?.apply()
        android.util.Log.i("WatchManSpeech", message)
    }

    private fun shutdown() {
        main.removeCallbacks(pumpTask)
        queue.clear()
        speechLease.set(null)
        retireEngine()
    }

    private fun stopEngine() {
        val tts = engine ?: return
        io.submit({ tts.stop() }) { result ->
            if (result.isFailure) record("engine_stop_failed ${result.exceptionOrNull()?.javaClass?.simpleName}")
        }
    }

    private fun retireEngine() {
        initialization.cancel()
        speechLease.set(null)
        initTimeout?.let { main.removeCallbacks(it) }
        initTimeout = null
        val previous = engine
        engine = null
        ready = false
        initialized = false
        selecting = false
        previous?.let { retireInstance(it) }
    }

    private fun retireInstance(tts: TextToSpeech) {
        io.submit({ try { tts.stop() } finally { tts.shutdown() } }) { result ->
            if (result.isFailure) record("engine_shutdown_failed ${result.exceptionOrNull()?.javaClass?.simpleName}")
        }
    }
}
