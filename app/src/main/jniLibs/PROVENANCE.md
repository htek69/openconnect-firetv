# ネイティブ成果物の出所（Provenance）

このディレクトリ（`app/src/main/jniLibs/`）配下の `.so` 8個、および
`app/src/main/assets/raw/{arm64-v8a,armeabi,x86,x86_64}/curl-bin` の計4個、
合わせて **12ファイル** は、このリポジトリでローカルにビルドしたものではない。

`make -C external` をこのマシン（Docker Desktop on Windows）で実行しようとしたが、
`registry.gitlab.com` のコンテナレジストリ（公式NDKビルドイメージの取得元）とのネットワーク
到達性がこの環境から不安定で、ローカルでのネイティブビルドを完走できなかった
（詳細は `docs/BUILD.md` を参照）。そのため、upstream の GitLab CI が過去に生成した
成功ビルドの成果物から、この12ファイルだけを抽出して配置している。

**Task 2 以降でこのプロジェクトの仕様（`docs/superpowers/specs/2026-09-19-firetv-openconnect-design.md`
§5）により、ネイティブ層は変更しない固定入力として扱われる。** つまりこの12ファイルは
「後で正式にリビルドする前提の仮置き」ではなく、「このプロジェクトが今後も使い続ける、
ピン留めされたバイナリ入力」である。

## 出所の詳細

| 項目 | 値 |
|---|---|
| upstream リポジトリ | `https://gitlab.com/openconnect/ics-openconnect` (project id `7773988`) |
| ビルド元コミット | `38925d3c79a25a765ac76a3a9157559246b2e74b` （このリポジトリの upstream/master マージ元コミットと完全一致） |
| コミット件名 | `gradle: add gradle-wrapper.jar SHA256 sum and update the binary.` |
| パイプライン | `#87` (pipeline id `1708030986`)、`https://gitlab.com/openconnect/ics-openconnect/-/pipelines/1708030986` |
| パイプライン実行日時 | 2025-03-10T10:11:44Z |
| ジョブ | `test/build-debug` (job id `9359217980`) |
| ジョブの成果物URL | `https://gitlab.com/api/v4/projects/7773988/jobs/9359217980/artifacts` |
| 成果物アーカイブ | `artifacts.zip`（17,213,303 bytes）を展開して得た `OpenConnect.debug.apk` |
| 抽出元APKの場所（このリポジトリ内） | `.superpowers/sdd/2026-09-19-firetv-openconnect-engine/upstream-ci-artifact/OpenConnect.debug.apk`（Gitには入れていない。抽出用の一時的な参照物） |
| 抽出元APKのSHA256 | `450bff104405cab5f07080869bbdddb6a9f4a3cb9ecf377699b3c89bddc08ae5` |
| 抽出元APKのサイズ | 32,448,919 bytes |

**重要な注記:** `test/build-debug` ジョブ自体はネイティブコードを一切コンパイルしていない。
upstream の `.gitlab-ci.yml` では、このジョブは `$BUILD_ENV_IMAGE`
（`registry.gitlab.com/openconnect/ics-openconnect:build-environment`、ネイティブ成果物が
あらかじめ焼き込まれた安定イメージ）を使い、`cp -r /prebuilt/app .` で焼き込み済みの
`app/` ツリーをコピーしてから `./gradlew assembleDebug` を実行しているだけである。
pipeline #87 では `external/**` に変更が無かったため、ネイティブビルドジョブ自体が
スキップされ、イメージに以前から焼き込まれていた（＝より以前のパイプラインでビルドされた）
ネイティブライブラリがそのまま使われている。つまり、この12ファイルの実際のビルド日時・
ビルドに使われたNDK/ツールチェーンのバージョンは、上記の pipeline #87 よりも古い可能性がある。
現時点でそれ以上正確な出所を遡ることはできなかった。

## 抽出したファイルの一覧とハッシュ

抽出コマンド:

```bash
unzip -o OpenConnect.debug.apk -d <展開先>
# <展開先>/lib/<abi>/lib{openconnect,stoken}.so
# <展開先>/assets/raw/<abi>/curl-bin
# をそれぞれ app/src/main/jniLibs/<abi>/ と app/src/main/assets/raw/<abi>/ にコピー
```

