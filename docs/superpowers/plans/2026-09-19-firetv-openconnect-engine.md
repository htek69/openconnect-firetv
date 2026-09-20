# Fire TV OpenConnect クライアント 実装計画 1/2: 土台とフェイルオーバーエンジン

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitLab 版 ics-openconnect をフォークし、Fire TV 上で複数の接続先を優先順位付きで自動フェイルオーバーする VPN エンジンを完成させる。

**Architecture:** 既存の OpenConnect コア（Java + JNI）には一切手を入れず、その上に Kotlin のフェイルオーバー層を載せる。状態機械 `FailoverController` を Android 非依存の純粋 Kotlin として書き、`Clock` / `HealthProbe` / `VpnController` / `NetworkGate` の4つの interface でプラットフォームを隔離する。これにより状態機械の全遷移が JVM 単体テストで検証でき、エミュレータも Robolectric も不要になる。

**Tech Stack:** Kotlin 2.1.0 / Compose for TV は計画2 / kotlinx-coroutines 1.10.1 / kotlinx-serialization-json 1.8.0 / JUnit 4 / AGP 8.7.2 / Gradle 8.10.2 / NDK r27c / WSL2 Ubuntu

**Spec:** `docs/superpowers/specs/2026-09-19-firetv-openconnect-design.md`

**この計画の完了時点で動くもの:** Fire TV のホーム画面からアプリを起動し（UI は既存のスマホ向けのまま）、複数の接続先を登録して、優先順位グループで自動フェイルオーバーが動く状態。計画2 でこの UI を TV 向けに置き換える。

## Global Constraints

仕様書から転記した、全タスクに暗黙に適用される制約。

- **フォーク元は GitLab のみ**: `https://gitlab.com/openconnect/ics-openconnect`。GitHub の `cernekee/ics-openconnect` は 2019-06 停止の古いミラーで、使用禁止
- **既存の Java コードは原則無改変**: 変更を許すのは `AndroidManifest.xml` / `app/build.gradle` / ランチャー移設 / `QSTileService` 削除の4点のみ
- **AGP 8.7.2 と Gradle 8.10.2 は変更しない**: 最新の AGP は 9.4.1 だが、フォーク元は AGP 9 で削除された `lintOptions` DSL を使用しており、メジャーバージョン跨ぎは禁止
- **バージョン固定値**: Kotlin `2.1.0` / Compose BOM `2025.01.00` / `androidx.tv:tv-material:1.0.0` / `kotlinx-coroutines-android:1.10.1` / `kotlinx-coroutines-test:1.10.1` / `kotlinx-serialization-json:1.8.0`
- **minSdk 23 / targetSdk 34 / compileSdk 35** は変更しない
- **ビルドは常に WSL2 側で実行する**: Windows と WSL2 で Gradle 実行位置を混在させるとキャッシュが衝突する
- **`make -C external` は Linux のみ**: Windows 上では実行不可
- **`applicationIdSuffix = ".firetv"`**: F-Droid 版 OpenConnect と同一端末に共存させる。`namespace` は `net.openconnect_vpn.android` から変更しない
- **ライセンスは GPLv2**: 派生物であることを明示し、`COPYING` を削除しない
- **認証はユーザー名＋パスワードのみ**: 証明書 / TOTP / SAML は実装しない
- **フェイルバックは実装しない**: 優先度の高い候補が復活しても自動では戻らない
- **プローブ宛先はアプリ全体で1つ**: 既定値 `1.1.1.1:443`。グループごとには持たない

---

## File Structure

### 新規作成するファイル

**純粋ロジック層**（`app/src/main/java/net/openconnect_vpn/android/failover/`）— Android API を一切参照しない。JVM 単体テストで全て検証する。

| ファイル | 責務 |
|---|---|
| `FailoverModels.kt` | `FailoverGroup` / `FailoverConfig` / `Candidate` / `ProbeTarget` のデータ定義とシリアライズ |
| `FailoverState.kt` | 状態を表す sealed interface（`Idle` / `Connecting` / `Verifying` / `Healthy` / `FailingOver` / `Exhausted`） |
| `FailoverEvent.kt` | 状態機械への入力イベント（接続状態変化・プローブ結果・時刻経過・ユーザー操作） |
| `Ports.kt` | `Clock` / `HealthProbe` / `VpnController` / `NetworkGate` / `KeyValueStore` の interface 定義 |
| `FailoverController.kt` | 状態機械の本体。唯一の状態遷移ロジック |
| `Backoff.kt` | 指数バックオフの計算（純関数） |
| `GroupStore.kt` | `FailoverGroup` の JSON 永続化。`KeyValueStore` 経由でプラットフォームを隔離 |

**Android 実装層**（同ディレクトリ）— 上の interface を実装する薄いアダプタ。

| ファイル | 責務 |
|---|---|
| `SystemClock.kt` | `Clock` の実装 |
| `TcpHealthProbe.kt` | `HealthProbe` の実装。TCP connect による疎通確認 |
| `OpenConnectVpnController.kt` | `VpnController` の実装。既存 `OpenVpnService` へのアダプタ |
| `ConnectivityNetworkGate.kt` | `NetworkGate` の実装。`ConnectivityManager` 監視 |
| `PrefsKeyValueStore.kt` | `KeyValueStore` の実装。`SharedPreferences` バックエンド |
| `VpnStatusBridge.kt` | `ACTION_VPN_STATUS` ブロードキャストを `FailoverEvent` に変換 |
| `FailoverService.kt` | フォアグラウンドサービス。状態機械を駆動しプローブを周期実行 |
| `FailoverNotifications.kt` | 通知チャンネルと通知の生成 |

**テスト**（`app/src/test/java/net/openconnect_vpn/android/failover/`）

| ファイル | 責務 |
|---|---|
| `Fakes.kt` | `FakeClock` / `FakeHealthProbe` / `FakeVpnController` / `FakeNetworkGate` / `InMemoryKeyValueStore` |
| `BackoffTest.kt` | バックオフ計算 |
| `GroupStoreTest.kt` | 永続化と削除済み UUID の除去 |
| `FailoverControllerHappyPathTest.kt` | 正常系の遷移 |
| `FailoverControllerFailoverTest.kt` | 障害検知と切替、誤爆防止、猶予期間 |
| `FailoverControllerSafetyTest.kt` | 安全策 S1〜S3 |
| `FailoverControllerExhaustionTest.kt` | 全候補枯渇とバックオフ、自動切替 OFF |

### 変更する既存ファイル

| ファイル | 変更内容 |
|---|---|
| `build.gradle`（ルート） | Kotlin / Compose / serialization プラグインの classpath 追加 |
| `app/build.gradle` | プラグイン適用、`applicationIdSuffix`、Kotlin/Java 17、依存追加、単体テスト設定 |
| `app/src/main/AndroidManifest.xml` | `FailoverService` 登録、FGS 権限、`QSTileService` 削除 |
| `app/src/main/java/net/openconnect_vpn/android/QSTileService.java` | 削除 |

---

## Phase 1: 土台の確立

このフェーズはリスク RK1・RK2 の解消が目的で、既存コードが現行環境でビルド・動作することを確認する。TDD の対象ではない検証タスクを含む。

### Task 1: フォークとネイティブビルドの検証

仕様書のリスク RK1 を解消する。ここが通らなければ計画全体が成立しないため最初に行う。

**Files:**
- Create: WSL2 上の作業ディレクトリ `~/work/firetv-openconnect/`（フォークのクローン先）
- Create: `docs/BUILD.md`（ビルド手順の記録）

**Interfaces:**
- Consumes: なし
- Produces: WSL2 上にビルド可能なクローン、`app/build/outputs/apk/debug/app-debug.apk`、`docs/BUILD.md` に記録された再現手順

- [ ] **Step 1: WSL2 のビルド依存を導入する**

```bash
wsl -d Ubuntu
sudo apt-get update
sudo apt-get install -y build-essential autoconf automake libtool pkg-config \
    git curl unzip zip openjdk-17-jdk python3
java -version   # openjdk 17 が出ることを確認
```

- [ ] **Step 2: Android SDK と NDK r27c を WSL2 に導入する**

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdline.zip && mv cmdline-tools latest
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-35" "ndk;27.2.12479018"
```

`~/.bashrc` に `ANDROID_HOME` と `PATH` の行を追記して永続化する。

- [ ] **Step 3: フォーク元をクローンする**

```bash
mkdir -p ~/work && cd ~/work
git clone --recursive https://gitlab.com/openconnect/ics-openconnect firetv-openconnect
cd firetv-openconnect
git log -1 --format='%H %ad %s'
```

サブモジュールが取得されていることを確認する（`--recursive` が必須）。

```bash
git submodule status
```

- [ ] **Step 4: ネイティブ依存をビルドする**

```bash
cd ~/work/firetv-openconnect
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
time make -C external 2>&1 | tee /tmp/external-build.log
```

30〜60分かかる。完了後、成果物が生成されていることを確認する。

```bash
find app/src/main/jniLibs -name '*.so' | head -20
ls app/src/main/assets/
```

`.so` が各 ABI ディレクトリに出ていれば成功。

**失敗した場合の代替手順（この順で試す）:**

```bash
# 代替1: リポジトリ付属の Dockerfile を使う（Windows 側の Docker Desktop から）
docker build -t ocbuild -f misc/Dockerfile .
docker run --rm -v "$PWD":/src -w /src ocbuild make -C external

# 代替2: GitLab CI の成果物を取得する（最終手段）
# https://gitlab.com/openconnect/ics-openconnect/-/pipelines から
# 最新の success パイプラインの artifacts をダウンロードし、
# jniLibs/ と assets/ を展開する
```

- [ ] **Step 5: APK をビルドする**

```bash
cd ~/work/firetv-openconnect
./gradlew assembleDebug 2>&1 | tail -30
ls -la app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 6: Fire TV に導入して既存 UI で1接続を確認する**

Fire TV 側で「設定 → マイFire TV → 開発者オプション → ADBデバッグ」を有効にしておく。

```bash
adb connect <FireTVのIP>:5555
adb devices          # device と表示されることを確認
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p net.openconnect_vpn.android -c android.intent.category.LAUNCHER 1
```

既存のスマホ向け UI で接続先を1件追加し、実際に接続できることを確認する。**ここで接続できなければ以降のタスクは無意味なので、必ず通す。**

- [ ] **Step 7: ビルド手順を記録する**

`docs/BUILD.md` に、Step 1〜6 で実際に実行したコマンドと、`make -C external` の所要時間、詰まった点と解決策を記録する。次回の再現とリスク RK1 の証跡になる。

