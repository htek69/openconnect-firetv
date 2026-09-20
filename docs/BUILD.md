# ビルド手順の記録（Task 1: フォークとネイティブビルドの検証）

このドキュメントは、仕様書のリスク **RK1**（`make -C external` が現行環境で通るか）を検証した記録である。

## 結論（2026-09-20 更新、Ruling 6 反映後）

> **RK1 の扱いはコントローラの Ruling 6 により変更された。** 仕様書 §5 によりネイティブ層は
> 今後も変更しない固定入力として扱うことになったため、「`make -C external` がこの環境で通るか」
> という当初の RK1 は検証目標から外れた。代わりに以下の2点が Task 1 の完了条件になった。
>
> 1. **ネイティブ成果物（.so 8個 + curl-bin 4個）を出所を明記した上でリポジトリに固定コミットする。**
>    → 完了。`app/src/main/jniLibs/PROVENANCE.md` に出所とSHA256を記録済み（コミット `6f381eb`）。
> 2. **このマシン上でローカルの Gradle ビルドが実際に動き、我々のソースから APK を生成できること。**
>    → **完了。** `sh gradlew assembleDebug` がこのマシン上の Docker コンテナ内で成功し
>    （`BUILD SUCCESSFUL in 42s`）、生成された `app-debug.apk` の中に固定した8個の `.so`
>    （4 ABI）と4個の `curl-bin` が正しく入っていることを `unzip -l` で確認した。
>    手順の詳細はセクション7を参照。
>
> 以前の版（このセクションの下、セクション0〜6）に書いた「RK1 は未解消」という記述は
> **Ruling 6 以前の状態の記録として、そのまま残してある**（歴史的経緯・診断の記録として価値があるため）。
> 今回のタスクの最終的な合否判断はセクション7の内容に基づく。

---

<details>
<summary>以下は Ruling 6 以前（2026-09-19時点）の記録。折りたたみ表示。</summary>

## 結論（旧版、参考情報）

> フォークの取り込み（Ruling 2）は完全に成功したが、
> ネイティブビルド（`make -C external`）はこのマシン上で最後まで完走させることができなかった。
> 原因は当初「帯域そのものが極端に低い」ことだったが、コントローラによるネットワーク切り替え
> （VPN再接続）後は「特定のCDN/レジストリ（`registry.gitlab.com` のコンテナレジストリ、
> Debian/Alpine/Fedoraの各パッケージミラー）への転送が不安定・低速・断続的に停止する」という、
> より原因の特定しにくい形の問題として残った。Docker Hub 由来のイメージ（`busybox`, `fedora:41`）は
> 問題なく pull できることを確認しており、環境全体が壊れているわけではない。
>
> このため、実際に動作する `app-debug.apk` は取得できたが、**それは GitLab CI が過去にビルドした
> 成果物であり、このマシン上で `make -C external` を実行して得たものではない。**
> `app/src/main/jniLibs/` は空のままである（後述）。

---

## 0. 環境

- ホスト: Windows 11 Pro、`C:\Users\htek6\claude\firetv-openconnect`
- Docker: Docker Desktop 4.76.0 / Engine 29.5.2、`linux/amd64`、WSL2 バックエンド
- リポジトリ: git ブランチ `firetv`
- `core.autocrlf=false` / `core.eol=lf` / `.gitattributes` に `* -text` を設定済み（CRLF破損防止、検証済み・問題なし）

WSL2 Ubuntu は使用しなかった（コントローラの事前調査により、autoconf等未導入・`sudo`が対話パスワード必須・
WSL2からDockerデーモンに到達不可、と判明済みのため）。Windows側のDocker Desktopを使用した。

---

## 1. フォークの取り込み（Ruling 2） — 完全に成功

```bash
cd /c/Users/htek6/claude/firetv-openconnect
git remote add upstream https://gitlab.com/openconnect/ics-openconnect.git
git fetch upstream
git commit -m "chore: CRLF破損防止の.gitattributesとビルド成果物のgitignoreを追加"
git merge upstream/master --allow-unrelated-histories --no-edit
git add .gitignore   # add/add コンフリクトを合成して解決
git commit --no-edit
git submodule update --init --recursive
```

- upstream HEAD: `38925d3c79a25a765ac76a3a9157559246b2e74b`
- `.gitignore` のみ merge コンフリクト（想定通り）。両方の除外パターンを合成して解決。
- `git submodule status`:
  ```
   70d1e79d1e55849dfc71dcc199b1edb535b547e4 external/openconnect (v9.21-25-g70d1e79d)
   bc25aa48922064881f17201c6302ab2640e36763 external/stoken ()
  ```
  （その後 upstream 側の更新により `external/openconnect` は `v9.12-201-gf17fe20d`、
  `external/stoken` は `v0.92-34-gbc25aa4` 相当に進んでいることをコントローラが確認済み）

### LF 検証

```bash
grep -c $'\r' external/Makefile gradlew misc/fetch.sh \
    external/openconnect/autogen.sh external/stoken/autogen.sh \
    app/src/main/java/net/openconnect_vpn/android/Application.java \
    external/openconnect/configure.ac
```
→ 全て `0`。**CRLF汚染なし。** `gradle-wrapper.jar`（バイナリ）も `unzip -l` で正常に読めることを確認済み。

