# OpenConnect TV — Fire TV 向け OpenConnect クライアント

**English summary:** a Fire TV / Android TV front-end for the OpenConnect VPN
client, with priority-ordered failover groups. When the current server stops
responding, it automatically switches to the next candidate. Everything is
operable with the TV remote alone. This is a fork of
[openconnect/ics-openconnect](https://gitlab.com/openconnect/ics-openconnect)
(GPLv2); the VPN core is upstream's, the TV UI and the failover engine are new.
Source comments and docs are in Japanese.

---

## これは何か

Fire TV のリモコンだけで OpenConnect VPN を使うためのアプリです。上流の
[ics-openconnect](https://gitlab.com/openconnect/ics-openconnect) のフォークで、
**VPN の中核（`external/openconnect`, `app/src/main/java/app/openconnect` など）は
ほぼ手を加えていません。** 足したのは次の2つです。

1. **優先順位つきのフェイルオーバー**
   複数の接続先を1つのグループにまとめ、上から順に試します。接続中の接続先が
   応答しなくなったら、次の候補へ自動で切り替えます。グループ単位で ON/OFF できます。

2. **TV 向けの画面**
   リモコン（D-pad）だけで接続先の追加・編集・削除、グループの作成・並び替え、
   接続・切断、疎通確認の設定まで行えます。

## 上流からの変更点（GPLv2 §2(a) の表示）

上流のコードを変更・追加しています。主な内容:

| 追加・変更 | 場所 |
|---|---|
| フェイルオーバーの状態機械（純 Kotlin、Android API 非依存） | `app/src/main/java/net/openconnect_vpn/android/failover/` |
| TV 向け UI（Compose for TV） | `app/src/main/java/net/openconnect_vpn/android/tv/` |
| VPN の状態を UI へ流すための UUID 付きブロードキャスト | `app/src/main/java/net/openconnect_vpn/android/core/OpenVpnService.java` ほか |
| ランチャーの起動先を TV 画面へ変更、バナー・アイコン | `app/src/main/AndroidManifest.xml`, `app/src/main/res/` |
| `applicationId` を `net.openconnect_vpn.android.firetv` に変更（上流版と併存できる） | `app/build.gradle` |

上流の README は [README-upstream.md](README-upstream.md) に残してあります。

## 仕組み（要点）

- 接続先の死活は**2つの経路**で判定します。ひとつは VPN コアからの切断イベント、
  もうひとつは**トンネル越しの TCP 疎通確認**（宛先・間隔・失敗回数は設定画面で変更可）。
- 切り替えは必ず**2段階**で行います。「切断を要求 → 完了を確認 → 次を起動」。
  これを飛ばすと、放棄したはずの接続が後からトンネルを掴み、
  **画面の表示と実際の通信経路が食い違う**ため、状態機械側で強制しています。
- **認証に失敗した候補は自動再試行の対象から外します。** 無人で誤った資格情報を
  投げ続けてサーバ側でロックされるのを避けるためです。除外はユーザー操作での
  接続で解除されます。
- **ネットワークが無いときは切り替えません。** 回線断を接続先の障害と誤認しないためです。

## ビルド

Docker のみでビルドします（ローカルに Android SDK は要りません）。イメージは
ダイジェストで固定しています。

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$PWD":/app \
  -v "$HOME/.gradle-cache":/gradle-home \
  -e GRADLE_USER_HOME=/gradle-home -w /app \
  mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331 \
  sh gradlew :app:testDebugUnitTest assembleDebug --console=plain
```

成果物は `app/build/outputs/apk/debug/app-debug.apk` です。詳細は
[docs/BUILD.md](docs/BUILD.md) を参照してください。

## インストール（ADB でのサイドロード）

Fire TV の「設定 → My Fire TV → 開発者オプション」で ADB デバッグを有効にし、

```bash
adb connect <Fire TV の IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使い方

| したいこと | 操作 |
|---|---|
| 接続・切断 | 行に合わせて決定 |
| グループを編集（並び替え・自動切替 ON/OFF・削除） | グループの行を長押し |
| 接続先を編集 | 接続先の行に決定 |
| 接続先を削除 | 接続先の行を長押し → 確認 |
| 接続先を追加 | 一覧の一番下「＋ 接続先を追加」 |
| 疎通確認の設定 | 右上の「設定」 |

文字入力は、欄に合わせて決定するとソフトキーボードが出ます。**戻るボタンを2回**
押すと欄から抜けます（1回目でキーボードが閉じ、2回目で欄を出ます）。

## 既知の制限・注意

- **画面が消灯すると VPN は切断されます。** これは意図した仕様です。Fire TV は
  消灯中に Doze へ入ってアプリの処理を止めるため、「繋がっているつもりで
  実際は死んでいる」状態を作るより、明示的に切る方を選びました。点灯時に繋ぎ直します。
- **Fire TV の IME が描画しなくなることがあります。** 端末側の問題で、その場合は
  キーボードが一切出ません。**端末を再起動すると直ります**（実機で確認）。
- **`adb install -r` の直後は、ランチャーのタイルが空（四角＋プラス）になります。**
  端末を再起動すると正しいアイコンに戻ります。アプリの欠陥ではありません。
- **日本語の入力は想定していません。** サーバ URL の欄は ASCII 系の IME を要求します。
- 実機で未検証の項目があります。[docs/MANUAL-TEST.md](docs/MANUAL-TEST.md) に
  **確認済みと未確認を分けて**記載しています。

## テスト

```bash
# 上のビルドコマンドに含まれています
sh gradlew :app:testDebugUnitTest
```

フェイルオーバーの状態機械は Android に依存しない純 Kotlin なので、
JVM のユニットテストで挙動を固定しています。Compose の画面はユニットテストで
押さえられないため、実機での確認手順を [docs/MANUAL-TEST.md](docs/MANUAL-TEST.md)
に残しています。

## ライセンス

GPLv2 です。上流と同じく [COPYING](COPYING) を参照してください。
上流のコードの著作権は各著作者に帰属します。

このフォークは無保証です。VPN の設定を誤ると通信が意図しない経路を通る可能性が
あります。自己責任でご利用ください。