- [ ] **Step 8: コミット**

```bash
cd ~/work/firetv-openconnect
git checkout -b firetv
git add docs/BUILD.md
git commit -m "docs: WSL2 でのビルド手順を記録

フォーク元のネイティブビルドが現行の WSL2 Ubuntu + NDK r27c で通ることを確認。
Fire TV 実機で既存 UI から接続できることも確認済み。"
```

---

### Task 2: Kotlin と Compose for TV のビルド構成を追加する

リスク RK2 を解消する。既存の Java コードベースに Kotlin と Compose を混在させられることを、空の画面1枚で先に証明する。

**Files:**
- Modify: `build.gradle`（ルート）
- Modify: `app/build.gradle`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 1 のビルド可能なクローン
- Produces: Kotlin と Compose がコンパイル・実行できる `app/build.gradle`。以降の全タスクがこれに依存する

- [ ] **Step 1: ルートの build.gradle にプラグインの classpath を追加する**

`build.gradle` の `buildscript.dependencies` を次の内容にする。

```groovy
    dependencies {
        classpath 'com.android.tools.build:gradle:8.7.2'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0'
        classpath 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.0'
        classpath 'org.jetbrains.kotlin:kotlin-serialization:2.1.0'

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
```

- [ ] **Step 2: app/build.gradle にプラグインと依存を追加する**

先頭の `plugins` ブロックを次にする。

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'org.jetbrains.kotlin.plugin.compose'
    id 'org.jetbrains.kotlin.plugin.serialization'
}
```

`android` ブロックの `defaultConfig` に `applicationIdSuffix` を追加する。

```groovy
    defaultConfig {
        applicationIdSuffix ".firetv"
        minSdk 23
        targetSdk 34
        compileSdk 35

        versionCode 1120
        versionName "1.12"
        testInstrumentationRunner "android.support.test.runner.AndroidJUnitRunner"
    }
```

`android` ブロックの末尾（`buildFeatures` の直前）に次を追加する。

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
    testOptions {
        unitTests.returnDefaultValues = true
    }
```

既存の `buildFeatures` ブロックに `compose true` を追加する。

```groovy
    buildFeatures {
        aidl true
        compose true
    }
```

`android` ブロックの後に `dependencies` ブロックを追加する（既存の `dependencies` ブロックがある場合はそこに追記する）。

```groovy
dependencies {
    implementation platform('androidx.compose:compose-bom:2025.01.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.foundation:foundation'
    implementation 'androidx.compose.runtime:runtime'
    implementation 'androidx.activity:activity-compose'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose'
    implementation 'androidx.tv:tv-material:1.0.0'

    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0'

    debugImplementation 'androidx.compose.ui:ui-tooling'

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1'
}
```

- [ ] **Step 3: 最小の Compose 画面を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text

/**
 * Kotlin と Compose for TV がビルド・実行できることを確認するための一時的な画面。
 * Task 3 完了後に削除する。
 */
class BuildProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("compose for tv ok")
            }
        }
    }
}
```

- [ ] **Step 4: マニフェストに一時的な Activity を登録する**

`AndroidManifest.xml` の `<application>` 内に追加する。

```xml
        <activity
            android:name=".tv.BuildProbeActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar" />
```

- [ ] **Step 5: ビルドして失敗しないことを確認する**

```bash
cd ~/work/firetv-openconnect
./gradlew assembleDebug 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`

**失敗した場合**: Kotlin と AGP のバージョン不整合が最も多い。`./gradlew assembleDebug --stacktrace` の出力で不整合を確認し、Kotlin を `2.1.0` に固定したまま Compose BOM を `2024.12.01` に下げる。それでも通らない場合は tv-material を一旦外して素の Compose だけで通るかを切り分ける。

- [ ] **Step 6: 実機で Compose 画面が表示されることを確認する**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.tv.BuildProbeActivity
```

画面に `compose for tv ok` が表示されることを目視確認する。`applicationIdSuffix` が効いているため、パッケージ名が `.firetv` 付きになっている点に注意する。

- [ ] **Step 7: コミット**

```bash
git add build.gradle app/build.gradle app/src/main/AndroidManifest.xml \
        app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt
git commit -m "build: Kotlin 2.1.0 と Compose for TV を導入

既存の Java コードベースとの混在を空の Compose 画面で検証済み。
applicationIdSuffix .firetv で F-Droid 版と共存可能にした。"
```

---

### Task 3: TV 向けマニフェスト整備と QSTileService の削除

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/net/openconnect_vpn/android/QSTileService.java`

**Interfaces:**
- Consumes: Task 2 のビルド構成
- Produces: FGS 権限が宣言されたマニフェスト。Task 12 の `FailoverService` がこれに依存する

**前提となる実測事実**: フォーク元のマニフェストには `android.software.leanback` / `android.hardware.touchscreen required=false` / `android:banner` / `MainActivity` への `LEANBACK_LAUNCHER` が**すでに入っている**。本タスクで TV 対応を新規に追加する必要はない。

- [ ] **Step 1: フォアグラウンドサービス関連の権限を追加する**

`AndroidManifest.xml` の既存の `<uses-permission>` 群の直後に追加する。

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: QSTileService の宣言を削除する**

`AndroidManifest.xml` から次のブロックを削除する。

```xml
        <service
            android:exported="true"
            android:name=".QSTileService"
            android:label="@string/app"
            android:icon="@drawable/ic_launcher"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE"/>
            </intent-filter>
        </service>
```

あわせて `MainActivity` の `intent-filter` から次の1行を削除する。

```xml
                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES"/>
```

- [ ] **Step 3: QSTileService.java を削除する**

```bash
git rm app/src/main/java/net/openconnect_vpn/android/QSTileService.java
```

- [ ] **Step 4: ビルドが通ることを確認する**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

`QSTileService` を参照している箇所が他にあればコンパイルエラーになる。その場合は該当箇所を確認し、参照を削除する。

- [ ] **Step 5: 実機でホーム画面にアイコンが出ることを確認する**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Fire TV のホーム画面（アプリ一覧）にバナーが表示され、リモコンで起動できることを目視確認する。

- [ ] **Step 6: コミット**

```bash
git add -A
git commit -m "manifest: FGS 権限を追加し QSTileService を削除

TV には通知シェードがないため QSTile は不要。
targetSdk 34 の要件として FOREGROUND_SERVICE_SPECIAL_USE を宣言。"
```

---

## Phase 2: フェイルオーバー層（純粋ロジック・TDD）

ここからは全て JVM 単体テストで駆動する。Android エミュレータは不要。各タスクは `./gradlew :app:testDebugUnitTest` で検証する。

### Task 4: データモデルと interface 定義

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverModels.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/Ports.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverState.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverEvent.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverModelsTest.kt`

