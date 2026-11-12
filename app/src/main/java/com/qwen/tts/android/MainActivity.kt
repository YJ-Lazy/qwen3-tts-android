package com.qwen.tts.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.data.VoiceRecorder
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ModelFile(
    val name: String,
    val url: String?,
    val sizeBytes: Long,
)

private data class ModelVariant(
    val id: String,
    val label: String,
    val displayName: String,
    val files: List<ModelFile>,
    val talkerName: String,
) {
    val totalBytes: Long = files.sumOf { it.sizeBytes }
}

private data class BackendOption(val id: String, val label: String, val preference: Int)
private data class GenerationTimeEstimate(val totalMillis: Long, val sampleSize: Int)

private val backendOptions = listOf(
    BackendOption("cpu", "CPU", QwenEngine.BACKEND_CPU),
)

private val defaultBackendOption = backendOptions.first { it.id == "cpu" }

private object QwenModel {
    private const val repo = "Serveurperso/Qwen3-TTS-GGUF"
    private const val baseUrl = "https://huggingface.co/$repo/resolve/main"

    val defaultVariant: ModelVariant = variant(
        id = "q4_k_m",
        label = "Q4_K_M",
        displayName = "Qwen3-TTS 0.6B（Q4_K_M）",
        talkerName = "qwen-talker-0.6b-base-Q4_K_M.gguf",
        tokenizerName = "qwen-tokenizer-12hz-Q4_K_M.gguf",
        talkerSizeBytes = 628_905_056L,
        tokenizerSizeBytes = 254_974_752L,
    )

    val variants = listOf(defaultVariant)

    val obsoleteFileNames = setOf(
        "qwen-talker-0.6b-base-Q8_0.gguf",
        "qwen-talker-0.6b-base-NVFP4.gguf",
        "qwen-tokenizer-12hz-Q8_0.gguf",
    )

    // Q8_0 and NVFP4 used to be available here. The Android app now intentionally
    // ships one supported path: Q4_K_M.

    fun variantById(id: String): ModelVariant =
        variants.firstOrNull { it.id == id } ?: defaultVariant

    private fun variant(
        id: String,
        label: String,
        displayName: String,
        talkerName: String,
        tokenizerName: String,
        talkerSizeBytes: Long,
        tokenizerSizeBytes: Long,
        talkerUrl: String? = "$baseUrl/$talkerName?download=true",
        tokenizerUrl: String? = "$baseUrl/$tokenizerName?download=true",
    ): ModelVariant =
        ModelVariant(
            id = id,
            label = label,
            displayName = displayName,
            talkerName = talkerName,
            files = listOf(
                ModelFile(tokenizerName, tokenizerUrl, tokenizerSizeBytes),
                ModelFile(talkerName, talkerUrl, talkerSizeBytes),
            ),
        )
}

