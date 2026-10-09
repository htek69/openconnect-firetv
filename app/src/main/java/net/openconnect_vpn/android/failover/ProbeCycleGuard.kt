package net.openconnect_vpn.android.failover

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
 * 鍵は [FailoverService] の `slowLinkCandidateKey`（グループ+候補の添字。トンネルを
 * 持たない状態は null）。**同じ候補へ繋ぎ直した場合は鍵が同じ**なので捨てられない
 * ——この限界は鍵を共有する測り直し（`syncSlowLinkCandidate`）と同じである。
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