**Interfaces:**
- Consumes: Task 2 のビルド構成
- Produces:
  - `FailoverGroup(id: String, name: String, memberUuids: List<String>, autoFailoverEnabled: Boolean, config: FailoverConfig)`
  - `FailoverConfig(probeIntervalSec: Int = 30, probeTimeoutMs: Int = 5000, failureThreshold: Int = 3, graceAfterConnectSec: Int = 15)`
  - `ProbeTarget(host: String = "1.1.1.1", port: Int = 443)`
  - `interface Clock { fun nowMs(): Long }`
  - `interface HealthProbe { suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean }`
  - `interface VpnController { fun connect(uuid: String): ConnectResult; fun disconnect() }`
  - `enum class ConnectResult { Started, NeedsUserConsent, Failed }`
  - `interface NetworkGate { fun hasUnderlyingNetwork(): Boolean }`
  - `interface KeyValueStore { fun getString(key: String): String?; fun putString(key: String, value: String); fun remove(key: String) }`
  - `sealed interface FailoverState` with `Idle`, `Connecting`, `Verifying`, `Healthy`, `FailingOver`, `Exhausted`
  - `sealed interface FailoverEvent`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/FailoverModelsTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverModelsTest {

    @Test
    fun `FailoverConfig の既定値は仕様書のとおり`() {
        val config = FailoverConfig()
        assertEquals(30, config.probeIntervalSec)
        assertEquals(5000, config.probeTimeoutMs)
        assertEquals(3, config.failureThreshold)
        assertEquals(15, config.graceAfterConnectSec)
    }

    @Test
    fun `ProbeTarget の既定値は 1_1_1_1 の 443`() {
        val target = ProbeTarget()
        assertEquals("1.1.1.1", target.host)
        assertEquals(443, target.port)
    }

    @Test
    fun `memberUuids の並び順が優先順位を表す`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )
        assertEquals("uuid-a", group.memberUuids.first())
        assertEquals(3, group.memberUuids.size)
    }

    @Test
    fun `Idle は初期状態として使える`() {
        val state: FailoverState = FailoverState.Idle
        assertTrue(state is FailoverState.Idle)
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverModelsTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー（`Unresolved reference: FailoverConfig` など）で FAIL

- [ ] **Step 3: データモデルを実装する**

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverModels.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.serialization.Serializable

/** 接続先プローブの宛先。アプリ全体で1つだけ持つ（仕様書 6章）。 */
@Serializable
data class ProbeTarget(
    val host: String = "1.1.1.1",
    val port: Int = 443,
)

/** フェイルオーバーの挙動を決めるパラメータ。 */
@Serializable
data class FailoverConfig(
    val probeIntervalSec: Int = 30,
    val probeTimeoutMs: Int = 5_000,
    val failureThreshold: Int = 3,
    val graceAfterConnectSec: Int = 15,
)

/**
 * 優先順位付きのフェイルオーバー群。
 * [memberUuids] の並び順がそのまま優先順位になる。
 */
@Serializable
data class FailoverGroup(
    val id: String,
    val name: String,
    val memberUuids: List<String>,
    val autoFailoverEnabled: Boolean,
    val config: FailoverConfig = FailoverConfig(),
)
```

`app/src/main/java/net/openconnect_vpn/android/failover/Ports.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/**
 * 状態機械がプラットフォームに触るための境界。
 * ここを interface にしておくことで FailoverController を JVM 単体テストできる。
 */

interface Clock {
    fun nowMs(): Long
}

interface HealthProbe {
    /** [target] へ疎通できたら true。例外は投げず false を返す。 */
    suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean
}

enum class ConnectResult {
    /** 接続処理を開始した。 */
    Started,

    /** VPN 許可が未取得のため自動接続できない。 */
    NeedsUserConsent,

    /** プロファイルが見つからない等で開始できなかった。 */
    Failed,
}

interface VpnController {
    fun connect(uuid: String): ConnectResult

    /** ユーザー意図または切替による明示的な切断。 */
    fun disconnect()
}

interface NetworkGate {
    /** Wi-Fi 等の下層ネットワークが使える状態なら true。 */
    fun hasUnderlyingNetwork(): Boolean
}

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
```

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverState.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/**
 * フェイルオーバー状態機械の状態（仕様書 7章）。
 * [Connecting] 以降は必ず対象グループと候補インデックスを保持する。
 */
sealed interface FailoverState {

    /** 未接続。自動切替も停止している。 */
    data object Idle : FailoverState

    /** 候補 [candidateIndex] へ接続中。 */
    data class Connecting(
        val groupId: String,
        val candidateIndex: Int,
        val startedAtMs: Long,
    ) : FailoverState

    /** トンネルは張れた。猶予期間中で初回プローブを待っている。 */
    data class Verifying(
        val groupId: String,
        val candidateIndex: Int,
        val connectedAtMs: Long,
    ) : FailoverState

    /** 疎通確認済み。定期プローブで監視している。 */
    data class Healthy(
        val groupId: String,
        val candidateIndex: Int,
        val consecutiveFailures: Int,
        val lastProbeAtMs: Long,
    ) : FailoverState

    /** 障害を検知し次候補へ移ろうとしている。 */
    data class FailingOver(
        val groupId: String,
        val failedIndex: Int,
    ) : FailoverState

    /** グループ内の全候補が一巡して全滅した。バックオフ待ち。 */
    data class Exhausted(
        val groupId: String,
        val attempt: Int,
        val retryAtMs: Long,
    ) : FailoverState
}
```

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverEvent.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/** 状態機械への入力。 */
sealed interface FailoverEvent {

    /** ユーザーがグループへの接続を指示した。 */
    data class UserConnectGroup(val groupId: String) : FailoverEvent

    /** ユーザーが切断を指示した。 */
    data object UserDisconnect : FailoverEvent

    /** 既存コアの接続状態が変化した。値は OpenConnectManagementThread.STATE_* に対応。 */
    data class VpnStateChanged(val state: VpnCoreState) : FailoverEvent

    /** 定期プローブの結果。 */
    data class ProbeResult(val reachable: Boolean) : FailoverEvent

    /** 時刻が進んだ（タイマー駆動）。 */
    data object Tick : FailoverEvent

    /** 下層ネットワークの可用性が変化した。 */
    data class UnderlyingNetworkChanged(val available: Boolean) : FailoverEvent
}

/**
 * 既存コアの状態を状態機械側の語彙に写したもの。
 * OpenConnectManagementThread の STATE_* と 1:1 で対応する。
 */
enum class VpnCoreState {
    Authenticating,  // STATE_AUTHENTICATING = 1
    UserPrompt,      // STATE_USER_PROMPT = 2
    Authenticated,   // STATE_AUTHENTICATED = 3
    Connecting,      // STATE_CONNECTING = 4
    Connected,       // STATE_CONNECTED = 5
    Disconnected,    // STATE_DISCONNECTED = 6
    ;

    companion object {
        /** 既存コアの int 値から変換する。未知の値は null。 */
        fun fromCoreInt(value: Int): VpnCoreState? = when (value) {
            1 -> Authenticating
            2 -> UserPrompt
            3 -> Authenticated
            4 -> Connecting
            5 -> Connected
            6 -> Disconnected
            else -> null
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverModelsTest*' 2>&1 | tail -20
```

Expected: PASS（4 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/ \
        app/src/test/java/net/openconnect_vpn/android/failover/
git commit -m "feat(failover): データモデルと境界 interface を定義

状態機械を JVM 単体テストできるよう Clock/HealthProbe/VpnController/
NetworkGate/KeyValueStore を interface として切り出した。"
```

---

### Task 5: テスト用フェイクとバックオフ計算

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/Backoff.kt`
- Create: `app/src/test/java/net/openconnect_vpn/android/failover/Fakes.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/BackoffTest.kt`

**Interfaces:**
- Consumes: Task 4 の全 interface とモデル
- Produces:
  - `object Backoff { fun delayMsForAttempt(attempt: Int): Long }` — 30秒から倍々、上限10分
  - `class FakeClock(var now: Long = 0L) : Clock { fun advance(ms: Long) }`
  - `class FakeHealthProbe : HealthProbe { var reachable: Boolean; var probeCount: Int }`
  - `class FakeVpnController : VpnController { val connectCalls: List<String>; var disconnectCalls: Int; var nextResult: ConnectResult }`
  - `class FakeNetworkGate(var available: Boolean = true) : NetworkGate`
  - `class InMemoryKeyValueStore : KeyValueStore`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/BackoffTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun `初回は30秒`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(0))
    }

    @Test
    fun `試行ごとに倍になる`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(0))
        assertEquals(60_000L, Backoff.delayMsForAttempt(1))
        assertEquals(120_000L, Backoff.delayMsForAttempt(2))
        assertEquals(240_000L, Backoff.delayMsForAttempt(3))
    }

    @Test
    fun `上限は10分で打ち止め`() {
        assertEquals(480_000L, Backoff.delayMsForAttempt(4))
        assertEquals(600_000L, Backoff.delayMsForAttempt(5))
        assertEquals(600_000L, Backoff.delayMsForAttempt(6))
        assertEquals(600_000L, Backoff.delayMsForAttempt(99))
    }

    @Test
    fun `負の試行回数は初回と同じ扱い`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(-1))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*BackoffTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: Backoff` で FAIL

- [ ] **Step 3: Backoff を実装する**

`app/src/main/java/net/openconnect_vpn/android/failover/Backoff.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/**
 * 全候補が枯渇したあとの再試行間隔（仕様書 7.2）。
 * 30秒から倍々に伸ばし、10分で打ち止めにする。
 */
object Backoff {

    private const val BASE_MS = 30_000L
    private const val MAX_MS = 600_000L

    fun delayMsForAttempt(attempt: Int): Long {
        if (attempt <= 0) return BASE_MS
        // シフト量を制限してオーバーフローを避ける
        val shift = attempt.coerceAtMost(30)
        val delay = BASE_MS shl shift
        return if (delay <= 0L || delay > MAX_MS) MAX_MS else delay
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*BackoffTest*' 2>&1 | tail -20
```

Expected: PASS（4 tests）

- [ ] **Step 5: テスト用フェイクを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/Fakes.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/** 時刻を手動で進められる Clock。 */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** 到達性を固定できる HealthProbe。 */
class FakeHealthProbe(var reachable: Boolean = true) : HealthProbe {
    var probeCount: Int = 0
        private set

    override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
        probeCount++
        return reachable
    }
}

/** connect/disconnect の呼び出しを記録する VpnController。 */
class FakeVpnController : VpnController {
    private val _connectCalls = mutableListOf<String>()
    val connectCalls: List<String> get() = _connectCalls

    var disconnectCalls: Int = 0
        private set

    var nextResult: ConnectResult = ConnectResult.Started

    override fun connect(uuid: String): ConnectResult {
        _connectCalls.add(uuid)
        return nextResult
    }

    override fun disconnect() {
        disconnectCalls++
    }
}

/** 下層ネットワークの有無を切り替えられる NetworkGate。 */
class FakeNetworkGate(var available: Boolean = true) : NetworkGate {
    override fun hasUnderlyingNetwork(): Boolean = available
}

/** メモリ上の KeyValueStore。 */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
```

- [ ] **Step 6: フェイクがコンパイルできることを確認する**

```bash
./gradlew :app:compileDebugUnitTestKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/Backoff.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/
git commit -m "feat(failover): 指数バックオフとテスト用フェイクを追加

30秒から倍々、上限10分。オーバーフロー対策としてシフト量を制限。"
```

---

### Task 6: GroupStore（永続化と削除済み UUID の除去）

仕様書のテストケース9を実装する。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/GroupStore.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/GroupStoreTest.kt`

**Interfaces:**
- Consumes: `FailoverGroup` / `ProbeTarget` / `KeyValueStore`（Task 4）、`InMemoryKeyValueStore`（Task 5）
- Produces:
  - `class GroupStore(private val store: KeyValueStore)`
  - `fun loadGroups(knownProfileUuids: Set<String>): List<FailoverGroup>`
  - `fun saveGroups(groups: List<FailoverGroup>)`
  - `fun loadProbeTarget(): ProbeTarget`
  - `fun saveProbeTarget(target: ProbeTarget)`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/GroupStoreTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStoreTest {

    private val allKnown = setOf("uuid-a", "uuid-b", "uuid-c")

    private fun group(vararg members: String) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = members.toList(),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Test
    fun `保存したグループを読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-a", "uuid-b")))

        val loaded = store.loadGroups(allKnown)

        assertEquals(1, loaded.size)
        assertEquals("自宅優先", loaded[0].name)
        assertEquals(listOf("uuid-a", "uuid-b"), loaded[0].memberUuids)
        assertTrue(loaded[0].autoFailoverEnabled)
    }

    @Test
    fun `並び順が優先順位として保持される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-c", "uuid-a", "uuid-b")))

        val loaded = store.loadGroups(allKnown)

        assertEquals(listOf("uuid-c", "uuid-a", "uuid-b"), loaded[0].memberUuids)
    }

    @Test
    fun `削除済みプロファイルの UUID は読み込み時に除去される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-a", "uuid-deleted", "uuid-b")))

        val loaded = store.loadGroups(knownProfileUuids = setOf("uuid-a", "uuid-b"))

        assertEquals(listOf("uuid-a", "uuid-b"), loaded[0].memberUuids)
    }

    @Test
    fun `メンバーが全て削除されたグループ自体も除去される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-gone-1", "uuid-gone-2")))

        val loaded = store.loadGroups(knownProfileUuids = setOf("uuid-a"))

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `未保存の状態では空リストを返す`() {
        val store = GroupStore(InMemoryKeyValueStore())
        assertTrue(store.loadGroups(allKnown).isEmpty())
    }

    @Test
    fun `壊れた JSON は空リストとして扱う`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("failover_groups_v1", "{ this is not json")

        val loaded = GroupStore(kv).loadGroups(allKnown)

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `未保存のプローブ宛先は既定値を返す`() {
        val store = GroupStore(InMemoryKeyValueStore())
        val target = store.loadProbeTarget()
        assertEquals("1.1.1.1", target.host)
        assertEquals(443, target.port)
    }

    @Test
    fun `プローブ宛先を保存して読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveProbeTarget(ProbeTarget("9.9.9.9", 853))

        val target = store.loadProbeTarget()

        assertEquals("9.9.9.9", target.host)
        assertEquals(853, target.port)
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*GroupStoreTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: GroupStore` で FAIL

- [ ] **Step 3: GroupStore を実装する**

`app/src/main/java/net/openconnect_vpn/android/failover/GroupStore.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * フェイルオーバーグループとプローブ宛先の永続化。
 * 実際の保存先は [KeyValueStore] の実装が決める（本番は SharedPreferences）。
 */
class GroupStore(private val store: KeyValueStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 保存済みのグループを読み込む。
     *
     * [knownProfileUuids] に無いメンバーは削除済みプロファイルとみなして除去し、
     * メンバーが1件も残らなかったグループはグループ自体を落とす。
     * 保存データが壊れていた場合は空リストを返す（起動不能を避ける）。
     */
    fun loadGroups(knownProfileUuids: Set<String>): List<FailoverGroup> {
        val raw = store.getString(KEY_GROUPS) ?: return emptyList()
        val decoded = try {
            json.decodeFromString<List<FailoverGroup>>(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        return decoded
            .map { it.copy(memberUuids = it.memberUuids.filter { uuid -> uuid in knownProfileUuids }) }
            .filter { it.memberUuids.isNotEmpty() }
    }

    fun saveGroups(groups: List<FailoverGroup>) {
        store.putString(KEY_GROUPS, json.encodeToString(groups))
    }

    fun loadProbeTarget(): ProbeTarget {
        val raw = store.getString(KEY_PROBE_TARGET) ?: return ProbeTarget()
        return try {
            json.decodeFromString<ProbeTarget>(raw)
        } catch (e: Exception) {
            ProbeTarget()
        }
    }

    fun saveProbeTarget(target: ProbeTarget) {
        store.putString(KEY_PROBE_TARGET, json.encodeToString(target))
    }

    private companion object {
        const val KEY_GROUPS = "failover_groups_v1"
        const val KEY_PROBE_TARGET = "failover_probe_target_v1"
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*GroupStoreTest*' 2>&1 | tail -20
```

Expected: PASS（8 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/GroupStore.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/GroupStoreTest.kt
git commit -m "feat(failover): グループとプローブ宛先の永続化

削除済みプロファイルの UUID を読み込み時に除去し、空になったグループを落とす。
壊れた JSON でも起動不能にならないよう空リストにフォールバックする。"
```

---

### Task 7: FailoverController — 正常系の遷移

状態機械の骨格を作る。まず正常系だけを通し、障害系は Task 8 以降で足す。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerHappyPathTest.kt`

**Interfaces:**
- Consumes: Task 4 の全 interface・モデル・状態・イベント、Task 5 のフェイク、Task 6 の `GroupStore`
- Produces:
  - `class FailoverController(groups: List<FailoverGroup>, clock: Clock, vpn: VpnController, network: NetworkGate)` — 各グループの `FailoverConfig` は `FailoverGroup.config` から引くため、コンストラクタに config は取らない
  - `val state: FailoverState` — 現在状態の読み取り
  - `fun handle(event: FailoverEvent)` — イベントを与えて状態を進める唯一の入口
  - `fun shouldProbeNow(): Boolean` — 呼び出し側（`FailoverService`）がプローブを打つべきかの判定

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerHappyPathTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerHappyPathTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController(
            groups = listOf(group),
            clock = clock,
            vpn = vpn,
            network = network,
        )
    }

    @Test
    fun `初期状態は Idle`() {
        assertTrue(controller.state is FailoverState.Idle)
    }

    @Test
    fun `ユーザーが接続を指示すると先頭候補へ接続する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(0, (state as FailoverState.Connecting).candidateIndex)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `接続完了で Verifying に入る`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        clock.advance(3_000L)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        val state = controller.state
        assertTrue(state is FailoverState.Verifying)
        assertEquals(0, (state as FailoverState.Verifying).candidateIndex)
        assertEquals(4_000L, state.connectedAtMs)
    }

    @Test
    fun `猶予期間中はプローブを打たない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        clock.advance(10_000L)  // graceAfterConnectSec = 15 未満
        assertFalse(controller.shouldProbeNow())
    }

    @Test
    fun `猶予期間を過ぎるとプローブを打つ`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        clock.advance(16_000L)  // graceAfterConnectSec = 15 を超えた
        assertTrue(controller.shouldProbeNow())
    }

    @Test
    fun `初回プローブ成功で Healthy になる`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        val state = controller.state
        assertTrue(state is FailoverState.Healthy)
        assertEquals(0, (state as FailoverState.Healthy).candidateIndex)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `Healthy 中はプローブ間隔ごとにプローブを打つ`() {
        toHealthy()

        clock.advance(29_000L)   // probeIntervalSec = 30 未満
        assertFalse(controller.shouldProbeNow())

        clock.advance(2_000L)    // 合計 31 秒
        assertTrue(controller.shouldProbeNow())
    }

    @Test
    fun `Healthy 中のプローブ成功で失敗カウントが 0 に戻る`() {
        toHealthy()

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        assertEquals(1, (controller.state as FailoverState.Healthy).consecutiveFailures)

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertEquals(0, (controller.state as FailoverState.Healthy).consecutiveFailures)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverControllerHappyPathTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: FailoverController` で FAIL

- [ ] **Step 3: FailoverController の正常系を実装する**

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/**
 * フェイルオーバー状態機械（仕様書 7章）。
 *
 * Android API を一切参照しない。プラットフォームとのやり取りは
 * [Clock] / [VpnController] / [NetworkGate] の3つの境界だけを通す。
 * プローブの実行自体は呼び出し側（FailoverService）が [shouldProbeNow] を見て行い、
 * 結果を [FailoverEvent.ProbeResult] として戻す。
 */
class FailoverController(
    private val groups: List<FailoverGroup>,
    private val clock: Clock,
    private val vpn: VpnController,
    private val network: NetworkGate,
) {

    var state: FailoverState = FailoverState.Idle
        private set

    fun handle(event: FailoverEvent) {
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId)
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            else -> state
        }
    }

    /** 呼び出し側がいまプローブを打つべきかの判定。 */
    fun shouldProbeNow(): Boolean {
        val now = clock.nowMs()
        return when (val s = state) {
            is FailoverState.Verifying ->
                now - s.connectedAtMs >= configOf(s.groupId).graceAfterConnectSec * 1_000L

            is FailoverState.Healthy ->
                now - s.lastProbeAtMs >= configOf(s.groupId).probeIntervalSec * 1_000L

            else -> false
        }
    }

    private fun onUserConnect(groupId: String): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        return startCandidate(group, index = 0)
    }

    private fun startCandidate(group: FailoverGroup, index: Int): FailoverState {
        val uuid = group.memberUuids.getOrNull(index) ?: return FailoverState.Idle
        vpn.connect(uuid)
        return FailoverState.Connecting(
            groupId = group.id,
            candidateIndex = index,
            startedAtMs = clock.nowMs(),
        )
    }

    private fun onVpnState(core: VpnCoreState): FailoverState {
        val s = state
        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                )

            else -> s
        }
    }

    private fun onProbeResult(reachable: Boolean): FailoverState {
        return when (val s = state) {
            is FailoverState.Verifying ->
                if (reachable) {
                    FailoverState.Healthy(
                        groupId = s.groupId,
                        candidateIndex = s.candidateIndex,
                        consecutiveFailures = 0,
                        lastProbeAtMs = clock.nowMs(),
                    )
                } else {
                    s
                }

            is FailoverState.Healthy ->
                s.copy(
                    consecutiveFailures = if (reachable) 0 else s.consecutiveFailures + 1,
                    lastProbeAtMs = clock.nowMs(),
                )

            else -> s
        }
    }

    private fun groupOf(groupId: String): FailoverGroup? = groups.firstOrNull { it.id == groupId }

    private fun configOf(groupId: String): FailoverConfig =
        groupOf(groupId)?.config ?: FailoverConfig()
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverControllerHappyPathTest*' 2>&1 | tail -20
```

Expected: PASS（8 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerHappyPathTest.kt
git commit -m "feat(failover): 状態機械の正常系を実装

Idle -> Connecting -> Verifying -> Healthy の遷移と、
猶予期間・プローブ間隔の判定を実装。障害系は後続タスクで追加する。"
```

---

### Task 8: 障害検知と候補の切り替え

仕様書のテストケース1・2・3を実装する。

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerFailoverTest.kt`

**Interfaces:**
- Consumes: Task 7 の `FailoverController`
- Produces: `FailoverController` に `FailingOver` 遷移と次候補への自動前進を追加。`state` と `handle` のシグネチャは変わらない

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerFailoverTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerFailoverTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController(listOf(group), clock, vpn, network)
    }

    @Test
    fun `候補1が無応答なら候補2へ切替し候補2が Healthy になる`() {
        toHealthy()

        // 3回連続でプローブ失敗
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 切断要求が出て候補2への接続が始まる
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)

        // 候補2が繋がって健全になる
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        val state = controller.state
        assertTrue(state is FailoverState.Healthy)
        assertEquals(1, (state as FailoverState.Healthy).candidateIndex)
    }

    @Test
    fun `連続失敗が閾値未満で回復したら切替しない`() {
        toHealthy()

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `猶予期間中のプローブ失敗では切替しない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        // 猶予期間内に何度失敗しても Verifying に留まる
        repeat(5) {
            clock.advance(1_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertTrue(controller.state is FailoverState.Verifying)
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `予期しない切断は即座に次候補へ切替する`() {
        toHealthy()

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
        // トンネルは既に落ちているので切断要求を出してはならない。
        // ここで余分な disconnect が入ると、安全策 S3（意図的切断と障害の判別）が壊れる。
        assertEquals(0, vpn.disconnectCalls)
    }

    @Test
    fun `接続中のまま切断されたら次候補へ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // Connected に至らず Disconnected が来た（接続失敗）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
        // 同上。接続が成立していないので切断要求は不要。
        assertEquals(0, vpn.disconnectCalls)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverControllerFailoverTest*' 2>&1 | tail -30
```

Expected: FAIL（切替が実装されていないため `connectCalls` が `[uuid-a]` のまま）

- [ ] **Step 3: 切替ロジックを実装する**

`FailoverController.kt` の `onVpnState` と `onProbeResult` を次に差し替え、`failOver` を追加する。

```kotlin
    private fun onVpnState(core: VpnCoreState): FailoverState {
        val s = state
        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                )

            // 予期しない切断。接続中でも接続後でも次候補へ進む。
            core == VpnCoreState.Disconnected -> when (s) {
                is FailoverState.Connecting -> failOver(s.groupId, s.candidateIndex, alreadyDown = true)
                is FailoverState.Verifying -> failOver(s.groupId, s.candidateIndex, alreadyDown = true)
                is FailoverState.Healthy -> failOver(s.groupId, s.candidateIndex, alreadyDown = true)
                else -> s
            }

            else -> s
        }
    }

    private fun onProbeResult(reachable: Boolean): FailoverState {
        return when (val s = state) {
            is FailoverState.Verifying ->
                if (reachable) {
                    FailoverState.Healthy(
                        groupId = s.groupId,
                        candidateIndex = s.candidateIndex,
                        consecutiveFailures = 0,
                        lastProbeAtMs = clock.nowMs(),
                    )
                } else {
                    // 猶予期間中の失敗は無視する（安全策 S4）
                    s
                }

            is FailoverState.Healthy -> {
                val failures = if (reachable) 0 else s.consecutiveFailures + 1
                if (failures >= configOf(s.groupId).failureThreshold) {
                    failOver(s.groupId, s.candidateIndex, alreadyDown = false)
                } else {
                    s.copy(consecutiveFailures = failures, lastProbeAtMs = clock.nowMs())
                }
            }

            else -> s
        }
    }

    /**
     * 候補 [failedIndex] を諦めて次候補へ進む。
     * [alreadyDown] が false のときはトンネルがまだ生きているので明示的に切断する。
     */
    private fun failOver(groupId: String, failedIndex: Int, alreadyDown: Boolean): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        if (!alreadyDown) {
            vpn.disconnect()
        }
        val next = failedIndex + 1
        if (next >= group.memberUuids.size) {
            return FailoverState.Idle  // Task 10 で Exhausted に差し替える
        }
        return startCandidate(group, next)
    }
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverController*' 2>&1 | tail -20
```

Expected: PASS（HappyPath 8 + Failover 5 = 13 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerFailoverTest.kt
git commit -m "feat(failover): 障害検知と次候補への切替を実装

閾値未満の失敗では切替せず、猶予期間中の失敗は無視する。
予期しない切断は即座に次候補へ進む。"
```

