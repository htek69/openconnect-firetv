package net.openconnect_vpn.android.tv

/** 画面遷移。ライブラリを増やさず sealed interface と状態で表現する。 */
sealed interface TvScreen {
    data object Home : TvScreen

    /** [uuid] が null なら新規作成。 */
    data class EditProfile(val uuid: String?) : TvScreen

    /** [groupId] が null なら新規作成。 */
    data class EditGroup(val groupId: String?) : TvScreen

    data object Settings : TvScreen
}
