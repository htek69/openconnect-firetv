# Fire TV 向け OpenConnect VPN クライアント 設計仕様書

作成日: 2026-09-19

## 1. 目的

Fire TV（Android ベースの Fire OS 機）上で動作する OpenConnect VPN クライアントを作る。
複数の接続先をリモコンだけで管理・切り替えでき、接続先が応答しなくなった場合は
優先順位に従って自動的に次の候補へ切り替わる。

## 2. 要件

### 2.1 確定要件

| # | 要件 | 決定内容 |
|---|---|---|
| R1 | 複数の接続先を登録できる | 接続先プロファイルを任意数登録。UUID で識別 |
| R2 | 接続先の追加・削除が簡単 | リモコン操作で完結する専用フォーム。削除は確認ダイアログ1枚 |
| R3 | 複数選択 | 複数の接続先を「優先順位付きフェイルオーバーグループ」としてまとめる |
| R4 | すべてリモコンで操作 | D-pad のみで全機能に到達。テキスト入力は Fire TV 標準ソフトキーボード |
| R5 | 自動フェイルオーバー | トンネル切断イベントと VPN 越しの疎通確認の両方で判定 |
| R6 | 自動切替の ON/OFF | グループ単位で切り替え可能 |
| R7 | 認証方式 | ユーザー名 + パスワードのみ。保存して無人再接続を可能にする |
| R8 | 配布 | ADB サイドロード。Amazon Appstore 公開はしない |

### 2.2 スコープ外（意図的に入れない）

- アプリ単位のスプリットトンネル
- クライアント証明書 / TOTP / SAML・SSO 認証
- Always-on VPN
- 速度・遅延の劣化を根拠とする切り替え
- フェイルバック（優先度の高い候補が復活しても自動では戻らない）
- `QSTileService`（TV に通知シェードがないため既存実装を削除）

いずれも後から追加できる形に境界を切る。

## 3. 前提条件と制約

### 3.1 デバイス

Android ベースの Fire OS 機を対象とする。

**重要な制約**: Amazon は 2025 年の Fire TV Stick 4K Select 以降、Linux ベースの
Vega OS へ移行した。Vega OS 機では ADB もサイドロードも一切使えず、Android APK は
原理的に動作しない。本アプリは Vega OS 機では利用できない。

### 3.2 開発環境（2026-09-19 時点の実測）

| 項目 | 状態 |
|---|---|
| ホスト OS | Windows 11 Pro |
| WSL2 (Ubuntu) | インストール済み |
| Docker Desktop | 稼働中 |
| Android Studio | インストール済み（JDK 17 同梱） |
| Android SDK | Android Studio 経由で導入済み。NDK r27c は追加取得が必要 |

### 3.3 フォーク元

`https://gitlab.com/openconnect/ics-openconnect`（GitLab が本家）

**注意**: GitHub の `cernekee/ics-openconnect` は 2019-06 で停止した古いミラー。
こちらをフォークしてはならない。

2026-09-19 時点の実測値:

| 項目 | 値 |
|---|---|
| 最終コミット | 2025-03-10 |
| minSdk / targetSdk / compileSdk | 23 / 34 / 35 |
| ツールチェーン | JDK 17 / NDK r27c / 現行 AGP |
| ライセンス | GPLv2 |
| CI | 直近パイプラインすべて success |
| AIDL | `buildFeatures { aidl true }` 有効 |

**ビルド上の制約**: README に明記されているとおり、ネイティブ依存のビルド
`make -C external` は Linux PC でのみ動作する。Windows 上では直接実行できない。

## 4. 方式選定

3案を比較し、案A を採用した。

### 案A: GitLab 版 ics-openconnect をフォークし、TV UI とフェイルオーバー層を追加（採用）

VPN クライアントで本当に難しいのは UI ではなくプロトコルとトンネル実装である。
案A は検証済みのコードでそこを押さえたうえで、本当に必要な部分（リモコン操作・
複数接続先・自動切替）だけを新規に書ける。

### 案B: 別アプリを作り、既存アプリを AIDL で遠隔操作（不採用）

既存の AIDL API は以下を提供する。

```
List<APIVpnProfile> getProfiles();
void startProfile(String profileUUID);
boolean addVPNProfile(String name, String config);
void disconnect();
void registerStatusCallback(IOpenVPNStatusCallback cb);
```

不採用の理由: この API は ics-openvpn から継承したコードで、インターフェース名も
`IOpenVPNAPIService` のまま。`addVPNProfile(name, config)` の `config` は
OpenVPN の設定ファイル文字列を前提としている可能性が高く、その場合は
要件 R2（接続先をリモコンで追加）が API 経由で実現できない。
加えて外部アプリ許可をスマホ向け設定画面でリモコン操作する必要があり、
アプリ2本構成になる。中核要件が落ちるリスクが高い。

### 案C: ゼロから自作（不採用）

openconnect / GnuTLS / libxml2 / zlib のクロスコンパイルと複数ステップ認証フォームの
XML 処理を全て自前で実装する必要があり、工数の大半をそこで消費して
得られるのは UI の綺麗さだけ。

## 5. アーキテクチャ

既存の VPN コアには一切手を入れない。

```
+----------------------------------------------+
|  新規: TV UI 層 (Kotlin + Compose for TV)    |
|   TvMainActivity / 接続先一覧 / 編集 / 設定  |
+-----------------------+----------------------+
                        |
+-----------------------v----------------------+
|  新規: フェイルオーバー層 (Kotlin)           |
|   FailoverController  <- 純ロジック・状態機械 |
|   HealthProbe         <- interface            |
|   VpnController       <- interface            |
|   NetworkGate         <- 下層ネット監視       |
|   FailoverService     <- フォアグラウンド常駐 |
|   GroupStore          <- グループ永続化       |
+-----------------------+----------------------+
                        | 既存 API のみ経由（改変なし）
+-----------------------v----------------------+
|  既存: OpenConnect コア (Java, 無改変)        |
|   OpenVpnService / OpenConnectManagementThread|
|   ProfileManager / AuthFormHandler            |
|   VPNConnector                                |
+-----------------------+----------------------+
                        | JNI
+-----------------------v----------------------+
|  既存: libopenconnect + GnuTLS (NDK, 無改変)  |
+----------------------------------------------+
```

### 5.1 既存コードの統合ポイント（調査で確認済み）