---

### Task 9: 安全策 S1〜S3

仕様書のテストケース4・5・6を実装する。この3つは自動化 VPN で実際に事故を起こす箇所なので、まとめて1タスクにする。

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerSafetyTest.kt`

**Interfaces:**
- Consumes: Task 8 の `FailoverController`
- Produces: `FailoverController` に次を追加
  - `fun handle(FailoverEvent.UserDisconnect)` で `Idle` へ
  - `fun handle(FailoverEvent.UnderlyingNetworkChanged)` の処理
  - `val excludedUuids: Set<String>` — 認証失敗でセッション中除外された UUID の読み取り
  - `val needsUserConsent: Boolean` — VPN 許可が未取得であることの通知用フラグ

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerSafetyTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerSafetyTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController(listOf(group), clock, vpn, network)
    }

    // --- S1: 認証失敗した候補は再試行しない ---

    @Test
    fun `認証中に切断された候補は除外され次候補へ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `認証失敗した候補はセッション中スキップされる`() {
        // uuid-a を認証失敗させる
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        // uuid-b で繋がったあと障害 -> uuid-c へ。先頭に戻っても uuid-a は試されない
        controller.handle(FailoverEvent.UserDisconnect)
        vpn.connectCalls.size.let { /* 記録はそのまま */ }
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // 先頭候補 uuid-a は除外済みなので uuid-b から始まる
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `認証を通過した候補は除外されない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertFalse("uuid-a" in controller.excludedUuids)
    }

    // --- S2: 下層ネットワークが死んでいるときは切り替えない ---

    @Test
    fun `下層ネット断中はプローブを打たない`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))

        clock.advance(31_000L)
        assertFalse(controller.shouldProbeNow())
    }

    @Test
    fun `下層ネット断中の切断では切替しない`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        // 候補は消費されず、接続要求も増えない
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `下層ネット復帰で即プローブできる状態に戻る`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))
        clock.advance(31_000L)
        assertFalse(controller.shouldProbeNow())

        network.available = true
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = true))

        assertTrue(controller.shouldProbeNow())
    }

    // --- S3: ユーザーの意図的な切断を障害と誤認しない ---

    @Test
    fun `ユーザー切断で Idle に入り自動切替が止まる`() {
        toHealthy()

        controller.handle(FailoverEvent.UserDisconnect)

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(1, vpn.disconnectCalls)

        // 続いて届く Disconnected は障害扱いしない
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    // --- VPN 許可未取得 ---

    @Test
    fun `VPN 許可が未取得なら Idle に留まりフラグが立つ`() {
        vpn.nextResult = ConnectResult.NeedsUserConsent

        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        assertTrue(controller.state is FailoverState.Idle)
        assertTrue(controller.needsUserConsent)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverControllerSafetyTest*' 2>&1 | tail -30
```

Expected: コンパイルエラー `Unresolved reference: excludedUuids` で FAIL

- [ ] **Step 3: 安全策を実装する**

`FailoverController.kt` を次の内容に差し替える。

```kotlin
package net.openconnect_vpn.android.failover

/**
 * フェイルオーバー状態機械（仕様書 7章）。
 *
 * Android API を一切参照しない。プラットフォームとのやり取りは
 * [Clock] / [VpnController] / [NetworkGate] の3つの境界だけを通す。
 * プローブの実行自体は呼び出し側（FailoverService）が [shouldProbeNow] を見て行い、
 * 結果を [FailoverEvent.ProbeResult] として戻す。
 */
class FailoverController(
    private val groups: List<FailoverGroup>,
    private val clock: Clock,
    private val vpn: VpnController,
    private val network: NetworkGate,
) {

    var state: FailoverState = FailoverState.Idle
        private set

    /** 安全策 S1: 認証失敗でこのセッション中は試さない候補。 */
    private val _excludedUuids = mutableSetOf<String>()
    val excludedUuids: Set<String> get() = _excludedUuids

    /** VPN 許可が未取得で自動接続できなかったことを UI へ伝えるフラグ。 */
    var needsUserConsent: Boolean = false
        private set

    /** 安全策 S3: 自分が出した切断要求による Disconnected を障害扱いしないための印。 */
    private var expectingDisconnect = false

    /** 安全策 S1: 現在の候補が認証を通過したか。通過前の切断は認証失敗とみなす。 */
    private var currentCandidatePassedAuth = false

    fun handle(event: FailoverEvent) {
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId)
            is FailoverEvent.UserDisconnect -> onUserDisconnect()
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            is FailoverEvent.UnderlyingNetworkChanged -> state
            is FailoverEvent.Tick -> state
        }
    }

    /**
     * 呼び出し側がいまプローブを打つべきかの判定。
     * 安全策 S2 により下層ネットワークが無いときは常に false。
     */
    fun shouldProbeNow(): Boolean {
        if (!network.hasUnderlyingNetwork()) return false
        val now = clock.nowMs()
        return when (val s = state) {
            is FailoverState.Verifying ->
                now - s.connectedAtMs >= configOf(s.groupId).graceAfterConnectSec * 1_000L

            is FailoverState.Healthy ->
                now - s.lastProbeAtMs >= configOf(s.groupId).probeIntervalSec * 1_000L

            else -> false
        }
    }

    private fun onUserConnect(groupId: String): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        needsUserConsent = false
        return startCandidateFrom(group, fromIndex = 0)
    }

    private fun onUserDisconnect(): FailoverState {
        expectingDisconnect = true
        vpn.disconnect()
        return FailoverState.Idle
    }

    /**
     * [fromIndex] 以降で除外されていない最初の候補へ接続する。
     * 候補が無ければ Idle（Task 10 で Exhausted に差し替える）。
     */
    private fun startCandidateFrom(group: FailoverGroup, fromIndex: Int): FailoverState {
        var index = fromIndex
        while (index < group.memberUuids.size) {
            val uuid = group.memberUuids[index]
            if (uuid in _excludedUuids) {
                index++
                continue
            }
            currentCandidatePassedAuth = false
            expectingDisconnect = false
            return when (vpn.connect(uuid)) {
                ConnectResult.Started -> FailoverState.Connecting(
                    groupId = group.id,
                    candidateIndex = index,
                    startedAtMs = clock.nowMs(),
                )

                ConnectResult.NeedsUserConsent -> {
                    needsUserConsent = true
                    FailoverState.Idle
                }

                ConnectResult.Failed -> {
                    _excludedUuids.add(uuid)
                    index++
                    continue
                }
            }
        }
        return FailoverState.Idle
    }

    private fun onVpnState(core: VpnCoreState): FailoverState {
        val s = state

        // 認証を通過したことを覚えておく（S1 の判定に使う）
        if (core == VpnCoreState.Authenticated || core == VpnCoreState.Connected) {
            currentCandidatePassedAuth = true
        }

        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                )

            core == VpnCoreState.Disconnected -> onDisconnected(s)

            else -> s
        }
    }

    private fun onDisconnected(s: FailoverState): FailoverState {
        // S3: 自分が出した切断要求の結果なら障害ではない
        if (expectingDisconnect) {
            expectingDisconnect = false
            return s
        }
        // S2: 下層ネットが無いなら切り替えても無意味。現状を保って待つ
        if (!network.hasUnderlyingNetwork()) return s

        val groupId = groupIdOf(s) ?: return s
        val index = candidateIndexOf(s) ?: return s

        // S1: 認証を通過せずに落ちたら認証失敗とみなして除外する
        if (!currentCandidatePassedAuth) {
            groupOf(groupId)?.memberUuids?.getOrNull(index)?.let { _excludedUuids.add(it) }
        }
        return failOver(groupId, index, alreadyDown = true)
    }

    private fun onProbeResult(reachable: Boolean): FailoverState {
        return when (val s = state) {
            is FailoverState.Verifying ->
                if (reachable) {
                    FailoverState.Healthy(
                        groupId = s.groupId,
                        candidateIndex = s.candidateIndex,
                        consecutiveFailures = 0,
                        lastProbeAtMs = clock.nowMs(),
                    )
                } else {
                    // 猶予期間中の失敗は無視する（S4）
                    s
                }

            is FailoverState.Healthy -> {
                val failures = if (reachable) 0 else s.consecutiveFailures + 1
                if (failures >= configOf(s.groupId).failureThreshold) {
                    failOver(s.groupId, s.candidateIndex, alreadyDown = false)
                } else {
                    s.copy(consecutiveFailures = failures, lastProbeAtMs = clock.nowMs())
                }
            }

            else -> s
        }
    }

    /**
     * 候補 [failedIndex] を諦めて次候補へ進む。
     * [alreadyDown] が false のときはトンネルがまだ生きているので明示的に切断する。
     */
    private fun failOver(groupId: String, failedIndex: Int, alreadyDown: Boolean): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        if (!alreadyDown) {
            expectingDisconnect = true
            vpn.disconnect()
        }
        return startCandidateFrom(group, fromIndex = failedIndex + 1)
    }

    private fun groupOf(groupId: String): FailoverGroup? = groups.firstOrNull { it.id == groupId }

    private fun configOf(groupId: String): FailoverConfig =
        groupOf(groupId)?.config ?: FailoverConfig()

    private fun groupIdOf(s: FailoverState): String? = when (s) {
        is FailoverState.Connecting -> s.groupId
        is FailoverState.Verifying -> s.groupId
        is FailoverState.Healthy -> s.groupId
        is FailoverState.FailingOver -> s.groupId
        is FailoverState.Exhausted -> s.groupId
        FailoverState.Idle -> null
    }

    private fun candidateIndexOf(s: FailoverState): Int? = when (s) {
        is FailoverState.Connecting -> s.candidateIndex
        is FailoverState.Verifying -> s.candidateIndex
        is FailoverState.Healthy -> s.candidateIndex
        is FailoverState.FailingOver -> s.failedIndex
        is FailoverState.Exhausted -> null
        FailoverState.Idle -> null
    }
}
```

`failOver` が `vpn.disconnect()` の前に `expectingDisconnect = true` を立てているため、切替に伴う `Disconnected` は障害として二重カウントされない。

- [ ] **Step 4: 全テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverController*' 2>&1 | tail -30
```

Expected: PASS（HappyPath 8 + Failover 5 + Safety 8 = 21 tests）

Task 8 のテスト `予期しない切断は即座に次候補へ切替する` は `toHealthy()` で認証通過済みのため除外されず、引き続き通る。

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerSafetyTest.kt
git commit -m "feat(failover): 安全策 S1-S3 を実装

S1 認証失敗した候補をセッション中除外（アカウントロック防止）
S2 下層ネット断中は切替せず待機
S3 意図的な切断を障害と誤認しない"
```

---

### Task 10: 全候補枯渇とバックオフ、自動切替 OFF

仕様書のテストケース7・8を実装する。

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerExhaustionTest.kt`

**Interfaces:**
- Consumes: Task 9 の `FailoverController`、Task 5 の `Backoff`
- Produces: `FailoverController` に `Exhausted` 遷移と `FailoverEvent.Tick` による再試行を追加

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerExhaustionTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerExhaustionTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate

    private fun controllerFor(group: FailoverGroup) =
        FailoverController(listOf(group), clock, vpn, network)

    private fun group(auto: Boolean = true) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b"),
        autoFailoverEnabled = auto,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
    }

    @Test
    fun `全候補が接続失敗すると Exhausted に入る`() {
        val controller = controllerFor(group())
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // uuid-a: 認証通過後に切断（除外されない）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        // uuid-b: 同様に失敗
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        val state = controller.state
        assertTrue(state is FailoverState.Exhausted)
        assertEquals(0, (state as FailoverState.Exhausted).attempt)
        assertEquals(clock.now + 30_000L, state.retryAtMs)
    }

    @Test
    fun `バックオフ満了で先頭候補から再試行する`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        val callsBefore = vpn.connectCalls.size

        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals("uuid-a", vpn.connectCalls.last())
        assertEquals(callsBefore + 1, vpn.connectCalls.size)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `バックオフ満了前の Tick では再試行しない`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        val callsBefore = vpn.connectCalls.size

        clock.advance(29_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(callsBefore, vpn.connectCalls.size)
        assertTrue(controller.state is FailoverState.Exhausted)
    }

    @Test
    fun `枯渇を繰り返すとバックオフ間隔が指数的に伸びる`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        assertEquals(0, (controller.state as FailoverState.Exhausted).attempt)

        // 1回目の再試行も全滅させる
        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)
        failAllCandidates(controller)

        val state = controller.state as FailoverState.Exhausted
        assertEquals(1, state.attempt)
        assertEquals(clock.now + 60_000L, state.retryAtMs)
    }

    @Test
    fun `自動切替 OFF のグループは障害時に切替せず Idle で止まる`() {
        val controller = controllerFor(group(auto = false))
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)
    }

    @Test
    fun `自動切替 OFF なら予期しない切断でも次候補へ行かない`() {
        val controller = controllerFor(group(auto = false))
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    private fun toExhausted(controller: FailoverController) {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        failAllCandidates(controller)
    }

    private fun failAllCandidates(controller: FailoverController) {
        repeat(2) {
            controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
            controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        }
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*FailoverControllerExhaustionTest*' 2>&1 | tail -30
```

Expected: FAIL（`Exhausted` に遷移せず `Idle` のままになる）

- [ ] **Step 3: Exhausted と自動切替 OFF を実装する**

`FailoverController.kt` の `handle` の `Tick` 分岐を差し替える。

```kotlin
            is FailoverEvent.Tick -> onTick()
```

`startCandidateFrom` の末尾（候補が尽きたときの `return FailoverState.Idle`）を差し替える。

```kotlin
        return enterExhausted(group.id, attempt = 0)
```

`failOver` を差し替える。

```kotlin
    /**
     * 候補 [failedIndex] を諦めて次候補へ進む。
     * 自動切替が無効なグループでは切り替えず Idle で停止する（R6）。
     * [alreadyDown] が false のときはトンネルがまだ生きているので明示的に切断する。
     */
    private fun failOver(groupId: String, failedIndex: Int, alreadyDown: Boolean): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        if (!alreadyDown) {
            expectingDisconnect = true
            vpn.disconnect()
        }
        if (!group.autoFailoverEnabled) return FailoverState.Idle
        return startCandidateFrom(group, fromIndex = failedIndex + 1)
    }
```

次の2メソッドをクラスに追加する。

```kotlin
    /** 全候補が枯渇した。指数バックオフ後に先頭から再試行する（仕様書 7.2）。 */
    private fun enterExhausted(groupId: String, attempt: Int): FailoverState =
        FailoverState.Exhausted(
            groupId = groupId,
            attempt = attempt,
            retryAtMs = clock.nowMs() + Backoff.delayMsForAttempt(attempt),
        )

    private fun onTick(): FailoverState {
        val s = state as? FailoverState.Exhausted ?: return state
        if (clock.nowMs() < s.retryAtMs) return s
        val group = groupOf(s.groupId) ?: return FailoverState.Idle

        // 再試行時は認証失敗による除外を維持したまま先頭から試す。
        // 次に枯渇したときの attempt を1つ進める。ここでリセットしてはならない
        // （リセットすると2回目の枯渇でも attempt が 0 に戻り、バックオフが伸びない）。
        exhaustionAttempt = s.attempt + 1
        return startCandidateFrom(group, fromIndex = 0)
    }
```

クラスのフィールドに次を追加する。**一時変数ではなく永続フィールドであることが重要**で、
`onTick` の中でリセットすると2回目の枯渇でも `attempt` が 0 に戻り、指数バックオフが成立しない。

```kotlin
    /**
     * 枯渇の世代。次に枯渇したときの Exhausted.attempt になる。
     * `onTick` の再試行で 1 つ進め、復旧（Healthy 到達）とユーザー操作でのみ 0 に戻す。
     */
    private var exhaustionAttempt = 0
```

`startCandidateFrom` の枯渇時の行を、この世代を使う形に直す。

```kotlin
        return enterExhausted(group.id, attempt = exhaustionAttempt)
```

リセットは3箇所に入れる。`onUserConnect` の先頭、`onUserDisconnect` の先頭、
そして `onProbeResult` で `Verifying` から `Healthy` へ遷移する直前。

```kotlin
        exhaustionAttempt = 0
```

復旧したら世代を 0 に戻すことで、次に障害が起きたときのバックオフが再び 30 秒から始まる。
これを忘れると、一度枯渇した後は復旧しても長い間隔が保持され続ける。

- [ ] **Step 4: 全テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: PASS（Models 4 + Backoff 4 + GroupStore 8 + HappyPath 8 + Failover 5 + Safety 8 + Exhaustion 6 = 43 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt \
        app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerExhaustionTest.kt
git commit -m "feat(failover): 枯渇時のバックオフと自動切替 OFF を実装

全候補が一巡して全滅したら Exhausted に入り 30秒->10分の指数バックオフで再試行。
autoFailoverEnabled=false のグループは障害時に Idle で停止する。"
```

---

## Phase 3: Android 実装への接続

純粋ロジックが揃ったので、interface の実装を書いて実機で動かす。

### Task 11: プラットフォーム実装（4つの interface）

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/SystemClock.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/TcpHealthProbe.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/OpenConnectVpnController.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/ConnectivityNetworkGate.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/PrefsKeyValueStore.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/TcpHealthProbeTest.kt`

**Interfaces:**
- Consumes: Task 4 の全 interface
- Produces:
  - `class SystemClock : Clock`
  - `class TcpHealthProbe : HealthProbe`
  - `class OpenConnectVpnController(private val context: Context) : VpnController`
  - `class ConnectivityNetworkGate(private val context: Context) : NetworkGate`
  - `class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore`

- [ ] **Step 1: TcpHealthProbe の失敗するテストを書く**

実際にソケットを開くので、ローカルの `ServerSocket` を相手にする。Android API を使わないため JVM テストで動く。

`app/src/test/java/net/openconnect_vpn/android/failover/TcpHealthProbeTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class TcpHealthProbeTest {

    @Test
    fun `待ち受けているポートには到達できる`() = runTest {
        ServerSocket(0).use { server ->
            val probe = TcpHealthProbe()
            val reachable = probe.probe(
                target = ProbeTarget("127.0.0.1", server.localPort),
                timeoutMs = 2_000,
            )
            assertTrue(reachable)
        }
    }

    @Test
    fun `閉じているポートには到達できない`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort }  // 閉じた直後のポート

        val probe = TcpHealthProbe()
        val reachable = probe.probe(ProbeTarget("127.0.0.1", closedPort), timeoutMs = 2_000)

        assertFalse(reachable)
    }

    @Test
    fun `解決できないホストでも例外を投げずに false を返す`() = runTest {
        val probe = TcpHealthProbe()
        val reachable = probe.probe(
            target = ProbeTarget("this-host-does-not-exist.invalid", 443),
            timeoutMs = 2_000,
        )
        assertFalse(reachable)
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*TcpHealthProbeTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: TcpHealthProbe` で FAIL

- [ ] **Step 3: TcpHealthProbe を実装する**

`app/src/main/java/net/openconnect_vpn/android/failover/TcpHealthProbe.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP connect による疎通確認。
 *
 * VpnService 配下では全トラフィックがトンネルに入るため、通常の Socket で
 * 宛先に繋がれば「VPN 越しに通信できている」と判定できる。
 * ICMP は Android で raw socket が使えないため採用しない（仕様書 8章）。
 */
class TcpHealthProbe : HealthProbe {

    override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                    true
                }
            } catch (e: Exception) {
                // 名前解決失敗・接続拒否・タイムアウトはすべて「到達不能」
                false
            }
        }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*TcpHealthProbeTest*' 2>&1 | tail -20
```

Expected: PASS（3 tests）

- [ ] **Step 5: 残る3つの実装を書く**

`app/src/main/java/net/openconnect_vpn/android/failover/SystemClock.kt`:

```kotlin
package net.openconnect_vpn.android.failover

/** 単調増加する時計。端末時刻の変更に影響されない elapsedRealtime を使う。 */
class SystemClock : Clock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}
```

`app/src/main/java/net/openconnect_vpn/android/failover/OpenConnectVpnController.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.content.Context
import android.content.Intent
import android.net.VpnService
import net.openconnect_vpn.android.core.OpenVpnService
import net.openconnect_vpn.android.core.ProfileManager

/**
 * 既存の OpenConnect コアへのアダプタ。
 *
 * 既存 UI は GrantPermissionsActivity を起動して接続するが、フェイルオーバー層は
 * この経路を使えない。Android 10 以降のバックグラウンド Activity 起動制限により
 * 確実に表示される保証がないためである（仕様書 5.2）。
 * ここでは GrantPermissionsActivity.onActivityResult が行う処理を直接実行する。
 *
 * 呼び出し元の FailoverService 自身がフォアグラウンドサービスなので、
 * ここからの startService はバックグラウンド起動制限に抵触しない。
 */
class OpenConnectVpnController(private val context: Context) : VpnController {

    override fun connect(uuid: String): ConnectResult {
        // 未許可なら自動接続はできない。UI 側で許可を取ってもらう。
        if (VpnService.prepare(context) != null) return ConnectResult.NeedsUserConsent

        if (ProfileManager.get(uuid) == null) return ConnectResult.Failed

        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(OpenVpnService.EXTRA_UUID, uuid)
        }
        return try {
            context.startService(intent)
            ConnectResult.Started
        } catch (e: Exception) {
            ConnectResult.Failed
        }
    }

    override fun disconnect() {
        // OpenVpnService は START_SERVICE アクションで bind されている前提ではなく、
        // 停止要求として stopService を使う。サービス側の onDestroy が stopVPN を呼ぶ。
        context.stopService(Intent(context, OpenVpnService::class.java))
    }
}
```

`app/src/main/java/net/openconnect_vpn/android/failover/ConnectivityNetworkGate.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 下層ネットワーク（Wi-Fi / 有線）の可用性を見る（安全策 S2）。
 * VPN トランスポートは除外する。VPN 自体が生きているかは状態機械が別に見ている。
 */
class ConnectivityNetworkGate(private val context: Context) : NetworkGate {

    override fun hasUnderlyingNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true  // 判定できないときは「ある」とみなして状態機械を止めない

        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isPhysical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            !isVpn && isPhysical && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
```

`app/src/main/java/net/openconnect_vpn/android/failover/PrefsKeyValueStore.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.content.SharedPreferences

/** SharedPreferences を KeyValueStore として見せる。 */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
```

- [ ] **Step 6: ビルドが通ることを確認する**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

`ProfileManager.get` と `OpenVpnService.EXTRA_UUID` が解決できない場合は、既存コードの可視性を確認する（いずれも `public static` なので通るはず）。

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/ \
        app/src/test/java/net/openconnect_vpn/android/failover/TcpHealthProbeTest.kt
git commit -m "feat(failover): プラットフォーム実装を追加

TcpHealthProbe は ServerSocket を相手に JVM テストで検証。
OpenConnectVpnController は Activity を経由せず OpenVpnService を直接起動し、
バックグラウンド Activity 起動制限を回避する。"
```

---

### Task 12: VpnStatusBridge と FailoverService

状態機械を実機で駆動する。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/VpnStatusBridge.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverNotifications.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/VpnCoreStateTest.kt`

**Interfaces:**
- Consumes: Task 10 の `FailoverController`、Task 11 の全実装、Task 6 の `GroupStore`
- Produces:
  - `class FailoverService : Service()` — `ACTION_CONNECT_GROUP`（extra `EXTRA_GROUP_ID`）と `ACTION_DISCONNECT` を受ける
  - `companion object { fun connectGroup(context: Context, groupId: String); fun disconnect(context: Context) }` — 計画2 の TV UI がこれを呼ぶ

- [ ] **Step 1: VpnCoreState 変換の失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/failover/VpnCoreStateTest.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnCoreStateTest {

    @Test
    fun `既存コアの STATE 値と 1対1 で対応する`() {
        assertEquals(VpnCoreState.Authenticating, VpnCoreState.fromCoreInt(1))
        assertEquals(VpnCoreState.UserPrompt, VpnCoreState.fromCoreInt(2))
        assertEquals(VpnCoreState.Authenticated, VpnCoreState.fromCoreInt(3))
        assertEquals(VpnCoreState.Connecting, VpnCoreState.fromCoreInt(4))
        assertEquals(VpnCoreState.Connected, VpnCoreState.fromCoreInt(5))
        assertEquals(VpnCoreState.Disconnected, VpnCoreState.fromCoreInt(6))
    }

    @Test
    fun `未知の値は null`() {
        assertNull(VpnCoreState.fromCoreInt(0))
        assertNull(VpnCoreState.fromCoreInt(7))
        assertNull(VpnCoreState.fromCoreInt(-1))
    }
}
```

- [ ] **Step 2: テストを実行して通ることを確認する**

`VpnCoreState.fromCoreInt` は Task 4 で実装済みなので、このテストは最初から通る。既存コアの定数と状態機械の語彙が食い違っていないことを固定するための回帰テストである。

```bash
./gradlew :app:testDebugUnitTest --tests '*VpnCoreStateTest*' 2>&1 | tail -20
```

Expected: PASS（2 tests）

- [ ] **Step 3: VpnStatusBridge を書く**

`app/src/main/java/net/openconnect_vpn/android/failover/VpnStatusBridge.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import net.openconnect_vpn.android.core.OpenVpnService

/**
 * 既存コアの ACTION_VPN_STATUS ブロードキャストを FailoverEvent に変換して流す。
 */
class VpnStatusBridge(
    private val context: Context,
    private val onEvent: (FailoverEvent) -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(OpenVpnService.EXTRA_CONNECTION_STATE, -1) ?: -1
            VpnCoreState.fromCoreInt(raw)?.let { onEvent(FailoverEvent.VpnStateChanged(it)) }
        }
    }

    fun register() {
        val filter = IntentFilter(OpenVpnService.ACTION_VPN_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
```

- [ ] **Step 4: 通知を書く**

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverNotifications.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import net.openconnect_vpn.android.R

/** FailoverService のフォアグラウンド通知と、ユーザーに伝えるべき事象の通知。 */
object FailoverNotifications {

    const val CHANNEL_ID = "failover_status"
    const val FOREGROUND_ID = 4201
    const val ALERT_ID = 4202

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN 自動切替",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    fun foreground(context: Context, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle(context.getString(R.string.app))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    fun alert(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        nm.notify(
            ALERT_ID,
            builder
                .setContentTitle(context.getString(R.string.app))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .build(),
        )
    }
}
```

- [ ] **Step 5: FailoverService を書く**

`app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt`:

```kotlin
package net.openconnect_vpn.android.failover

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.openconnect_vpn.android.core.ProfileManager

/**
 * フェイルオーバー状態機械を駆動するフォアグラウンドサービス。
 *
 * 状態機械そのもの（FailoverController）は Android 非依存で、ここが
 * ブロードキャスト・タイマー・プローブ実行という副作用だけを担う。
 */
class FailoverService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var loop: Job? = null

    private lateinit var controller: FailoverController
    private lateinit var probe: HealthProbe
    private lateinit var groupStore: GroupStore
    private lateinit var bridge: VpnStatusBridge
    private lateinit var networkGate: NetworkGate
    private var probeTarget: ProbeTarget = ProbeTarget()

    override fun onCreate() {
        super.onCreate()
        FailoverNotifications.ensureChannel(this)
        startForegroundCompat("待機中")

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        groupStore = GroupStore(PrefsKeyValueStore(prefs))
        probe = TcpHealthProbe()
        networkGate = ConnectivityNetworkGate(this)
        probeTarget = groupStore.loadProbeTarget()

        ProfileManager.init(this)
        val knownUuids = ProfileManager.getProfiles().map { it.getUUIDString() }.toSet()

        controller = FailoverController(
            groups = groupStore.loadGroups(knownUuids),
            clock = SystemClock(),
            vpn = OpenConnectVpnController(this),
            network = networkGate,
        )

        bridge = VpnStatusBridge(this) { event -> dispatch(event) }
        bridge.register()

        loop = scope.launch {
            var lastNetworkAvailable = networkGate.hasUnderlyingNetwork()
            while (true) {
                val available = networkGate.hasUnderlyingNetwork()
                if (available != lastNetworkAvailable) {
                    lastNetworkAvailable = available
                    dispatch(FailoverEvent.UnderlyingNetworkChanged(available))
                }
                if (controller.shouldProbeNow()) {
                    val reachable = probe.probe(probeTarget, PROBE_TIMEOUT_MS)
                    dispatch(FailoverEvent.ProbeResult(reachable))
                }
                dispatch(FailoverEvent.Tick)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_GROUP -> intent.getStringExtra(EXTRA_GROUP_ID)?.let { groupId ->
                dispatch(FailoverEvent.UserConnectGroup(groupId))
            }

            ACTION_DISCONNECT -> dispatch(FailoverEvent.UserDisconnect)
        }
        return START_STICKY
    }

    private fun dispatch(event: FailoverEvent) {
        controller.handle(event)
        updateForegroundText()
        if (controller.needsUserConsent) {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
        }
    }

    private fun updateForegroundText() {
        val text = when (val s = controller.state) {
            FailoverState.Idle -> "待機中"
            is FailoverState.Connecting -> "接続中 (候補 ${s.candidateIndex + 1})"
            is FailoverState.Verifying -> "疎通確認中"
            is FailoverState.Healthy -> "接続済み (候補 ${s.candidateIndex + 1})"
            is FailoverState.FailingOver -> "切り替え中"
            is FailoverState.Exhausted -> "全候補が応答しません。再試行を待機中"
        }
        startForegroundCompat(text)
    }

    private fun startForegroundCompat(text: String) {
        val notification = FailoverNotifications.foreground(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FailoverNotifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FailoverNotifications.FOREGROUND_ID, notification)
        }
    }

    override fun onDestroy() {
        bridge.unregister()
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT_GROUP = "net.openconnect_vpn.android.failover.CONNECT_GROUP"
        const val ACTION_DISCONNECT = "net.openconnect_vpn.android.failover.DISCONNECT"
        const val EXTRA_GROUP_ID = "net.openconnect_vpn.android.failover.GROUP_ID"

        private const val TICK_INTERVAL_MS = 5_000L
        private const val PROBE_TIMEOUT_MS = 5_000

        /** 計画2 の TV UI から呼ぶ入口。 */
        fun connectGroup(context: Context, groupId: String) {
            val intent = Intent(context, FailoverService::class.java).apply {
                action = ACTION_CONNECT_GROUP
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            startCompat(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, FailoverService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
```

- [ ] **Step 6: VpnProfile の UUID アクセサ名を確認する**

`FailoverService` が UUID を取り出している。既存の `VpnProfile` の API を確認する。

```bash
grep -nE 'getUUIDString|getUUID|uuidString' app/src/main/java/net/openconnect_vpn/android/VpnProfile.java
```

**注意: `getUUIDString()` を Kotlin から `it.uuidString` として参照することはできない。**
Kotlin が Java の getter をプロパティとして見せるのは、getter 名を decapitalize できる場合に限る。
`UUIDString` は先頭に大文字が2つ以上続くため decapitalize されず、`uuidString` という
プロパティ名は生成されない。`it.getUUIDString()` とメソッドとして呼ぶ。

実際の名前が異なっていた場合は `FailoverService.onCreate` の該当行をそれに合わせる。

- [ ] **Step 7: マニフェストにサービスを登録する**

`AndroidManifest.xml` の `<application>` 内、既存の `OpenVpnService` の宣言の後に追加する。

```xml
        <service
            android:name=".failover.FailoverService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vpn_failover_monitoring" />
        </service>
```

- [ ] **Step 8: ビルドしてインストールし、サービスが起動することを確認する**

```bash
./gradlew assembleDebug 2>&1 | tail -20
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start-foreground-service \
    -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverService
adb shell dumpsys activity services net.openconnect_vpn.android.firetv | head -30
```

Expected: `FailoverService` が実行中として表示される。クラッシュする場合は次でログを確認する。

```bash
adb logcat -d -s AndroidRuntime:E OpenConnect:* | tail -40
```

- [ ] **Step 9: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/ \
        app/src/test/java/net/openconnect_vpn/android/failover/VpnCoreStateTest.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(failover): 状態機械を駆動するフォアグラウンドサービス

ACTION_VPN_STATUS をイベントに変換し、5秒ティックでプローブと Tick を回す。
targetSdk 34 の要件に従い foregroundServiceType=specialUse を宣言。"
```

---

### Task 13: 実機での自動フェイルオーバー検証

仕様書 13章の実機検証シナリオを通す。テストコードではなく手動検証だが、ここを通さないと計画1は完了しない。

**Files:**
- Create: `docs/MANUAL-TEST.md`

**Interfaces:**
- Consumes: Task 12 までの全成果物
- Produces: `docs/MANUAL-TEST.md` に記録された検証結果

- [ ] **Step 1: 接続先を2件登録してグループを作る**

TV UI は計画2 で作るため、この時点では既存のスマホ向け UI で接続先2件を登録する。グループはまだ UI が無いので `adb` で直接投入する。

```bash
# アプリを一度起動して SharedPreferences を作らせる
adb shell monkey -p net.openconnect_vpn.android.firetv -c android.intent.category.LAUNCHER 1

# 登録済みプロファイルの UUID を確認する
adb shell run-as net.openconnect_vpn.android.firetv ls shared_prefs/
```

`profile-<uuid>.xml` のファイル名から UUID を2つ取得する。

- [ ] **Step 2: グループ定義を投入する**

取得した UUID を使い、`GroupStore` が読む形式の JSON を既定 SharedPreferences に書き込む。デバッグビルドなので `run-as` で直接編集できる。

```bash
UUID_A="<1つめのUUID>"
UUID_B="<2つめのUUID>"
JSON="[{\"id\":\"g1\",\"name\":\"テスト群\",\"memberUuids\":[\"$UUID_A\",\"$UUID_B\"],\"autoFailoverEnabled\":true,\"config\":{\"probeIntervalSec\":30,\"probeTimeoutMs\":5000,\"failureThreshold\":3,\"graceAfterConnectSec\":15}}]"
echo "$JSON" > /tmp/groups.json
```

`GroupStore` は既定 SharedPreferences（`PreferenceManager.getDefaultSharedPreferences`）を使うため、ファイル名は `net.openconnect_vpn.android.firetv_preferences.xml` になる。XML を直接書くより、一時的なデバッグ用 Activity を足すよりも、次の方法が確実である。

```bash
# adb から Service にグループ JSON を渡せるよう、
# FailoverService に一時的なデバッグアクションを足す（検証後に削除する）
```

`FailoverService.companion object` に次を追加する。

```kotlin
        /** 実機検証用。検証完了後に削除する。 */
        const val ACTION_DEBUG_SET_GROUPS = "net.openconnect_vpn.android.failover.DEBUG_SET_GROUPS"
        const val EXTRA_GROUPS_JSON = "net.openconnect_vpn.android.failover.GROUPS_JSON"
```

`onStartCommand` の `when` に次を追加する。

```kotlin
            ACTION_DEBUG_SET_GROUPS -> intent.getStringExtra(EXTRA_GROUPS_JSON)?.let { raw ->
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putString("failover_groups_v1", raw).apply()
            }
```

ビルドして投入する。

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start-foreground-service \
    -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverService \
    -a net.openconnect_vpn.android.failover.DEBUG_SET_GROUPS \
    --es net.openconnect_vpn.android.failover.GROUPS_JSON "$JSON"
```

サービスを再起動して読み込ませる。

```bash
adb shell am force-stop net.openconnect_vpn.android.firetv
```

- [ ] **Step 3: グループ接続が動くことを確認する**

```bash
adb shell am start-foreground-service \
    -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverService \
    -a net.openconnect_vpn.android.failover.CONNECT_GROUP \
    --es net.openconnect_vpn.android.failover.GROUP_ID g1
adb logcat -c && adb logcat -s OpenConnect:* | tail -40
```

先頭候補に接続され、通知が「接続済み (候補 1)」になることを確認する。

- [ ] **Step 4: シナリオ1 — サーバ停止によるトンネル切断を検証する**

サーバ側で ocserv を止める。

```bash
# VPN サーバ側で実行
sudo systemctl stop ocserv
```

Fire TV 側で、通知が「切り替え中」を経て「接続済み (候補 2)」に変わることを確認する。トンネルの明示的な切断なので**即座に**切り替わるはずである。

```bash
adb logcat -s OpenConnect:* | tail -40
```

- [ ] **Step 5: シナリオ2 — パケット drop による「繋がっているのに通らない」を検証する**

サーバを再開し、候補1に接続し直したうえで、サーバ側でトンネル越しの転送だけを落とす。

```bash
# VPN サーバ側で実行（トンネルは維持されるが転送されなくなる）
sudo iptables -I FORWARD -i vpns+ -j DROP
```

Fire TV 側で、**約90秒後**（プローブ30秒 × 失敗3回）に候補2へ切り替わることを確認する。これが仕様書 8章に書いた検知遅延の実測になる。

検証後に元に戻す。

```bash
sudo iptables -D FORWARD -i vpns+ -j DROP
```

- [ ] **Step 6: シナリオ3 — 下層ネット断で切替が起きないことを検証する（安全策 S2）**

Fire TV の Wi-Fi を切る。

```bash
adb shell svc wifi disable
```

通知が変わらず、候補が消費されないことを確認する（`adb logcat` で `connect` 呼び出しが増えないこと）。Wi-Fi を戻して復帰することを確認する。

```bash
adb shell svc wifi enable
```

注意: Wi-Fi 経由で `adb connect` している場合は接続が切れる。USB 接続か、有線 LAN の Fire TV Cube で行うか、Fire TV のリモコンから Wi-Fi を切る操作で代替する。

- [ ] **Step 7: デバッグアクションを削除する**

Step 2 で追加した `ACTION_DEBUG_SET_GROUPS` と `EXTRA_GROUPS_JSON`、および `onStartCommand` の該当分岐を削除する。グループの作成は計画2 の TV UI が担う。

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

- [ ] **Step 8: 検証結果を記録する**

`docs/MANUAL-TEST.md` に、シナリオ1〜3の実行日時・観測した挙動・切替までの実測秒数を記録する。特にシナリオ2 の実測秒数は、仕様書 8章の「最大約90秒」の裏付けになる。

- [ ] **Step 9: 全テストを流して回帰がないことを確認する**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: 48 tests PASS、`BUILD SUCCESSFUL`

- [ ] **Step 10: コミット**

```bash
git add docs/MANUAL-TEST.md app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt
git commit -m "test: 実機での自動フェイルオーバー検証を記録

サーバ停止による即時切替、パケット drop による約90秒後の切替、
下層ネット断時に切替しないこと（安全策 S2）を実機で確認。
検証用のデバッグアクションは削除済み。"
```

---

## 計画1 完了条件

- [ ] `./gradlew :app:testDebugUnitTest` が 48 tests 全て PASS
- [ ] `./gradlew assembleDebug` が成功する
- [ ] Fire TV のホーム画面にアイコンが出て、リモコンで起動できる
- [ ] 実機で候補1→候補2 の自動フェイルオーバーが2シナリオとも動く
- [ ] `docs/BUILD.md` と `docs/MANUAL-TEST.md` に再現手順と検証結果が記録されている

この時点で UI は既存のスマホ向けのまま。計画2 でこれを TV 向けに置き換える。
