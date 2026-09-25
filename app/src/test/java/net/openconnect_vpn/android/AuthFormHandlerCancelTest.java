package net.openconnect_vpn.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.infradead.libopenconnect.LibOpenConnect;
import org.junit.Before;
import org.junit.Test;

import net.openconnect_vpn.android.core.UserDialog;

import android.content.SharedPreferences;

/**
 * 裁定95: 認証フォームを取り消したときの結果値を固定する。
 *
 * <p>見捨てた候補の認証ダイアログを畳むときは、park している VPN スレッドに
 * {@code OC_FORM_RESULT_CANCELLED} を返さなければならない。これは
 * {@code onDismiss}（利用者が Cancel/Back を押したとき）と
 * {@code BATCH_MODE_ABORTED} が使う値と同じであり、
 * {@code OpenConnectManagementThread:243} はこれを {@code Integer} にキャストする。
 *
 * <p>ダイアログの描画（{@code onStart}）は Android のビューを作るので JVM では
 * 押さえられない。ここでは値と型だけを押さえる。
 */
public class AuthFormHandlerCancelTest {

	/** 既定値をそのまま返す SharedPreferences。実装漏れを避けるため Proxy で作る。 */
	private static SharedPreferences defaultsOnlyPrefs() {
		return (SharedPreferences) Proxy.newProxyInstance(
				SharedPreferences.class.getClassLoader(),
				new Class<?>[] { SharedPreferences.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						// getString(key, def) / getBoolean(key, def) などは def を返す
						if (args != null && args.length == 2) {
							return args[1];
						}
						Class<?> returnType = method.getReturnType();
						if (returnType == boolean.class) {
							return Boolean.FALSE;
						}
						if (returnType == int.class) {
							return Integer.valueOf(0);
						}
						return null;
					}
				});
	}

	@Before
	public void setUp() {
		UserDialog.clearDeferredPrefs();
	}

	private AuthFormHandler newHandler() {
		// 空の AuthForm で足りる。コンストラクタは opts を走査してフォームの
		// ダイジェストを作るだけで、ビューには触らない。
		LibOpenConnect.AuthForm form = new LibOpenConnect.AuthForm();
		return new AuthFormHandler(defaultsOnlyPrefs(), form, false, null);
	}

	@Test
	public void 認証フォームの取り消し結果はOC_FORM_RESULT_CANCELLEDである() {
		Object result = newHandler().cancelResult();

		assertTrue(result instanceof Integer);
		assertEquals(LibOpenConnect.OC_FORM_RESULT_CANCELLED, ((Integer) result).intValue());
	}

	@Test
	public void 取り消しはparkしたスレッドにCANCELLEDを返す() throws Exception {
		AuthFormHandler handler = newHandler();

		handler.cancel();

		Object result = handler.waitForResponse();
		assertEquals(LibOpenConnect.OC_FORM_RESULT_CANCELLED, ((Integer) result).intValue());
	}

	@Test
	public void 取り消し結果はOKとも新グループとも違う() {
		Object result = newHandler().cancelResult();

		assertTrue(((Integer) result).intValue() != LibOpenConnect.OC_FORM_RESULT_OK);
		assertTrue(((Integer) result).intValue() != LibOpenConnect.OC_FORM_RESULT_NEWGROUP);
	}
}
