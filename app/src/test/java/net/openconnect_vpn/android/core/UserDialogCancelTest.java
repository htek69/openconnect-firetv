package net.openconnect_vpn.android.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

/**
 * 裁定95 の回帰テスト。
 *
 * <p>実機で確認された事故: 自動切替が見捨てた候補の認証ダイアログが画面に残り、
 * 既に別候補で接続が確立しているのに利用者はその幽霊に認証情報を入力していた。
 * 1枚答えると次の幽霊が現れるため「また Username 画面が出る」ように見えていた。
 *
 * <p>原因は {@code UserDialog.finish()} が {@code mDialogUp} に守られていること。
 * Activity が前面に無い間は結果が入らないので VPN スレッドは
 * {@code waitForResponse()} で park したままになる。ここで固定するのは
 * 「取り消しは {@code mDialogUp} に関わらず結果を入れてスレッドを起こす」
 * という状態遷移そのもの（Android のビューには触らない純粋なロジック）である。
 *
 * <p>{@code OpenVpnService} 側の配線（{@code killVPNThread} からの呼び出し、
 * {@code mHandler} 経由の dismiss、通知の消滅）は Android 依存なので JVM では
 * 押さえられない。報告書に明記してある。
 */
public class UserDialogCancelTest {

	private static final Object CANCEL_SENTINEL = new Object();

	/** Android のビューを作らない最小の派生。onStart/onStop は基底の状態だけ動かす。 */
	private static class TestDialog extends UserDialog {
		int dismissCalls;

		TestDialog() {
			super(null);
		}

		@Override
		protected Object cancelResult() {
			return CANCEL_SENTINEL;
		}

		@Override
		public void dismissDialog() {
			dismissCalls++;
		}
	}

	@Before
	public void setUp() {
		// 静的な繰り越し prefs はテスト間で共有されるので毎回空にする
		UserDialog.clearDeferredPrefs();
	}

	/** waitForResponse() に入ったスレッドを起こし、受け取った答えを持ち帰る足場。 */
	private static class Waiter {
		final Thread thread;
		final AtomicReference<Object> result = new AtomicReference<Object>();

		Waiter(final UserDialog dialog) {
			thread = new Thread(new Runnable() {
				@Override
				public void run() {
					result.set(dialog.waitForResponse());
				}
			}, "test-vpn-thread");
			thread.setDaemon(true);
			thread.start();
		}

		/** wait() に入るまで待つ。既に答えが入っていれば TERMINATED で戻る。 */
		void awaitParkedOrDone() throws InterruptedException {
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (System.nanoTime() < deadline) {
				Thread.State s = thread.getState();
				if (s == Thread.State.WAITING || s == Thread.State.TERMINATED) {
					return;
				}
				Thread.sleep(1);
			}
			fail("スレッドが waitForResponse() で park しなかった: " + thread.getState());
		}

		Object awaitResult() throws InterruptedException {
			thread.join(5000);
			assertFalse("cancel() の後もスレッドが park したまま残っている", thread.isAlive());
			return result.get();
		}
	}

	@Test
	public void 前面に無いダイアログでも取り消しで結果が入りスレッドが起きる() throws Exception {
		TestDialog dialog = new TestDialog();
		// onStart() を呼ばない = mDialogUp が false。これが事故の本体である。
		Waiter waiter = new Waiter(dialog);
		waiter.awaitParkedOrDone();

		dialog.cancel();

		assertSame(CANCEL_SENTINEL, waiter.awaitResult());
	}

	@Test
	public void 前面に無いダイアログでは従来のfinishが結果を入れない() throws Exception {
		TestDialog dialog = new TestDialog();
		Waiter waiter = new Waiter(dialog);
		waiter.awaitParkedOrDone();

		dialog.finish("ignored");

		// cancel() という別口が必要である理由そのものを固定する
		waiter.thread.join(50);
		assertTrue("finish() で起きてしまった", waiter.thread.isAlive());
		assertNull(waiter.result.get());

		dialog.cancel();
		assertSame(CANCEL_SENTINEL, waiter.awaitResult());
	}

	@Test
	public void 前面にあるダイアログも取り消しでスレッドが起きる() throws Exception {
		TestDialog dialog = new TestDialog();
		dialog.onStart(null);
		Waiter waiter = new Waiter(dialog);
		waiter.awaitParkedOrDone();

		dialog.cancel();

		assertSame(CANCEL_SENTINEL, waiter.awaitResult());
	}

	@Test
	public void 取り消しの後に走るfinishは答えを差し替えない() throws Exception {
		TestDialog dialog = new TestDialog();
		dialog.onStart(null);
		Waiter waiter = new Waiter(dialog);
		waiter.awaitParkedOrDone();

		dialog.cancel();
		assertSame(CANCEL_SENTINEL, waiter.awaitResult());

		// 畳んだ AlertDialog の onDismiss は cancel() より後に UI スレッドで走る
		dialog.finish("late answer from the dismissed dialog");

		assertSame(CANCEL_SENTINEL, dialog.waitForResponse());
	}

	@Test
	public void 利用者が既に答えていたら取り消しは上書きしない() throws Exception {
		TestDialog dialog = new TestDialog();
		dialog.onStart(null);
		dialog.finish("user said OK");

		dialog.cancel();

		assertEquals("user said OK", dialog.waitForResponse());
	}

	@Test
	public void 取り消しは何度呼んでも同じ答えのまま() throws Exception {
		TestDialog dialog = new TestDialog();
		dialog.cancel();
		dialog.cancel();

		assertSame(CANCEL_SENTINEL, dialog.waitForResponse());
	}

	@Test
	public void 取り消し後に呼んだwaitForResponseも同じ答えを返す() throws Exception {
		TestDialog dialog = new TestDialog();
		Waiter waiter = new Waiter(dialog);
		waiter.awaitParkedOrDone();

		dialog.cancel();

		assertSame(CANCEL_SENTINEL, waiter.awaitResult());
		// 既に答えがある場合の早期 return 経路でも同じ値
		assertSame(CANCEL_SENTINEL, dialog.waitForResponse());
	}

	@Test
	public void 取り消しはビューに触らずdismissは呼び出し側がUIスレッドで行う() {
		TestDialog dialog = new TestDialog();
		dialog.cancel();

		assertEquals(0, dialog.dismissCalls);

		// OpenVpnService.cancelActiveDialog() が mHandler.post() の中で呼ぶ相当
		dialog.dismissDialog();
		assertEquals(1, dialog.dismissCalls);
	}

	@Test
	public void 上書きしない派生でも既定の取り消し結果は非nullでparkが解ける() throws Exception {
		UserDialog plain = new UserDialog(null) {
		};
		Waiter waiter = new Waiter(plain);
		waiter.awaitParkedOrDone();

		plain.cancel();

		Object result = waiter.awaitResult();
		assertNotNull("既定の cancelResult() が null だと park が解けない", result);
		assertTrue(result instanceof Boolean);
	}

	@Test
	public void 証明書の警告の取り消しは証明書を受け入れない() {
		CertWarningDialog dialog = new CertWarningDialog(null, "vpn.example.com", "sha1", "self signed");

		Object result = dialog.cancelResult();

		// 呼び出し元（OpenConnectManagementThread:209-213）は Integer にキャストする
		assertTrue(result instanceof Integer);
		assertEquals(CertWarningDialog.RESULT_NO, ((Integer) result).intValue());
	}

	@Test
	public void 基底のdismissDialogは何もしない() {
		UserDialog plain = new UserDialog(null) {
		};
		plain.dismissDialog();
	}
}
