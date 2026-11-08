package io.github.ddagunts.screencast.target

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.media.PlaybackCaptureConfigBuilder
import io.github.ddagunts.screencast.shizuku.LeaseAcquisitionOutcome
import io.github.ddagunts.screencast.shizuku.MultiProfileCatalogSnapshot
import io.github.ddagunts.screencast.shizuku.ShizukuAudioMuteUserService
import io.github.ddagunts.screencast.shizuku.ShizukuMuteBackend
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.ui.ScreenCastViewModel
import io.github.ddagunts.screencast.webrtc.PlaybackAudioRecorder
import io.github.ddagunts.screencast.webrtc.WebRtcAudioCapture
import io.github.ddagunts.screencast.webrtc.WebRtcPcmBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PerAppAudioCastAndMuteTest {

    private lateinit var app: Application
    private lateinit var projection: MediaProjection
    private lateinit var fakeBackend: FakeShizukuMuteBackend

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        projection = Shadow.newInstanceOf(MediaProjection::class.java)
        AudioCastSessionCoordinator.resetForTesting()
        fakeBackend = FakeShizukuMuteBackend()
        ShizukuSessionMuteController.installBackendForTesting(fakeBackend)
    }

    @After
    fun tearDown() {
        ShizukuSessionMuteController.resetToDefaultBackend()
        AudioCastSessionCoordinator.resetForTesting()
    }

    @Test
    fun captureTargetSerializesWithoutUidAndIsolatesSamePackageAcrossThreeProfiles() {
        val primaryMusic = CaptureTarget("com.android.music", 0L)
        val workMusic = CaptureTarget("com.android.music", 10L)
        val cloneMusic = CaptureTarget("com.android.music", 999L)

        val set = setOf(primaryMusic, workMusic, cloneMusic)
        assertEquals(3, set.size)

        for (target in set) {
            val encoded = target.toStorageKey()
            assertFalse(encoded.contains("101")) // Never stores UID
            assertEquals(target, CaptureTarget.fromStorageKey(encoded))
        }
    }

    @Test
    fun unifiedTargetRepositoryPersistsAddedTargetsAndSelectionsAcrossProfiles() {
        CaptureTargetRepository.setTargetsForTesting(emptySet(), emptySet())

        val importPrimary = CaptureTargetRepository.importTargetApp(
            context = app,
            packageName = "com.android.music",
            userSerialNumber = 0L,
            selectImmediately = true,
            isEditingLocked = false
        )
        val importWork = CaptureTargetRepository.importTargetApp(
            context = app,
            packageName = "com.android.music",
            userSerialNumber = 10L,
            selectImmediately = true,
            isEditingLocked = false
        )
        assertTrue(importPrimary.isSuccess)
        assertTrue(importWork.isSuccess)
        assertEquals(2, CaptureTargetRepository.importedTargets.value.size)
        assertEquals(2, CaptureTargetRepository.selectedTargets.value.size)

        // Removing one profile instance leaves the other profile instance intact
        CaptureTargetRepository.removeImportedTarget(
            context = app,
            target = CaptureTarget("com.android.music", 0L),
            isEditingLocked = false
        )
        assertEquals(
            setOf(CaptureTarget("com.android.music", 10L)),
            CaptureTargetRepository.importedTargets.value
        )
        assertEquals(
            setOf(CaptureTarget("com.android.music", 10L)),
            CaptureTargetRepository.selectedTargets.value
        )
    }

    @Test
    fun sharedPlaybackCaptureConfigBuilderAppliesMatchingUidsAndMediaUsages() {
        val targetUids = setOf(10185, 1010185, 99910185)
        val recordedUsages = mutableListOf<Int>()
        val recordedUids = mutableListOf<Int>()

        PlaybackCaptureConfigBuilder.applyConstraints(
            targetUids = targetUids,
            addMatchingUsage = { recordedUsages.add(it) },
            addMatchingUid = { recordedUids.add(it) }
        )

        assertEquals(listOf(10185, 1010185, 99910185), recordedUids)
        val matchingUsages = recordedUsages.toSet()
        assertTrue(matchingUsages.contains(AudioAttributes.USAGE_MEDIA))
        assertTrue(matchingUsages.contains(AudioAttributes.USAGE_GAME))
        assertTrue(matchingUsages.contains(AudioAttributes.USAGE_UNKNOWN))
        assertFalse(matchingUsages.contains(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        assertFalse(matchingUsages.contains(AudioAttributes.USAGE_ALARM))

        // Empty target UID whitelist must be rejected
        val emptyError = runCatching {
            PlaybackCaptureConfigBuilder.build(projection, emptySet())
        }.exceptionOrNull()
        assertNotNull(emptyError)
    }

    @Test
    fun webRtcPassesTargetUidsToRecorderFactory() {
        val expectedUids = setOf(10240, 1010240)
        val webRtcCapturedUids = AtomicReference<Set<Int>>(emptySet())

        val webRtcCapture = WebRtcAudioCapture(
            onError = {},
            onStats = {},
            onLog = {},
            minBufferSizeProvider = { _, _, _ -> WebRtcPcmBuffer.CHUNK_BYTES * 2 },
            recorderFactory = { _, _, _, uids ->
                webRtcCapturedUids.set(uids)
                object : PlaybackAudioRecorder {
                    override val state: Int = AudioRecord.STATE_INITIALIZED
                    override val recordingState: Int = AudioRecord.RECORDSTATE_RECORDING
                    override fun startRecording() {}
                    override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                        Thread.sleep(10)
                        return sizeInBytes
                    }
                    override fun stop() {}
                    override fun release() {}
                }
            }
        )
        webRtcCapture.attachProjection(projection, expectedUids)
        webRtcCapture.release()
        assertEquals(expectedUids, webRtcCapturedUids.get())
    }

    @Test
    fun prepareCaptureRequestRejectsEmptyTargetsSharedUidConflictsAndLocksEditingWhenFrozen() {
        val viewModel = ScreenCastViewModel(app)

        // 1. Empty target selection must be rejected
        CaptureTargetRepository.setTargetsForTesting(emptySet())
        assertFalse(viewModel.prepareCaptureRequest(requireConnectedCastReceiver = false))
        assertTrue(viewModel.telemetry.value.error.orEmpty().contains("至少勾选 1 个"))

        // 2. Shared UID conflict (unselected sibling package sharing same UID) must be rejected
        val targetA = CaptureTarget("com.shared.alpha", 0L)
        fakeBackend.nextResolutionResult = TargetResolutionResult(
            isValid = false,
            resolvedUids = setOf(10555),
            sharedUidConflicts = mapOf(10555 to listOf("com.shared.beta")),
            failureReason = "共享 UID (10555) 包含未选中的应用 [com.shared.beta]，拒绝启动且不自动扩大采集/静音范围。"
        )
        CaptureTargetRepository.setTargetsForTesting(setOf(targetA))
        assertFalse(viewModel.prepareCaptureRequest(requireConnectedCastReceiver = false))
        assertTrue(viewModel.telemetry.value.error.orEmpty().contains("com.shared.beta"))
        assertFalse(viewModel.isTargetEditingLocked.value)

        // 3. Valid target freezes snapshot and locks target editing during authorization
        val musicMain = CaptureTarget("com.android.music", 0L)
        val musicWork = CaptureTarget("com.android.music", 10L)
        fakeBackend.nextResolutionResult = TargetResolutionResult(
            isValid = true,
            resolvedUids = setOf(10188, 1010188),
            targetUidMap = mapOf(musicMain to 10188, musicWork to 1010188)
        )
        CaptureTargetRepository.setTargetsForTesting(setOf(musicMain))
        assertTrue(viewModel.prepareCaptureRequest(requireConnectedCastReceiver = false))
        assertTrue(viewModel.isTargetEditingLocked.value)

        // Attempting to toggle or import targets while locked must be rejected
        assertFalse(
            CaptureTargetRepository.toggleTargetSelection(
                app,
                musicWork,
                viewModel.isTargetEditingLocked.value
            )
        )
        assertEquals(setOf(musicMain), viewModel.selectedTargets.value)

        // Authorization failure unlocks target editing and releases mute lease immediately
        val releasesBefore = fakeBackend.releaseCalls.get()
        viewModel.captureAuthorizationFailed("User cancelled MediaProjection dialog")
        assertFalse(viewModel.isTargetEditingLocked.value)
        assertEquals(releasesBefore + 1, fakeBackend.releaseCalls.get())
    }

    @Test
    fun sessionMuteLeaseRenewsAndRestoresImmediatelyOnFailureOrStop() = runBlocking {
        val fatalErrors = CopyOnWriteArrayList<String>()
        val targetUids = setOf(10188, 1010188)

        fakeBackend.leaseOutcomeToReturn = LeaseAcquisitionOutcome(
            success = true,
            activeTargetPlayerCount = 2,
            confirmedMutedPlayerCount = 2,
            lastMixerMutedByMask = ShizukuAudioMuteUserService.MUTED_BY_VOLUME_SHAPER,
            lastMuteConfirmationLatencyMs = 18L,
            maxObservedNewTrackMuteLatencyMs = 24L
        )

        val initial = ShizukuSessionMuteController.startSessionMuteLease(
            targetUids = targetUids,
            onFatalMuteError = { fatalErrors.add(it) }
        )
        assertTrue(initial.success)
        assertTrue(ShizukuSessionMuteController.muteTelemetry.value.leaseActive)
        assertEquals(2, ShizukuSessionMuteController.muteTelemetry.value.confirmedMutedPlayerCount)
        assertEquals(true, ShizukuSessionMuteController.muteTelemetry.value.isNewTrackLatencyWithinGate)

        // Simulate a new playback session failing the 100ms confirmation gate or unsupported player
        fakeBackend.emitRemoteMuteFailure(
            "新播放会话 (piid=77) 混音层静音确认耗时 118ms 超过 100ms 门禁上限，已终止投放并恢复。"
        )
        assertEquals(1, fatalErrors.size)
        assertFalse(ShizukuSessionMuteController.muteTelemetry.value.leaseActive)
        assertTrue(fakeBackend.releaseCalls.get() >= 1)
    }

    private class FakeShizukuMuteBackend : ShizukuMuteBackend {
        private val _connectionState = MutableStateFlow(ShizukuConnectionState.READY)
        override val connectionState: StateFlow<ShizukuConnectionState> = _connectionState

        private val failureListeners = CopyOnWriteArrayList<(String) -> Unit>()
        private val stateListeners = CopyOnWriteArrayList<(LeaseAcquisitionOutcome) -> Unit>()

        val releaseCalls = AtomicInteger(0)
        @Volatile var activeStartedUids: Set<Int> = emptySet()
        @Volatile var nextResolutionResult: TargetResolutionResult = TargetResolutionResult(
            isValid = true,
            resolvedUids = setOf(10188)
        )
        @Volatile var leaseOutcomeToReturn: LeaseAcquisitionOutcome = LeaseAcquisitionOutcome(
            success = true,
            activeTargetPlayerCount = 1,
            confirmedMutedPlayerCount = 1,
            lastMixerMutedByMask = ShizukuAudioMuteUserService.MUTED_BY_VOLUME_SHAPER,
            lastMuteConfirmationLatencyMs = 12L,
            maxObservedNewTrackMuteLatencyMs = 12L
        )

        override fun refreshShizukuStatus() {}
        override fun requestPermission(requestCode: Int) {}

        override fun queryCatalog(
            context: Context,
            importedTargets: Set<CaptureTarget>
        ): MultiProfileCatalogSnapshot {
            val profiles = listOf(
                UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true),
                UserProfileInfo(10L, 10, "工作资料 (User 10)", ProfileType.WORK, true),
                UserProfileInfo(999L, 999, "克隆资料 (User 999)", ProfileType.CLONE, true)
            )
            return MultiProfileCatalogSnapshot(
                profiles = profiles,
                catalogApps = emptyList(),
                searchableApps = emptyList()
            )
        }

        override fun resolveAndValidateTargets(
            context: Context,
            selectedTargets: Set<CaptureTarget>
        ): TargetResolutionResult = nextResolutionResult

        override fun acquireOrRenewLease(
            targetUids: Set<Int>,
            leaseDurationMs: Long
        ): LeaseAcquisitionOutcome = leaseOutcomeToReturn

        override fun releaseLeaseImmediately() {
            releaseCalls.incrementAndGet()
        }

        override fun hasActiveStartedPlaybackForUids(targetUids: Set<Int>): Boolean {
            return targetUids.any { it in activeStartedUids }
        }

        override fun addFailureListener(listener: (String) -> Unit) {
            failureListeners.add(listener)
        }

        override fun removeFailureListener(listener: (String) -> Unit) {
            failureListeners.remove(listener)
        }

        override fun addStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit) {
            stateListeners.add(listener)
        }

        override fun removeStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit) {
            stateListeners.remove(listener)
        }

        fun emitRemoteMuteFailure(reason: String) {
            for (l in failureListeners) l(reason)
        }
    }
}