---

## 2. ネイティブビルド（`make -C external`） — 未完走

### 2-1. upstream CI の構成の把握

`.gitlab-ci.yml` を読んで判明したこと：

- ネイティブ依存のビルドは `registry.gitlab.com/openconnect/build-images:openconnect-android-ndk-r27c`
  （プリビルド済みNDK環境イメージ）上で `make -C external install-$TARGET NDK=$NDK_DIR` を
  アーキ毎に実行している。
- `misc/Dockerfile`（`fedora:41` ベース）は **ネイティブビルド用ではない**。Gradleビルド専用ジョブが使う
  イメージで、`COPY artifacts/app /prebuilt/app` により「別ジョブで既にビルド済みの成果物」を
  焼き込む前提になっている。単体ではネイティブコンパイルを一切行わない。

つまり、upstream 自身の設計として「ネイティブビルドをローカルで再現する」ための単体Dockerfileは
このリポジトリには存在せず、`registry.gitlab.com` 上のプリビルド済みイメージに依存している。

### 2-2. 第1フェーズ：帯域が極端に低かった期間（コントローラのVPN切断前）

`docker pull registry.gitlab.com/openconnect/build-images:openconnect-android-ndk-r27c` や
`docker build -f misc/Dockerfile .` を試したが、新規レイヤーの取得が25分監視しても1バイトも
進まなかった。無関係の小イメージ（`busybox`）でも同様。実測すると、コンテナ内からの生の
HTTPS転送も **15〜65 KB/s** 程度しか出ておらず、`docker info` の
`HTTP Proxy: http.docker.internal:3128` を経由している可能性が高い状態だった。
この段階でGitLab CIの成果物取得（`test/build-debug` ジョブ、17.2MB）にフォールバックし、
本物の（ただしCIビルドの）`app-debug.apk` を取得した。詳細は本ファイルの旧版・タスク報告書を参照。

### 2-3. 第2フェーズ：コントローラがVPNを再接続した後

コントローラより「人間がVPNを再接続し、ネットワーク経路が変わった。ダウンロードは通るはず」との
連絡を受け、再検証した。

**確認できたこと（良い方向の変化）：**

- コンテナ内からの生の単発HTTPS転送は大幅に高速化（Cloudflareのテストで実測 ~2-3MB/s+）。
- `docker pull busybox:latest`（Docker Hub）は瞬時に成功。
- `docker pull fedora:41`（Docker Hub、レイヤー約170〜200MB）も、**60秒では終わらないが
  280秒以内には確実に完了する**ことを確認（`docker` CLIは非TTY出力だと、レイヤーが
  完全に終わるまで何も表示しないため、「進んでいないように見えて実は進んでいる」ことがあると判明）。

**依然としてブロックされたこと：**

- `docker pull registry.gitlab.com/openconnect/build-images:openconnect-android-ndk-r27c`
  （NDKイメージ、残りレイヤーが 273MB + 703MB）は、30分以上監視しても
  `docker system df` 上のイメージ総サイズが一切増えず、完走しなかった。
  レジストリのマニフェスト取得（数KB）は一瞬で終わるが、実データ（blob）の転送だけが進まない。
- 生のHTTPSで同じ blob（`cdn.registry.gitlab-static.net` 経由）を直接叩くテストでは、
  ある時点では ~150KB/s で読めたが、別の時点では 25秒でタイムアウトして全く読めなかった。
  **`registry.gitlab.com`／GitLabのCDN配信自体が、この環境から見て不安定・低速である**
  （Docker Hub由来のCDNは安定して速いのとは対照的）。
- パッケージマネージャ経由のインストールも同様に不安定だった：
  - Debian (`apt-get update`, https ミラーに書き換え済み): 複数回試したが応答なしのままタイムアウト。
  - Alpine (`apk update`): 同様にタイムアウト。
  - Fedora (`dnf install ...`): **完全に停止はしないが極端に遅い**
    （実測 883 B/s でリポジトリメタデータを取得しており、実用的な時間で完了しない）。
- これらは「一つ前のコマンドが `timeout` で強制終了された際に残ったゾンビコンテナが
  次のDocker操作と資源を奪い合っていた」ことが一因である場合があり、実際に
  `docker ps -a` で残存コンテナを `docker rm -f` した直後は `docker pull busybox` が
  瞬時に成功する、という再現も確認した。ただしこのクリーンアップ後でも
  `registry.gitlab.com` のNDKイメージ pull と各種パッケージマネージャの動作は
  改善しなかったため、コンテナの資源競合だけが原因ではない。

### 2-4. 判断

上記の通り、複数の独立した経路（公式NDKイメージのpull、Debian/Alpine/Fedoraの各パッケージ取得、
GitLab CDNからの生blob取得）のすべてで、**GitLab関連またはLinuxディストリのパッケージミラー関連の
転送だけが**一貫して不安定・低速だった一方、Docker Hub由来の転送は安定して速かった。
これは「回線が全体的に遅い」という単純な話ではなく、「このネットワーク経路から特定の
CDN/レジストリ群への到達性・スループットが悪い」という、このエージェントの権限では
特定・解消できない環境要因だと判断した。

