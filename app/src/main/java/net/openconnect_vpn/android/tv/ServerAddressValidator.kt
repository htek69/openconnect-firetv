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
}

/**
 * サーバアドレスの入力検証と正規化。
 * Android API を使わないため JVM 単体テストで全ケースを検証できる。
 */
object ServerAddressValidator {

    fun validate(raw: String): AddressValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AddressValidation.Invalid(InvalidReason.Empty)
        if (trimmed.any { it.isWhitespace() }) {
            return AddressValidation.Invalid(InvalidReason.ContainsWhitespace)
        }
        if (trimmed.startsWith("http://")) {
            return AddressValidation.Invalid(InvalidReason.BadScheme)
        }
        val withoutScheme = trimmed.removePrefix("https://")
        if (withoutScheme.isEmpty() || withoutScheme.startsWith("/")) {
            return AddressValidation.Invalid(InvalidReason.NoHost)
        }
        return AddressValidation.Valid
    }

    /**
     * 既存コアの `server_address` に入れる形に整える。
     * スキームは落とし、ホスト（とパス）だけを残す。
     */
    fun normalize(raw: String): String =
        raw.trim()
            .removePrefix("https://")
            .removeSuffix("/")
}
