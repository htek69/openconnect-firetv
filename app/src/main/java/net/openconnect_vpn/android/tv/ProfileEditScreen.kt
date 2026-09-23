package net.openconnect_vpn.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
 * 裁定68（実機で発見した阻害欠陥）: 素の `BasicTextField`（`material3` の
 * `OutlinedTextField` が使えないための代替。その判断自体は裁定なし・維持）は、
 * フォーカスが乗っている間 D-pad の UP/DOWN/CENTER を内部の編集処理が横取りし、
 * 外へキーイベントを逃さない。実機計測ではこれが原因で UP/DOWN が効かず、
 * CENTER に至っては文字が入力されてしまい、「保存」にも「キャンセル」にも
 * 到達できなかった（BACK で画面を捨てるしか脱出手段が無い＝R2/R4 が壊れる）。
 * [TvTextField] 内の `Modifier.onPreviewKeyEvent` で UP/DOWN/CENTER/ENTER を
 * `BasicTextField` 本体より先に横取りし、フォーカス移動とソフトキーボード表示に
 * 差し替える（詳細は [TvTextField] と [TvTextFieldKeys] を参照）。
 *
 * 裁定69: 同じ実機検証で、サーバ URL 欄に `test.example.com` と打つと日本語 IME を
 * 経由して `てst。えぁmpぇ。こm` に変換されてしまうことが判明した。従来の
 * `KeyboardType.Uri` では日本語 IME を抑止できなかったため、URL 欄だけ
 * `KeyboardType.Ascii` に変更した（詳細は URL 欄の `TvTextField` 呼び出し部を参照）。
 * 表示名欄は意図的に既定の IME のまま（日本語で名付けたいはずだから）。
 */
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

    val addressFocus = remember { FocusRequester() }
    val displayNameFocus = remember { FocusRequester() }
    val isEditing = editingUuid != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            if (!isEditing) "接続先を追加" else "接続先を編集",
            style = MaterialTheme.typography.headlineMedium,
        )

        TvTextField(
            value = address,
            onValueChange = { address = it; error = null },
            label = "サーバ URL（例: vpn.example.com）",
            readOnly = false,
            // 裁定69: KeyboardType.Uri は実機の日本語 IME を抑止できず、
            // "test.example.com" が "てst。えぁmpぇ。こm" に変換された。
            // Ascii なら英数字・記号キーボードを要求するので日本語変換に落ちない。
            // 表示名欄（下）は逆に日本語で名付けたいはずなので既定のまま指定しない。
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            focusRequester = addressFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isEditing) {
            // 裁定67: 「無効になることがあります」という可能性の言い方は誤りだった。
            // updateServerAddress はアドレスが変わるたびに保存済みの資格情報と
            // 証明書承認を必ず削除する（同じ認証フォーム構造の別サーバへ
            // 無言で送られるのを防ぐため）。起きることを断定形で伝える。
            Text(
                "サーバ URL を変更すると、保存済みのユーザー名・パスワードと" +
                    "サーバ証明書の承認をこの接続先から削除します。" +
                    "変更後、次に接続するときに改めてログイン情報の入力を求められます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TvTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = "表示名（任意）",
            readOnly = false,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            focusRequester = displayNameFocus,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Text(
            "保存後、この接続先に初めて接続するときにユーザー名とパスワードの入力を求められます。" +
                "そこで「パスワードを保存」をチェックすると、以降は自動で再接続できます。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
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
                                is ProfileEditMessages.SaveOutcome.Error -> error = outcome.message
                            }
                        }
                    }
                }
            }) {
                Text("保存")
            }
            Button(onClick = onDone) {
                Text("キャンセル")
            }
        }
    }

    // 裁定66: アドレス欄が常に編集可能になったので、常にそちらへ初期フォーカスを置く
    // （作成・編集どちらも最初の項目という位置づけで揃う）。
    LaunchedEffect(Unit) {
        runCatching { addressFocus.requestFocus() }
    }
}