このため、**「今のコードに対して今の環境で `make -C external` が通ること」の証跡は得られなかった。**
`app/src/main/jniLibs/` は作成されておらず、`app/src/main/assets/raw/` にも
`noarch/` 以下のスクリプト（gitに元から入っているもの）以外の内容（各ABI向けの
`libopenconnect.so` / `libstoken.so` / `curl-bin`）は一切生成されていない。

```bash
$ ls app/src/main/jniLibs
ls: cannot access 'app/src/main/jniLibs': No such file or directory

$ find app/src/main/assets/raw -type f
app/src/main/assets/raw/noarch/android_csd_anyconnect.sh
app/src/main/assets/raw/noarch/android_csd_gp.sh
app/src/main/assets/raw/noarch/android_csd_nc.sh
```

---

## 3. 採用した経路：GitLab CI 成果物のダウンロード（ブリーフの「最終手段」）

タスクブリーフに明記されていた最終手段を採用した。**これは "misc/Dockerfile を使ったビルド" ではない。**
自前で用意した `python:3.11-slim` コンテナから GitLab の公開REST APIを直接叩いて、
過去に成功した CI パイプラインの成果物（ビルド済み `.apk`）をダウンロードしただけである。
なぜ upstream 自身の `misc/Dockerfile` や公式NDKイメージが使えなかったかは、上のセクション2で
説明した通り（それらは docker pull に依存しており、その pull 自体がこの環境から
`registry.gitlab.com` に対して安定して機能しなかったため）。

### 3-1. 対象パイプラインの特定

```bash
curl https://gitlab.com/api/v4/projects/openconnect%2Fics-openconnect        # → project id = 7773988
curl "https://gitlab.com/api/v4/projects/7773988/pipelines?per_page=10&order_by=id&sort=desc"
```

最新（かつ全体でも最新）の成功パイプラインは **id=1708030986（iid 87）**、コミットは
`38925d3c79a25a765ac76a3a9157559246b2e74b` — このリポジトリに merge した upstream/master の
HEAD と完全一致。このプロジェクトはこのpipeline以降CIが実行されていない
（`external/**` に変更が無い限りネイティブビルドジョブはスキップされる仕組みのため）。

`test/build-debug` ジョブ（id=9359217980）の artifacts.zip、サイズ 17,213,303 bytes (16.4MiB)。
メタデータ上「期限切れ」表示だったが、実際には302リダイレクト→GCS由来CDNで200 OK、実体を取得できた。

### 3-2. ダウンロード

同じ環境要因（GitLab CDNの不安定さ）により、素直な単発の `wget` では30%地点で無応答のまま
停止した。**リトライ＋レジューム（`wget -c`）を明示的なループで回す**ことで完走させた：

```bash
URL="https://gitlab.com/api/v4/projects/7773988/jobs/9359217980/artifacts"
TARGET=17213303
i=0
while [ $i -lt 200 ]; do
  i=$((i+1))
  cur=$(stat -c%s artifacts.zip 2>/dev/null || echo 0)
  [ "$cur" -ge "$TARGET" ] && { echo "COMPLETE at $cur bytes after $i attempts"; break; }
  wget -c -T 30 -O artifacts.zip "$URL"
  sleep 1
done
```
→ **3回の接続断・再開を経て完走**（`COMPLETE at 17213303 bytes after 3 attempts`）。

### 3-3. なぜこれが「ローカルビルドの証跡」にならないか

`test/build-debug` ジョブは `$BUILD_ENV_IMAGE`（ネイティブ成果物があらかじめ焼き込まれた
安定イメージ、`cp -r /prebuilt/app .` 後に `gradlew assembleDebug` を実行するだけ）を使っている。
この pipeline では `external/**` に変更がなかったため、ネイティブビルドジョブ自体がスキップされ、
イメージに以前から焼き込まれていたネイティブライブラリがそのまま使われた。
**つまりこの経路は「今のコードに対して今の環境で `make -C external` が通るか」を一切検証していない。**

---

## 4. 取得したAPKの検証

- 取得元: GitLab CI pipeline #87 (`38925d3c`), job `test/build-debug` (id 9359217980)
- 配置先: `app/build/outputs/apk/debug/app-debug.apk`（`.gitignore` の `*.apk` によりコミット対象外）
- SHA256: `450bff104405cab5f07080869bbdddb6a9f4a3cb9ecf377699b3c89bddc08ae5`
- サイズ: 32,448,919 bytes (約31MB)

### 4-1. ABI ディレクトリの確認（Fire TV Stick 4K 向け、コーディネータからの追加要求）

対象機: Fire TV Stick 4K (1st gen, AFTMM, mantis), Fire OS 6.7.1.1 / Android 7.1.2 (API25),
`ro.product.cpu.abi=armeabi-v7a`, `ro.product.cpu.abilist=armeabi-v7a,armeabi`（32bit専用機）。