| 必要な機能 | 既存の資産 |
|---|---|
| 接続状態の通知 | `ACTION_VPN_STATUS` ブロードキャスト + `EXTRA_CONNECTION_STATE` + `EXTRA_UUID` |
| 状態定義 | `OpenConnectManagementThread.STATE_AUTHENTICATING(1)` / `USER_PROMPT(2)` / `AUTHENTICATED(3)` / `CONNECTING(4)` / `CONNECTED(5)` / `DISCONNECTED(6)` |
| 接続開始（UI から） | `GrantPermissionsActivity` に `getPackageName() + ".UUID"` を渡す。同 Activity が `VpnService.prepare()` を通し `OpenVpnService` を起動する |
| 接続開始（自動切替から） | `VpnService.prepare(ctx) == null`（許可済み）なら `OpenVpnService` を `EXTRA_UUID` 付きで直接 `startService` する。詳細は 5.2 |
| 切断 | `OpenVpnService.stopVPN()` |
| 通信量監視 | `VPNConnector` が `VPNStats`（rx/tx バイト・パケット）を1秒ごとにポーリング |
| トンネル死活の高速検知 | プロファイル設定 `dpd_override` / `dpd_value` |
| 無人での再認証 | `AuthFormHandler` が保存済み資格情報で非対話モードで通る |
| プロファイル永続化 | `profile-<uuid>.xml` 形式の個別 SharedPreferences |
| スリープ検知 | `DeviceStateReceiver` が `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` を処理 |

初回に `VpnService.prepare()` の許可を通せば、以降は `null` が返るため
自動切替が無人で回る。

### 5.2 自動切替からの接続開始（重要）

既存 UI は `GrantPermissionsActivity` を起動して接続するが、**フェイルオーバー層は
この経路を使えない**。Android 10 以降のバックグラウンドからの Activity 起動制限により、
フォアグラウンドサービスから Activity を起動しても確実に表示される保証がないためである。

フェイルオーバー層は `GrantPermissionsActivity.onActivityResult` が行っている処理を
直接実行する。

```kotlin
// 許可済みかを確認（未許可なら自動接続は不可能）
if (VpnService.prepare(context) != null) return ConnectResult.NeedsUserConsent

val intent = Intent(context, OpenVpnService::class.java)
intent.putExtra(OpenVpnService.EXTRA_UUID, uuid)
context.startService(intent)
```

`FailoverService` 自身がフォアグラウンドサービスであるため、そこからの
`startService` はバックグラウンド起動制限に抵触しない。

初回の VPN 許可だけは TV UI 上でユーザーに取得させ、以降は `VpnService.prepare()` が
`null` を返すため自動切替が無人で回る。未許可の状態を検知した場合は通知で
ユーザーに知らせ、状態機械は `IDLE` に留まる。

#### 他の VPN アプリによる許可の取り消し（運用上の注意）

Android は**同時に1つの VpnService しか許可しない**。別の VPN アプリが
`VpnService.prepare()` の承認を得ると、システムは本アプリの許可を取り消す。

検証端末には別の OpenConnect アプリ（`com.github.digitalsoftwaresolutions.openconnect`
v1.15、2025-10-01 にサイドロード）が既に入っており、これは実際に起こりうる。
パッケージ名が異なるためインストール上の衝突は無いが、**そちらで接続すると本アプリの
許可が失われ、自動フェイルオーバーが停止する**。

設計はこれを検知できる。`OpenConnectVpnController.connect()` が
`ConnectResult.NeedsUserConsent` を返し、`FailoverService` が通知を出して状態機械は
`IDLE` に留まる。ユーザーが本アプリを開いて再度許可するまで自動切替は再開しない。
この挙動は仕様として意図したものであり、黙って再接続を試み続けてはならない。

### 5.3 targetSdk 34 で必要なサービス宣言

`FailoverService` は Android 14 の要件により以下が必要になる。

- `android:foregroundServiceType="specialUse"`
- `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn_failover_monitoring" />`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />`
- `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`（API 33+、通知表示のため）

`POST_NOTIFICATIONS` が拒否されても通知が出ないだけでサービス自体は稼働する。

### 5.4 パッケージ構成

```
net/openconnect_vpn/android/
  tv/                          <- 新規
    TvMainActivity.kt
    ProfileListScreen.kt
    ProfileEditScreen.kt
    GroupEditScreen.kt
    SettingsScreen.kt
  failover/                    <- 新規
    FailoverController.kt      状態機械（Android 非依存）
    FailoverState.kt
    Candidate.kt
    FailoverConfig.kt
    HealthProbe.kt             interface
    TcpHealthProbe.kt          実装
    VpnController.kt           interface
    OpenConnectVpnController.kt
    NetworkGate.kt
    FailoverService.kt         フォアグラウンドサービス
    GroupStore.kt              永続化
```

### 5.5 既存ファイルへの変更（4点のみ）

1. `AndroidManifest.xml` — Leanback 対応、`FailoverService` 登録、ランチャー差し替え
2. `app/build.gradle` — Kotlin プラグインと Compose for TV の追加、`applicationIdSuffix`
3. ランチャー Activity を `TvMainActivity` に差し替え
4. `QSTileService.java` の削除とマニフェスト登録の除去（TV に通知シェードがない）

既存のスマホ向け UI は**削除せず残す**。認証が通らない場合の切り分けに既存のログ画面が
有効なため、設定画面の奥から到達できるようにする。

## 6. データモデル

既存の `VpnProfile`（接続先1件）はそのまま使い、その上にグループの概念を足す。

```kotlin
data class FailoverGroup(
    val id: String,
    val name: String,                  // 例: 自宅優先
    val memberUuids: List<String>,     // 並び順 = 優先順位
    val autoFailoverEnabled: Boolean,  // R6
    val config: FailoverConfig,
)