| ファイル | サイズ (bytes) | SHA256 |
|---|---|---|
| `app/src/main/jniLibs/arm64-v8a/libopenconnect.so` | 5038760 | `df08fc90cebac4070476847e22c628616a45432bd00ed8ef070b4b496f821b76` |
| `app/src/main/jniLibs/arm64-v8a/libstoken.so` | 1066520 | `cc681e00ed40da8f7e04589869b686346485310e84a26e7d2c504bf584b16c0c` |
| `app/src/main/jniLibs/armeabi-v7a/libopenconnect.so` | 4497764 | `032409ce5409c7709c19db2b126d6313b63fec9c81744d464ac85e3fdb809b05` |
| `app/src/main/jniLibs/armeabi-v7a/libstoken.so` | 927228 | `1ecd136af2b392c375f4ec53298196a1ae6ddb765f38129847eb27f6784dde6c` |
| `app/src/main/jniLibs/x86/libopenconnect.so` | 5201480 | `5249c838ab65eb4c532ba01a176da3ad09512ffa7f3ad751911540c8902fa4e9` |
| `app/src/main/jniLibs/x86/libstoken.so` | 976800 | `bd64baee81b407fe0d5db47987ea28236df275edd39aca9448d49e147bc70c4d` |
| `app/src/main/jniLibs/x86_64/libopenconnect.so` | 5061536 | `ff69960877924d7d0f4c2a978add6dec7b077e59228298ba7eb2e9498215b9d7` |
| `app/src/main/jniLibs/x86_64/libstoken.so` | 997240 | `dc3a2a5d853fcb079c866b07e9791c71a53f13b7b449cb48c838a177ea26b22f` |
| `app/src/main/assets/raw/arm64-v8a/curl-bin` | 4747384 | `8ee0536d692aaa0f21b0753db0582b223c4d04f8eb0c40283b1badff08c68ddb` |
| `app/src/main/assets/raw/armeabi/curl-bin` | 4323744 | `0c08ce3400f1e25614dd3f997475f1af0421f1a9d5caf9295eb84b4bf4e74afc` |
| `app/src/main/assets/raw/x86/curl-bin` | 4984472 | `564a7c8c7f716665f68dbe7552a311d35d3ee83a57f9f290fcfb2350b7ddeeed` |
| `app/src/main/assets/raw/x86_64/curl-bin` | 4761568 | `49620f664aba4044c9a76b46d48cc37373e7220ba52d6f1fb4ee12812060ac1a` |

（`curl-bin` はここでは通常ファイル（モード `100644`）としてコミットしてある。実行ビットは
立てていない。`OpenConnectManagementThread.java` がアプリの files ディレクトリへ展開する際に
`setExecutable(true)` を呼んで実行可能にするため、git 上のモードは実行時の挙動に影響しない。）

## ABI 命名についての注記

`armeabi` は upstream の `external/Makefile` が 32bit ARMv7 ビルド
（`-march=armv7-a -mthumb`）の出力先に意図的に使っている名前であり、`armeabi-v7a` という
名前のディレクトリは（upstream のビルド出力上は）存在しない。詳細は `docs/BUILD.md` を参照。

**Ruling 8（Task 2/3 修正）: `jniLibs/` 配下のみ `armeabi-v7a` に改名した。**
Compose 導入（Task 2）により `libandroidx.graphics.path.so` が APK の
`lib/armeabi-v7a/` に自動的にパッケージされるようになった結果、APK 内に
`lib/armeabi/`（このプロジェクトの `.so`）と `lib/armeabi-v7a/`（Compose由来）の
**2つの ABI ディレクトリが共存する状態になった。** Android は1アプリにつき1つの
ABI ディレクトリしか選択しないため、対象機（Fire TV Stick 4K, `abilist=armeabi-v7a,armeabi`）
では `armeabi-v7a` が優先され、そちらには openconnect/stoken の `.so` が無いという
`UnsatisfiedLinkError` を起こす状態になっていた。

この2つの `.so`（`libopenconnect.so` / `libstoken.so`）は実体としては ARMv7 向けバイナリ
であり（`file` で `ELF 32-bit LSB shared object, ARM, EABI5 ... built by NDK r27c` と確認済み。
NDK r27c は ARMv5 相当の `armeabi` ターゲットのサポート自体を r17 で廃止済みのため、
ARMv7 でしかありえない）、ディレクトリ名が `armeabi` だったのは前述のとおり upstream の
`external/Makefile`（`NDK_ARCH_arm := armeabi` だが `TRIPLET_arm := armv7a-linux-androideabi`）
の慣習的な誤命名に過ぎない。そのため **`app/src/main/jniLibs/armeabi/` を
`app/src/main/jniLibs/armeabi-v7a/` へ改名するだけで安全に解決できる**（バイナリの
再ビルドや変換は一切不要、`git mv` によるパス変更のみ。ファイルはバイト単位で同一のため、
上表のハッシュは変更していない）。

**`app/src/main/assets/raw/armeabi/` は改名していない。** こちらは Android プラットフォームの
ABI 選択ではなく、アプリ自身のコード（`AssetExtractor.getArch()`）が
`System.getProperty("os.arch")` を見て解決しており、32bit ARM 機（`os.arch=armv7l`）では
常に文字列 `"armeabi"` を返す実装になっている。もしこちらも `armeabi-v7a` に改名すると
`curl-bin` の展開先パスがコード側の期待と一致しなくなり、実行時に静かに失敗する。
`jniLibs/` と `assets/raw/` は異なる解決規則を持つため、意図的に非対称な扱いにしている。