APK内部の `lib/` をダンプ（`unzip -l app-debug.apk`）：

| ABIディレクトリ | .soファイル数 | 内訳 |
|---|---|---|
| **armeabi** | **2** | libopenconnect.so (4,497,764 B), libstoken.so (927,228 B) |
| arm64-v8a | 2 | libopenconnect.so (5,038,760 B), libstoken.so (1,066,520 B) |
| x86 | 2 | libopenconnect.so (5,201,480 B), libstoken.so (976,800 B) |
| x86_64 | 2 | libopenconnect.so (5,061,536 B), libstoken.so (997,240 B) |

**`armeabi-v7a` という名前のディレクトリは存在しない。** `armeabi` はある。

**命名の注記（重要）:** `external/Makefile` の `NDK_ARCH_arm := armeabi` という定義により、
このプロジェクトは 32bit ARMv7 ビルド（`-march=armv7-a -mthumb`、実体は armeabi-v7a 相当のコード）の
出力先ディレクトリ名を、**`armeabi-v7a` ではなく `armeabi`** にしている
（`misc/download-artifacts.sh` の旧フォールバックスクリプトでも同じ命名）。これは upstream の
意図的な仕様であり、ビルドの不具合ではない。

Fire TV Stick 4K の `ro.product.cpu.abilist` には `armeabi-v7a` に加えて `armeabi` 自体も
含まれている。`app/build.gradle` に `ndk.abiFilters` や `packagingOptions` によるABI絞り込みは
一切無く、`jniLibs` 配下のディレクトリ名がそのまま APK の `lib/<name>/` に入るだけなので、
Androidのネイティブライブラリ選択ロジック（`abilist` を順に見て最初に一致する `lib/<abi>/` を採用）上は
`armeabi` ディレクトリでも当該デバイスに解決される**はず**である。

**ただし、これは実機で最終確認されたものではない**（Step 6 は本タスクのスコープ外であり、
ADB接続や実機インストールは一切行っていない）。もし実機で `armeabi` ディレクトリが
無視される・インストールに失敗する等の事象が起きた場合は、`app/build.gradle` に
明示的な `ndk { abiFilters 'armeabi', 'armeabi-v7a', ... }` や、`armeabi` を
複製して `armeabi-v7a` という名前でも配置する対応が必要になる可能性がある。

### 4-2. assets の確認

```
assets/raw/noarch/android_csd_anyconnect.sh
assets/raw/noarch/android_csd_gp.sh
assets/raw/noarch/android_csd_nc.sh
assets/raw/armeabi/curl-bin      (4,323,744 B)
assets/raw/arm64-v8a/curl-bin    (4,747,384 B)
assets/raw/x86_64/curl-bin       (4,761,568 B)
assets/raw/x86/curl-bin          (4,984,472 B)
```
（これもAPK内部の展開結果であり、リポジトリの `app/src/main/assets/` 自体には反映していない。
リポジトリ側は「ネイティブビルドを実行していない」という事実をそのまま残すため、意図的に
`noarch/` 以下のみのソース状態のままにしてある。）

---

## 5. 詰まった点まとめ（両フェーズ通じて）

| # | 詰まった点 | 原因 | 対処 |
|---|---|---|---|
| 1 | `.gitignore` が merge で add/add コンフリクト | 双方が独自の除外設定を追加済みだった | 手動で合成 |
| 2 | （第1フェーズ）`docker pull`・`docker build` が新規レイヤーで全く進まない | ホスト全体の実効帯域が15〜65KB/sと極端に低かった | ローカルネイティブビルドを断念、CI成果物へフォールバック |
| 3 | （第2フェーズ）ネットワーク改善後も `registry.gitlab.com` の pull だけ進まない | GitLabのCDN配信がこの経路から見て不安定（Docker Hub由来は安定して速い） | 複数回切り分けたが解消できず。RK1は未解消のまま記録 |
| 4 | `timeout N docker run ...` がタイムアウトすると、コンテナ自体は残り続ける | `timeout` はクライアントプロセスのみを終了させ、デーモン側のコンテナは止めない | 都度 `docker ps -a` で確認して `docker rm -f` | 
| 5 | 残存コンテナが後続のDocker操作と資源を奪い合い、一時的に `docker pull busybox` すら止まって見えた | ゾンビコンテナの蓄積 | クリーンアップ後は解消（ただしGitLab関連の遅さ自体は別問題として残存） |
| 6 | `apt-get`/`apk`/`dnf` がいずれも極端に遅い、または無応答 | Debian/Alpine/Fedoraの各パッケージミラーへの到達性がこの環境から悪い（生のwget/curl単発リクエストは動くこともある） | 都度確認したが解消できず。ローカルにautotoolsをインストールする経路は断念 |
| 7 | GitLab CI成果物(17.2MB)の単発ダウンロードも30%地点で無応答に | 同じGitLab CDN不安定性 | `wget -c` によるレジューム・リトライループで完走（3回の再接続） |
| 8 | Git Bashの `docker run -v /path` がWindowsパスに誤変換される | MSYSパス変換 | `MSYS_NO_PATHCONV=1` を指定 |