data class FailoverConfig(
    val probeIntervalSec: Int = 30,
    val probeTimeoutMs: Int = 5_000,
    val failureThreshold: Int = 3,      // 連続失敗で切替
    val graceAfterConnectSec: Int = 15, // 接続直後の猶予
    val connectTimeoutSec: Int = 45,    // Ruling 22: CONNECTING の上限
)
```

プローブ宛先はグループごとではなく**アプリ全体の設定に1箇所**だけ持つ
（既定値 `1.1.1.1:443`、設定画面から変更可能）。

グループは `SharedPreferences` に JSON で保存する。既存のプロファイル保存方式と揃え、
新たな DB 依存を持ち込まない。`memberUuids` は UUID 参照なので、プロファイル単体の
編集・削除と自然に共存する（削除済み UUID は読み込み時に除去）。

## 7. フェイルオーバー状態機械

```
                   +------+
      ユーザー接続 | IDLE |<------- ユーザーが明示的に切断
          +--------+------+         （自動切替も停止）
          v
  +---------------+  STATE_CONNECTED   +-----------+
  | CONNECTING(i) |------------------->| VERIFYING |
  +-------+-------+                    +-----+-----+
          |  接続失敗 / タイムアウト         | 初回プローブ成功
          |                                  v
          |                            +-----------+
          |              プローブ成功  |  HEALTHY  |
          |                       +--->+-----+-----+
          |                       |          | 連続 N 回失敗
          |                       +----------+ または予期しない切断
          v                                  v
  +--------------+                +----------------+
  | EXHAUSTED    |<---------------| FAILING_OVER   |
  | (バックオフ) |  全候補が尽きた +-------+--------+
  +------+-------+                        | Disconnected 確認
         | 指数バックオフ後に先頭から      | または 3秒タイムアウトで
         +---------------------------------+ 次候補へ接続開始
