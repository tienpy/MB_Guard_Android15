# Tien Call Recorder Pro

Tien Call Recorder Pro is a custom build based on CallVault v1.5.7 by madkongo, which itself is a fork of ShizuCallRecorder by kitsumed.

Upstream: https://github.com/madkongo/CallVault
Pinned upstream tag: v1.5.7
License: GNU GPL v3 or later, including CallVault's additional Section 7 terms.

This fork is not affiliated with, endorsed by, or supported by CallVault, ShizuCallRecorder, Shizuku, scrcpy, Google, Android, or HONOR.

Local modifications applied by `.github/workflows/build-callrecorder-pro.yml`:
- visible app name changed to `Tien Call Recorder Pro`
- automatic incoming carrier-call recording defaults to ON
- automatic outgoing carrier-call recording defaults to ON
- experimental VoIP recording defaults to ON
- VoIP auto-start defaults to ON
- upstream in-app update checking defaults to OFF because this build uses a different signing key

The workflow clones the exact upstream tag, applies the above textual patches, builds a signed APK, and uploads both the APK and the complete patched source tree. The upstream LICENSE and NOTICE files are preserved unchanged.

Call recording laws vary by jurisdiction. Users are responsible for obtaining any legally required consent.