---

## 6. 今後の再現・解消に向けて

- **RK1 の最終的な解消には、`registry.gitlab.com` のコンテナレジストリに対して安定した帯域が出る
  ネットワーク環境が必要。** Docker Hub は安定して速かったため、環境そのものやDockerの設定が
  一般に壊れているわけではない。GitLabのCDN（`cdn.registry.gitlab-static.net` /
  `cdn.artifacts.gitlab-static.net`）や各Linuxディストリのパッケージミラーとの相性が悪い
  ネットワーク（プロキシ、DNS、あるいは特定CDNへの経路）である可能性が高い。
- 再現手順そのもの（コマンド列）は以下の通りで、環境の相性さえ良ければそのまま通るはずである：
  ```bash
  docker pull registry.gitlab.com/openconnect/build-images:openconnect-android-ndk-r27c
  docker run --rm -v "$PWD":/src -w /src \
      registry.gitlab.com/openconnect/build-images:openconnect-android-ndk-r27c \
      make -C external install NDK=/opt/android-sdk-linux_x86/android-ndk-r27c
  find app/src/main/jniLibs -name '*.so'
  ls app/src/main/assets/raw/

  mkdir -p artifacts/app  # misc/Dockerfile の COPY 対象を満たすためのプレースホルダ
  docker build -t ocbuild -f misc/Dockerfile .
  docker run --rm -v "$PWD":/app -w /app ocbuild sh gradlew assembleDebug
  ls -la app/build/outputs/apk/debug/app-debug.apk
  ```
  （`make -C external` のデフォルトターゲットは `install` で、`ARCH_LIST := arm arm64 x86 x86_64`
  全アーキを一度に処理する。）
- `docker pull`/`docker build` を試す際は、**直前に `docker ps -a` で残存コンテナが無いことを
  確認してから**実行すること（本タスクで確認した資源競合の再発を避けるため）。
- 途中で止まった場合は `timeout` で強制終了せず、`docker system df` のサイズ変化で
  「進んでいるが遅いだけ」なのか「本当に止まっている」のかを見極めること
  （非TTY出力の `docker pull` はレイヤー完了までログに何も出さないため、一見止まって見える）。

</details>

---

## 7. Ruling 6: ローカル Gradle ビルドの実施と成功記録（2026-09-20）

コントローラの Ruling 6 により、ネイティブ層（`external/`）はこれ以上いじらず固定入力として扱い、
その代わり「ローカルで `sh gradlew assembleDebug` が通り、我々の Java/Kotlin ソースから
ネイティブペイロード入りの APK が生成できること」を Task 1 の完了条件とすることになった。
本セクションはその実施記録。

### 7-1. 使用した Docker イメージと選定理由

**`mingc/android-build-box:latest`**（Docker Hub、約13.9GB）を使用した。

再現性のため、実際に使用したイメージのダイジェストを固定して記録する：

```
mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331
```

`:latest` タグは初回 pull 時の入口としての利便性でしかなく、指すイメージが将来変わりうる
（＝再現性を保証しない）。**再現するときは上記のダイジェスト形式で pull・run すること。**
タグ名（`mingc/android-build-box`、通称 latest）は人間が「どのイメージか」を識別するために
併記してあるだけで、実際に固定しているのはダイジェストの方である。

選定理由：
- Docker Hub 上のイメージは pull が安定して速い（セクション2で確認済みの経験則）。
- JDK 8/11/17/21 が全て `/usr/lib/jvm/` 配下に揃っており、`JAVA_HOME` を切り替えるだけで
  プロジェクトが要求する JDK 17 を選べる。
- Android SDK が `/opt/android-sdk` に既にインストール済みで、`build-tools;34.0.0` と
  `platforms;android-35`（このプロジェクトが要求するバージョン）を含む非常に広い範囲の
  バージョンが最初から揃っており、追加の `sdkmanager` 呼び出しが一切不要だった
  （`ls $ANDROID_HOME/build-tools` と `ls $ANDROID_HOME/platforms` で確認済み）。
- Ubuntu 22.04 (jammy) ベースで、`apt-get` 経由のパッケージ取得（後述の `ant`）が
  `archive.ubuntu.com` に対して安定して速かった（同じ apt でも Debian trixie のミラーは
  この環境から不安定だったのとは対照的）。

他に `mobiledevops/android-sdk-image`（3.52GB）と `eclipse-temurin:17-jdk`（448MB、JDKのみ）も
事前に pull 済みだったが、最終的に SDK・JDK が両方最初から揃っている `mingc/android-build-box`
だけで完結させた。**AGP 8.7.2 / Gradle 8.10.2 / minSdk 23 / targetSdk 34 / compileSdk 35 は
一切変更していない。**

### 7-2. つまずいた点1: `gradlew` 自身の Gradle 配布物ダウンロードがタイムアウトする

素直に `sh gradlew assembleDebug` を実行すると、Gradle Wrapper 自身が
`https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`（136,715,430 bytes、
実体は GitHub Releases 経由で Azure Blob Storage にリダイレクトされる）を取得しようとして
以下の例外で失敗した：