```

Ruling 25: 切断（`stopVPN()`）は `FAILING_OVER` **への入口**で要求する
（`EXHAUSTED` に入るときも `FAILING_OVER` を経由しており、そこで既に切断済みなので
2回目の切断はしない）。上の図は簡略化のため省略しているが、切替の契機が
`Disconnected` 自身だった場合（トンネルは既に落ちている）は待つ対象が無いので
`FAILING_OVER` を経由せず、`CONNECTING(i)` から直接 `CONNECTING(i+1)` へ進む。

状態遷移の要点（図では読み取りにくい部分を明示する）:

- `CONNECTING(i)` が接続失敗またはタイムアウトした場合は、`EXHAUSTED` には行かず
  **`FAILING_OVER` を経て次候補 `CONNECTING(i+1)` へ進む**
- `EXHAUSTED` に入るのは、**グループ内の全候補を一巡して全滅した場合のみ**
- `autoFailoverEnabled = false` のグループでは `FAILING_OVER` へ遷移せず、
  障害検知時に `IDLE`（切断状態）で停止する
- Ruling 22a: `CONNECTING(i)` の「タイムアウト」は `connectTimeoutSec`（既定45秒）を
  `Tick` で監視して検出する。ブラックホール宛先など既存コアから `RST` が返らない
  相手だと `STATE_CONNECTED` も `STATE_DISCONNECTED` も来ないため、これが無いと
  OS の TCP タイムアウト（約2分）まで状態機械側は何もできない。タイムアウトは
  ネットワーク障害であり認証失敗ではないので、S1 と違って**候補を除外しない**
- Ruling 24: **`stopVPN()` を呼ぶのは「これ以上何もしない」ときだけ**（`EXHAUSTED`
  に入る、または `autoFailoverEnabled = false` で `IDLE` に戻るとき）。次候補へ
  切り替えるだけのとき（`CONNECTING(i)` → `CONNECTING(i+1)`）は明示的な切断を
  要求しない。`OpenVpnService.onStartCommand` は新プロファイルを起動する前に
  自分で `killVPNThread(true)` して旧トンネルを止めるので、切替のたびに
  `stopVPN()` してから次を起動するのは冗長なだけでなく、停止処理の最中に
  新しい起動が割り込む競合を生む（実機で確認: 認証段階まで到達した健全な候補が、
  自分自身の停止処理に巻き込まれて切断され、S1 に認証失敗と誤判定されて
  焼き切られた）
- Ruling 25: 上の Ruling 24 の結論（切替では明示的な切断を要求しない）はここで
  覆った。`killVPNThread(true)` のスレッド join は 1000ms で打ち切られるため、
  接続処理の途中でブロックしているスレッドはその時間内に終わらず、後から接続を
  完了させることがある（実機で確認、7.1.2 参照）。Ruling 24 が警告した競合
  （停止処理の最中に新しい起動が割り込む）は今も本物の懸念だが、対処は
  「切断を*省く*」ことではなく「切断と起動を*直列化*する」ことである
  （`FAILING_OVER` でその候補の切断完了を待ってから次候補を起動する）。

### 7.1 必須の安全策

素直に実装すると必ず事故る4点。すべて実装必須。

**S1: 認証失敗と「サーバ無応答」を区別する**
認証エラーで無限リトライするとサーバ側でアカウントがロックされる。ただし
「認証失敗」と判定してよいのは、`STATE_AUTHENTICATING`（TLS接続が成立し、
サーバが認証フォームを返した）または `STATE_USER_PROMPT` まで**到達していながら**
`STATE_AUTHENTICATED` に届かずに `STATE_DISCONNECTED` した候補だけである。
TLS接続自体が成立せず認証段階に一度も到達していない候補（到達不能ホスト、
Wi-Fi瞬断など）は**ネットワーク障害**であり、認証失敗とは別物である。この区別を
せず「認証を通過せずに落ちたら除外」とだけ実装すると、一時的なネットワーク障害
一つでグループ内の全候補を焼き切り、プロセス生存中ずっと接続不能になる
（Ruling 23。実機検証で確認された不具合。旧版のこの節がこの区別を明記していな
かったことが原因）。認証段階まで到達していながら通過しなかった候補だけを、
そのセッション中は候補から**除外**し、通知でユーザーに知らせる。
自動化する VPN クライアントで最も危険な落とし穴。

**S2: 下層ネットワークが死んでいるときは切り替えない**
Fire TV の Wi-Fi 自体が切れている場合、候補を巡回しても全滅するだけで候補リストを
無駄に焼き切る。`NetworkGate` が `ConnectivityManager` で物理ネットワークの有無を見て、
下層が落ちている間は状態機械を一時停止し、復帰時に即座にプローブする。

**S3: ユーザーの意図的な切断を障害と誤認しない**
`STATE_DISCONNECTED` は「障害」と「ユーザーが切った」の両方で飛んでくる。
`VpnController` 経由の切断要求にフラグを立て、フラグが立っている切断は `IDLE` へ、
立っていない切断だけ `FAILING_OVER` へ遷移させる。このフラグを立てるのは実際に
ユーザー切断、および `EXHAUSTED`/`IDLE` へ諦めるときだけである。Ruling 25 で
`FAILING_OVER` に入るときにも `stopVPN()` を呼ぶようになったが、ここではこの
フラグを**立てない**。理由は「切替では `stopVPN()` を呼ばないから」ではなく、
その `Disconnected` は捨てる対象ではなく、次候補へ進む合図として観測しなければ
ならないからである（フラグを立てて S3 で握りつぶすと、切替の合図そのものが
消えてしまう）。

**S4: 接続直後の猶予期間を設ける**
ルート設定と DNS が整う前にプローブすると必ず失敗し、接続成功直後に切り替わる
無限ループになる。`VERIFYING` 状態と `graceAfterConnectSec` がこれを防ぐ:
猶予期間中はプローブそのものを打たない。ただし Ruling 22b により、この猶予は
**期間限定**であって「`VERIFYING` の間は失敗を無視し続ける」という意味ではない。
猶予期間を過ぎてから届いた連続失敗は `failureThreshold` 回で `HEALTHY` と同じ
基準で切替える（トンネルは張れたが疎通が無い、という状態に無期限に留まらない
ため）。これも認証失敗ではないので候補は除外しない。

### 7.1.1 状態通知は接続試行と照合する（S3 の前提）

既存コアの `ACTION_VPN_STATUS` ブロードキャストは接続状態とあわせて
プロファイル UUID を載せている（`vpnstatus.putExtra(EXTRA_UUID, mUUID)`）。
`FailoverEvent.VpnStateChanged` はこの UUID を保持し、**現在の候補と一致しない
イベントを無視する**。

これが無いと次の事故が起きる。`failOver` が次候補へ進んだ直後、**旧候補の切断
完了通知が遅れて届く**と、S3 の `expectingDisconnect` は既に次候補用にリセット
されているため、その通知が新候補の障害として解釈される。新候補はまだ認証を
通過していないので `currentCandidatePassedAuth` は false であり、結果として
**S1 が「一度も試していない候補」を認証失敗として除外する**。

この「旧候補の切断完了通知」は、Ruling 24 の間は既存コア（`OpenVpnService.
onStartCommand` の `killVPNThread(true)`）が旧トンネルを止めた結果として遅れて
届くものだった。Ruling 25 で切替の入口では明示的に `stopVPN()` を呼ぶようになった
今も、旧候補の切断完了通知が遅れて届く可能性そのものは無くなっていない
（`FAILING_OVER` の確認待ちにも `DISCONNECT_WAIT_MS` の上限があり、それを過ぎて
から届く通知は依然としてありうる。7.1.2 参照）。発生源がどちらであっても
UUID 照合は同じように機能するので、この安全策は Ruling 25 の後もそのまま
必要であり、むしろ切替の入口で毎回切断を要求するようになった分、必要性は
増している。

UUID 照合はこの一例だけでなく、「切替前の古い通知」という事故のクラス全体を排除する。
`uuid` が null のイベントは照合せず受理するが、これはテスト専用の経路であり、
本番の `VpnStatusBridge` は常に値を入れる。

**既知の限界（Ruling 29、裁定34 で範囲が縮んだ）**: 欠陥15（実機で確定）により、
UUID 照合単体では「切替中に旧スレッドの通知が新候補の UUID を載せて届く」事故を
防げないことが判明した。原因は既存コアの `wakeUpActivity()` が**サービスの現在の
`mUUID`**をブロードキャストに載せていたことである。裁定34 で、状態通知は
「その状態を生成したスレッドのプロファイル UUID」（`OpenVpnService.mStateUUID` /
`OpenConnectManagementThread.setState` が渡す `mProfile.getUUIDString()`）を
状態と対で運ぶように変更した。これにより既存コアは**状態と対の UUID を運ぶ**ので、
切替中に別候補の通知だと取り違える事故そのものは起きなくなった
（`FailoverController.sawCoreConnecting`（裁定31a）は、この修正が入る前の実機で
実際に踏んだ欠陥15の症状を止める状態機械側のガードとして今も有効に働く。
詳細は 7.1.2 参照）。

ただし `EXTRA_UUID` は依然として**候補（プロファイル）の UUID**であって、
**接続試行ごとの識別子**ではない。したがって、ある候補がタイムアウトで放棄され
（除外はされない）、`Exhausted` を経てバックオフ満了後に**同じ候補**へ再試行した
場合、そこへ放棄した旧い試行の通知が遅れて届くと、UUID は（同じ候補なので）
一致するため新しい試行の通知として受理されてしまう。このとき偶然 Ruling 23 の
条件（認証段階まで到達していながら通過しなかった）が満たされていると、S1 が
新しい試行を誤って認証失敗として除外する。これは裁定34 の対象外であり
（同一候補の再試行を区別するには接続試行ごとの世代番号のような、既存コアが
持たない情報が必要になる）、当初の Ruling 29 が懸念していたリスクとして
そのまま残る。発火条件は「同一候補への再試行後に旧い試行の通知が遅れて届く」
という狭いケースであり、影響もグループ全体の停止ではなく「1候補がセッション中
除外される」に留まる。実機検証で実際に発生したら再検討する。

### 7.1.2 `FailingOver` は必須の中間状態である（Ruling 25）

切替は2段階の操作である。障害を検知した状態機械は、まず現在の候補の切断を要求し、
`FailingOver` に入ってその候補の `Disconnected` を待ち、確認できてから次候補を起動する。

切断と起動を同時に行ってはならない。既存コアの `killVPNThread(true)` はスレッドの
join を 1000ms で打ち切るため、接続処理の途中でブロックしているスレッドはその時間内に
終わらず、後から接続を完了させる。そのトンネルの `Connected` は 7.1.1 の UUID 照合で
捨てられるので、**誰も管理していないトンネルが残り、状態機械は無関係に巡回し続ける**
（実機で観測: 状態が `Exhausted ⇄ Connecting 0` を巡回する間、放棄した候補の
`tun0` が生きていた）。

確認が来ない場合もある（ブロックしたスレッドが `mOC.cancel()` で解けない、
生きたスレッドがそもそも無い）。したがって待ち時間には上限を設ける
（`FailoverController.DISCONNECT_WAIT_MS` = 3000ms）。

Ruling 28（裁定39 で改訂）: `disconnect()` は `stopService` を**使わない**。
`stopService` は `BIND_AUTO_CREATE` の bind が残っている間サービスを破棄せず、既存 UI
（`MainActivity` / `StatusFragment` / `LogFragment` / `VPNProfileList`）が `VPNConnector`
経由で bind するため、legacy UI が前面にある間は切断要求が**完全に失われていた**。
VPN 許可の付与と認証ダイアログへの回答は legacy UI を開かないとできないので、
状態機械が最も忙しい場面と bind が存在する場面はむしろ重なる。

裁定39 により `disconnect()` は `OpenVpnService.ACTION_STOP_VPN` を `startService` で
送る。これは bind の有無に依存せず `onStartCommand` に届き、`stopVPN()` が実行される。
以前ここに「アプリ層で打てる追加の手は無い」と書いていたのは**誤りだった**
（既存 UI 自身が `service.stopVPN()` を直接呼んで切断していた）。

ただし切断要求が届くことと、スレッドが実際に終わることは別である。認証ダイアログで
`UserDialog.waitForResponse()` にブロックしたスレッドは `stopVPN()`（`mOC.cancel()`）
では解放されない。したがって `DISCONNECT_WAIT_MS` の上限で諦める経路は残り、その場合は
放棄した候補が後からトンネルを張る可能性も残る。これは既存コアのダイアログ機構に
起因する制約であり、フォローアップ課題として記録している。

例外は、切替の契機が `Disconnected` 自身だった場合である。このときトンネルは既に
落ちているので待つ対象が無く、`FailingOver` を経ずに次候補へ進む。

Ruling 31（欠陥15・実機で確定）: 上記の UUID 照合（7.1.1）は「ブロードキャストに
載る UUID がその状態を生成したスレッドのものである」ことを前提にしているが、
当初の既存コアの `wakeUpActivity()` は**サービスの現在の `mUUID`**をそのまま
載せていた。`OpenVpnService.onStartCommand` は新候補の起動時に `mUUID` を
書き換えて旧スレッドを止める（join は最大1秒）ため、1秒で終わらなかった旧スレッドが
後から `STATE_DISCONNECTED` を出すと、そのブロードキャストには**新しい候補の
UUID**が付き、UUID 照合では区別できなかった。実機で観測: v-server の切断を
要求して `FailingOver` に入り、3秒のタイムアウトで myvpn へ進んだところ、myvpn は
起動した同じ秒に `Exhausted` へ落とされた（myvpn の HTTPS 200 応答はその1秒後に
届いていた——myvpn 自身は何も失敗していなかった）。

- **裁定31a（`FailoverController.kt`、実際に欠陥15を止めているのはこれだけ）**:
  新しい試行は必ず `runVPN()` の冒頭で `STATE_CONNECTING` を送る。したがって
  自分の `Connecting` を観測する前に届いた `Disconnected` は旧スレッドのものと
  判断して無視する（`sawCoreConnecting` フラグ）。`FailingOver` 中は切断を待って
  いる候補自身が既に `Connecting` を出しているので、このフラグは確認の受け取りを
  妨げない。両方のブロードキャストが同じメインルーパーのキューを通るため、
  join 中に post された旧スレッドの `Disconnected` は、新スレッドの `Connecting`
  （`onStartCommand` の return 後にしか post されない）より必ず先に配送される。
  よってこのガードはこのケースを構造的に確実に捕まえる。
- **裁定31b（`OpenVpnService.java`）**: `mUUID` への代入を `killVPNThread(true)`
  の後に移した（それまでは局所変数 `newUUID` を使う）。**これ自体はブロード
  キャストに載る UUID を変えない**——`wakeUpActivity()` は `mHandler.post()`
  したランナブルの中で `mUUID` を読み、`onStartCommand` はそのランナブルと
  同じメインスレッドで走るため、ランナブルが実行されるのは `onStartCommand`
  が return した**後**であり、その時点では `mUUID` はどのみち新候補の値に
  なっている。代入順序をどう変えても防げない（裁定34 のレビューで判明）。
  ブロードキャストの UUID の正しさを保証しているのは裁定34
  （状態と対の `mStateUUID` を運ぶ）であり、欠陥15 を実際に捕まえているのは
  状態機械側の裁定31a の `sawCoreConnecting` である。
  **裁定42a（M8）で訂正**: 以前ここには「`mUUID = newUUID` は
  `killVPNThread(true)` の後、かつ `mVPNThread.start()` の前でなければならない
  不変条件がある」と書かれていたが、裁定34 以降これは誤りである。新スレッドの
  `STATE_CONNECTING` は `OpenConnectManagementThread.setState()` が
  `mProfile.getUUIDString()`（そのスレッド自身が担当するプロファイルの UUID）
  を渡すので、`mUUID` の値にも代入順序にも依存しない。`killVPNThread` →
  `doStopVPN` → `mVPN.stopVPN()` も `mUUID` を一切読まない。したがって
  この代入順序に機能的な要件は無い。ロールバックせずに残しているのは
  副次的な改善のためである: `profile == null` の早期 return 経路で、`mUUID`
  と `service_mUUID` が新候補の値で汚染されず古い値を保つ。

**裁定34（根本原因の修正）**: 欠陥15 の根本原因は、状態のブロードキャストが
「サービスの現在の `mUUID`」という、状態を生成したスレッドとは無関係の値を
運んでいたことである。`OpenVpnService` に `mStateUUID`（`mConnectionState` を
生成したスレッドのプロファイル UUID）を追加し、`setConnectionState(int state,
String uuid)` で状態と UUID を対で受け取って保存するようにした。
`OpenConnectManagementThread.setState()` は自分が担当する `mProfile.
getUUIDString()` を渡す。`wakeUpActivity()` は `mUUID` ではなく `mStateUUID`
（無ければ `mUUID` にフォールバック）をブロードキャストに載せる。既存の
`setConnectionState(int)` は `setConnectionState(state, mUUID)` へ委譲する
オーバーロードとして残し、`setStats` など状態を伴わずに `wakeUpActivity()` を
呼ぶ箇所は変更しない（現在の状態を再アナウンスするだけなので、その状態と対の
`mStateUUID` が載るのが正しい）。これにより「切替中に旧スレッドの通知が新候補の
UUID を載せる」事故はブロードキャストの時点で起きなくなる。裁定31a の
`sawCoreConnecting` は、この修正が入る前の実機で実際に踏んだ欠陥15の症状を
止める状態機械側のガードとして、引き続き有効に保つ（多層防御であり、
どちらか一方に依存しない）。裁定34 が対処しないケースは 7.1.1 の Ruling 29 を
参照。

Ruling 26（裁定30 で分岐自体は無くなった）: 当初は `FailingOver` 中に届いた
`UserPrompt` で `onUnattendedUserPrompt` に再入し、2度目の `disconnect()` と
`startedAtMs` のリセットが起き `DISCONNECT_WAIT_MS` の上限が上限でなくなる欠陥が
あった（既存コアは1つの認証フォームにつき `USER_PROMPT` を2回ブロードキャストする
ため、稀な競合ではなく Ruling 21 が絡む切替のほぼ全てで起きていた）。この分岐は
裁定30 で「`UserPrompt` を観測したら除外」自体をやめたことで丸ごと不要になった。
`FailingOver` 中の `UserPrompt` が現在も無害である理由は次の2つが揃っているから
である: (1) `onTick` の `FailingOver` 分岐は `onFailingOverTimeout` しか呼ばず、
`onUnattendedPromptTimeout` を経由しない（今この瞬間の主たる理由）。(2) `failOver`
が `FailingOver` へ入る直前に `userPromptSinceMs` を null にし、古いタイムスタンプを
持ち越さない（(1) の判定経路が将来変わっても、突入直後に古い値で即座に誤判定
しないための保険）。どちらか一方だけでは説明として不十分である。

Ruling 27: `FailoverService` の tick 間隔は `FailingOver` かつ下層ネットがある間
だけ 1 秒に詰める（S2 で保留中は待ちが進まないので細かく起きても意味が無い）。
また、ブロードキャストや `onStartCommand` のようなループ外からのイベントは
専用のチャネルでループを早起こしする（`dispatchExternal`）。ループ自身の
`dispatch` 呼び出し（`Tick`・`ProbeResult`・`UnderlyingNetworkChanged`）からは
この合図を出さない。出すとループが tick するたびに即座に自分自身を起こす
ビジーループになる。

### 7.2 バックオフ

全候補が枯渇したら `EXHAUSTED` に入り、30秒 -> 60秒 -> ... -> 上限10分の
指数バックオフで先頭候補から再試行する。

## 8. ヘルスチェック

`HealthProbe` インターフェースの背後に実装を隠す。

VpnService 配下では全トラフィックがトンネルに入るため、通常の `Socket` で対象へ
TCP connect するだけで「VPN 越しの疎通」を検証できる。ICMP は Android で
raw socket が使えないため採用しない。プローブは `withTimeout` 付きの
コルーチンで実行する。

判定は2系統の併用（R5）:

1. `ACTION_VPN_STATUS` による予期しない `STATE_DISCONNECTED` で即座に切替
2. 定期 TCP プローブが `failureThreshold` 回連続失敗したら切替
   （「繋がっているのに通らない」状態を検知する）

既定値（`probeIntervalSec = 30`、`failureThreshold = 3`）での**検知遅延は最大約90秒**
となる。トンネルが明示的に切断される経路（系統1）は即座に検知されるため、この90秒は
「繋がっているのに通らない」ケースにのみ適用される。動画視聴中の体感を優先して
短縮したい場合は `probeIntervalSec` を下げるが、誤爆のリスクと引き換えになる。

## 9. UI 設計

Compose for TV を使う（`minSdk 23` のため問題なし）。既存は Java + 旧
`PreferenceFragment` で D-pad 操作に適さないため、新規画面は最初から TV 向けに書く。

```
[ホーム] 接続先一覧
  + グループ「自宅優先」   接続中 (sv1.example.com)  [自動切替 ON]
  + グループ「予備」       未接続                    [自動切替 OFF]
  + -- 個別の接続先 --
  + sv1.example.com        接続中
  + sv2.example.com        未接続
  + [＋ 接続先を追加]

  決定     = 接続 / 切断のトグル
  長押し   = メニュー（編集・削除・グループに追加・複製）
  メニュー = 設定
