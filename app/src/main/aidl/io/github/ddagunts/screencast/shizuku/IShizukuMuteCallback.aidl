package io.github.ddagunts.screencast.shizuku;

interface IShizukuMuteCallback {
    void onSessionStateUpdated(String jsonSnapshot);
    void onMuteFailure(String reason);
}
