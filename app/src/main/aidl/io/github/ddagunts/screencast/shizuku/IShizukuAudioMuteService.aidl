package io.github.ddagunts.screencast.shizuku;

import io.github.ddagunts.screencast.shizuku.IShizukuMuteCallback;

interface IShizukuAudioMuteService {
    String queryMultiProfileCatalog(in List<String> importedTargets, boolean includeOtherApps);
    String resolveAndValidateTargets(in List<String> selectedTargets);
    String acquireOrRenewMuteLease(in int[] targetUids, long leaseDurationMs);
    String releaseMuteLease();
    String queryPlaybackSnapshot(in int[] targetUids);
    void registerCallback(IShizukuMuteCallback callback);
    void unregisterCallback(IShizukuMuteCallback callback);
    void destroy();
}
