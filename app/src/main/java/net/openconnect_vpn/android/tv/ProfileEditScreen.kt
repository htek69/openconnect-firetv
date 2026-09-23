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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
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
 * 編集時にサーバアドレスを変更すると、`AuthFormHandler` が認証フォームの構造
 * から導く鍵（MD5）が変わり、既に保存済みの資格情報と一致しなくなる
 * おそれがある。そのため編集モードではアドレス欄を読み取り専用にし、
 * 表示名だけを変更できるようにしている（`rename` だけを呼ぶ）。
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
            readOnly = isEditing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            focusRequester = addressFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isEditing) {
            Text(
                "既存の接続先のサーバ URL は変更できません" +
                    "（保存済みの認証情報と紐付いているため）。" +
                    "サーバを変えたい場合は削除してから新しく追加してください。",
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
                if (!isEditing) {
                    when (val result = ServerAddressValidator.validate(address)) {
                        AddressValidation.Valid -> {
                            val normalized = ServerAddressValidator.normalize(address)
                            profiles.create(normalized, displayName)
                            onDone()
                        }

                        is AddressValidation.Invalid ->
                            error = ProfileEditMessages.messageFor(result.reason)
                    }
                } else {
                    val uuid = requireNotNull(editingUuid)
                    val renamed = profiles.rename(uuid, displayName)
                    val batchModeOk = renamed && profiles.ensureBatchMode(uuid)
                    when (val outcome = ProfileEditMessages.outcomeForEdit(renamed, batchModeOk)) {
                        ProfileEditMessages.SaveOutcome.Success -> onDone()
                        is ProfileEditMessages.SaveOutcome.Error -> error = outcome.message
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

    // 編集時はアドレス欄が読み取り専用のため、フォーカスは編集できる表示名欄に置く。
    LaunchedEffect(Unit) {
        runCatching { (if (isEditing) displayNameFocus else addressFocus).requestFocus() }
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
                keyboardOptions = keyboardOptions,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
            )
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
     * `rename` が false を返すのは対象プロファイルが見つからなかったとき
     * （他の経路で既に削除された等）。`ensureBatchMode` も同じ理由で false に
     * なり得る。どちらであっても、保存できていないのに保存できたかのように
     * ホーム画面へ戻ってはいけないため、[SaveOutcome.Error] を返す。
     */
    fun outcomeForEdit(renamed: Boolean, batchModeEnsured: Boolean): SaveOutcome = when {
        !renamed -> SaveOutcome.Error(
            "この接続先は見つかりませんでした（既に削除された可能性があります）。" +
                "「キャンセル」でホーム画面に戻って確認してください。",
        )

        !batchModeEnsured -> SaveOutcome.Error(
            "表示名は保存されましたが、自動接続の設定に失敗しました。" +
                "「キャンセル」でホーム画面に戻って確認してください。",
        )

        else -> SaveOutcome.Success
    }
}