```

リモコンで完結させるための具体策:

- すべてのフォーカス移動を D-pad で閉じる。Compose for TV の `Modifier.focusable()` と
  明示的な `focusRequester` を使い、画面遷移ごとに初期フォーカスを必ず設定する
  （TV UI で最も多いバグは「フォーカスがどこにも無い」状態）
- テキスト入力は Fire TV 標準のソフトキーボードに委ねる。入力項目は
  **サーバ URL と表示名の2つだけ**（理由は 9.1）
- 接続先の追加は1画面のフォームにまとめ、内部で `ProfileManager.create(hostname)` を
  呼んで `profile-<uuid>.xml` を生成する
- 削除は誤爆防止のため確認ダイアログを1枚挟む

### 9.1 資格情報を TV UI で事前入力できない理由（重要）

調査により、既存コアはユーザー名とパスワードを**固定のプロファイル設定として持っていない**
ことが判明した。`AuthFormHandler` は認証フォームの構造から鍵を組み立てて保存する。

```
キー = "FORMDATA-" + md5(フォーム構造) + "-" + md5(項目名 + ラベル)
```

フォーム構造はサーバが認証フォームを返してくるまで分からないため、**接続前に
資格情報を書き込むことは原理的にできない**。したがって接続先の追加は次の流れになる。

1. TV UI でサーバ URL と表示名を入力し、プロファイルを作成する
2. 初回接続時に既存の認証ダイアログが出る。ユーザー名とパスワードを入力し、
   「パスワードを保存」をチェックする
3. 以降は非対話で接続でき、自動フェイルオーバーが無人で回る

初回ログインダイアログは既存の Android View（`EditText` / `CheckBox`）で構成されており
D-pad でフォーカス移動できる。TV 向けに作り直さず、そのまま使う。

### 9.2 `batch_mode` を有効にすることが無人動作の必須条件（重要）

プロファイル設定 `batch_mode` の値が無人フェイルオーバーの成否を決める。
判定は `AuthFormHandler.java:470-471` にある。

```java
if ((batchMode == BATCH_MODE_EMPTY_ONLY && mAllFilled) ||
    batchMode == BATCH_MODE_ENABLED || !hasUserOptions) { /* ダイアログを出さない */ }
