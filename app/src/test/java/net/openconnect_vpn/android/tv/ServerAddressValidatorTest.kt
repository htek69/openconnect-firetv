package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressValidatorTest {

    @Test
    fun `ホスト名だけなら有効`() {
        assertTrue(ServerAddressValidator.validate("vpn.example.com") is AddressValidation.Valid)
    }

    @Test
    fun `https 付きなら有効`() {
        assertTrue(ServerAddressValidator.validate("https://vpn.example.com") is AddressValidation.Valid)
    }

    @Test
    fun `ポート付きなら有効`() {
        assertTrue(ServerAddressValidator.validate("vpn.example.com:4443") is AddressValidation.Valid)
    }

    @Test
    fun `IPv4 アドレスなら有効`() {
        assertTrue(ServerAddressValidator.validate("192.0.2.10") is AddressValidation.Valid)
    }

    @Test
    fun `空文字は Empty で無効`() {
        val result = ServerAddressValidator.validate("")
        assertEquals(InvalidReason.Empty, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `空白のみは Empty で無効`() {
        val result = ServerAddressValidator.validate("   ")
        assertEquals(InvalidReason.Empty, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `途中に空白があれば無効`() {
        val result = ServerAddressValidator.validate("vpn example.com")
        assertEquals(InvalidReason.ContainsWhitespace, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `http は無効`() {
        val result = ServerAddressValidator.validate("http://vpn.example.com")
        assertEquals(InvalidReason.BadScheme, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `スキームだけでホストが無ければ無効`() {
        val result = ServerAddressValidator.validate("https://")
        assertEquals(InvalidReason.NoHost, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `正規化は前後の空白を落とす`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("  vpn.example.com  "))
    }

    @Test
    fun `正規化は https スキームを落として素のホストにする`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("https://vpn.example.com"))
    }

    @Test
    fun `正規化は末尾のスラッシュを落とす`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("https://vpn.example.com/"))
    }

    @Test
    fun `正規化はパスを保持する`() {
        assertEquals("vpn.example.com/group1", ServerAddressValidator.normalize("https://vpn.example.com/group1"))
    }

    @Test
    fun `大文字の HTTPS スキームも有効`() {
        assertTrue(ServerAddressValidator.validate("HTTPS://vpn.example.com") is AddressValidation.Valid)
    }

    @Test
    fun `正規化は大文字の HTTPS スキームも落とす`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("HTTPS://vpn.example.com"))
    }

    @Test
    fun `ユーザ情報付き URL は資格情報として無効`() {
        val result = ServerAddressValidator.validate("https://user:pass@vpn.example.com")
        assertEquals(InvalidReason.ContainsCredentials, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `スキーム無しでも @ を含めば資格情報として無効`() {
        val result = ServerAddressValidator.validate("admin@vpn.example.com")
        assertEquals(InvalidReason.ContainsCredentials, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `https 以外のスキームは BadScheme`() {
        val result = ServerAddressValidator.validate("ftp://vpn.example.com")
        assertEquals(InvalidReason.BadScheme, (result as AddressValidation.Invalid).reason)
    }
}