/**
 * TV のリモコン操作に合わせた最小限のテキスト入力欄。
 *
 * tv-material には入力欄が無く、androidx.compose.material3 は依存に追加していない
 * （新規依存を増やさないという制約のため）ため、既存依存の
 * `androidx.compose.foundation.text.BasicTextField` を土台に、枠線とラベルだけを
 * 自前で描く。フォーカスの有無は [onFocusChanged] で追い、枠線の色に反映して
 * どの項目にフォーカスがあるかをリモコン操作でも分かるようにする。
 *
 * 裁定68: 素の `BasicTextField` はフォーカスが乗っている間、D-pad の
 * UP/DOWN/CENTER を内部の編集処理が先に消費してしまい、外（フォーカス移動）へ
 * 逃がさない。実機ではこれが原因で UP/DOWN が無反応、CENTER に至っては文字が
 * 入力されて「保存」にも「キャンセル」にも到達できなかった。
 * `Modifier.onPreviewKeyEvent` は子（`BasicTextField` 本体）より**先に**呼ばれるため、
 * ここで UP/DOWN/CENTER/ENTER を横取りしてフォーカス移動・ソフトキーボード表示に
 * 差し替え、`true` を返して `BasicTextField` に渡さない。これは編集中・非編集中で
 * 区別しない（`onPreviewKeyEvent` は IME の composing 状態に関係なく常に先に発火する
 * ため、「編集中は素通りする」といった抜け道が無い）。
 * KeyDown と KeyUp の両方を消費する（[TvTextFieldKeys.isHandled] は type を見ない）。
 * 実際のアクション（フォーカス移動／キーボード表示）は KeyDown のときだけ実行し、
 * 対になる KeyUp は「既に処理済みのキー」として無条件に消費するだけにする
 * （そうしないと KeyUp だけが `BasicTextField` に漏れて同じ不具合が再発しかねない）。
 *
 * 加えて `imeAction = Done` とセットの `onDone` で、ソフトキーボードの「完了」から
 * でも次の項目へフォーカスが送れるようにしてある（D-pad の物理キーだけに
 * 依存しない、もう1つの脱出経路）。
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    readOnly: Boolean,
    keyboardOptions: KeyboardOptions,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isFocused) colors.primary else colors.border
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = if (isFocused) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(4.dp))
                .background(colors.surfaceVariant, shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                singleLine = true,
                keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (!TvTextFieldKeys.isHandled(event.key)) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            when (TvTextFieldKeys.actionFor(event.key)) {
                                TvKeyAction.MoveFocusUp -> focusManager.moveFocus(FocusDirection.Up)
                                TvKeyAction.MoveFocusDown -> focusManager.moveFocus(FocusDirection.Down)
                                TvKeyAction.OpenKeyboard -> keyboardController?.show()
                                TvKeyAction.Ignore -> Unit
                            }
                        }
                        true
                    },
            )
        }
    }
}

/** [TvTextFieldKeys.actionFor] が返す、横取りした D-pad キーに対する挙動。 */
enum class TvKeyAction { MoveFocusUp, MoveFocusDown, OpenKeyboard, Ignore }

/**
 * どの D-pad キーを [TvTextField] が横取りするかの判定を、Compose から切り離した
 * 純粋ロジックとして持つ。`Key` / `KeyEventType` は Android フレームワークではなく
 * compose-ui のプレーンな値クラスなので Robolectric 無しでテストできる。
 */
object TvTextFieldKeys {

    private val HANDLED_KEYS = setOf(
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
    )

    /** true なら KeyDown・KeyUp を問わず [TvTextField] がこのキーを消費する。 */
    fun isHandled(key: Key): Boolean = key in HANDLED_KEYS

    /** [isHandled] が true のキーについて、実際に何をするか。 */
    fun actionFor(key: Key): TvKeyAction = when (key) {
        Key.DirectionUp -> TvKeyAction.MoveFocusUp
        Key.DirectionDown -> TvKeyAction.MoveFocusDown
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> TvKeyAction.OpenKeyboard
        else -> TvKeyAction.Ignore
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