```
Exception in thread "main" java.io.IOException: Downloading from https://services.gradle.org/distributions/gradle-8.10.2-bin.zip failed: timeout (10000ms)
Caused by: java.net.SocketTimeoutException: Read timed out
	at org.gradle.wrapper.Install.forceFetch(SourceFile:2)
```

`gradle-wrapper.properties` の `networkTimeout=10000`（10秒）に対し、このネットワーク経路は
「概ね流れているが数秒〜十数秒単位で瞬断する」性質があり、Gradle Wrapper 自身のダウンローダには
リトライ機構が無いため、10秒待って1バイトも来ないとその場で失敗する。**`gradle-wrapper.properties`
は変更していない**（プロジェクトのバージョン指定を変えないという制約を守るため）。

代わりに、**Gradle Wrapper が期待するキャッシュ配置場所に、こちらで先に完全なファイルを
用意しておく**ことで、`gradlew` 自身のダウンロード処理を丸ごとスキップさせた。

1. `GRADLE_USER_HOME` をホスト側のディレクトリにバインドマウントする
   （コンテナを使い捨てにしても再ダウンロードにならないようにするため）。
2. 一度 `gradlew --version` を実行し、`$GRADLE_USER_HOME/wrapper/dists/gradle-8.10.2-bin/<hash>/`
   というディレクトリ名（`<hash>` は distributionUrl から計算される固定値、今回は
   `a04bxjujx95o3nb99gddekhwo`）を確認する。
3. **8並列の Range リクエスト**（`curl -H "Range: bytes=$start-$end"`、`-L` でリダイレクト追従必須）
   で `gradle-8.10.2-bin.zip` を分割ダウンロードし、`<hash>/gradle-8.10.2-bin.zip` として配置する。
   単一ストリームの `wget -c`（レジューム付き再試行ループ）でも最終的には完走できたが、
   このネットワークでは1ストリームあたり数十〜百数十KB/sしか出ない一方、8並列にすると
   合計で数MB/s近くまで伸びることを確認したため、8並列に切り替えた。
   （busybox の `wget --header` は HTTP 206 Partial Content を「エラー」として扱ってしまい
   Range リクエストが使えないため、`curl` が入っている `mingc/android-build-box` 側で実行した。）
4. ダウンロード完了後、`sha256sum` で `gradle-wrapper.properties` の
   `distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`
   と**完全一致**することを確認した。
5. `.lck` ロックファイルを削除し、`gradlew` を再実行 → ログに
   `Welcome to Gradle 8.10.2!` が即座に表示され、ダウンロード処理は発生しなかった。

```bash
# 1. hash ディレクトリ名を確認するための最初の（失敗してよい）実行
docker run --rm \
  -v "$PWD":/app -v "$HOME/.gradle-cache":/gradle-home \
  -w /app -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 -e GRADLE_USER_HOME=/gradle-home \
  mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331 \
  sh -c 'export PATH=$JAVA_HOME/bin:$PATH; sh gradlew --version'
# → /gradle-home/wrapper/dists/gradle-8.10.2-bin/<hash>/ ができる
# (上記イメージは "mingc/android-build-box:latest" として pull したものと同一。タグは
#  可読性のためにここに書いているだけで、固定しているのはダイジェストの方)

# 2. 8並列 Range ダウンロードでその中に gradle-8.10.2-bin.zip を完成させる（本文中スクリプト参照）
#    完了確認:
sha256sum "$HOME/.gradle-cache/wrapper/dists/gradle-8.10.2-bin/<hash>/gradle-8.10.2-bin.zip"
# → 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26 と一致すること
```

### 7-3. つまずいた点2: `LibOpenConnect` / wrapper jar が無くコンパイルエラー

Gradle 配布物の問題を解決した後の最初のビルドは、Java コンパイルの段階で失敗した：

```
/app/app/src/main/java/net/openconnect_vpn/android/core/OpenConnectManagementThread.java:762: error: cannot find symbol
    symbol:   variable LibOpenConnect
/app/app/src/main/java/net/openconnect_vpn/android/AuthFormHandler.java:118: error: cannot find symbol
    ... package LibOpenConnect does not exist
```

`app/build.gradle` は `implementation fileTree(dir: 'libs', include: ['*.jar'])` として
`app/libs/*.jar` を依存に含める設計だが、**`app/libs/` ディレクトリ自体が存在していなかった**。
`org.infradead.libopenconnect.LibOpenConnect` は `openconnect-wrapper.jar` の中身で、
これは `external/Makefile` の以下のルールで作られるとわかった：

```makefile
openconnect-wrapper.jar:
	cd openconnect/java && ant
	cp openconnect/java/dist/$@ .

stoken-wrapper.jar:
	cd stoken/java && ant
	cp stoken/java/dist/$@ .
```

これは **NDKクロスコンパイルとは無関係な、純粋な Java ビルド**（`external/openconnect/java/src/`
と `external/stoken/java/src/` は Java 純正のJNI宣言クラスで、サブモジュールに既に含まれている）
であることが分かった。ネイティブ .so 本体とは異なり、これは**このマシンでネットワーク無しに
再現可能**（`ant` のインストールにのみ Ubuntu の apt リポジトリへの接続が要る）。