```

| 値 | 挙動 |
|---|---|
| `"enabled"` | **常に**ダイアログを出さない。保存済み資格情報が無ければ空欄を送信する |
| `"empty_only"` | 全項目が埋まっているときだけ出さない。空欄があればダイアログを出す |
| その他 | 毎回ダイアログを出す |

**必ず `batch_mode = "empty_only"` を設定する。`"enabled"` を使ってはならない。**

理由は実機で確認した。`"enabled"` は保存済み資格情報が無い状態でも空欄を送信するため、
**初回ログインが原理的に不可能になる**。実機のログにこう出た。

```
CALLBACK: onProcessAuthForm
AUTH: message 'Please enter your username.'    ← フォームは正常に受信できている
LIB: POST .../auth → HTTP/1.1 401 Authentication failed
LIB: Server requested Basic authentication which is disabled by default
Error obtaining cookie
```

`"empty_only"` なら初回はダイアログが出て、資格情報が保存されたあとは全項目が
埋まるためダイアログを出さず、無人で再接続できる。

#### `"empty_only"` の副作用と、その対処

保存済み資格情報が**拒否された**場合、`AuthFormHandler`（99-105行）は
`BATCH_MODE_DISABLED` に落ちてダイアログを表示する（`"enabled"` なら
`BATCH_MODE_ABORTED` で中断していた）。誰も見ていない TV では無期限に停止し、
接続は認証もせず切断もしないため、**安全策 S1 が発火しない**。

これを補うため、状態機械は `VpnCoreState.UserPrompt`（コアの `STATE_USER_PROMPT = 2`）を
観測する（Ruling 21）。ユーザーが自分で接続を指示した候補での `UserPrompt` は正当なので
状態を変えない。**自動切替または枯渇後の再試行で到達した候補**での `UserPrompt` は、
誰も見ていない可能性があるため注意して扱う必要がある。

**Ruling 21 の当初の実装は誤りだった（裁定30・欠陥14、実機で確定）。** 「`UserPrompt`
を観測した = 人間の操作が必要」と解釈し、観測した瞬間に候補を除外していた。しかし
`OpenConnectManagementThread.onProcessAuthForm`（229-244行）は `AuthFormHandler` が
ダイアログを出すかどうか決める**前**に、無条件に `setState(STATE_USER_PROMPT)` を送る。
`AuthFormHandler`（469-474行）には `batch_mode=empty_only` かつ全項目が埋まっていれば
ダイアログを出さずに `saveAndStore()` して即座に `OC_FORM_RESULT_OK` を返す経路がある。
つまり **`UserPrompt` は「認証フォームを処理中」という意味しかなく、保存済み資格情報で
完全に自動ログインする場合でも必ず1回（フォームごとに1回）観測される。** Ruling 21 の
当初の実装は、この区別ができず、**認証情報が完全に保存されている健全な候補を、
起動から約1秒で除外して切り替えていた**（実機シナリオ①、v-server で確認）。

裁定30 で「`UserPrompt` を観測した瞬間」から「`UserPrompt` から
`USER_PROMPT_WAIT_MS`（10秒）経っても次の状態（`Authenticating` など）へ進まない」
に判定基準を変えた。自動入力の経路は `promptUser` のローカル処理だけなのでミリ秒単位で
次の状態へ進み、ダイアログを出す経路は `waitForResponse()` で無期限にブロックして
進展が止まる。この形は S1 が本来守りたかったケース（保存済み資格情報がサーバに
拒否された場合）でも正しく働く: サーバが同じフォームを再送すると
`AuthFormHandler` のコンストラクタ（99-105行）が `formPfx.equals(lastFormDigest)` で
`BATCH_MODE_EMPTY_ONLY` を `BATCH_MODE_DISABLED` に落とすため、ダイアログが出て
進展が止まり、タイムアウトが正しく発火する。

**裁定33**: `USER_PROMPT_WAIT_MS` は `connectTimeoutSec`（Ruling 22、利用者が変更
できる）の半分を上回らないよう `FailoverController.userPromptWaitMs()` で実装上
クランプする。除外を伴う `UserPrompt` のタイムアウト判定は、除外を伴わない接続
タイムアウトより先に発火してほしい。後になると認証情報が間違っている候補が
除外されずに切り替わり、次の巡回でまた試されてサーバ側のアカウントロックを招く
（S1 が存在する理由）。ただし2つの期限は起点が違うため、この順序は**無条件には**
成り立たない: 接続タイムアウトは候補が接続を開始した瞬間を起点とするのに対し、
`UserPrompt` の待ち時間は `UserPrompt` が実際に届いた瞬間を起点とする。順序が
保証されるのは `UserPrompt` が候補開始から `connectTimeoutSec / 2` 以内に届いた
場合だけである。実際の認証フォームは接続直後に届くため実務上はほぼ常に成立するが、
無条件の保証ではない。

### 9.3 既存 UI が D-pad で操作できない原因（実機で計測）

同一コードベースの既存アプリ（`com.github.digitalsoftwaresolutions.openconnect` v1.15、
Activity は `app.openconnect.MainActivity` = ics-openconnect の旧パッケージ名）を
検証端末で `uiautomator dump` して計測した結果、原因が特定できた。

プロファイル一覧画面の実測値:

```
node 総数 40    clickable="true" 15    focusable="true" 7
```

クリック可能な15要素のうち**8要素が `focusable="false"`** で、D-pad では到達できない。

| D-pad で到達できる | D-pad で到達できない |
|---|---|
| `ActionBar$Tab` ×2 | **`vpn_list_item_left` ×4（プロファイルの行そのもの）** |
| 「追加」`TextView` | **`quickedit_settings` ×4（各行の編集ボタン）** |
| 設定 `ImageButton` | |
| `reconnect_button` | |

結果として、リモコンで到達できるのは「最後に使った接続先への再接続」ボタンだけになり、
**接続先を選び直すことも、プロファイルを編集・削除することもできない**。
これが本プロジェクトが解決すべき中核の問題である。

### 9.4 D-pad 到達性の客観的な受け入れ基準

9.3 の計測方法をそのまま受け入れ基準に使う。主観的な「操作できた気がする」ではなく、
次の条件を満たすことを機械的に検証する。

> **本アプリの各画面について、`clickable="true"` かつ `focusable="false"` である
> 要素が1つも存在しないこと。**

数の比較ではなく、**要素ごとの述語**で判定する。合計数の一致は偶然成立しうる
（到達不能な要素と、clickable でない focusable 要素が同数あれば一致してしまう）ため、
基準として不十分である。計測手順:

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > ui.xml
python3 - ui.xml <<'PY'
import sys, xml.etree.ElementTree as ET
bad = [n for n in ET.parse(sys.argv[1]).getroot().iter('node')
       if n.get('clickable') == 'true' and n.get('focusable') != 'true']
for n in bad:
    print("UNREACHABLE", n.get('resource-id') or n.get('class'), repr(n.get('text')))
print("violations:", len(bad))
PY
```