data class QwenTtsUiState(
    val text: String = "你好！欢迎使用本地 Qwen3 语音合成。",
    val selectedModelId: String = QwenModel.defaultVariant.id,
    val selectedBackendId: String = defaultBackendOption.id,
    val selectedVoiceId: String? = null,
    val activeBackendName: String? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.defaultVariant.totalBytes,
    val status: String = "模型尚未下载",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val playingGenerationId: Long? = null,
    val operationElapsedMillis: Long = 0,
    val synthesisFrames: Int = 0,
    val maxAudioTokens: Int = 512,
    val cpuThreads: Int = 0,
    val selectedCpuThreads: Int = 0,
    val selectedLanguageId: Int = 2055,
    val tokenizeMillis: Long = 0,
    val encodeMillis: Long = 0,
    val generateMillis: Long = 0,
    val decodeMillis: Long = 0,
    val decodeFrames: Int = 0,
    val decodeSamples: Long = 0,
    val decodeGraphComputeMillis: Long = 0,
    val savedFileName: String? = null,
    val snackbarMessage: String? = null,
    val estimatedSynthesisMillis: Long? = null,
    val estimateSampleCount: Int = 0,
    val supportsCloning: Boolean? = null,
    val speakerEmbeddingDim: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val voiceDir = File(application.filesDir, "voices")
    private val generationDir = File(application.filesDir, "generations")
    private val dao = QwenDatabase.getDatabase(application).qwenDao()
    private val recorder = VoiceRecorder()

    val voices = dao.observeVoices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generations = dao.observeGenerations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorderState = recorder.state

    private val _uiState = MutableStateFlow(
        QwenTtsUiState(
            modelReady = isModelReady(QwenModel.defaultVariant),
            downloadTotalBytes = QwenModel.defaultVariant.totalBytes,
        ),
    )
    val uiState = _uiState.asStateFlow()

    private var engine: QwenEngine? = null
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null

    init {
        val threads = preferredCpuThreadCount()
        _uiState.update { it.copy(selectedCpuThreads = threads, cpuThreads = threads) }
        refreshModelState()
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun updateCpuThreads(value: Int) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedCpuThreads = value, cpuThreads = value) }
        engine?.setCpuThreads(value)
    }

    fun selectVoice(voiceId: String?) {
        _uiState.update { it.copy(selectedVoiceId = voiceId) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun selectModel(modelId: String) {
        if (_uiState.value.busy || _uiState.value.selectedModelId == modelId) return
        val variant = QwenModel.variantById(modelId)
        stopAudio()
        engine?.close()
        engine = null
        generatedAudio = null
        val ready = isModelReady(variant)
        _uiState.update {
            it.copy(
                selectedModelId = variant.id,
                activeBackendName = null,
                modelReady = ready,
                loaded = false,
                status = if (ready) "模型已就绪" else "模型尚未下载",
                error = null,
                downloadProgress = 0f,
                downloadBytes = 0L,
                downloadTotalBytes = variant.totalBytes,
                sampleRate = 0,
                sampleCount = 0,
                synthesisMillis = 0,
                synthesisFrames = 0,
                tokenizeMillis = 0,
                encodeMillis = 0,
                generateMillis = 0,
                decodeMillis = 0,
                decodeFrames = 0,
                decodeSamples = 0,
                decodeGraphComputeMillis = 0,
                savedFileName = null,
                estimatedSynthesisMillis = null,
                estimateSampleCount = 0,
                supportsCloning = null,
                speakerEmbeddingDim = 0,
            )
        }
    }

    fun downloadModel() {
        if (_uiState.value.downloading) return
        viewModelScope.launch {
            val variant = selectedVariant()
            startBusy(status = "正在准备下载模型", downloading = true)
            _uiState.update {
                it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = variant.totalBytes)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles(variant)
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("模型已就绪")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "模型下载失败",
                            error = throwable.message ?: "模型下载失败",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || _uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "正在加载本地模型")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(selectedVariant())
                    native.getActiveBackendName().orEmpty() to native.getCpuThreads()
                }
            }

            result.fold(
                onSuccess = { (backend, threads) ->
                    stopBusyTicker()
                    val caps = engine?.getModelCapabilities()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (backend.isBlank()) "模型已加载" else "模型已加载（$backend）",
                            activeBackendName = backend.ifBlank { null },
                            cpuThreads = threads,
                            supportsCloning = caps?.supportsCloning,
                            speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            status = "模型加载失败",
                            error = throwable.message ?: "模型加载失败",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty() || _uiState.value.busy) return
        viewModelScope.launch {
            val variant = selectedVariant()
            val selectedVoice = _uiState.value.selectedVoiceId?.let { id -> voices.value.firstOrNull { it.voiceId == id } }
            val estimate = estimateSynthesisMillis(text)
            startBusy(status = "正在合成语音", resetSynthesis = true, synthesisEstimate = estimate)

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(variant)
                    native.setProgressCallback { frames, maxTokens ->
                        _uiState.update {
                            it.copy(
                                loaded = true,
                                status = "正在生成语音编码",
                                synthesisFrames = frames,
                                maxAudioTokens = maxTokens,
                            )
                        }
                    }
                    try {
                        native.synthesize(
                            text = text,
                            speakerEmbeddingPath = selectedVoice?.speakerEmbeddingPath,
                            params = QwenEngine.NativeParams(languageId = _uiState.value.selectedLanguageId),
                        )
                    } finally {
                        _uiState.update {
                            it.copy(activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() })
                        }
                        native.setProgressCallback(null)
                    }
                }
            }

            result.fold(
                onSuccess = { nativeResult ->
                    stopBusyTicker()
                    if (!nativeResult.success || nativeResult.audio == null) {
                        _uiState.update {
                            it.copy(
                                busy = false,
                                loaded = true,
                                status = "语音合成失败",
                                error = nativeResult.errorMsg ?: "本地语音合成失败",
                            )
                        }
                        return@fold
                    }

                    generatedAudio = nativeResult.audio
                    val sampleRate = nativeResult.sampleRate.takeIf { it > 0 } ?: 24_000
                    val voiceName = selectedVoice?.name ?: "默认音色"
                    persistGeneratedAudio(text, nativeResult.audio, sampleRate, voiceName, selectedVoice?.voiceId, nativeResult.timeMs)
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = "语音已生成",
                            sampleRate = sampleRate,
                            sampleCount = nativeResult.audio.size,
                            synthesisMillis = nativeResult.timeMs,
                            tokenizeMillis = nativeResult.tokenizeMs,
                            encodeMillis = nativeResult.encodeMs,
                            generateMillis = nativeResult.generateMs,
                            decodeMillis = nativeResult.decodeMs,
                            decodeFrames = nativeResult.decodeFrames,
                            decodeSamples = nativeResult.decodeSamples,
                            decodeGraphComputeMillis = nativeResult.decodeGraphComputeMs,
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = null,
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "语音合成失败",
                            error = throwable.message ?: "语音合成失败",
                        )
                    }
                },
            )
        }
    }

    fun startVoiceRecording() {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        val file = File(voiceDir, "pending-${System.currentTimeMillis()}.wav")
        if (!recorder.start(file)) {
            _uiState.update { it.copy(error = "无法启动麦克风录音") }
        }
    }

    fun stopVoiceRecordingAndCreate(name: String) {
        val trimmedName = name.trim().ifBlank { "音色 ${voices.value.size + 1}" }
        val result = recorder.stop() ?: return
        viewModelScope.launch {
            startBusy(status = "正在创建音色特征")
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(selectedVariant())
                    val caps = native.getModelCapabilities()
                    if (caps?.supportsCloning == false) {
                        error("当前模型未提供说话人编码器。")
                    }

                    val voiceId = "voice-${System.currentTimeMillis()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    val reference = File(targetDir, "reference.wav")
                    result.file.copyTo(reference, overwrite = true)
                    runCatching { result.file.delete() }

                    val embedding = File(targetDir, "speaker.json")
                    if (!native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                        error(native.getLastError() ?: "提取音色特征失败")
                    }

                    VoiceProfileEntity(
                        voiceId = voiceId,
                        name = makeUniqueVoiceName(trimmedName),
                        referenceWavPath = reference.absolutePath,
                        speakerEmbeddingPath = embedding.absolutePath,
                        durationMillis = result.durationMillis,
                    )
                }
            }

            created.fold(
                onSuccess = { voice ->
                    dao.insertVoice(voice)
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            selectedVoiceId = voice.voiceId,
                            status = "音色创建成功",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "音色创建失败",
                            error = throwable.message ?: "音色创建失败",
                        )
                    }
                },
            )
        }
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
    }

    fun deleteVoice(voice: VoiceProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoice(voice)
            runCatching { File(voice.referenceWavPath).parentFile?.deleteRecursively() }
            if (_uiState.value.selectedVoiceId == voice.voiceId) {
                _uiState.update { it.copy(selectedVoiceId = null) }
            }
        }
    }

    fun playGeneration(generationId: Long) {
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) {
            stopAudio()
            return
        }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            val loaded = runCatching { withContext(Dispatchers.IO) { readWav(generation.wavPath) } }
            loaded.onSuccess { wav ->
                stopAudio()
                generatedAudio = wav.samples
                _uiState.update {
                    it.copy(
                        sampleRate = wav.sampleRate,
                        sampleCount = wav.samples.size,
                        status = "语音已生成",
                        error = null,
                    )
                }
                playAudio(generationId)
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "无法播放这条历史音频") }
            }
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteGeneration(generation)
            runCatching { File(generation.wavPath).delete() }
        }
    }

    fun playAudio(historyGenerationId: Long? = null) {
        val samples = generatedAudio ?: return
        val sampleRate = _uiState.value.sampleRate.takeIf { it > 0 } ?: 24_000
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMillis = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
            kotlinx.coroutines.delay(durationMillis.coerceAtLeast(200L))
            track.release()
            if (activeTrack == track) activeTrack = null
            _uiState.update { it.copy(playing = false, playingGenerationId = null) }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

    fun suggestedExportFileName(generation: GenerationEntity): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        return "qwen3-tts-export-${LocalDateTime.now().format(formatter)}-${generation.generationId}.wav"
    }

    fun exportGenerationToUri(generation: GenerationEntity, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { output ->
                        File(generation.wavPath).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("无法打开导出目标")
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            savedFileName = null,
                            snackbarMessage = "导出完成",
                            status = "WAV 音频已导出",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            snackbarMessage = throwable.message ?: "无法导出 WAV 音频",
                            error = null,
                        )
                    }
                },
            )
        }
    }

    private fun ensureEngineLoaded(variant: ModelVariant): QwenEngine {
        val native = engine ?: QwenEngine().also { engine = it }
        val backend = selectedBackendOption()
        if (!native.setBackendPreference(backend.preference)) {
            error("无法切换到 ${backend.label} 后端")
        }
        val threads = selectedCpuThreadCount()
        native.setCpuThreads(threads)
        if (!_uiState.value.loaded) {
            if (!native.loadModels(modelDir.absolutePath, variant.talkerName)) {
                error(native.getLastError() ?: "本地模型加载失败")
            }
            val caps = native.getModelCapabilities()
            _uiState.update {
                it.copy(
                    loaded = true,
                    cpuThreads = native.getCpuThreads(),
                    activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() },
                    supportsCloning = caps?.supportsCloning,
                    speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                )
            }
        }
        return native
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun selectedCpuThreadCount(): Int =
        _uiState.value.selectedCpuThreads.takeIf { it > 0 } ?: preferredCpuThreadCount()

    private fun selectedBackendOption(): BackendOption =
        backendOptionById(_uiState.value.selectedBackendId)

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        resetSynthesis: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                synthesisFrames = if (resetSynthesis) 0 else it.synthesisFrames,
                sampleCount = if (resetSynthesis) 0 else it.sampleCount,
                synthesisMillis = if (resetSynthesis) 0 else it.synthesisMillis,
                tokenizeMillis = if (resetSynthesis) 0 else it.tokenizeMillis,
                encodeMillis = if (resetSynthesis) 0 else it.encodeMillis,
                generateMillis = if (resetSynthesis) 0 else it.generateMillis,
                decodeMillis = if (resetSynthesis) 0 else it.decodeMillis,
                decodeFrames = if (resetSynthesis) 0 else it.decodeFrames,
                decodeSamples = if (resetSynthesis) 0 else it.decodeSamples,
                decodeGraphComputeMillis = if (resetSynthesis) 0 else it.decodeGraphComputeMillis,
                savedFileName = if (resetSynthesis) null else it.savedFileName,
                estimatedSynthesisMillis = synthesisEstimate?.totalMillis ?: if (resetSynthesis) null else it.estimatedSynthesisMillis,
                estimateSampleCount = synthesisEstimate?.sampleSize ?: if (resetSynthesis) 0 else it.estimateSampleCount,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    private fun estimateSynthesisMillis(text: String): GenerationTimeEstimate? {
        val ratios = generations.value
            .asSequence()
            .filter { it.synthesisMillis > 0L && it.text.isNotBlank() }
            .take(20)
            .map { generation ->
                generation.synthesisMillis.toDouble() / generation.text.length.coerceAtLeast(1).toDouble()
            }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()

        if (ratios.size < 3) return null

        val trimmed = if (ratios.size >= 5) {
            ratios.drop(1).dropLast(1)
        } else {
            ratios
        }
        val millisPerCharacter = trimmed.average()
        val estimate = (millisPerCharacter * text.length.coerceAtLeast(1)).toLong()
            .coerceAtLeast(1_000L)
        return GenerationTimeEstimate(estimate, ratios.size)
    }

    private fun refreshModelState(status: String? = null) {
        val variant = selectedVariant()
        val ready = isModelReady(variant)
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                downloadTotalBytes = variant.totalBytes,
                status = status ?: if (ready) "模型已就绪" else "模型尚未下载",
            )
        }
    }

    private fun selectedVariant(): ModelVariant =
        QwenModel.variantById(_uiState.value.selectedModelId)

    private fun isModelReady(variant: ModelVariant): Boolean =
        variant.files.all { file ->
            val local = File(modelDir, file.name)
            local.isFile && local.length() >= file.sizeBytes
        }

    private fun downloadFiles(variant: ModelVariant) {
        var completedBytes = 0L
        variant.files.forEachIndexed { index, file ->
            val target = File(modelDir, file.name)
            if (target.isFile && target.length() >= file.sizeBytes) {
                completedBytes += file.sizeBytes
                return@forEachIndexed
            }
            val url = file.url ?: error("${file.name} 是实验性本地模型文件，请使用对应安装脚本导入。")
            val temp = File(modelDir, "${file.name}.download")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fileBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250L) {
                            lastUpdate = now
                            val total = completedBytes + fileBytes
                            _uiState.update {
                                it.copy(
                                    downloadBytes = total,
                                    downloadProgress = total.toFloat() / variant.totalBytes.toFloat(),
                                    status = "正在下载 ${index + 1}/${variant.files.size}：${file.name}",
                                )
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            if (temp.length() < file.sizeBytes) {
                temp.delete()
                error("${file.name} 下载不完整")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                error("无法替换 ${file.name}")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("无法保存 ${file.name}")
            }
            completedBytes += file.sizeBytes
        }
    }

    private fun persistGeneratedAudio(
        text: String,
        samples: FloatArray,
        sampleRate: Int,
        voiceName: String,
        voiceId: String?,
        synthesisMillis: Long,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            generationDir.mkdirs()
            val file = File(generationDir, "generation-${System.currentTimeMillis()}.wav")
            file.outputStream().use { writeWav(it, samples, sampleRate) }
            dao.insertGeneration(
                GenerationEntity(
                    text = text,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    wavPath = file.absolutePath,
                    sampleRate = sampleRate,
                    sampleCount = samples.size,
                    synthesisMillis = synthesisMillis,
                ),
            )
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    override fun onCleared() {
        recorder.cancel()
        stopAudio()
        stopBusyTicker()
        engine?.close()
        engine = null
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QwenTtsApp()
                }
            }
        }
    }
}

private sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    object Studio : AppDestination("studio", "工作台", { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) })
    object Voices : AppDestination("voices", "音色管理", { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) })
    object History : AppDestination("history", "历史", { Icon(Icons.Default.History, contentDescription = null) })
    object Settings : AppDestination("settings", "设置", { Icon(Icons.Default.Settings, contentDescription = null) })
}

private val appDestinations = listOf(
    AppDestination.Studio,
    AppDestination.Voices,
    AppDestination.History,
    AppDestination.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QwenTtsApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Studio.route
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbarMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                appDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(AppDestination.Studio.route) { saveState = true }
                            }
                        },
                        icon = destination.icon,
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Studio.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideIntoContainer(direction, animationSpec = tween(260))
            },
            exitTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideOutOfContainer(direction, animationSpec = tween(260))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
        ) {
            composable(AppDestination.Studio.route) {
                StudioScreen(viewModel)
            }
            composable(AppDestination.Voices.route) {
                VoicesScreen(viewModel)
            }
            composable(AppDestination.History.route) {
                HistoryScreen(viewModel)
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(viewModel)
            }
        }
    }
}

private fun destinationIndex(route: String?): Int =
    appDestinations.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    var showVoicePicker by remember { mutableStateOf(false) }
    val selectedVoiceName = voices.firstOrNull { it.voiceId == state.selectedVoiceId }?.name ?: "默认音色"

    if (showVoicePicker) {
        VoicePickerSheet(
            voices = voices,
            selectedVoiceId = state.selectedVoiceId,
            onDismiss = { showVoicePicker = false },
            onVoiceSelected = { voiceId ->
                viewModel.selectVoice(voiceId)
                showVoicePicker = false
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("文字转语音", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ComposerPanel(
                state = state,
                selectedVoiceName = selectedVoiceName,
                onTextChange = viewModel::updateText,
                onVoicePickerClick = { if (!state.busy) showVoicePicker = true },
            )
            if ((state.busy && !state.downloading) || state.sampleCount > 0) {
                ResultPanel(
                    state = state,
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                onClick = viewModel::synthesize,
                enabled = state.modelReady && state.text.isNotBlank() && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy && !state.downloading) "正在生成……" else "开始合成")
            }
        }
    }
}

