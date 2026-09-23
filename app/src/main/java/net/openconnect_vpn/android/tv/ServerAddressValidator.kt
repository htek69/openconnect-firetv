package net.openconnect_vpn.android.tv

sealed interface AddressValidation {
    data object Valid : AddressValidation
    data class Invalid(val reason: InvalidReason) : AddressValidation
}

enum class InvalidReason {
    Empty,
    ContainsWhitespace,
    NoHost,
    BadScheme,
    ContainsCredentials,
}

/**
 * サーバアドレスの入力検証と正規化。
 * Android API を使わないため JVM 単体テストで全ケースを検証できる。
 */
object ServerAddressValidator {

    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_DELIMITER = "://"

    fun validate(raw: String): AddressValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AddressValidation.Invalid(InvalidReason.Empty)
        if (trimmed.any { it.isWhitespace() }) {
            return AddressValidation.Invalid(InvalidReason.ContainsWhitespace)
        }

        val schemeSeparator = trimmed.indexOf(SCHEME_DELIMITER)
        val rest = if (schemeSeparator >= 0) {
            val scheme = trimmed.substring(0, schemeSeparator)
            // https のみ許可（大文字小文字を無視）。http を含む他のスキームはすべて拒否する。
            if (!scheme.equals(SCHEME_HTTPS, ignoreCase = true)) {
                return AddressValidation.Invalid(InvalidReason.BadScheme)
            }
            trimmed.substring(schemeSeparator + SCHEME_DELIMITER.length)
        } else {
            trimmed
        }

        // オーソリティ部（パスより前）だけを見る。ホスト[:ポート] の範囲。
        val authority = rest.substringBefore("/")
        if (authority.isEmpty()) {
            return AddressValidation.Invalid(InvalidReason.NoHost)
        }
        // "user:pass@host" / "admin@host" のように資格情報が埋め込まれていれば拒否する。
        // server_address は平文で profile-<uuid>.xml に保存され、一覧画面にも表示されるため、
        // ここを通すとパスワードが画面に出てしまう。
        if (authority.contains("@")) {
            return AddressValidation.Invalid(InvalidReason.ContainsCredentials)
        }
        return AddressValidation.Valid
    }

    /**
     * 既存コアの `server_address` に入れる形に整える。
     *
     * 剥がすのは大文字小文字を無視した `https://` スキームだけである。
     * 資格情報や https 以外のスキームはそのまま残るため、必ず [validate] が
     * [AddressValidation.Valid] を返した入力にのみ使うこと。
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val schemeSeparator = trimmed.indexOf(SCHEME_DELIMITER)
        val withoutScheme = if (schemeSeparator >= 0 &&
            trimmed.substring(0, schemeSeparator).equals(SCHEME_HTTPS, ignoreCase = true)
        ) {
            trimmed.substring(schemeSeparator + SCHEME_DELIMITER.length)
        } else {
            trimmed
        }
        return withoutScheme.removeSuffix("/")
    }
}
