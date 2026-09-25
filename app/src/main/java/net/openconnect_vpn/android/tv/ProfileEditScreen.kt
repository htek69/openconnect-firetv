package net.openconnect_vpn.android.tv

import android.text.InputType
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 接続先の追加・編集。
 *
 * 入力項目はサーバ URL と表示名の2つだけ。ユーザー名とパスワードは
 * 認証フォームの構造から鍵が決まるため接続前に書き込めず、初回接続時に
 * 既存の認証ダイアログで入力・保存する（仕様書 9.1）。
 *
 * 裁定66（Task 6 の判断の差し戻し）: 編集時にサーバ URL を読み取り専用にする案は
 * 一度採用されたが、差し戻した。理由はアドレスの打ち間違いが最も起きやすい失敗で
 * あり、Fire TV では文字入力が D-pad ＋ソフトキーボードで著しく遅いこと。
 * 読み取り専用だと1文字の訂正のために削除して作り直すことになり、
 * そのプロファイルが属するグループのメンバーシップも失う（[GroupStore] は
 * 存在しないメンバー参照を読み込み時に自動的に落とすため）。「簡単に
 * 追加・削除できる」（R2）の趣旨に反するので、編集可能にする。
 *
 * 裁定67: 当初はここを「無効になりうる」という注意書きだけに留めていたが、それは
 * 間違いだった。`AuthFormHandler.getFormPrefix`（`AuthFormHandler.java:166-173`）が
 * 組み立てる資格情報の鍵 `FORMDATA-<ダイジェスト>-` は認証フォームの**構造**
 * （各項目の type/name/label）だけから決まり、接続先サーバの identity を
 * 一切含まない。つまり同じ形の認証フォームを出す別サーバへアドレスを変更すると、
 * 「無効になるかもしれない」ではなく、**次回接続時に `batch_mode=empty_only` の
 * もとで資格情報がダイアログ無しで新サーバへ自動送信される**（利用者のパスワードが
 * 意図しないサーバへ渡るプライバシー事故）。そのため [ProfileRepository.updateServerAddress]
 * がアドレス変更のたびにこのプロファイル自身の prefs から `FORMDATA-`/`ACCEPTED-CERT-`
 * キーを消すようにした。ここの注意書きも「無効になることがある」ではなく
 * 「消す・次回は必ず聞かれる」という事実に合わせてある。
 *
 * 裁定68（実機で発見、裁定70で撤回）: 最初はテキスト欄にフォーカスを乗せたまま
 * `Modifier.onPreviewKeyEvent` で D-pad の UP/DOWN/CENTER をアプリ側から横取りしよう
 * とした。実機では効かなかった（裁定70参照）ため、この方式は撤回・削除した。
 *
 * 裁定69: 実機検証で、サーバ URL 欄に `test.example.com` と打つと日本語 IME を
 * 経由して `てst。えぁmpぇ。こm` に変換されてしまうことが判明した。URL 欄だけ
 * `InputType.TYPE_TEXT_VARIATION_URI` を指定する（詳細は URL 欄の [TvFieldRow]
 * 呼び出し部と裁定71を参照）。表示名欄は意図的に既定のまま
 * （日本語で名付けたいはずだから）。
 *
 * 裁定70（実機で発見した阻害欠陥）: `dumpsys` で確認したところ、フォーカスが乗った
 * `BasicTextField` は画面を開いた瞬間にソフトキーボードを要求し、Fire TV では
 * それが別ウィンドウ（`com.amazon.tv.ime/.FireTVIME`）としてアプリの上に乗って
 * D-pad を独占してしまう。アプリの Window にキーイベントが届かなくなるため、
 * 裁定68の `onPreviewKeyEvent` は原理的に効かなかった。テキスト欄そのものに
 * 初期フォーカスを持たせるのをやめ、[TvFieldRow]（ラベル＋現在値を表示する
 * フォーカス可能な行。CENTER で選んだときだけ内部の入力欄へフォーカスを移す）に
 * 置き換えた。D-pad のナビゲーション・CENTER での編集開始・BACK での離脱・
 * 保存への到達はこの時点で実機確認済みになった。
 *
 * 裁定71（実機で発見した阻害欠陥）: 裁定70の行方式に切り替えた後も、実際には
 * ソフトキーボードが**描画されない**ことが `dumpsys` で判明した
 * （`imeOptions` に `IME_FLAG_NO_FULLSCREEN` / `IME_FLAG_NO_EXTRACT_UI` が
 * 立っており、Fire TV の IME はフルスクリーンの extract エディタしか描画手段を
 * 持たないため、両方禁止されると何も描かない）。これは `BasicTextField` が既定で
 * 立てるフラグで、Compose に外す公開 API が無い。アプリの旧 UI のパスワード入力
 * （素の `EditText`）は同じ実機で実際にキーボードが出ることが確認されているため、
 * [TvFieldRow] の入力欄の実体を `AndroidView` 経由の `EditText` に置き換えた。
 * 詳細と設計判断は [TvFieldRow] の KDoc を参照。
 * `GroupEditScreen`（Task 7）でも自由入力欄が要る場合は [TvFieldRow] を
 * 再利用すること（この画面専用にしていない）。
 *
 * 裁定76（実機で再現した阻害欠陥）: 最後の入力行から `DPAD_DOWN` で
 * ボタン行に入ると、既定の2次元フォーカス探索では「キャンセル」に着地して
 * いた（`保存` には着地しない）。`保存` はこの画面で最も押される操作なので、
 * 利用者が自然に「下 → 決定」と押すと変更が保存されずに破棄される事故に
 * つながる。原因は Compose の既定の2次元フォーカス探索が、直前のフォーカス
 * 矩形（幅いっぱいの [TvFieldRow] の行）ともっとも近い候補をボタンの
 * 矩形の重なり・距離だけで機械的に選ぶことで、「意味的にどのボタンが
 * 既定であるべきか」という情報を一切持たないため。ボタンの並び順を
 * 保存→キャンセルにしていても、それだけでは探索結果を保証できない。
 * 最初の対策として、ボタン行に `Modifier.focusGroup()` と
 * `Modifier.focusProperties { enter = ... }` を付けたが、これは**実機では
 * 効かなかった**（裁定77参照）。
 *
 * 裁定77（実機で計測、裁定76の修正が効かないことを確認）: 裁定76の
 * `enter` を実機にインストールして計測したところ、`DOWN` は依然
 * 「キャンセル」に着地した。`FocusProperties.enter` は `FocusDirection.Enter`
 * （明示的な「グループへ入れ」という要求）に対してのみ参照される
 * プロパティであり、上下左右の2次元フォーカス探索がグループ内の要素を
 * 直接選ぶ経路（今回の `DOWN` はまさにこれ）では一切参照されない。
 * 2次元探索はあくまで `focusGroup()` の内側の候補を幾何的に評価して
 * 「キャンセル」を選び続けていた。
 *
 * 直し方を、グループへの「進入時」を横取りする方式（`enter`）から、
 * **ボタン行の直前の要素の `DOWN` を名指しで固定する方式**（`down`）に
 * 変えた。表示名 の行（[TvFieldRow]）に `downTarget = saveButtonFocus` を
 * 渡し、[TvFieldRow] 内部でその行の実際のフォーカス対象である `Card` に
 * `Modifier.focusProperties { down = saveButtonFocus }` を適用させている
 * （`FocusProperties.down` は `enter`/`exit` と違って素の [FocusRequester]
 * プロパティであり、2次元探索の `DOWN` から直接参照される。理由の詳細は
 * [TvFieldRow] の `downTarget` パラメータの KDoc を参照）。
 *
 * `focusGroup()` はボタン行に残した。`down` の指定は表示名の行という
 * **入る側**のノードに付けたものであり、ボタン行自身の `focusProperties`
 * には触れていないため、`focusGroup()` があってもなくても `down` の解決には
 * 影響しない。`LEFT`/`RIGHT`（ボタン間移動）・`UP`（ボタン行から表示名の行へ
 * 戻る）はいずれも実機で既に正しいと確認済みであり、このラウンドでは
 * `up`/`left`/`right` のいずれも一切変更していないので、既定の2次元探索の
 * ままそれらは維持される。`focusGroup()` 自体は方向探索の結果を書き換える
 * ものではなく（Tab 的な走査順序やフォーカスの進入/離脱点の境界を示すだけ）、
 * 今回 `enter` を外したこと以外に振る舞いを変える要素が無いことからも、
 * LEFT/RIGHT/UP には影響しないと判断した。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileEditScreen(
    profiles: ProfileRepository,
    editingUuid: String?,
    onDone: () -> Unit,
) {
    val existing = remember(editingUuid) {
        editingUuid?.let { uuid -> profiles.list().firstOrNull { it.uuid == uuid } }
    }

    var address by remember { mutableStateOf(existing?.serverAddress ?: "") }
    var displayName by remember { mutableStateOf(existing?.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // 裁定76/77: ボタン行への DOWN の着地先を「保存」に固定するための FocusRequester。
    val saveButtonFocus = remember { FocusRequester() }

    val isEditing = editingUuid != null

    // 裁定88（保険。欠陥の修正ではない）: 再レビューは「裁定86（L3）で常設の
    // 説明文を足したことで、編集モードでは `保存`/`キャンセル` が画面外へ
    // 落ちているはずだ」と計算した（裁定82/85 と同じ壊れ方の再生産）。
    // コントローラが実機（Fire TV Stick 4K、1080p）で
    // 実在の接続先（アドレスは伏せる。`vpn.example.com:4443` と同程度の長さ）を
    // 編集モードで開いて計測した結果、
    // **落ちていない**:
    //   注意書き（アドレス変更） [96,348][1824,440]
    //   表示名を空に…の注意書き  [96,658][1370,686]
    //   初回ログインの注意書き   [96,734][1824,794]
    //   保存 [128,866][185,899]  キャンセル [281,866][422,899]
    //   DOWN #2 → 保存 [96,834][217,930]、RIGHT → キャンセル、LEFT → 保存
    // つまり 1080px に対して下端にまだ約 180px の余裕がある。
    // したがってこの 1 行は**現在の欠陥を直すものではない**。ただし余裕が
    // 薄いこと自体は妥当な懸念であり、この画面族では同じ壊れ方が既に2度
    // （裁定82 の GroupEditScreen、裁定85 の SettingsScreen）実機で計測されて
    // いる。将来この画面へ文言を1つ足すだけで再発しうるので、裁定85 が
    // SettingsScreen に施したのと同じ1行を先に入れておく。
    // この画面の内容はすべて固定長（可変長のリストを持たない）ので、裁定82 の
    // LazyColumn + weight(1f) 構成にする理由は無い。Compose はフォーカス移動に
    // 追従してこの ScrollState をスクロールするため、裁定77 の
    // `downTarget = saveButtonFocus` による `保存` への着地も、裁定70 の
    // 「テキスト欄自身に初期フォーカスを置かない」もそのまま維持される
    // （verticalScroll はスクロール可能にするだけで、初期フォーカスの位置や
    // フォーカス探索の経路を変えない）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            if (!isEditing) "接続先を追加" else "接続先を編集",
            style = MaterialTheme.typography.headlineMedium,
        )

        // 裁定70: 画面全体でここだけ requestInitialFocus = true。テキスト欄では
        // なく「行」に初期フォーカスが乗るので、開いた瞬間にソフトキーボードは
        // 要求されない（TV では常にどこかへ初期フォーカスを置く必要があるが、
        // それがテキスト欄自身であってはならない、というのが今回の教訓）。
        TvFieldRow(
            value = address,
            onValueChange = { address = it; error = null },
            label = "サーバ URL（例: vpn.example.com）",
            // 裁定69/71: URI 用の inputType を指定する。表示名欄（下）は逆に
            // 日本語で名付けたいはずなので既定のまま指定しない。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            modifier = Modifier.fillMaxWidth(),
            requestInitialFocus = true,
        )
        if (isEditing) {
            // 裁定67: 「無効になることがあります」という可能性の言い方は誤りだった。
            // updateServerAddress はアドレスが変わるたびに保存済みの資格情報と
            // 証明書承認を必ず削除する（同じ認証フォーム構造の別サーバへ
            // 無言で送られるのを防ぐため）。起きることを断定形で伝える。
            //
            // F8（レビュー）: この文言は資格情報が消えること自体は正確だったが、
            // 「次の接続で改めてログインを求められる」だけでは足りなかった。
            // この接続先が自動切替グループのメンバーだった場合、消えた資格情報で
            // 無人の自動試行が認証ダイアログに到達すると、裁定30 により
            // userPromptWaitMs 経過後に「応答が無い」候補として除外され、
            // ユーザーが気付かないまま自動切替から外れ続ける。手動で一度この
            // 接続先へログインし直す（かつパスワードを保存する）までその状態が
            // 続くことを、断定形のまま利用者に伝える。
            Text(
                "サーバ URL を変更すると、保存済みのユーザー名・パスワードと" +
                    "サーバ証明書の承認をこの接続先から削除します。" +
                    "変更後、次に接続するときに改めてログイン情報の入力を求められます。" +
                    "この接続先が自動切替グループに含まれている場合、無人での自動試行は" +
                    "ログイン画面で応答を得られず、次に手動でこの接続先へログインして" +
                    "パスワードを保存するまで自動切替から外れます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TvFieldRow(
            value = displayName,
            onValueChange = { displayName = it },
            label = "表示名（任意）",
            modifier = Modifier.fillMaxWidth(),
            // 裁定77: この行がボタン行の直前の入力行。DOWN の着地先を「保存」に
            // 名指しで固定する（詳細は上のクラス KDoc の裁定77参照）。
            downTarget = saveButtonFocus,
        )

        // 裁定86（L3）: 表示名を空にして保存したときの挙動を先に伝える。
        // 以前はここが無言の no-op で、空にして保存すると成功として画面が
        // 閉じるのに名前は変わらなかった（[ProfileRepository.rename] の
        // 裁定86 の節を参照）。現在は空欄を「既定の名前に戻す」意味に解釈して
        // サーバ URL から導出した名前を書き込むので、その規則を画面に出す。
        // 保存は成功すると即座に画面を閉じるため、事後のメッセージでは
        // 利用者に届かない（TV では特に）。
        Text(
            "表示名を空にして保存すると、サーバ URL から作った既定の名前" +
                "（例: vpn.example.com なら Example）に戻ります。",
            style = MaterialTheme.typography.bodySmall,
        )

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Text(
            "保存後、この接続先に初めて接続するときにユーザー名とパスワードの入力を求められます。" +
                "そこで「パスワードを保存」をチェックすると、以降は自動で再接続できます。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // 裁定77: DOWN の着地先固定は、この行に入る `enter` ではなく、
            // 直前の表示名の行（上の TvFieldRow の downTarget）側の `down` で
            // 行っている（`enter` は2次元探索の DOWN では参照されないため、
            // 実機で効かなかった。詳細はクラス KDoc の裁定77参照）。
            // `focusGroup()` は LEFT/RIGHT/UP の挙動を変えないので残す。
            modifier = Modifier.focusGroup(),
        ) {
            Button(
                onClick = {
                    when (val result = ServerAddressValidator.validate(address)) {
                        is AddressValidation.Invalid ->
                            error = ProfileEditMessages.messageFor(result.reason)

                        AddressValidation.Valid -> {
                            val normalized = ServerAddressValidator.normalize(address)
                            if (!isEditing) {
                                profiles.create(normalized, displayName)
                                onDone()
                            } else {
                                val uuid = requireNotNull(editingUuid)
                                // 裁定66: アドレスが変わっていなければ書き込みを増やさない。
                                // 変わっていれば updateServerAddress を呼び、その成否を
                                // outcomeForEdit に渡す（対象が見つからなければ false）。
                                val addressChanged = normalized != existing?.serverAddress
                                val addressUpdated = !addressChanged ||
                                    profiles.updateServerAddress(uuid, normalized)
                                val renamed = profiles.rename(uuid, displayName)
                                val batchModeOk = renamed && profiles.ensureBatchMode(uuid)
                                val outcome = ProfileEditMessages.outcomeForEdit(
                                    addressUpdated = addressUpdated,
                                    renamed = renamed,
                                    batchModeEnsured = batchModeOk,
                                )
                                when (outcome) {
                                    ProfileEditMessages.SaveOutcome.Success -> onDone()
                                    is ProfileEditMessages.SaveOutcome.Error ->
                                        error = outcome.message
                                }
                            }
                        }
                    }
                },
                // 裁定77: このボタンが、表示名の行（TvFieldRow の downTarget）に
                // 付けた `focusProperties { down = saveButtonFocus }` の着地先になる。
                modifier = Modifier.focusRequester(saveButtonFocus),
            ) {
                Text("保存")
            }
            Button(onClick = onDone) {
                Text("キャンセル")
            }
        }
    }
}

/**
 * 入力検証エラーおよび保存失敗のメッセージ組み立てを Compose から切り離した
 * 純粋ロジック。Robolectric 無しでユニットテストできる。
 */
object ProfileEditMessages {

    sealed interface SaveOutcome {
        data object Success : SaveOutcome
        data class Error(val message: String) : SaveOutcome
    }

    /** [InvalidReason] の全ケースを網羅する。将来ケースが増えたらコンパイルエラーで気づける。 */
    fun messageFor(reason: InvalidReason): String = when (reason) {
        InvalidReason.Empty -> "サーバ URL を入力してください"
        InvalidReason.ContainsWhitespace -> "サーバ URL に空白を含めないでください"
        InvalidReason.NoHost -> "ホスト名が入力されていません"
        InvalidReason.BadScheme ->
            "https:// 以外は使えません。https:// を付けるかホスト名だけを入力してください"
        InvalidReason.ContainsCredentials ->
            "ユーザー名やパスワードを含む URL は使えません。ホスト名だけを入力してください" +
                "（ユーザー名とパスワードは保存後、初回接続時の画面で入力します）"
    }

    /**
     * 編集保存の結果を判定する。
     *
     * [addressUpdated] / [renamed] / [batchModeEnsured] が false を返すのは
     * いずれも対象プロファイルが見つからなかったとき（他の経路で既に削除された等）。
     * どれか1つでも false であれば、保存できていないのに保存できたかのように
     * ホーム画面へ戻ってはいけないため、[SaveOutcome.Error] を返す。
     * 判定は実行順（アドレス → 表示名 → batch_mode）どおりに並べ、
     * 最初に失敗した段階のメッセージを返す。
     *
     * 裁定66: [addressUpdated] を追加した。アドレスが変わっていない場合は
     * 呼び出し側が `updateServerAddress` を呼ばず true を渡す（対象が既に
     * 見つからない場合を除き、常に成功扱いにしてよい操作だから）。
     */
    fun outcomeForEdit(
        addressUpdated: Boolean,
        renamed: Boolean,
        batchModeEnsured: Boolean,
    ): SaveOutcome = when {
        !addressUpdated -> SaveOutcome.Error(
            "この接続先は見つかりませんでした（既に削除された可能性があります）。" +
                "「キャンセル」でホーム画面に戻って確認してください。",
        )

        !renamed -> SaveOutcome.Error(
            "サーバ URL は保存されましたが、表示名の変更に失敗しました" +
                "（この接続先が見つかりませんでした。既に削除された可能性があります）。" +
                "「キャンセル」でホーム画面に戻って確認してください。",
        )

        !batchModeEnsured -> SaveOutcome.Error(
            "サーバ URL と表示名は保存されましたが、自動接続の設定に失敗しました。" +
                "「キャンセル」でホーム画面に戻って確認してください。",
        )

        else -> SaveOutcome.Success
    }
}