@Composable
private fun VoicesScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val context = LocalContext.current
    var voiceName by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.startVoiceRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("新建音色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                )
                if (recorderState.isRecording) {
                    LinearProgressIndicator(
                        progress = { recorderState.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在录音 · ${formatDuration(recorderState.elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (recorderState.isRecording) {
                                viewModel.stopVoiceRecordingAndCreate(voiceName)
                                voiceName = ""
                            } else if (hasPermission) {
                                viewModel.startVoiceRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.busy || recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (recorderState.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (recorderState.isRecording) "停止并创建" else "开始录音")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelVoiceRecording,
                        enabled = recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                }
                if (state.supportsCloning == false) {
                    Text(
                        "当前模型不支持声音克隆功能。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("音色管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (voices.isEmpty()) {
                    Text("还没有保存的音色。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    voices.forEach { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = state.selectedVoiceId == voice.voiceId,
                            onSelect = { viewModel.selectVoice(voice.voiceId) },
                            onDelete = { viewModel.deleteVoice(voice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val generations by viewModel.generations.collectAsState()
    var pendingExport by remember { mutableStateOf<GenerationEntity?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val generation = pendingExport
        pendingExport = null
        if (uri != null && generation != null) {
            viewModel.exportGenerationToUri(generation, uri)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (generations.isEmpty()) {
            item {
                Text("还没有生成语音。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(generations, key = { it.generationId }) { generation ->
            val isPlayingThis = state.playing && state.playingGenerationId == generation.generationId
            var menuExpanded by remember(generation.generationId) { mutableStateOf(false) }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(generation.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${generation.voiceName} · ${formatSeconds(generation.sampleCount.toDouble() / generation.sampleRate.coerceAtLeast(1))} · ${formatDate(generation.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            if (isPlayingThis) {
                                viewModel.stopAudio()
                            } else {
                                viewModel.playGeneration(generation.generationId)
                            }
                        },
                    ) {
                        Icon(
                            if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlayingThis) "停止" else "播放")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    pendingExport = generation
                                    exportLauncher.launch(viewModel.suggestedExportFileName(generation))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.deleteGeneration(generation)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModelPanel(
            state = state,
            onDownload = viewModel::downloadModel,
            onLoad = viewModel::loadModel,
        )
        RuntimePanel(
            state = state,
            onCpuThreadsChange = viewModel::updateCpuThreads,
        )
    }
}

@Composable
private fun ComposerPanel(
    state: QwenTtsUiState,
    selectedVoiceName: String,
    onTextChange: (String) -> Unit,
    onVoicePickerClick: () -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("输入文本") },
            )
            OutlinedButton(
                onClick = onVoicePickerClick,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text("音色", style = MaterialTheme.typography.labelMedium)
                    Text(selectedVoiceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<VoiceProfileEntity>,
    selectedVoiceId: String?,
    onDismiss: () -> Unit,
    onVoiceSelected: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("选择音色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            VoicePickerItem(
                title = "默认音色",
                subtitle = "使用模型默认音色",
                selected = selectedVoiceId == null,
                onClick = { onVoiceSelected(null) },
            )
            voices.forEach { voice ->
                VoicePickerItem(
                    title = voice.name,
                    subtitle = "参考音频 · ${formatDuration(voice.durationMillis)}",
                    selected = selectedVoiceId == voice.voiceId,
                    onClick = { onVoiceSelected(voice.voiceId) },
                )
            }
        }
    }
}

@Composable
private fun VoicePickerItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "已选中", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceProfileEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "使用中" else "使用") })
        Column(Modifier.weight(1f)) {
            Text(voice.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "参考音频 ${formatDuration(voice.durationMillis)} · ${formatDate(voice.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun ModelPanel(
    state: QwenTtsUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    val selectedVariant = QwenModel.variantById(state.selectedModelId)
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedVariant.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "模型文件总大小：${formatBytes(selectedVariant.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(if (state.modelReady) "已就绪" else "未下载") })
            }

            if (state.downloading) {
                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.downloadBytes)} / ${formatBytes(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy && !state.modelReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.modelReady) "模型已就绪" else "下载模型")
                }
                OutlinedButton(
                    onClick = onLoad,
                    enabled = state.modelReady && !state.loaded && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loaded) "已加载" else "加载模型")
                }
            }
        }
    }
}

@Composable
private fun RuntimePanel(
    state: QwenTtsUiState,
    onCpuThreadsChange: (Int) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("性能设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "CPU 运算后端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { threads ->
                    FilterChip(
                        selected = state.selectedCpuThreads == threads,
                        onClick = { onCpuThreadsChange(threads) },
                        enabled = !state.busy,
                        label = { Text("$threads 个线程") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                GenerationStats(state)
            } else {
                if (state.sampleCount > 0 && state.sampleRate > 0) {
                    Text(
                        "音频时长 ${formatSeconds(state.sampleCount.toDouble() / state.sampleRate.toDouble())} · 合成耗时 ${formatDuration(state.synthesisMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { if (state.playing) onStop() else onPlay() },
                    enabled = state.sampleCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.playing) "停止" else "播放")
                }
            }
        }
    }
}

@Composable
private fun GenerationStats(state: QwenTtsUiState) {
    val generatedAudioSeconds = state.synthesisFrames * 0.08
    val remainingMillis = state.estimatedSynthesisMillis
        ?.minus(state.operationElapsedMillis)
        ?.coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "已用时间：${formatDuration(state.operationElapsedMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.synthesisFrames > 0) {
            Text(
                "已生成音频约 ${formatSeconds(generatedAudioSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (remainingMillis != null && state.estimateSampleCount >= 3) {
            Text(
                "预计剩余 ${formatDuration(remainingMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun readWav(path: String): WavData {
    val bytes = File(path).readBytes()
    require(bytes.size > 44) { "WAV 文件为空" }
    fun intLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    fun shortLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    var cursor = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = intLe(cursor + 4)
        val payload = cursor + 8
        when (id) {
            "fmt " -> {
                channels = shortLe(payload + 2).coerceAtLeast(1)
                sampleRate = intLe(payload + 4)
                bits = shortLe(payload + 14)
            }
            "data" -> {
                dataOffset = payload
                dataSize = size
                break
            }
        }
        cursor = payload + size
    }
    require(dataOffset >= 0 && bits == 16) { "仅支持播放 PCM16 格式的 WAV 历史音频" }
    val frameBytes = channels * 2
    val frameCount = dataSize / frameBytes
    val samples = FloatArray(frameCount)
    var offset = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channels) {
            val value = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort()
            sum += value / 32768f
            offset += 2
        }
        samples[i] = sum / channels
    }
    return WavData(samples, sampleRate)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes 字节"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "%d:%02d".format(Locale.US, minutes, seconds) else "%d秒".format(Locale.US, seconds)
}

private fun formatSeconds(seconds: Double): String =
    "%.1f秒".format(Locale.US, seconds.coerceAtLeast(0.0))

private fun formatDate(epochMillis: Long): String {
    val date = java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault())
    return date.format(java.util.Date(epochMillis))
}

private fun backendOptionById(id: String): BackendOption =
    backendOptions.firstOrNull { it.id == id } ?: defaultBackendOption

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}