`violations: 0` が合格。1つでも出たら、その `resource-id` の要素はリモコンから
永久に到達できないため不具合として扱う。

Compose for TV の `Card` や `Button` は既定でフォーカス可能なため、この基準は
自然に満たされる見込みだが、`Modifier.clickable` を素の `Box` や `Row` に付けた場合は
違反しうる。画面を追加するたびに計測する。

## 10. Fire TV 固有の対応

**実測で判明した重要な事実**: フォーク元のマニフェストには TV 対応が**すでに入っている**。

- `<uses-feature android:name="android.software.leanback" android:required="false" />` 済
- `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />` 済
- `android:banner="@drawable/banner"` 済
- `MainActivity` に `android.intent.category.LEANBACK_LAUNCHER` 済

したがって本項で必要な作業は、**ランチャーのカテゴリを `MainActivity` から
`TvMainActivity` へ移設すること**だけである。フォーク元は元々 Fire TV 上でも
ホーム画面にアイコンが出る状態にある（当初想定していた作業の大半は不要）。

VPN 許可ダイアログはシステム標準のもので D-pad 操作可能。初回のみ通過すればよい。

スタンバイ対応: 既存 `DeviceStateReceiver` に相乗りし、`ACTION_SCREEN_OFF` で
プローブ間隔を延長、`ACTION_SCREEN_ON` で即プローブする。

