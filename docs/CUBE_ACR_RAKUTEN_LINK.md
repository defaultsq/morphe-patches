# Cube ACR: Rakuten Link VoIP support

This patch reassigns Cube ACR's retired BBM VoIP handler to Rakuten Link. It is
part of the complete `rushiranpise/morphe-patches` bundle and does not modify
Cube ACR's licensing, subscription, or billing logic.

## Supported installations

- Cube ACR `com.catalinagroup.callrecorder` 2.4.281 (version code 281)
- Cube ACR App Connector `com.catalinagroup.callrecorder.helper` 1.0.30
  (version code 30)
- Rakuten Link `jp.co.rakuten.mobile.rcs` 4.0.1 (version code 618) was used to
  derive and physically test the call-screen identifiers.

Patch both Cube ACR and App Connector. App Connector owns the accessibility
service that observes the foreground call screen, while Cube ACR also contains
its own copy of the VoIP handler registry.

## What the patch changes

The patch changes only the existing `BBMRecording` handler and its manifest
package-visibility query:

- package: `com.bbm` -> `jp.co.rakuten.mobile.rcs`
- call activity: all legacy BBM activities ->
  `jp.co.rakuten.mobile.rcs/.call.activecall.view.CallActivity`
- caller view IDs: all legacy BBM IDs ->
  `jp.co.rakuten.mobile.rcs:id/tv_call_name`

The Rakuten activity and caller view ID came from the exact installed APK and
were confirmed with a live accessibility UI dump. Rakuten Link 4.0.1 uses
native Android Views/data binding for this screen, so the view ID is visible to
Cube's accessibility service.

The patch deliberately leaves Cube's shared VoIP recording profile unchanged.
On the tested Xiaomi/Android 16 phone, the user's existing source 6 (`Voice
recognition`) recorded both sides. It also captured more ambient noise than a
direct call-line source. Experimental source 1 (`Microphone`) lost connected
call audio, and source 7 (`Voice communication`) was silenced by Android when
Rakuten opened its own source-7 stream. Those experiments are not in the patch.

An additional experiment allowed this handler to use App Connector's privileged
ADB recorder. The shell process successfully opened source 4 (`Voice call`) on
the device's `TELEPHONY_RX` input, but Android returned muted frames for the
entire Rakuten call. That source is tied to cellular telephony and does not carry
Rakuten's VoIP stream, so the ADB opt-in is also not in the final patch.

Source 6 is an acoustic compromise: the local side comes from the microphone,
while the remote side is picked up from the phone's output. The remote side can
therefore be quiet through the earpiece, is most audible on speakerphone, and is
expected to be absent with wired or Bluetooth earphones. Headset-compatible
two-sided recording cannot be provided by Cube's cross-application recorder.

## Rakuten's native internal-audio path

Follow-up inspection of the exact Rakuten Link 4.0.1 APK found a more promising
route for a future project. Its bundled Mavenir WebRTC implementation constructs
an `AudioMixer` with callbacks for both captured input samples and decoded output
samples. The mixer combines the two PCM streams and can encode the result to an
`.m4a` file through `startCallRecording` and `stopCallRecording`.

Rakuten already calls this machinery from its `AI通話要約` (AI Call Summary)
feature. The call-screen control is gated by the remote-config key
`link_enable_ai_call_summary`; starting it also plays Rakuten's recording
announcement and initializes a recording entry. This is genuine in-process
capture and should retain both sides when a headset is used, unlike Cube's
source-6 acoustic fallback.

No Rakuten APK modification is included in this branch. A future prototype
could first test the official AI Call Summary control when the account has it,
then consider a separate Morphe patch that exposes the existing control when
the remote flag is disabled. Such a patch would target Rakuten Link itself,
would change its APK signature on a non-root device, could require signing in
again, and might still be rejected by server-side feature eligibility. Recording
announcements and applicable consent requirements must remain intact.

## Build the complete Morphe bundle

The repository requires Java 21, Android command-line tools, and GitHub package
credentials:

```bash
export GITHUB_ACTOR="$(gh api user --jq .login)"
export GITHUB_TOKEN="$(gh auth token)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew buildAndroid --no-daemon
```

The Android-ready bundle is written to:

```text
patches/build/libs/patches-<version>.mpp
```

Use `buildAndroid`, not the plain JVM `build` output. The latter does not place
the Android DEX payload Morphe Manager expects in the bundle.

The generated `.mpp` remains the full upstream patch collection. `Add Rakuten
Link VoIP support` is an additional, non-default patch inside it. During
development, Morphe Desktop's `--exclusive` option is useful for producing a
controlled test APK containing only this patch; it does not remove other
patches from the `.mpp`.

## Apply and install

In Morphe Manager, import the built `.mpp` and patch the two packages separately:

- For Cube ACR, select `Add Rakuten Link VoIP support`. The upstream `Unlock
  Premium` patch is a separate compatible selection and can be selected in the
  same patching run; this Rakuten patch neither depends on nor conflicts with it.
- For App Connector, select `Add Rakuten Link VoIP support` only. The upstream
  Cube unlock patch does not target the helper package.

Other upstream patches remain independently selectable according to their own
documentation; they are outside this patch.

For repeatable desktop testing, the equivalent shape is:

```bash
java -jar morphe-desktop.jar patch --exclusive \
  --enable "Add Rakuten Link VoIP support" \
  --patches patches/build/libs/patches-<version>.mpp \
  --out cube-helper-rakuten.apk \
  cube-helper-original.apk
```

Patched APKs have a different signer from the Play Store APK. A non-root first
install therefore requires uninstalling the stock package; back up recordings
and settings first. Later updates work with `adb install -r` only when they use
the same Morphe signing key. Re-enable App Connector in Android Accessibility
after replacing it. On a rooted device, Morphe's mount workflow can preserve
the stock installation and data.

## Verification

1. Confirm App Connector is enabled under Android Accessibility.
2. Start a Rakuten Link call and keep it connected long enough for both people
   to speak.
3. Confirm Cube displays its recording indicator and creates a Rakuten/BBM-type
   recording when the call ends.
4. Play the result and check both directions separately.

If playback unexpectedly uses the earpiece, use Cube's playback output selector
to choose `Loudspeaker`, or enable `Disable playback auto-switch` in Misc so the
proximity sensor does not reroute playback.

## Updating for a future Cube version

Compatibility is intentionally pinned. For every new Cube ACR or App Connector
release:

1. Pull the exact installed base and split APKs with `adb shell pm path` and
   `adb pull`.
2. Decompile both Cube and App Connector.
3. Verify that `BBMRecording`, `getPackageName`, the four BBM activity strings,
   the four BBM caller-view strings, and the `com.bbm` manifest query still
   exist.
4. Verify Rakuten's installed `CallActivity` and `tv_call_name` identifiers with
   its APK and a live accessibility dump.
5. Add the new version/version code to the compatibility declarations, adjust
   the fingerprint or replacements if necessary, rebuild, and test both apps.

The patch is version-sensitive in two ways: compatibility declarations reject
unknown releases, and the bytecode fingerprint depends on Cube retaining the
`BBMRecording.getPackageName()` class/method shape. The literal replacements
also depend on the retired BBM constants remaining present. Treat a new Cube or
App Connector release as requiring inspection and a physical call test, even
if the patch still applies successfully.
