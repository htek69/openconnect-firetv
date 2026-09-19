# ビルド手順の記録（Task 1: フォークとネイティブビルドの検証）

このドキュメントは、仕様書のリスク **RK1**（`make -C external` が現行環境で通るか）を検証した記録である。

## 結論（先に書く）

> **RK1 は未解消のまま残っている。** フォークの取り込み（Ruling 2）は完全に成功したが、
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