## 11. ビルド・配布

ネイティブ層のビルドは WSL2 内で行う。ネイティブ成果物を Windows 側へ手作業で
持ち越す運用は、パス・改行・実行属性で事故るため採らない。

```
WSL2 Ubuntu 側
  git clone --recursive https://gitlab.com/openconnect/ics-openconnect
  apt: build-essential autoconf automake libtool git
  Android SDK (build-tools;34.0.0, platforms;android-35) + NDK r27c + JDK 17
  make -C external          # 初回のみ、30〜60分想定
      -> 成果物: jniLibs/ と assets/
  ./gradlew assembleDebug
      -> app-debug.apk
  adb connect <FireTVのIP>:5555
  adb install -r app-debug.apk
```

Windows 側に Android Studio が入っているため、日常のコード編集と Kotlin/Compose の
補完は Android Studio、ネイティブ層のビルドは WSL2 という併用も可能。その場合は
WSL2 側のクローンを Android Studio から開く（`\\wsl$\Ubuntu\...`）。ただし
Gradle ビルドの実行位置を Windows と WSL2 で混在させるとキャッシュが衝突するため、
**ビルドは常に WSL2 側で実行する**ことを規約とする。

Fire TV への `adb connect` は WSL2 から通る。

`make -C external` が詰まった場合の保険が2段:

1. リポジトリの `misc/Dockerfile` を使い Docker Desktop でビルドする
2. 最終手段として GitLab CI の成果物を取得する（直近パイプラインはすべて success）

`applicationId` に `.firetv` サフィックスを付け、F-Droid 版 OpenConnect と
同一端末に共存できるようにする。署名は debug キーで十分（サイドロード用途）。

## 12. エラー処理

| 事象 | 挙動 |
|---|---|
| 認証失敗 | その候補をセッション中除外し通知。リトライしない（S1） |
| サーバ証明書の検証エラー | 接続中止。既存の `CertWarningDialog` をそのまま使う（9.1 の認証ダイアログと同じ判断。D-pad で操作できることの確認のみ行う）。R7 のクライアント証明書認証とは別物 |
| 全候補が枯渇 | `EXHAUSTED`。指数バックオフで先頭から再試行 |
| 下層ネット断 | 切替せず一時停止。復帰時に即プローブ（S2） |
| スタンバイ | プローブ間隔を延長、復帰時に即プローブ |
| プロセス kill | フォアグラウンドサービスと `START_SERVICE_STICKY` で復帰し、最後の接続状態を復元 |

## 13. テスト戦略

`FailoverController` を Android 非依存の純粋 Kotlin にすることで、状態機械の
全遷移を JVM 単体テストで検証できる。`Clock` / `HealthProbe` / `VpnController` /
`NetworkGate` をすべて interface にし、テストではフェイクを注入する。
Robolectric もエミュレータも不要。

必ず書くテストケース:

1. 候補1が無応答なら候補2へ切替し、候補2が HEALTHY になる
2. 連続失敗が閾値未満で回復した場合は切替しない（誤爆防止）
3. 猶予期間中のプローブ失敗では切替しない（S4）
4. 認証失敗した候補は再試行されない（S1）
5. 下層ネット断中は切替が発生しない（S2）
6. ユーザーによる切断で `IDLE` に入り、自動切替が止まる（S3）
7. 全候補枯渇でバックオフ間隔が指数的に伸びる
8. `autoFailoverEnabled = false` のとき、障害時に切替せず切断状態で止まる（R6）
9. 削除済み UUID を含むグループを読み込むと、その UUID が除去される

実機検証は次の2シナリオを手動で通す:

- サーバ側で `ocserv` を停止する（トンネル切断の検知）
- サーバ側のファイアウォールでパケットを drop する（繋がっているのに通らない状態の検知）

UI は D-pad 操作の手動チェックリストで担保する。

## 14. 未解決事項・リスク

| # | 内容 | 対応 |
|---|---|---|
| RK1 | `make -C external` が現行の WSL2 Ubuntu で通るか未検証 | 実装の最初のステップで検証する。Docker と CI 成果物の保険が2段ある |
| RK2 | 既存 Java コードベースへの Kotlin/Compose 混在時のビルド設定 | 早期に空の Compose 画面を1枚ビルドして確認する |
| RK3 | `applicationId` 変更の影響 | **調査済み・低リスク**。Intent の extra キーは put 側（`VPNProfileList`）と get 側（`GrantPermissionsActivity`）の両方が `getPackageName()` を使うため自動的に整合する。`profile-<uuid>.xml` の名前にパッケージ名は含まれない。`namespace` は固定し `applicationIdSuffix` で対応する |
| RK4 | Fire TV スタンバイ中のフォアグラウンドサービス継続性 | 実機で長時間の放置テストを行う |
