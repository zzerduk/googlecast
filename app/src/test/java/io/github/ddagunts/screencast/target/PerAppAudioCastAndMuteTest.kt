package io.github.ddagunts.screencast.target

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.VolumeShaper
import android.media.projection.MediaProjection
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSessionVolumeEndpoint
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.media.PhoneGlobalMediaMuteController
import io.github.ddagunts.screencast.media.PlaybackCaptureConfigBuilder
import io.github.ddagunts.screencast.media.SystemMediaAudioAdapter
import io.github.ddagunts.screencast.shizuku.LeaseAcquisitionOutcome
import io.github.ddagunts.screencast.shizuku.MultiProfileCatalogSnapshot
import io.github.ddagunts.screencast.shizuku.ShizukuAudioMuteUserService
import io.github.ddagunts.screencast.shizuku.ShizukuMuteBackend
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.ui.ScreenCastViewModel
import io.github.ddagunts.screencast.webrtc.PlaybackAudioRecorder
import io.github.ddagunts.screencast.webrtc.WebRtcAudioCapture
import io.github.ddagunts.screencast.webrtc.WebRtcPcmBuffer
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PerAppAudioCastAndMuteTest {

    private lateinit var app: Application
    private lateinit var projection: MediaProjection
    private lateinit var fakeBackend: FakeShizukuMuteBackend
    private lateinit var fakeAudioAdapter: FakeSystemMediaAudioAdapter

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        projection = Shadow.newInstanceOf(MediaProjection::class.java)
        AudioCastSessionCoordinator.resetForTesting(app)
        CastSdkSessionController.resetForTesting()
        PublicApiTargetResolver.resetEnvironmentForTesting()
        fakeAudioAdapter = FakeSystemMediaAudioAdapter(currentVolume = 9, maxVolume = 15, currentMuted = false)
        PhoneGlobalMediaMuteController.audioAdapter = fakeAudioAdapter
        fakeBackend = FakeShizukuMuteBackend()
        ShizukuSessionMuteController.installBackendForTesting(fakeBackend)
    }

    @After
    fun tearDown() {
        PublicApiTargetResolver.resetEnvironmentForTesting()
        CastSdkSessionController.resetForTesting()
        ShizukuSessionMuteController.resetToDefaultBackend()
        AudioCastSessionCoordinator.resetForTesting(app)
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
    fun prepareCaptureRequestRejectsEmptyTargetsSharedUidConflictsAndLocksEditingWhenFrozen() = runBlocking {
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
    }

    @Test
    fun dedicatedVolumeShaperRemovalTerminatesByShaperIdInsteadOfReverse() {
        val service = ShizukuAudioMuteUserService()
        val capturedConfigs = mutableListOf<VolumeShaper.Configuration>()
        val capturedOperations = mutableListOf<VolumeShaper.Operation>()

        val fakePlayerProxy = object {
            @Suppress("unused")
            fun applyVolumeShaper(
                config: VolumeShaper.Configuration,
                operation: VolumeShaper.Operation
            ) {
                capturedConfigs.add(config)
                capturedOperations.add(operation)
            }
        }

        val removed = service.removeDedicatedShaperFromPlayer(fakePlayerProxy)
        assertTrue(removed)
        assertEquals(1, capturedConfigs.size)
        assertEquals(1, capturedOperations.size)

        val appliedOp = capturedOperations.single()
        // Must NEVER use REVERSE on a zero-gain curve
        assertNotEquals(VolumeShaper.Operation.REVERSE, appliedOp)
        assertNotEquals(VolumeShaper.Operation.PLAY, appliedOp)

        val idField = runCatching {
            VolumeShaper.Configuration::class.java.getDeclaredMethod("getId").apply { isAccessible = true }
                .invoke(capturedConfigs.single()) as? Int
        }.getOrNull()
        assertEquals(ShizukuAudioMuteUserService.DEDICATED_SHAPER_ID, idField)
    }

    @Test
    fun fallbackModeDialogOpensWhenShizukuUnavailableOrPermissionRequiredAndFreezesSelectedMode() = runBlocking {
        val viewModel = ScreenCastViewModel(app)
        val metrolist = CaptureTarget("com.metrolist.music", 0L)
        CaptureTargetRepository.setTargetsForTesting(setOf(metrolist))

        PublicApiTargetResolver.environment = FakePublicTargetEnvironment(
            profiles = listOf(
                PublicTargetEnvironment.AccessibleProfile(
                    userSerialNumber = 0L,
                    userId = 0,
                    label = "主空间 (Serial 0)",
                    profileType = ProfileType.PRIMARY,
                    isRunning = true,
                    isCurrentProcessUser = true
                )
            ),
            apps = mapOf(
                metrolist to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = "com.metrolist.music",
                    userSerialNumber = 0L,
                    appLabel = "Metrolist",
                    uid = 10234,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf("com.metrolist.music"),
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                )
            )
        )

        // 1. Shizuku UNAVAILABLE -> initiateStartCastFlow opens output mode dialog instead of starting
        fakeBackend.setConnectionState(ShizukuConnectionState.UNAVAILABLE)
        assertFalse(viewModel.initiateStartCastFlow(requireConnectedCastReceiver = false))
        assertTrue(viewModel.showOutputModeDialog.value)

        // User chooses CAPTURE_ONLY_UNMUTED -> freezes mode and prepares request without Shizuku
        assertTrue(
            viewModel.confirmFallbackOutputModeAndPrepare(
                chosenMode = PhoneOutputMode.CAPTURE_ONLY_UNMUTED,
                requireConnectedCastReceiver = false
            )
        )
        assertFalse(viewModel.showOutputModeDialog.value)
        assertEquals(PhoneOutputMode.CAPTURE_ONLY_UNMUTED, CaptureTargetRepository.frozenOutputMode.value)
        assertEquals(PhoneOutputMode.CAPTURE_ONLY_UNMUTED, viewModel.telemetry.value.activeOutputMode)
        viewModel.stopAudioCast()

        // 2. Shizuku PERMISSION_REQUIRED -> also opens dialog; user chooses GLOBAL_MEDIA_MUTE
        fakeBackend.setConnectionState(ShizukuConnectionState.PERMISSION_REQUIRED)
        assertFalse(viewModel.initiateStartCastFlow(requireConnectedCastReceiver = false))
        assertTrue(viewModel.showOutputModeDialog.value)

        assertTrue(
            viewModel.confirmFallbackOutputModeAndPrepare(
                chosenMode = PhoneOutputMode.GLOBAL_MEDIA_MUTE,
                requireConnectedCastReceiver = false
            )
        )
        assertEquals(PhoneOutputMode.GLOBAL_MEDIA_MUTE, CaptureTargetRepository.frozenOutputMode.value)
        viewModel.stopAudioCast()

        // 3. When GLOBAL_MEDIA_MUTE is marked incompatible on device, selecting it is rejected with reason,
        //    while CAPTURE_ONLY_UNMUTED still succeeds
        viewModel.setGlobalMediaMuteCompatibility(
            compatible = false,
            reason = "本机 STREAM_MUSIC 归零会导致回采静音"
        )
        assertFalse(
            viewModel.confirmFallbackOutputModeAndPrepare(
                chosenMode = PhoneOutputMode.GLOBAL_MEDIA_MUTE,
                requireConnectedCastReceiver = false
            )
        )
        assertTrue(viewModel.telemetry.value.error.orEmpty().contains("回采静音"))
        assertTrue(
            viewModel.confirmFallbackOutputModeAndPrepare(
                chosenMode = PhoneOutputMode.CAPTURE_ONLY_UNMUTED,
                requireConnectedCastReceiver = false
            )
        )
        viewModel.stopAudioCast()
    }

    @Test
    fun publicApiTargetResolverRejectsInaccessibleSpacesAndUnverifiableSharedUidsWithoutIndexGuessing() {
        fakeBackend.setConnectionState(ShizukuConnectionState.UNAVAILABLE)

        val primaryMusic = CaptureTarget("com.metrolist.music", 0L)
        val workMusicUnverifiableShared = CaptureTarget("com.work.player", 15L) // Non-sequential serial 15L at index 1
        val cloneMusicInaccessible = CaptureTarget("com.metrolist.music", 999L) // Inaccessible clone profile
        val restrictedBili = CaptureTarget("tv.danmaku.bili", 0L)
        val nonLaunchableServicePkg = CaptureTarget("com.android.providers.media", 0L)

        PublicApiTargetResolver.environment = FakePublicTargetEnvironment(
            profiles = listOf(
                PublicTargetEnvironment.AccessibleProfile(
                    userSerialNumber = 0L,
                    userId = 0,
                    label = "主空间 (Serial 0)",
                    profileType = ProfileType.PRIMARY,
                    isRunning = true,
                    isCurrentProcessUser = true
                ),
                PublicTargetEnvironment.AccessibleProfile(
                    userSerialNumber = 15L,
                    userId = 15,
                    label = "工作资料 (Serial 15)",
                    profileType = ProfileType.WORK,
                    isRunning = true,
                    isCurrentProcessUser = false
                )
            ),
            apps = mapOf(
                primaryMusic to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = "com.metrolist.music",
                    userSerialNumber = 0L,
                    appLabel = "Metrolist",
                    uid = 10234,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf("com.metrolist.music"),
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                ),
                workMusicUnverifiableShared to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = "com.work.player",
                    userSerialNumber = 15L,
                    appLabel = "Work Player",
                    uid = 1510299,
                    isSystemApp = false,
                    isLaunchable = true,
                    // Cross-profile getPackagesForUid unavailable without Shizuku -> null
                    verifiedSharedUidPackages = null,
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                ),
                restrictedBili to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = "tv.danmaku.bili",
                    userSerialNumber = 0L,
                    appLabel = "Bilibili",
                    uid = 10288,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf("tv.danmaku.bili"),
                    appCategory = ApplicationInfo.CATEGORY_VIDEO
                ),
                nonLaunchableServicePkg to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = "com.android.providers.media",
                    userSerialNumber = 0L,
                    appLabel = "Media Storage",
                    uid = 10015,
                    isSystemApp = true,
                    isLaunchable = false,
                    verifiedSharedUidPackages = listOf("com.android.providers.media"),
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                )
            )
        )

        val imported = setOf(primaryMusic, workMusicUnverifiableShared, cloneMusicInaccessible, restrictedBili)
        val catalog = ShizukuSessionMuteController.queryCatalog(app, imported)

        // 1. Default searchable catalog in fallback mode must NOT include non-launchable system packages
        assertTrue(catalog.searchableApps.none { it.target == nonLaunchableServicePkg })
        assertTrue(catalog.searchableApps.any { it.target == primaryMusic })

        // 2. All imported targets (including inaccessible clone and unverifiable shared UID) are retained with reasons
        assertEquals(4, catalog.catalogApps.size)
        val cloneEntry = catalog.catalogApps.first { it.target == cloneMusicInaccessible }
        assertNull(cloneEntry.resolvedUid) // Never guesses UID!
        assertFalse(cloneEntry.isIdentityVerified)
        assertTrue(cloneEntry.statusReason.orEmpty().contains("无法访问"))

        val workEntry = catalog.catalogApps.first { it.target == workMusicUnverifiableShared }
        assertFalse(workEntry.isSharedUidVerified)
        assertTrue(workEntry.statusReason.orEmpty().contains("共享 UID"))

        // 3. Resolution succeeds for verifiable primaryMusic alone
        val validRes = PublicApiTargetResolver.resolveAndValidateTargets(app, setOf(primaryMusic))
        assertTrue(validRes.isValid)
        assertEquals(setOf(10234), validRes.resolvedUids)

        // 4. Resolution rejects inaccessible clone target without guessing UID
        val cloneRes = PublicApiTargetResolver.resolveAndValidateTargets(
            app,
            setOf(primaryMusic, cloneMusicInaccessible)
        )
        assertFalse(cloneRes.isValid)
        assertTrue(cloneRes.failureReason.orEmpty().contains("999"))

        // 5. Resolution rejects cross-profile target when shared UID completeness cannot be verified
        val workRes = PublicApiTargetResolver.resolveAndValidateTargets(
            app,
            setOf(workMusicUnverifiableShared)
        )
        assertFalse(workRes.isValid)
        assertTrue(workRes.failureReason.orEmpty().contains("共享 UID"))
    }

    @Test
    fun globalMediaMutePersistsRestoresPreservesUserNonZeroOverrideAndPromptsOnCrashLeftover() {
        // 1. Normal engage & restore when volume remains 0
        fakeAudioAdapter.currentVolume = 10
        fakeAudioAdapter.currentMuted = false

        val engageRes = PhoneGlobalMediaMuteController.engageGlobalMediaMute(app)
        assertTrue(engageRes.isSuccess)
        assertEquals(0, fakeAudioAdapter.currentVolume)
        assertTrue(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)

        val restoreRes1 = PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app)
        assertTrue(restoreRes1.isSuccess)
        assertNotNull(restoreRes1.getOrNull())
        assertEquals(10, fakeAudioAdapter.currentVolume)
        assertFalse(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)

        // 2. User manually adjusts phone media volume to non-zero (e.g., 6) during casting -> preserve 6 on stop!
        fakeAudioAdapter.currentVolume = 11
        assertTrue(PhoneGlobalMediaMuteController.engageGlobalMediaMute(app).isSuccess)
        assertEquals(0, fakeAudioAdapter.currentVolume)

        // Simulate user pressing hardware volume up during session
        fakeAudioAdapter.currentVolume = 6
        assertFalse(PhoneGlobalMediaMuteController.isPhoneCurrentlyMutedByGlobalMode(app))

        assertTrue(PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app).isSuccess)
        assertEquals(6, fakeAudioAdapter.currentVolume) // Preserved user's new volume 6, not overwritten with 11!

        // 3. Rollback on failure when device refuses to set volume to 0
        fakeAudioAdapter.currentVolume = 8
        fakeAudioAdapter.refuseZeroVolume = true
        val failedEngage = PhoneGlobalMediaMuteController.engageGlobalMediaMute(app)
        assertTrue(failedEngage.isFailure)
        assertEquals(8, fakeAudioAdapter.currentVolume)
        assertFalse(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)
        fakeAudioAdapter.refuseZeroVolume = false

        // 4. Force-kill leftover record on reopen: must NOT auto-write back; must expose pendingRecovery prompt
        fakeAudioAdapter.currentVolume = 0
        PhoneGlobalMediaMuteController.simulateLeftoverCrashRecordForTesting(
            context = app,
            savedVolume = 12,
            maxVolume = 15,
            wasMuted = false
        )
        PhoneGlobalMediaMuteController.initialize(app, isSessionCurrentlyActive = false)
        assertEquals(0, fakeAudioAdapter.currentVolume) // Did NOT write back behind user's back!
        val pending = PhoneGlobalMediaMuteController.pendingRecovery.value
        assertNotNull(pending)
        assertEquals(12, pending?.savedVolume)

        // User chooses "恢复原音量"
        assertTrue(PhoneGlobalMediaMuteController.confirmRestoreLeftoverVolume(app).isSuccess)
        assertNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        assertEquals(12, fakeAudioAdapter.currentVolume)

        // Simulate another crash leftover where user chooses "保留当前设置"
        fakeAudioAdapter.currentVolume = 3
        PhoneGlobalMediaMuteController.simulateLeftoverCrashRecordForTesting(
            context = app,
            savedVolume = 14,
            maxVolume = 15,
            wasMuted = false
        )
        PhoneGlobalMediaMuteController.initialize(app, isSessionCurrentlyActive = false)
        assertNotNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        assertTrue(PhoneGlobalMediaMuteController.dismissLeftoverRecoveryKeepCurrent(app).isSuccess)
        assertNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        assertEquals(3, fakeAudioAdapter.currentVolume)
    }

    @Test
    fun targetPresetsEnforceValidationPersistWithoutUidsReplaceSelectionRetainImportedAndRespectLock() {
        val appA = CaptureTarget("com.metrolist.music", 0L)
        val appB = CaptureTarget("org.videolan.vlc", 0L)
        val appC = CaptureTarget("com.Clone.music", 999L)

        CaptureTargetRepository.setTargetsForTesting(
            selected = emptySet(),
            imported = setOf(appA, appB, appC),
            presets = emptyList()
        )

        // 1. Empty selection cannot be saved as a preset
        val emptyRes = CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "常听组合", isEditingLocked = false)
        assertTrue(emptyRes.isFailure)

        // 2. Blank name cannot be saved
        CaptureTargetRepository.setTargetsForTesting(
            selected = setOf(appA, appB),
            imported = setOf(appA, appB, appC),
            presets = emptyList()
        )
        val blankRes = CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "   ", isEditingLocked = false)
        assertTrue(blankRes.isFailure)

        // 3. Valid preset saves and serializes without UIDs
        val save1 = CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "音乐双应用", isEditingLocked = false)
        assertTrue(save1.isSuccess)
        assertEquals(1, CaptureTargetRepository.presets.value.size)

        // 4. Duplicate name is rejected
        val dupRes = CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "音乐双应用", isEditingLocked = false)
        assertTrue(dupRes.isFailure)

        // 5. Save a second preset including an inaccessible target
        CaptureTargetRepository.setTargetsForTesting(
            selected = setOf(appC),
            imported = setOf(appA, appB, appC),
            presets = CaptureTargetRepository.presets.value
        )
        val save2 = CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "克隆空间组合", isEditingLocked = false)
        assertTrue(save2.isSuccess)

        // 6. Flush & reload from DataStore verifies persistence without UIDs
        val rawEncoded = CaptureTargetRepository.encodePresetsJson(CaptureTargetRepository.presets.value)
        assertFalse(rawEncoded.contains("10234"))
        CaptureTargetRepository.flushAndReloadForTesting(app)
        assertEquals(2, CaptureTargetRepository.presets.value.size)

        // 7. Applying preset replaces current selection
        assertTrue(CaptureTargetRepository.applyPreset(app, "音乐双应用", isEditingLocked = false))
        assertEquals(setOf(appA, appB), CaptureTargetRepository.selectedTargets.value)

        // 8. Editing, applying, or deleting presets is blocked when locked (during active cast / auth)
        assertFalse(CaptureTargetRepository.applyPreset(app, "克隆空间组合", isEditingLocked = true))
        assertEquals(setOf(appA, appB), CaptureTargetRepository.selectedTargets.value)
        assertTrue(
            CaptureTargetRepository.saveCurrentSelectionAsPreset(app, "新组合", isEditingLocked = true).isFailure
        )
        assertFalse(CaptureTargetRepository.deletePreset(app, "音乐双应用", isEditingLocked = true))

        // 9. Deleting preset when unlocked removes preset but retains imported apps
        assertTrue(CaptureTargetRepository.deletePreset(app, "音乐双应用", isEditingLocked = false))
        assertEquals(listOf("克隆空间组合"), CaptureTargetRepository.presets.value.map { it.name })
        assertTrue(CaptureTargetRepository.importedTargets.value.containsAll(setOf(appA, appB, appC)))
    }

    @Test
    fun castSpeakerVolumeAndMuteSyncsExternalChangesIgnoresStaleCallbacksAndDoesNotStopCastOrTouchPhoneVolume() {
        fakeAudioAdapter.currentVolume = 9
        val endpoint1 = FakeCastVolumeEndpoint(initialVolume = 0.4, initialMute = false)
        CastSdkSessionController.installConnectedEndpointForTesting("Living Room Home", endpoint1)

        assertEquals(0.4, CastSdkSessionController.status.value.volume, 0.001)
        assertFalse(CastSdkSessionController.status.value.isMuted)
        assertTrue(CastSdkSessionController.status.value.isVolumeControlAvailable)

        // 1. Adjusting speaker volume updates CastSession, does NOT auto-unmute if muted, and never changes phone volume
        assertTrue(CastSdkSessionController.setSpeakerMuted(true))
        assertTrue(CastSdkSessionController.status.value.isMuted)
        assertTrue(CastSdkSessionController.setSpeakerVolume(0.75))
        assertEquals(0.75, CastSdkSessionController.status.value.volume, 0.001)
        assertTrue(CastSdkSessionController.status.value.isMuted) // Did not auto-unmute speaker!
        assertEquals(9, fakeAudioAdapter.currentVolume) // Phone STREAM_MUSIC volume untouched!

        // 2. External volume/mute change via Cast.Listener.onVolumeChanged() syncs to status
        endpoint1.simulateExternalChange(newVolume = 0.25, newMute = false)
        assertEquals(0.25, CastSdkSessionController.status.value.volume, 0.001)
        assertFalse(CastSdkSessionController.status.value.isMuted)

        // 3. Single volume adjustment failure records volumeControlError without disconnecting or stopping active cast
        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(
                isSessionActive = true,
                isStreaming = true,
                webRtcStage = WebRtcStage.SENDING
            )
        }
        endpoint1.throwOnNextMutation = IOException("Transient Cast channel busy")
        assertFalse(CastSdkSessionController.setSpeakerVolume(0.9))
        assertTrue(CastSdkSessionController.status.value.isConnected)
        assertTrue(CastSdkSessionController.status.value.volumeControlError.orEmpty().contains("Transient"))
        assertTrue(AudioCastSessionCoordinator.telemetry.value.isStreaming)

        // 4. Replacing session ignores stale callbacks from endpoint1
        val endpoint2 = FakeCastVolumeEndpoint(initialVolume = 0.6, initialMute = false)
        CastSdkSessionController.installConnectedEndpointForTesting("Bedroom Nest", endpoint2)
        endpoint1.simulateExternalChange(newVolume = 0.05, newMute = true)
        assertEquals(0.6, CastSdkSessionController.status.value.volume, 0.001)
        assertFalse(CastSdkSessionController.status.value.isMuted)

        // 5. Disconnecting unregisters listener and disables speaker controls without restoring speaker volume
        CastSdkSessionController.disconnectEndpointForTesting()
        assertFalse(CastSdkSessionController.status.value.isConnected)
        assertFalse(CastSdkSessionController.status.value.isVolumeControlAvailable)
        assertEquals(0.6, endpoint2.getVolume(), 0.001)
    }

    // =========================================================================
    // Section 1: 目录与搜索 (Default Audio/Video Filtering & On-Demand Search)
    // =========================================================================

    @Test
    fun defaultCatalogPreFiltersAudioVideoBeforeLabelAndSharedUidAndLoadsExtendedSearchOnDemand() = runBlocking {
        fakeBackend.setConnectionState(ShizukuConnectionState.UNAVAILABLE)

        val audioApp = CaptureTarget("com.metrolist.music", 0L)
        val videoApp = CaptureTarget("org.videolan.vlc", 0L)
        val importedNotesApp = CaptureTarget("com.example.notes", 0L)
        val unselectedProductivityApp = CaptureTarget("com.example.office", 0L)
        val unselectedGameApp = CaptureTarget("com.example.game", 0L)

        // Verify Shizuku UserService catalog pre-filtering BEFORE loading labels or querying shared UIDs
        val shizukuService = ShizukuAudioMuteUserService()
        val shizukuLabelsLoaded = CopyOnWriteArrayList<String>()
        val shizukuSharedUidsQueried = CopyOnWriteArrayList<Int>()
        shizukuService.onLabelLoadedHook = { pkg -> shizukuLabelsLoaded.add(pkg) }
        shizukuService.onSharedUidQueriedHook = { uid -> shizukuSharedUidsQueried.add(uid) }
        shizukuService.userProfilesProvider = {
            listOf(ShizukuAudioMuteUserService.ShellUserProfile(0, 0L, "主空间 (User 0)", ProfileType.PRIMARY, true))
        }
        shizukuService.installedAppsProvider = {
            listOf(
                ApplicationInfo().apply {
                    packageName = audioApp.packageName
                    uid = 10201
                    category = ApplicationInfo.CATEGORY_AUDIO
                },
                ApplicationInfo().apply {
                    packageName = unselectedProductivityApp.packageName
                    uid = 10204
                    category = ApplicationInfo.CATEGORY_PRODUCTIVITY
                }
            )
        }
        shizukuService.packagesForUidProvider = { uid ->
            when (uid) {
                10201 -> listOf(audioApp.packageName)
                10204 -> listOf(unselectedProductivityApp.packageName)
                else -> emptyList()
            }
        }
        val shizukuDefaultJson = JSONObject(
            shizukuService.queryMultiProfileCatalog(mutableListOf(), false)
        )
        assertEquals(1, shizukuDefaultJson.getJSONArray("searchableApps").length())
        assertTrue(shizukuLabelsLoaded.contains(audioApp.packageName))
        assertFalse(
            "Shizuku default catalog must filter out non-audio/video apps BEFORE loading labels",
            shizukuLabelsLoaded.contains(unselectedProductivityApp.packageName)
        )
        assertFalse(
            "Shizuku default catalog must filter out non-audio/video apps BEFORE querying shared UIDs",
            shizukuSharedUidsQueried.contains(10204)
        )

        // Now verify Public API fallback catalog & CaptureTargetRepository on-demand search
        PublicApiTargetResolver.environment = FakePublicTargetEnvironment(
            profiles = listOf(
                PublicTargetEnvironment.AccessibleProfile(
                    userSerialNumber = 0L,
                    userId = 0,
                    label = "主空间 (Serial 0)",
                    profileType = ProfileType.PRIMARY,
                    isRunning = true,
                    isCurrentProcessUser = true
                )
            ),
            apps = mapOf(
                audioApp to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = audioApp.packageName,
                    userSerialNumber = 0L,
                    appLabel = "Metrolist",
                    uid = 10201,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf(audioApp.packageName),
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                ),
                videoApp to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = videoApp.packageName,
                    userSerialNumber = 0L,
                    appLabel = "VLC",
                    uid = 10202,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf(videoApp.packageName),
                    appCategory = ApplicationInfo.CATEGORY_VIDEO
                ),
                importedNotesApp to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = importedNotesApp.packageName,
                    userSerialNumber = 0L,
                    appLabel = "Imported Notes",
                    uid = 10203,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf(importedNotesApp.packageName),
                    appCategory = ApplicationInfo.CATEGORY_UNDEFINED
                ),
                unselectedProductivityApp to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = unselectedProductivityApp.packageName,
                    userSerialNumber = 0L,
                    appLabel = "Office Suite",
                    uid = 10204,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf(unselectedProductivityApp.packageName),
                    appCategory = ApplicationInfo.CATEGORY_PRODUCTIVITY
                ),
                unselectedGameApp to PublicTargetEnvironment.PublicAppMetadata(
                    packageName = unselectedGameApp.packageName,
                    userSerialNumber = 0L,
                    appLabel = "Action Game",
                    uid = 10205,
                    isSystemApp = false,
                    isLaunchable = true,
                    verifiedSharedUidPackages = listOf(unselectedGameApp.packageName),
                    appCategory = ApplicationInfo.CATEGORY_GAME
                )
            )
        )

        CaptureTargetRepository.setTargetsForTesting(
            selected = setOf(audioApp),
            imported = setOf(audioApp, importedNotesApp),
            presets = emptyList()
        )
        CaptureTargetRepository.refreshCatalogNowForTesting(app)

        // 1. Default catalog only includes AUDIO, VIDEO, and imported/selected/preset pool apps
        val defaultSearchable = CaptureTargetRepository.searchableApps.value.map { it.target }.toSet()
        assertEquals(setOf(audioApp, videoApp, importedNotesApp), defaultSearchable)

        // 2. First non-empty search loads extended catalog once; subsequent typing uses memory cache without rescanning
        val extendedBeforeSearch = CaptureTargetRepository.extendedCatalogScanCountForTesting.get()
        CaptureTargetRepository.onSearchQueryChanged(app, "Office")
        CaptureTargetRepository.awaitIdleForTesting()
        assertEquals(extendedBeforeSearch + 1, CaptureTargetRepository.extendedCatalogScanCountForTesting.get())
        assertTrue(CaptureTargetRepository.searchableApps.value.any { it.target == unselectedProductivityApp })

        // Subsequent search query changes do NOT trigger another extended catalog scan
        CaptureTargetRepository.onSearchQueryChanged(app, "Action")
        CaptureTargetRepository.awaitIdleForTesting()
        assertEquals(extendedBeforeSearch + 1, CaptureTargetRepository.extendedCatalogScanCountForTesting.get())
        assertTrue(CaptureTargetRepository.searchableApps.value.any { it.target == unselectedGameApp })

        // 3. Clearing search immediately restores default filtered catalog from cache without scanning
        CaptureTargetRepository.onSearchQueryChanged(app, "")
        assertEquals(extendedBeforeSearch + 1, CaptureTargetRepository.extendedCatalogScanCountForTesting.get())
        assertEquals(
            setOf(audioApp, videoApp, importedNotesApp),
            CaptureTargetRepository.searchableApps.value.map { it.target }.toSet()
        )

        // 4. Manual package import still adds non-audio/video app and keeps it in default catalog
        val manualImport = CaptureTargetRepository.importTargetApp(
            context = app,
            packageName = unselectedProductivityApp.packageName,
            userSerialNumber = 0L,
            selectImmediately = true,
            isEditingLocked = false
        )
        assertTrue(manualImport.isSuccess)
        assertTrue(CaptureTargetRepository.importedTargets.value.contains(unselectedProductivityApp))
        assertTrue(CaptureTargetRepository.catalogApps.value.any { it.target == unselectedProductivityApp })
    }

    // =========================================================================
    // Section 2: 异步与竞态 (Background Scan, Fast Toggles, Stop-During-Start)
    // =========================================================================

    @Test
    fun backgroundCatalogScanDiscardsStaleResultsCoalescesColdStartAndAvoidsFullRescanOnSelectionMutations() = runBlocking {
        val appA = CaptureTarget("com.metrolist.music", 0L)
        val appB = CaptureTarget("org.videolan.vlc", 0L)
        val appC = CaptureTarget("com.other.podcast", 0L)

        val entryA = TargetAppEntry(
            target = appA,
            appLabel = "Metrolist",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10201,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )
        val entryB = TargetAppEntry(
            target = appB,
            appLabel = "VLC",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10202,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_VIDEO
        )
        val entryC = TargetAppEntry(
            target = appC,
            appLabel = "Podcast",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10203,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )

        fakeBackend.catalogAppsToReturn = listOf(entryA, entryB)
        fakeBackend.resolvedEntriesMap = mapOf(appA to entryA, appB to entryB, appC to entryC)

        CaptureTargetRepository.setTargetsForTesting(
            selected = setOf(appA),
            imported = setOf(appA, appB),
            presets = emptyList()
        )
        CaptureTargetRepository.refreshCatalogNowForTesting(app)
        // Register preset containing uncached appC after initial catalog scan
        CaptureTargetRepository.setTargetsForTesting(
            selected = setOf(appA),
            imported = setOf(appA, appB),
            presets = listOf(TargetPreset("三应用组合", setOf(appA, appB, appC)))
        )
        val baselineScans = CaptureTargetRepository.catalogScanCountForTesting.get()
        val baselineResolves = CaptureTargetRepository.targetEntryResolveCountForTesting.get()

        // 1. Rapid toggleTargetSelection, clearSelectedTargets, removeImportedTarget update state immediately with ZERO catalog scans
        assertTrue(CaptureTargetRepository.toggleTargetSelection(app, appB, isEditingLocked = false))
        assertEquals(setOf(appA, appB), CaptureTargetRepository.selectedTargets.value)
        assertTrue(CaptureTargetRepository.clearSelectedTargets(app, isEditingLocked = false))
        assertTrue(CaptureTargetRepository.selectedTargets.value.isEmpty())
        assertTrue(CaptureTargetRepository.removeImportedTarget(app, appB, isEditingLocked = false))
        assertFalse(CaptureTargetRepository.importedTargets.value.contains(appB))
        assertEquals(baselineScans, CaptureTargetRepository.catalogScanCountForTesting.get())

        // 2. applyPreset immediately updates selection and ONLY resolves targets missing from cache (appC)
        assertTrue(CaptureTargetRepository.applyPreset(app, "三应用组合", isEditingLocked = false))
        assertEquals(setOf(appA, appB, appC), CaptureTargetRepository.selectedTargets.value)
        CaptureTargetRepository.awaitIdleForTesting()
        assertEquals(baselineScans, CaptureTargetRepository.catalogScanCountForTesting.get())
        assertEquals(baselineResolves + 1, CaptureTargetRepository.targetEntryResolveCountForTesting.get())
        assertTrue(CaptureTargetRepository.catalogApps.value.any { it.target == appC })

        // 3. Stale scan version is discarded when a newer target mutation occurs during scan
        val scanStarted = CompletableDeferred<Unit>()
        val allowScanFinish = CompletableDeferred<Unit>()
        CaptureTargetRepository.catalogScanInterceptorForTesting = { _ ->
            if (!scanStarted.isCompleted) {
                scanStarted.complete(Unit)
                allowScanFinish.await()
            }
            MultiProfileCatalogSnapshot(
                profiles = listOf(UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true)),
                catalogApps = listOf(entryA), // Stale snapshot missing newly imported appD
                searchableApps = listOf(entryA)
            )
        }

        CaptureTargetRepository.refreshCatalog(app)
        scanStarted.await()
        // Mutate target pool while scan #1 is still in-flight, bumping latestRequestVersion
        val appD = CaptureTarget("com.new.player", 0L)
        val entryD = entryA.copy(target = appD, appLabel = "New Player", resolvedUid = 10204)
        fakeBackend.resolvedEntriesMap = fakeBackend.resolvedEntriesMap + (appD to entryD)
        CaptureTargetRepository.importTargetApp(app, appD.packageName, 0L, selectImmediately = true, isEditingLocked = false)

        CaptureTargetRepository.catalogScanInterceptorForTesting = { req ->
            MultiProfileCatalogSnapshot(
                profiles = listOf(UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true)),
                catalogApps = req.targetPoolSnapshot.mapNotNull { fakeBackend.resolvedEntriesMap[it] },
                searchableApps = listOf(entryA, entryB, entryC, entryD)
            )
        }
        allowScanFinish.complete(Unit)
        CaptureTargetRepository.awaitIdleForTesting()
        // Final catalog must contain appD from the newer state, not overwritten by the stale scan
        assertTrue(CaptureTargetRepository.catalogApps.value.any { it.target == appD })
        CaptureTargetRepository.catalogScanInterceptorForTesting = null
    }

    @Test
    fun stopDuringStartupInvalidatesGenerationAndCompensatesSoStoppedSessionNeverMutesPhone() = runBlocking {
        fakeAudioAdapter.currentVolume = 10
        fakeAudioAdapter.currentMuted = false
        val activeGen = AtomicLong(1L)
        val myGen = activeGen.get()

        val muteEntered = CompletableDeferred<Unit>()
        val allowMuteProceed = CompletableDeferred<Unit>()

        // Simulate startup coroutine entering serialized mute section just as stop invalidates generation
        val startupJob = async(Dispatchers.IO) {
            AudioCastSessionCoordinator.withMuteLock {
                muteEntered.complete(Unit)
                allowMuteProceed.await()
                PhoneGlobalMediaMuteController.engageGlobalMediaMute(app)
                // Stale generation check inside serialized startup block must compensate and restore!
                if (activeGen.get() != myGen) {
                    PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app)
                }
            }
        }

        muteEntered.await()
        // User stops session while startup is in progress -> invalidates generation first
        activeGen.incrementAndGet()
        allowMuteProceed.complete(Unit)
        startupJob.await()

        // Also run serialized stop restoration
        val stopRestoreErr = AudioCastSessionCoordinator.restorePhoneAudioSerialized(app)
        assertNull(stopRestoreErr)
        // Phone volume must be restored to 10 and global mute must NOT remain active
        assertEquals(10, fakeAudioAdapter.currentVolume)
        assertFalse(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)
    }

    // =========================================================================
    // Section 3: 整体媒体静音的恢复事务 (Global Media Mute Transaction & Recovery)
    // =========================================================================

    @Test
    fun globalMediaMuteHandlesAlreadyMutedPhonePersistAndClearFailuresAndBlocksNewSessionUntilResolved() = runBlocking {
        val viewModel = ScreenCastViewModel(app)
        val musicTarget = CaptureTarget("com.android.music", 0L)
        CaptureTargetRepository.setTargetsForTesting(setOf(musicTarget))

        // 1. Phone already muted before start (currentVolume == 0 or wasMuted == true):
        //    Mode is ready, does NOT call setMusicVolume(0) or write recovery record, and stop keeps original state
        fakeAudioAdapter.currentVolume = 0
        fakeAudioAdapter.currentMuted = true
        fakeAudioAdapter.setVolumeCalls.set(0)

        val alreadyMutedRes = PhoneGlobalMediaMuteController.engageGlobalMediaMute(app)
        assertTrue(alreadyMutedRes.isSuccess)
        assertTrue(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)
        assertEquals(0, fakeAudioAdapter.setVolumeCalls.get())
        assertNull(PhoneGlobalMediaMuteController.pendingRecovery.value)

        val restoreAlreadyMuted = PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app)
        assertTrue(restoreAlreadyMuted.isSuccess)
        assertEquals(0, fakeAudioAdapter.currentVolume)
        assertTrue(fakeAudioAdapter.currentMuted)
        assertEquals(0, fakeAudioAdapter.setVolumeCalls.get())
        assertFalse(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)

        // 2. Persistence commit failure before muting refuses to modify phone audio
        fakeAudioAdapter.currentVolume = 9
        fakeAudioAdapter.currentMuted = false
        PhoneGlobalMediaMuteController.persistCommitOverrideForTesting = { false }
        val persistFailRes = PhoneGlobalMediaMuteController.engageGlobalMediaMute(app)
        assertTrue(persistFailRes.isFailure)
        assertEquals(9, fakeAudioAdapter.currentVolume)
        assertFalse(PhoneGlobalMediaMuteController.isGlobalMuteActive.value)
        PhoneGlobalMediaMuteController.persistCommitOverrideForTesting = null

        // 3. Restoration failure (or clear commit failure) returns Result.failure, retains pendingRecovery,
        //    and blocks starting a new cast session until resolved
        fakeAudioAdapter.currentVolume = 11
        fakeAudioAdapter.currentMuted = false
        assertTrue(PhoneGlobalMediaMuteController.engageGlobalMediaMute(app).isSuccess)
        assertEquals(0, fakeAudioAdapter.currentVolume)

        fakeAudioAdapter.throwOnSetVolume = IllegalStateException("AudioService binder dead during restore")
        val failedRestore = PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app)
        assertTrue(failedRestore.isFailure)
        assertNotNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        assertEquals(11, PhoneGlobalMediaMuteController.pendingRecovery.value?.savedVolume)

        // Attempting to start a new session while pendingRecovery is non-null must be rejected
        assertFalse(viewModel.initiateStartCastFlow(requireConnectedCastReceiver = false))
        assertTrue(viewModel.telemetry.value.error.orEmpty().contains("未处理的媒体音量恢复记录"))

        // User clicking "恢复原音量" while hardware still fails returns Result.failure and keeps dialog open
        val failedUserConfirm = PhoneGlobalMediaMuteController.confirmRestoreLeftoverVolume(app)
        assertTrue(failedUserConfirm.isFailure)
        assertNotNull(PhoneGlobalMediaMuteController.pendingRecovery.value)

        // Once hardware recovers, user confirm succeeds, clears pendingRecovery, and restores volume 11
        fakeAudioAdapter.throwOnSetVolume = null
        val okUserConfirm = PhoneGlobalMediaMuteController.confirmRestoreLeftoverVolume(app)
        assertTrue(okUserConfirm.isSuccess)
        assertNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        assertEquals(11, fakeAudioAdapter.currentVolume)

        // 4. Clearing persisted record failure during automatic restore also reports failure and retains pendingRecovery
        assertTrue(PhoneGlobalMediaMuteController.engageGlobalMediaMute(app).isSuccess)
        PhoneGlobalMediaMuteController.clearCommitOverrideForTesting = { false }
        val clearFailRestore = PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(app)
        assertTrue(clearFailRestore.isFailure)
        assertNotNull(PhoneGlobalMediaMuteController.pendingRecovery.value)
        PhoneGlobalMediaMuteController.clearCommitOverrideForTesting = null
        assertTrue(PhoneGlobalMediaMuteController.dismissLeftoverRecoveryKeepCurrent(app).isSuccess)
    }

    // =========================================================================
    // Section 4: 定向静音包络的失败回收 (Shizuku VolumeShaper Failure & 3s Retry)
    // =========================================================================

    @Test
    fun shizukuVolumeShaperRetainsPausedPlayerOnRemoveFailureRetriesWithin3SecondsAndReportsPendingRestore() {
        val service = ShizukuAudioMuteUserService()
        val nowMs = AtomicLong(1_000L)
        service.elapsedRealtimeProvider = { nowMs.get() }

        val removeAttempts = AtomicInteger(0)
        val shouldFailRemove = AtomicBoolean(true)
        val capturedOps = CopyOnWriteArrayList<VolumeShaper.Operation>()

        val fakePlayerProxy = object {
            @Suppress("unused")
            fun applyVolumeShaper(
                config: VolumeShaper.Configuration,
                operation: VolumeShaper.Operation
            ) {
                capturedOps.add(operation)
                if (operation != VolumeShaper.Operation.PLAY) {
                    removeAttempts.incrementAndGet()
                    if (shouldFailRemove.get()) {
                        throw IllegalStateException("Simulated IPlayer TERMINATE failure")
                    }
                }
            }
        }

        val currentPlayerState = AtomicInteger(ShizukuAudioMuteUserService.PLAYER_STATE_STARTED)
        service.playbackSessionsProvider = {
            listOf(
                ShizukuAudioMuteUserService.PlaybackSessionDescriptor(
                    piid = 88,
                    uid = 10188,
                    state = currentPlayerState.get(),
                    playerType = ShizukuAudioMuteUserService.PLAYER_TYPE_JAM_AUDIOTRACK,
                    usage = AudioAttributes.USAGE_MEDIA,
                    mutedBy = ShizukuAudioMuteUserService.MUTED_BY_VOLUME_SHAPER,
                    playerProxy = fakePlayerProxy
                )
            )
        }

        // 1. Acquire lease and shape active player piid=88
        val acquireJson = JSONObject(service.acquireOrRenewMuteLease(intArrayOf(10188), 3_500L))
        assertTrue(acquireJson.getBoolean("success"))
        assertEquals(1, acquireJson.getInt("confirmedMutedPlayerCount"))
        assertEquals(0, acquireJson.getInt("pendingRestoreCount"))

        // 2. Player transitions to PAUSED (NOT destroyed!) and releaseMuteLease fails to remove shaper
        currentPlayerState.set(ShizukuAudioMuteUserService.PLAYER_STATE_PAUSED)
        val releaseFailJson = JSONObject(service.releaseMuteLease())
        assertFalse("releaseMuteLease must return success=false when shaper removal fails", releaseFailJson.getBoolean("success"))
        assertEquals(1, releaseFailJson.getInt("pendingRestoreCount"))
        assertTrue(releaseFailJson.getString("error").contains("未能终止"))
        assertEquals(1, removeAttempts.get())

        // 3. Watchdog retries within the 3-second retry window (at t=1500ms and t=2500ms)
        nowMs.set(1_500L)
        service.triggerWatchdogTickForTesting()
        assertEquals(2, removeAttempts.get())

        nowMs.set(2_500L)
        service.triggerWatchdogTickForTesting()
        assertEquals(3, removeAttempts.get())

        // 4. After 3 seconds (t=4200ms > 1000ms + 3000ms), automatic watchdog retry stops, but record is retained
        nowMs.set(4_200L)
        service.triggerWatchdogTickForTesting()
        assertEquals(3, removeAttempts.get())
        assertTrue(service.isRetryExhaustedForTesting(88))
        val snapshotAfterWindow = JSONObject(service.queryPlaybackSnapshot(intArrayOf(10188)))
        assertEquals(1, snapshotAfterWindow.getInt("pendingRestoreCount"))

        // 5. Explicit releaseMuteLease resets the retry window and succeeds once player allows TERMINATE
        shouldFailRemove.set(false)
        nowMs.set(5_000L)
        val releaseOkJson = JSONObject(service.releaseMuteLease())
        assertTrue(releaseOkJson.getBoolean("success"))
        assertEquals(0, releaseOkJson.getInt("pendingRestoreCount"))
        assertEquals(4, removeAttempts.get())

        // Verify REVERSE was never used across any attempt
        assertTrue(capturedOps.none { it == VolumeShaper.Operation.REVERSE })
    }

    @Test
    fun binderMuteFailureCallbackDoesNotSynchronouslyReenterRemoteReleaseOnCallbackStack() {
        val inCallbackStack = AtomicBoolean(false)
        val releaseCalledSynchronouslyInsideCallback = AtomicBoolean(false)

        fakeBackend.onReleaseHook = {
            if (inCallbackStack.get()) {
                releaseCalledSynchronouslyInsideCallback.set(true)
            }
        }

        val fatalErrors = CopyOnWriteArrayList<String>()
        runBlocking {
            ShizukuSessionMuteController.startSessionMuteLease(
                targetUids = setOf(10188),
                onFatalMuteError = { fatalErrors.add(it) }
            )
        }

        inCallbackStack.set(true)
        try {
            fakeBackend.emitRemoteMuteFailure("Watchdog detected unshaperable player")
        } finally {
            inCallbackStack.set(false)
        }

        assertFalse(
            "Binder callback must not synchronously call releaseLeaseImmediately on the callback stack",
            releaseCalledSynchronouslyInsideCallback.get()
        )
        assertEquals(1, fatalErrors.size)
        assertFalse(ShizukuSessionMuteController.muteTelemetry.value.leaseActive)
    }

    private class FakeSystemMediaAudioAdapter(
        var currentVolume: Int,
        val maxVolume: Int,
        var currentMuted: Boolean
    ) : SystemMediaAudioAdapter {
        var refuseZeroVolume: Boolean = false
        var throwOnSetVolume: Exception? = null
        val setVolumeCalls = AtomicInteger(0)

        override fun getMusicVolume(context: Context): Int = currentVolume
        override fun getMusicMaxVolume(context: Context): Int = maxVolume
        override fun isMusicMuted(context: Context): Boolean = currentMuted

        override fun setMusicVolume(context: Context, volume: Int) {
            setVolumeCalls.incrementAndGet()
            throwOnSetVolume?.let { throw it }
            if (refuseZeroVolume && volume == 0) return
            currentVolume = volume.coerceIn(0, maxVolume)
        }

        override fun setMusicMuted(context: Context, muted: Boolean) {
            throwOnSetVolume?.let { throw it }
            currentMuted = muted
        }
    }

    private class FakeCastVolumeEndpoint(
        initialVolume: Double,
        initialMute: Boolean
    ) : CastSessionVolumeEndpoint {
        override var isConnected: Boolean = true
        private var currentVol: Double = initialVolume
        private var currentMute: Boolean = initialMute
        private var listener: (() -> Unit)? = null
        var throwOnNextMutation: Exception? = null

        override fun getVolume(): Double = currentVol

        override fun setVolume(volume: Double) {
            throwOnNextMutation?.let {
                throwOnNextMutation = null
                throw it
            }
            currentVol = volume.coerceIn(0.0, 1.0)
        }

        override fun isMute(): Boolean = currentMute

        override fun setMute(muted: Boolean) {
            throwOnNextMutation?.let {
                throwOnNextMutation = null
                throw it
            }
            currentMute = muted
        }

        override fun registerVolumeListener(onVolumeChanged: () -> Unit) {
            listener = onVolumeChanged
        }

        override fun unregisterVolumeListener() {
            listener = null
        }

        fun simulateExternalChange(newVolume: Double, newMute: Boolean) {
            currentVol = newVolume
            currentMute = newMute
            listener?.invoke()
        }
    }

    private class FakePublicTargetEnvironment(
        private val profiles: List<PublicTargetEnvironment.AccessibleProfile>,
        private val apps: Map<CaptureTarget, PublicTargetEnvironment.PublicAppMetadata>
    ) : PublicTargetEnvironment {
        override fun queryAccessibleProfiles(context: Context): List<PublicTargetEnvironment.AccessibleProfile> =
            profiles

        override fun queryLaunchableAppsInProfile(
            context: Context,
            userSerialNumber: Long
        ): List<PublicTargetEnvironment.PublicAppMetadata> =
            apps.values.filter { it.userSerialNumber == userSerialNumber && it.isLaunchable }

        override fun resolveTargetApp(
            context: Context,
            target: CaptureTarget
        ): PublicTargetEnvironment.PublicAppMetadata? = apps[target]
    }

    private class FakeShizukuMuteBackend : ShizukuMuteBackend {
        private val _connectionState = MutableStateFlow(ShizukuConnectionState.READY)
        override val connectionState: StateFlow<ShizukuConnectionState> = _connectionState

        private val failureListeners = CopyOnWriteArrayList<(String) -> Unit>()
        private val stateListeners = CopyOnWriteArrayList<(LeaseAcquisitionOutcome) -> Unit>()

        val releaseCalls = AtomicInteger(0)
        var onReleaseHook: (() -> Unit)? = null
        @Volatile var activeStartedUids: Set<Int> = emptySet()
        @Volatile var catalogAppsToReturn: List<TargetAppEntry> = emptyList()
        @Volatile var resolvedEntriesMap: Map<CaptureTarget, TargetAppEntry> = emptyMap()
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
        @Volatile var releaseOutcomeToReturn: LeaseAcquisitionOutcome = LeaseAcquisitionOutcome(
            success = true,
            activeTargetPlayerCount = 0,
            confirmedMutedPlayerCount = 0,
            pendingRestoreCount = 0
        )

        fun setConnectionState(state: ShizukuConnectionState) {
            _connectionState.value = state
        }

        override fun refreshShizukuStatus() {}
        override fun requestPermission(requestCode: Int) {}

        override fun queryCatalog(
            context: Context,
            importedTargets: Set<CaptureTarget>,
            includeOtherApps: Boolean
        ): MultiProfileCatalogSnapshot {
            val profiles = listOf(
                UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true),
                UserProfileInfo(10L, 10, "工作资料 (User 10)", ProfileType.WORK, true),
                UserProfileInfo(999L, 999, "克隆资料 (User 999)", ProfileType.CLONE, true)
            )
            val catalog = importedTargets.mapNotNull { resolvedEntriesMap[it] }.ifEmpty { catalogAppsToReturn }
            return MultiProfileCatalogSnapshot(
                profiles = profiles,
                catalogApps = catalog,
                searchableApps = catalogAppsToReturn
            )
        }

        override fun resolveTargetEntries(
            context: Context,
            targets: Set<CaptureTarget>
        ): List<TargetAppEntry> {
            return targets.map { target ->
                resolvedEntriesMap[target] ?: TargetAppEntry(
                    target = target,
                    appLabel = target.packageName,
                    profileLabel = "主空间 (User ${target.userSerialNumber})",
                    profileType = ProfileType.PRIMARY,
                    resolvedUid = 10188,
                    isInstalled = true,
                    isProfileRunning = true,
                    appCategory = ApplicationInfo.CATEGORY_AUDIO
                )
            }
        }

        override fun resolveAndValidateTargets(
            context: Context,
            selectedTargets: Set<CaptureTarget>
        ): TargetResolutionResult = nextResolutionResult

        override fun acquireOrRenewLease(
            targetUids: Set<Int>,
            leaseDurationMs: Long
        ): LeaseAcquisitionOutcome = leaseOutcomeToReturn

        override fun releaseLeaseImmediately(): LeaseAcquisitionOutcome {
            onReleaseHook?.invoke()
            releaseCalls.incrementAndGet()
            return releaseOutcomeToReturn
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
