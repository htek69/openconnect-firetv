package net.openconnect_vpn.android.failover

/**
 * プローブ結果を適用してよいかを決めるための、状態の鍵。
 *
 * **`FailoverService.slowLinkCandidateKey`（判定器の測り直しの鍵）と近いが、別の関数に
 * してある。2つは違う答えを要るからである。**
 * - 測り直しの鍵は、`Verifying` と `Healthy` で**同じ**でなければならない。同じトンネルの
 *   昇格で判定器を空にすると、`Verifying` のあいだに集めた証拠を捨ててしまう（意図した
 *   振る舞い。`slowLinkCandidateKey` の KDoc）。
 * - この鍵は `Verifying` に**接続完了時刻を含める**。含めないと、`Healthy(g,i)` のあいだに
 *   始めた周期が、再接続（`Connecting(g,i)` → `Verifying(g,i,T)`、添字は同じ）をまたいで
 *   返ったとき、新しいトンネルへ昇格の結果が当たる。**一度もプローブされていない新しい
 *   トンネルが、猶予（`graceAfterConnectSec`）を飛ばして `Healthy` になる。**
 *   再接続は利用者の切断・自動接続・全滅後の再試行が先頭へ戻る経路で、先頭が現候補と
 *   同じなら添字は変わらない。トンネルの世代を数える仕組みは要らない:
 *   `Verifying` は接続完了時刻を持っている。
 *
 * 組み合わせの確認（前 → 後。同じなら適用、違えば捨てる）:
 * - `Healthy(g,i)` → `Healthy(g,i)`: `g#i` で同じ。適用（正しい）
 * - `Verifying(g,i,T)` → `Verifying(g,i,T)`: `g#i@T` で同じ。適用（昇格はここで起きる）
 * - `Healthy(g,i)` → `Verifying(g,i,T)`: `g#i` と `g#i@T` で違う。**捨てる**（再接続）
 * - `Verifying(g,i,T1)` → `Verifying(g,i,T2)`: 違う。捨てる
 * - 添字が違う: 違う。捨てる
 * 残る穴は無い。`Healthy` への昇格は成功したプローブを要し、ティックループは同時に
 * 1つのプローブしか走らせないので、1周期の中で `Healthy` → … → `Healthy` の往復は完結しない。
 */
internal fun probeGuardKey(state: FailoverState): String? = when (state) {
    FailoverState.Idle -> null
    is FailoverState.Connecting -> null
    is FailoverState.Verifying -> "${state.groupId}#${state.candidateIndex}@${state.connectedAtMs}"
    is FailoverState.Healthy -> "${state.groupId}#${state.candidateIndex}"
    is FailoverState.FailingOver -> null
    is FailoverState.Exhausted -> null
}

/**
 * プローブ1周期を測り、**測っているあいだに候補が入れ替わっていたら結果を捨てる**。
 *
 * **なぜ要るのか。** プローブは `Dispatchers.IO` へ suspend し、その間に
 * `dispatchExternal`（ブロードキャスト等）が状態遷移を進める。測り終えた結果は
 * 「測り始めた時点の候補」の経路の結果であり、いまの候補のものではない。例:
 * `Healthy(A)` で周期が始まり、1回目が成功したあと、コアが切断を報告して
 * `Verifying(B)` まで進む。2回目以降が A の経路で詰まって周期が10秒後に返ると、
 * 結果（到達可能）が B に当たる。
 *
 * (a) `ProbeResult(true)` が `Verifying(B)` を `Healthy` へ昇格させる。**B は一度も
 *     プローブされていない**うえ、`shouldProbeNow()` の `Verifying` の門
 *     （猶予 `graceAfterConnectSec`）を丸ごと飛ばす。
 * (b) A の応答時間が B の窓に入る。`slowLinkCandidateKey()` は `Verifying` と `Healthy`
 *     で同じ鍵を返すので昇格では測り直されず、サンプルは窓の長さだけ生き残り、
 *     B の受信の窓が最初に判定を出せる瞬間にもまだ残っている。5000ms のサンプル
 *     1個で `spread > degradedJitterMs` を満たすので、健全な B から切り替えさせうる。
 * (c) 一周の記録にも A のサンプルが B のものとして載りうる。
 *
 * **この窓は新しくない。** 失敗した周期はこの分岐より前から約5秒かかっていたので、
 * `reachable = false` が違う候補へ当たる余地はあった。複数回化は、1回目が成功した周期
 * （最長 `timeoutMs * 2`）にも窓を広げ、応答時間の混入という経路を足した。
 * このガードは両方を閉じる。
 *
 * **捨てる方向が安全。** 候補Aで測った結果は候補Bについて何も言わない。止まるものは
 * 無い: `Verifying` は猶予が明ければ自分でもう一度プローブする（本来そうあるべき姿）。
 *
 * 鍵は [probeGuardKey]。**`slowLinkCandidateKey`（測り直しの鍵）とは別物である**
 * （理由は [probeGuardKey]）。
 *
 * [apply] の中の順序（`onProbe` が先、`dispatch(ProbeResult)` が後）は呼び出し側の責任で、
 * `FailoverServiceWiringTest` が固定している。
 *
 * @return 結果を [apply] へ渡したら true、捨てたら false。
 */
internal suspend fun runGuardedProbe(
    candidateKey: () -> String?,
    measure: suspend () -> ProbeOutcome,
    apply: (ProbeOutcome) -> Unit,
): Boolean {
    val keyBefore = candidateKey()
    val outcome = measure()
    if (candidateKey() != keyBefore) return false
    apply(outcome)
    return true
}