```bash
# コンテナ内で（mingc/android-build-box は ant 未インストールだったので追加）
apt-get update -qq && apt-get install -y -qq ant
cd external/openconnect/java && ant   # → dist/openconnect-wrapper.jar
cd external/stoken/java && ant        # → dist/stoken-wrapper.jar
mkdir -p app/libs
cp external/openconnect/java/dist/openconnect-wrapper.jar app/libs/
cp external/stoken/java/dist/stoken-wrapper.jar app/libs/
```

`apt-get install ant` はこの環境（Ubuntu 22.04 ベースイメージ、`archive.ubuntu.com` ミラー）では
7秒程度で完了し、Debian trixie（`deb.debian.org`）で経験した無応答は再現しなかった。
**`app/libs/*.jar` はリポジトリにコミットしていない**（`.gitignore` の `*.jar` パターンに
従い、upstream の慣習と同じくビルド時生成物として扱う。サブモジュールのソースから
`ant` 一発で再現できるため、ネイティブ .so のようにピン留めする必要が無い）。

生成物のハッシュ（参考、コミットはしていない）：

| ファイル | サイズ | SHA256 |
|---|---|---|
| `app/libs/openconnect-wrapper.jar` | 6774 bytes (元jarの実サイズ) | `330115533ff5e6efc13a7d2d875671dfc69aa73afc25df42fe2158716d43ac8a` |
| `app/libs/stoken-wrapper.jar` | 2049 bytes | `9062cb5a7fbf9b3efd84f6c29743c90b47bcab00e236bb0c6b06c6938dc47336` |

（ant のバージョンは Ubuntu jammy の `ant 1.10.12-1`。ビルドソースは
`external/openconnect` サブモジュール `f17fe20d` 時点、`external/stoken` サブモジュール
`bc25aa4` 時点のもの。ピン留めした `.so` はこれより古い upstream CI 環境でビルドされた
可能性がある点はセクション4-1・PROVENANCE.md に記載の通り。ラッパー層はネイティブ側の
JNI シンボルが大きく変わらない限り後方互換であることが期待される薄いブリッジ層であり、
今回のビルド成功・APK内部構造の一致（後述7-4）がその実用上の整合性を裏付けている。）

### 7-4. 最終結果: ビルド成功

```bash
docker run -d --name gradle-build2 \
  -v "$PWD":/app -v "$HOME/.gradle-cache":/gradle-home \
  -w /app \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 -e ANDROID_HOME=/opt/android-sdk \
  -e GRADLE_USER_HOME=/gradle-home \
  mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331 \
  sh -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq && apt-get install -y -qq ant
    export PATH=$JAVA_HOME/bin:$PATH
    cd /app/external/openconnect/java && ant
    cd /app/external/stoken/java && ant
    mkdir -p /app/app/libs
    cp /app/external/openconnect/java/dist/openconnect-wrapper.jar /app/app/libs/
    cp /app/external/stoken/java/dist/stoken-wrapper.jar /app/app/libs/
    cd /app && sh gradlew assembleDebug --no-daemon -s
  '
```

出力の末尾：

```
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 42s
34 actionable tasks: 12 executed, 22 up-to-date
```

（初回はGradle配布物とAGP等の依存関係を新規に取得したため `test/build-debug` 相当の
`3m 42s`。`GRADLE_USER_HOME` を永続化した2回目の実行＝実際にAPKが生成できた回は
依存関係が全てキャッシュ済みのため `42s` だった。）

生成物: `app/build/outputs/apk/debug/app-debug.apk`（32,448,918 bytes、
SHA256 `1ec017a631c4b8cb5e0e4479daf2e665fae04d2687a2100aa3e61845b9402470`）。
`.gitignore` の `*.apk` によりコミット対象外（＝毎回ビルドし直す想定の成果物）。

### 7-5. 検証: 生成された APK にネイティブペイロードが入っていることの確認

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'lib/|assets/raw/'
```

```
       89  1981-01-01 01:01   assets/raw/noarch/android_csd_nc.sh
      821  1981-01-01 01:01   assets/raw/noarch/android_csd_anyconnect.sh
     1853  1981-01-01 01:01   assets/raw/noarch/android_csd_gp.sh
  927228  1981-01-01 01:01   lib/armeabi/libstoken.so
  976800  1981-01-01 01:01   lib/x86/libstoken.so
  997240  1981-01-01 01:01   lib/x86_64/libstoken.so
 1066520  1981-01-01 01:01   lib/arm64-v8a/libstoken.so
 4323744  1981-01-01 01:01   assets/raw/armeabi/curl-bin
 4497764  1981-01-01 01:01   lib/armeabi/libopenconnect.so
 4747384  1981-01-01 01:01   assets/raw/arm64-v8a/curl-bin
 4761568  1981-01-01 01:01   assets/raw/x86_64/curl-bin
 4984472  1981-01-01 01:01   assets/raw/x86/curl-bin
 5038760  1981-01-01 01:01   lib/arm64-v8a/libopenconnect.so
 5061536  1981-01-01 01:01   lib/x86_64/libopenconnect.so
 5201480  1981-01-01 01:01   lib/x86/libopenconnect.so
