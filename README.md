# thingino-app

Android app for [thingino](https://github.com/themactep/thingino-firmware) cameras.
Two things: set a new camera up on your Wi-Fi, and flash firmware over USB.

## Screens

**Hub.** Landing screen. Two tasks plus whatever cameras are already on the
network, discovered over mDNS. Tapping a camera opens its web UI.

**Set Up a Camera.** Joins an unprovisioned camera's captive-portal access
point and configures it. Requires Android 10.

**Flash Firmware.** The former thingino-dfu app, unchanged. Local USB OTG or a
remote `dfu-remote` daemon over TCP.

Plugging an Ingenic device into OTG opens the flashing screen directly and skips
the hub, so the fast path stays fast.

## Building

```
./gradlew assembleDebug
```

No NDK, no CMake, no libusb. Everything native arrives prebuilt from
[thingino-dfu-rs](https://github.com/thingino/thingino-dfu-rs): `libtdfu_jni.so`
per ABI (the JNI bridge and the Rust core, with only Android's own system
libraries external) plus the `firmware/` bootstrap blobs, in one tarball attached
to its releases. The version is pinned by `libtdfuVersion` in `gradle.properties`.

To build against an unreleased libtdfu, produce the tarball locally and point at
it:

```
# in a thingino-dfu-rs checkout, with the NDK installed
ANDROID_NDK_HOME=/path/to/ndk cargo xtask package --target android

# here
./gradlew assembleDebug \
  -PlibtdfuFile=../thingino-dfu-rs/dist/libtdfu-android-2.0.0-rc.3.tar.gz
```

Release builds need the signing keystore at `thingino-release.jks` plus
`KEYSTORE_PASSWORD` and `KEY_PASSWORD` in the environment. CI decodes it from a
secret.

## Two things that look wrong but are not

**`TdfuBridge` lives in `com.thingino.dfu`, not `com.thingino.app.dfu`.** The
prebuilt `.so` registers its JNI entry points statically, as
`Java_com_thingino_dfu_TdfuBridge_*`. A Java package is not an applicationId and
does not have to match it. Move that class and the app still compiles, installs
and launches, then throws `UnsatisfiedLinkError` on the first flash.

**`minSdk` is 26 while provisioning needs 29.** Flashing has always worked on
Android 8 and there is no reason to drop those users. The provisioning entry is
gated at runtime in `HubActivity`, and the classes behind it carry
`@RequiresApi(Q)` so lint enforces the boundary.

## Layout

```
com.thingino.app              HubActivity
com.thingino.app.dfu          DfuActivity, Releases, RemoteClient, UsbHelper
com.thingino.app.provision    ProvisionActivity, PortalClient, PortalWifi,
                              CameraDiscovery
com.thingino.dfu              TdfuBridge  (see above)
```
