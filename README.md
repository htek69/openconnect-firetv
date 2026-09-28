# OpenConnect TV — Fire TV 向け OpenConnect クライアント

**English summary:** a Fire TV / Android TV front-end for the OpenConnect VPN
client, with priority-ordered failover groups. When the current server stops
responding, it automatically switches to the next candidate. Everything is
operable with the TV remote alone. This is a fork of
[openconnect/ics-openconnect](https://gitlab.com/openconnect/ics-openconnect)
(GPLv2); the VPN core is upstream's, the TV UI and the failover engine are new.
Source comments and docs are in Japanese.

**Published as a reference implementation and a record of Fire TV findings, not
as a supported product.** No support, no releases, no prebuilt APK; issues and
pull requests may go unanswered. Fork freely under the GPLv2.

**Requires an Android-based Fire OS device.** It does **not** run on Fire TV
devices that ship with Vega OS — Vega is not Android and cannot install Android
APKs at all. See 「対応端末」 below.

**The new code in this fork was written by AI** (Claude Code — Anthropic's
Claude Opus 5 / Sonnet 5), directed and reviewed by the repository owner. The
VPN core itself is upstream's, human-written code. No independent human security
audit has been performed. See the section 「このリポジトリの成り立ち」 below.

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

## このリポジトリの位置づけ（サポートはしません）

**動く参照実装と、実機で得た知見の置き場**として公開しています。製品ではありません。

- **サポートはしません。** Issue や Pull Request に返信できる保証はありません。
  リリースも配布もせず、ビルド済みの APK は置きません
- **動作の保証はありません。** 作者が自分の Fire TV で使うために作ったもので、
  確認したのはその1台（Fire TV Stick 4K 第1世代）だけです
- フォークや改変は GPLv2 の範囲で自由にどうぞ。**返答を待たずに進めてください**

### なぜ公開するのか

1. **上流には TV 用の画面がありません。** 上流は 2025年1月に
   ランチャーのバナーと `LEANBACK_LAUNCHER` を足しましたが、開いたあとの UI は
   スマホ向けのままです。リモコンだけで完結する画面は誰も作っていません
2. **優先順位つきのフェイルオーバーは上流に無い機能**です。皮の張り替えではありません
3. **Fire TV 固有の知見が、検索してもなかなか出てきません。** たとえば:
   - ソフトキーボードは**明示要求**でないとフルスクリーンの IME に拒否される
   - 祖先の `focusProperties` が子孫の `focusGroup()` を**無効化する**
   - ランチャーのバナーは `<application>` ではなく**アクティビティ**側が読まれる
   - 認証ダイアログは**サービスに bind した Activity** が無いと表示先を持たない

   いずれも実機でしか分からず、突き止めるのに相応の時間がかかりました。
   コミットメッセージと [docs/MANUAL-TEST.md](docs/MANUAL-TEST.md) には、
   **症状・切り分け・計測値**を残してあります。本アプリを使わない人にも
   役に立つかもしれません

4. GPLv2 の実務上の理由。**APK を誰かに渡すならソースの提供が必要**になるので、
   先に公開しておけばその都度考えずに済みます

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
- **初回ログインは利用者の操作が必要です。** 追加したばかりの接続先には資格情報が
  保存されていないので、認証ダイアログに人が答えないかぎり絶対に接続できません。
  そのため、**まだ一度もログインしていない接続先は自動切替の候補にしません**
  （ホームの一覧でその行に `初回ログインが必要` と出ます）。自動の巡回で無人のまま
  起動してしまうと、答える人が居ない認証待ちとみなして10秒で候補を切り替え、
  **利用者が入力している最中のダイアログを消してしまう**ためです。
- **初回ログインは、その接続先の行の「初回ログイン」から行います。**
  ホームの一覧で未ログインの接続先の行に合わせ、**→** で隣の `初回ログイン` に移り、
  **決定**を押します。確認が1枚出るので（この操作は**いまの VPN 接続を切ります**）、
  内容——切れる接続と、繋ぎ直されるグループ——を読んでから `接続する` を選んでください。
  そのあと認証画面が出るので、ユーザー名とパスワードを入力し、
  「パスワードを保存」にチェックを入れて進めます。
  実行した直後からこの操作は消え、行が `初回ログイン中` に変わります
  （**もう一度押す必要はありません。**押せてしまうと、2回目の接続指示が
  入力中の認証画面を閉じてしまうためです）。ログインに失敗した場合や
  取り消した場合は操作が戻るので、やり直せます。
  グループに接続する操作（グループの行の決定）は従来どおり**そのグループの先頭の
  接続先から**順に試すので、未ログインの接続先が2番目以降にあってもそこへは
  到達しません。この操作はその接続先を指定して有人で接続を始めるためのものです。
  なお、どのグループにも属していない接続先には `初回ログインが必要` の注記も
  この操作も出ません（自動切替の対象ではなく、飛ばされることもないためです）。
  接続先が複数のグループに属している場合は、グループ一覧の順で最初のものを
  経由して繋ぎ直します（どの経路でも認証情報の保存という目的は変わりません）。
  一度ログインして資格情報を保存すれば、その接続先は自動切替の通常の候補に戻ります
  （アプリの再起動は要りません）。なお接続先のサーバアドレスを変更すると保存済みの
  資格情報は消えるので、同じ手順でもう一度ログインが必要になります。
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

## 対応端末

**Android ベースの Fire OS が載った Fire TV 専用です。**

- 必要: Android 6.0（API 23）以上の Fire OS。確認は Fire TV Stick 4K
  （第1世代 / AFTMM、Fire OS 6.7.1.1 / Android 7.1.2、API 25）で行いました
- **Vega OS の Fire TV では動きません。** Vega OS は Android ではない別の OS で、
  アプリの作り（React Native 系）も実行環境も異なります。**Android の APK は
  インストールできず、本アプリも例外ではありません。** 新しい Fire TV には
  Vega OS を載せた機種があるため、購入・移行の前に確認してください
- 見分け方: 「設定 → My Fire TV → バージョン情報」に **Android のバージョンが
  出るなら Fire OS（本アプリの対象）**です。出ない場合や、開発者オプションに
  「ADB デバッグ」「不明なアプリのインストール」が見当たらない場合は、
  Vega OS の可能性が高く、対象外です

> Vega OS への対応予定はありません。本アプリは上流の ics-openconnect
> （Android アプリ）のフォークであり、移植は事実上の作り直しになります。

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

**個別の接続先は単体では接続できません。** 接続はグループ単位で行います
（接続先1件だけのグループでも構いません）。資格情報を保存していない接続先に
初めて接続すると、証明書の確認とログインを順に聞かれます。

## 既知の制限・注意

- **画面が消灯すると VPN は切断されます。** これは意図した仕様です。Fire TV は
  消灯中に Doze へ入ってアプリの処理を止めるため、「繋がっているつもりで
  実際は死んでいる」状態を作るより、明示的に切る方を選びました。点灯時に繋ぎ直します。
- **Fire TV の IME が描画しなくなることがあります。** 端末側の問題で、その場合は
  キーボードが一切出ません。**端末を再起動すると直ります**（実機で確認）。
- **`adb install -r` の直後は、ランチャーのタイルが空（四角＋プラス）になります。**
  端末を再起動すると正しいアイコンに戻ります。アプリの欠陥ではありません。
- **日本語の入力は想定していません。** サーバ URL の欄は ASCII 系の IME を要求します。
- **接続を止めると、入力中のログイン画面はその場で消えます。** 切断したとき、
  消灯したとき、グループ設定を変えたときです。意図した動作で、答える相手が
  居なくなった画面を残さないためですが、入力の途中で消えると驚くかもしれません。
- **旧画面（`詳細設定とログ`）を前面にしているあいだは、認証の待ち時間を
  延ばす保護が効きません。** 待ち時間の判定は TV 画面が前面にあるかどうかで
  行っているためです。旧画面からの単体接続は切替エンジンを通らないので、
  通常は問題になりません。
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

## このリポジトリの成り立ち（AI による実装であることの明示）

**このフォークで新しく書いたコードは、[Claude Code](https://claude.com/claude-code)
（Anthropic の Claude Opus 5 / Sonnet 5）が書いています。** 利用にあたって知って
おいていただくべきことなので、隠さず書きます。

内訳は次のとおりです。

| | 行数 | 誰が書いたか |
|---|---|---|
| TV UI とフェイルオーバー（新規 Kotlin） | 約 5,800 | **AI** |
| そのユニットテスト | 約 4,700 | **AI** |
| VPN の中核（Java / C、上流から継承） | 約 6,700＋ | 上流の作者（人間） |

**VPN の通信そのものを担う部分は上流のコードであり、AI は書いていません。**
`external/openconnect`（OpenConnect 本体）にも手を入れていません。

### 人間が担ったこと

AI が自律的に企画したものではありません。リポジトリの所有者が、

- 何を作るか（複数の接続先・自動切替・リモコンだけで操作）を決め、
- 設計上の判断（消灯時は VPN を切る、独自ソフトキーボードは作らない、
  画面の配色など）を下し、
- 実機を操作し、
- **AI が「直った」と誤って結論づけたのを、画面を見て2回指摘して正しました。**

2回とも AI 側の計測が不十分だったことが原因です（テレビが消灯していたのに
気づかず回帰と誤認した件、注入したキーイベントが IME に届かない既知の制約を
不具合と誤認した件）。**この種の間違いは起こりうる**という前提で読んでください。

### 検証の範囲

- ユニットテスト 295 件（フェイルオーバーの状態機械は Android 非依存の
  純 Kotlin なので、JVM のテストで挙動を固定しています）
- 実機（Fire TV Stick 4K / AFTMM, Fire OS 6.7.1.1 / Android 7.1.2）での確認。
  接続先の追加・初回ログイン・自動切替・削除・消灯と点灯の往復まで、
  リモコンだけで通しています。**確認済みと未確認を分けて**
  [docs/MANUAL-TEST.md](docs/MANUAL-TEST.md) に記載しています
- **Compose の画面はユニットテストで押さえられません。** フォーカスが消える、
  ボタンが画面外に落ちる、ダイアログが表示先を持たない——といった欠陥は
  すべて実機でしか見つかりませんでした。実際、開発中に見つかった欠陥の多くが
  その種類です
- コードレビューも AI が行っています。人間による独立した監査は受けていません

**VPN クライアントは通信の経路を左右するソフトウェアです。** 上記を踏まえ、
必要ならご自身でコードを確認したうえでご利用ください。

## ライセンス

GPLv2 です。上流と同じく [COPYING](COPYING) を参照してください。
上流のコードの著作権は各著作者に帰属します。

このフォークは無保証です。VPN の設定を誤ると通信が意図しない経路を通る可能性が
あります。自己責任でご利用ください。