```

**8個の `.so`（4 ABI: armeabi, arm64-v8a, x86, x86_64 × libopenconnect.so, libstoken.so）と
4個の `curl-bin` が全て揃っている。** 各ファイルのサイズは `app/src/main/jniLibs/PROVENANCE.md`
に記載したピン留め元ファイルのサイズと1バイトも違わず一致しており、Gradle が
`app/src/main/jniLibs/` と `app/src/main/assets/raw/` からピン留め済みの成果物を
そのままパッケージしたことを裏付けている。**「ビルドは通るがネイティブペイロードが
入っていない」という失敗パターンではないことを確認済み。**

### 7-6. 詰まった点まとめ（セクション7分）

| # | 詰まった点 | 原因 | 対処 |
|---|---|---|---|
| 1 | `gradlew` 自身の Gradle 配布物ダウンロードが `SocketTimeoutException` で失敗 | `networkTimeout=10000`(10秒)に対しネットワークが数秒〜十数秒単位で瞬断する。Wrapperにリトライ機構が無い | `GRADLE_USER_HOME`をホスト側に永続化し、Wrapperが期待するキャッシュパスに8並列Range取得＋SHA256照合で配布物を事前配置。`gradle-wrapper.properties`は無変更 |
| 2 | busybox `wget --header "Range: ..."` が HTTP206をエラー扱い | busybox wgetはRangeレスポンスの206を成功と認識しない | `curl -H "Range: ..." -L`（`mingc/android-build-box`に同梱）を使用 |
| 3 | `docker run -v /path` がGit BashでWindowsパスに誤変換 | MSYSパス変換 | `MSYS_NO_PATHCONV=1` |
| 4 | `timeout N docker run`が期限切れてもコンテナが残り続け、後続の並列ダウンロードと衝突してファイルが壊れかけた | `timeout`はクライアントプロセスのみ終了、コンテナはデーモン側で動き続ける | `docker run -d --name <固定名>`で明示的に管理し、都度`docker ps -a`で確認・`docker rm -f`で片付けてから次を実行 |
| 5 | 最初のGradleビルドが`LibOpenConnect`シンボル無しでコンパイル失敗 | `app/libs/*.jar`（openconnect-wrapper.jar, stoken-wrapper.jar）が存在しなかった。これらはNDKとは無関係な純Javaビルド(`ant`)の成果物 | `external/openconnect/java`と`external/stoken/java`で`ant`を実行し`app/libs/`に配置（コミットはせず、`.gitignore`の`*.jar`規則通りビルド時生成物として扱う） |
| 6 | Debian trixieの`apt-get`は無応答だったが、Ubuntu jammyの`apt-get install ant`は問題なく動いた | ディストリ・ミラーによって到達性が異なる（GitLabのCDNやDebianミラーは不安定、Docker HubやUbuntuの公式ミラーは安定） | Ubuntuベースイメージ(`mingc/android-build-box`)を使用 |

## 8. debug 署名の安定化（実機で判明した必須事項）

**この設定をしないと、ビルドし直すたびに実機のアプリデータが消える。**

`debug.keystore` はコンテナ内で自動生成され、コンテナ終了とともに消える。次に
ビルドすると別の鍵が生成されるため署名が変わり、`adb install -r` が
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` で失敗する。
回避のためアンインストールすると、**ユーザーが手入力した VPN 認証情報も消える**
（`FORMDATA-*` は `shared_prefs/profile-<uuid>.xml` にあるため）。

このイメージは `ANDROID_SDK_HOME=/opt/android-sdk` を設定しているため、AGP は
`/opt/android-sdk/.android/debug.keystore` を見る（`$HOME/.android` ではない）。
そこをホスト側のディレクトリにバインドマウントして永続化する。

```bash
docker run --rm   -v "$PWD":/app   -v "/c/Users/htek6/.gradle-cache":/gradle-home   -v "/c/Users/htek6/.android-docker":/opt/android-sdk/.android   -e GRADLE_USER_HOME=/gradle-home -w /app   mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331   sh gradlew assembleDebug --console=plain
```

初回ビルド時に `/c/Users/htek6/.android-docker/debug.keystore` が生成され、
以降のビルドはすべて同じ鍵で署名される。実機で `adb install -r` がアンインストール
なしで通り、認証情報が保持されることを確認済み。

Git Bash から実行する場合は `MSYS_NO_PATHCONV=1` を前置すること（パス変換対策）。

### 実機インストール時の注意

**VPN 接続中に APK をインストールしてはならない。** 小さな adb コマンドは通るが、
40MB 規模の転送は `tun0` が経路を取るため
`connect error for write: closed` で失敗し、adb が offline になる。
先に切断する:

```bash
adb shell am force-stop net.openconnect_vpn.android.firetv
```

`onDestroy` → `killVPNThread(true)` → `mVPN.stopVPN()` が走ってトンネルが落ちる。
