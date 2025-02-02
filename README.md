OpenConnect for Android
=======================

Multi-protocol version, based on [openconnect](http://www.infradead.org/openconnect).

XDA thread: [comment](https://forum.xda-developers.com/showthread.php?p=77318683#post77318683)

This is a VPN client for Android, based on the Linux build of
[OpenConnect](http://www.infradead.org/openconnect/).

Much of the Java code was derived from [OpenVPN for Android](https://play.google.com/store/apps/details?id=de.blinkt.openvpn&hl=en) by Arne Schwabe.

OpenConnect for Android is released under the GPLv2 license.  For more
information see the [COPYING](COPYING) and [doc/LICENSE.txt](misc/doc/LICENSE.txt)
files.

Changelog: see [doc/CHANGES.txt](misc/doc/CHANGES.txt)

## Downloads and support

You can download the latest release from the GitLab [releases](https://gitlab.com/openconnect/ics-openconnect/-/releases) page directly. F-Droid listing is comming soon.

## Screenshots

|<img src="metadata/en-US/images/screenshot-0.png" alt="screenshot-0" height="400" width="180">|<img src="metadata/en-US/images/screenshot-1.png" alt="screenshot-1" height="400" width="180">|<img src="metadata/en-US/images/screenshot-2.png" alt="screenshot-2" height="400" width="180">|<img src="metadata/en-US/images/screenshot-3.png" alt="screenshot-3" height="400" width="180">|<img src="metadata/en-US/images/screenshot-4.png" alt="screenshot-multiproto" height="400" width="180">|
|---|---|---|---|---|

## Building from source

### Prerequisites

On the host side you'll need to install:

* Android SDK in your $PATH (both platform-tools/ and tools/ directories)
* $ANDROID\_HOME pointed at the Android SDK directory
* JDK 17 and a recent version of Apache ant in your $PATH
* Use the Android SDK Manager to install `"platform-tools" "build-tools;34.0.0" "platforms;android-35"`
* NDK r27c, nominally unzipped under /opt/android-sdk-linux\_x86/
* Host-side gcc, make, etc. (Red Hat "Development Tools" group or Debian build-essential)
* git, autoconf, automake, and libtool

If you encounter any issues, take a look at [`misc/Dockerfile`](https://gitlab.com/openconnect/ics-openconnect/-/blob/master/misc/Dockerfile).

### Compiling the external dependencies

Building OpenConnect from source requires compiling several .jar files and
native binaries from external packages.  These commands will build the binary
components and copy them into the appropriate library and asset directories:

```sh
git clone --recursive https://gitlab.com/openconnect/ics-openconnect
cd ics-openconnect
make -C external
```

This procedure only runs on a Linux PC.  If you are unable to build from
source, you can try fetching the cached artifacts from a recent [CI build](https://gitlab.com/openconnect/ics-openconnect/-/pipelines).


### Compiling the app

After the binary components are built, this compiles the Java sources into
an APK file:

```sh
cd ics-openconnect
./gradlew assembleDebug
```

To install the APK on a device:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Logs of successful (and not-so-successful) builds can be found on this project's
[CI page](https://gitlab.com/openconnect/ics-openconnect/-/pipelines).
