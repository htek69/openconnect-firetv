/*
 * Copyright (c) 2013, Kevin Cernekee
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * In addition, as a special exception, the copyright holders give
 * permission to link the code of portions of this program with the
 * OpenSSL library.
 */

package net.openconnect_vpn.android.core;

import java.util.HashMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public abstract class UserDialog {
	public static final String TAG = "OpenConnect";

	private Object mResult;
	private boolean mDialogUp;
	protected SharedPreferences mPrefs;

	private static HashMap<String,DeferredPref> mDeferredPrefs = new HashMap<String,DeferredPref>();

	public UserDialog(SharedPreferences prefs) {
		mPrefs = prefs;
	}

	/*
	 * 裁定95: mResult の検査を synchronized の外に置いていた元の形は起床を
	 * 落としうる（検査で null を見た直後に cancel()/finish() が結果を入れて
	 * notifyAll しても、まだ wait() に入っていないので通知が届かず、その後
	 * 入った wait() は誰にも起こされない）。裁定95 は「取り消しは必ず
	 * スレッドを起こす」ことを要求するので、検査と wait を同じモニタの
	 * 中に入れた教科書どおりの形に直す。ループの本体は wait() だけであり、
	 * wait() はモニタを解放するので、保持期間が伸びても他者を待たせない。
	 */
	public Object waitForResponse() {
		synchronized (this) {
			while (mResult == null) {
				try {
					this.wait();
				} catch (InterruptedException e) {
				}
			}
			return mResult;
		}
	}

	protected void finish(Object result) {
		synchronized (this) {
			if (mDialogUp) {
				mResult = result;
				this.notifyAll();
			}
		}
	}

	/**
	 * 裁定95: 止めると決めた候補の保留中ダイアログを畳むための公開の取り消し口。
	 *
	 * <p>{@link #finish(Object)} は {@code mDialogUp} に守られているため、
	 * Activity が前面に無い間は結果が入らない。そのため候補を見捨てても VPN
	 * スレッドは {@link #waitForResponse()} で park したままになり、
	 * {@code AlertDialog} も画面に残り続ける（実機では見捨てた候補のダイアログが
	 * 2枚積まれ、利用者が幽霊に認証情報を入力していた）。取り消しは
	 * {@code mDialogUp} に関わらず結果を入れてスレッドを起こす。
	 *
	 * <p>呼び出し元のスレッドは問わない。Android のビューに触る後始末は
	 * {@link #dismissDialog()} に分けてあり、そちらは UI スレッドから呼ぶこと。
	 */
	public final void cancel() {
		synchronized (this) {
			if (mResult == null) {
				mResult = cancelResult();
			}
			// 以降 finish() に答えを差し替えさせない（畳んだ AlertDialog の
			// onDismiss がこの後 UI スレッドで走るため）
			mDialogUp = false;
			this.notifyAll();
		}
	}

	/**
	 * 取り消し時に {@link #waitForResponse()} が返す値。
	 *
	 * <p>呼び出し元（{@code OpenConnectManagementThread}）は派生ごとに具体型へ
	 * キャストするので、キャストされる派生は必ず上書きすること。既定値は
	 * 非 null である（null では park したスレッドが起きない）。戻り値を読まない
	 * {@code ErrorDialog} の通常結果と同じ Boolean に合わせてある。
	 */
	protected Object cancelResult() {
		return Boolean.FALSE;
	}

	/**
	 * 描画済みのダイアログを畳む。UI スレッドから呼ぶこと。
	 *
	 * <p>{@link #onStop(Context)} と違い、利用者が入力した値を保存してはならない。
	 * 見捨てた候補のダイアログに打ち込まれた資格情報を残さないためである。
	 */
	public void dismissDialog() {
	}

	private abstract class DeferredPref {
		protected SharedPreferences mPrefs;
		protected String mKey;

		public DeferredPref(SharedPreferences prefs, String name) {
			mPrefs = prefs;
			mKey = name;
		}
		public abstract void commit();
	}

	private class DeferredStringPref extends DeferredPref {
		public String value;

		public DeferredStringPref(SharedPreferences prefs, String name, String newValue) {
			super(prefs, name);
			value = newValue;
		}

		public void commit() {
			mPrefs.edit().putString(mKey, value).apply();
		}
	}

	private class DeferredBooleanPref extends DeferredPref {
		public boolean value;

		public DeferredBooleanPref(SharedPreferences prefs, String name, boolean newValue) {
			super(prefs, name);
			value = newValue;
		}

		public void commit() {
			mPrefs.edit().putBoolean(mKey, value).apply();
		}
	}

	public static void clearDeferredPrefs() {
		mDeferredPrefs.clear();
	}

	public static void writeDeferredPrefs() {
		for (DeferredPref p : mDeferredPrefs.values()) {
			p.commit();
		}
		mDeferredPrefs.clear();
	}

	protected void setStringPref(String key, String value) {
		mDeferredPrefs.put(key, new DeferredStringPref(mPrefs, key, value));
	}

	protected String getStringPref(String key) {
		try {
			DeferredStringPref p = (DeferredStringPref)mDeferredPrefs.get(key);
			return p.value;
		} catch (ClassCastException e) {
		} catch (NullPointerException e) {
		}
		return mPrefs.getString(key, "");
	}

	protected void setBooleanPref(String key, boolean value) {
		mDeferredPrefs.put(key, new DeferredBooleanPref(mPrefs, key, value));
	}

	protected boolean getBooleanPref(String key) {
		try {
			DeferredBooleanPref p = (DeferredBooleanPref)mDeferredPrefs.get(key);
			return p.value;
		} catch (ClassCastException e) {
		} catch (NullPointerException e) {
		}
		return mPrefs.getBoolean(key, false);
	}

	// Render the dialog; called from the UI thread.  May not block.
	public void onStart(Context context) {
		mDialogUp = true;
		Log.d(TAG, "rendering user dialog");
	}

	// Dismiss a pending dialog, e.g. if the Activity is being torn down.  Called from the UI thread.
	public void onStop(Context context) {
		mDialogUp = false;
		Log.d(TAG, "tearing down user dialog");
	}

	// See if the dialog can be safely skipped based on SharedPreferences.  Called from the background thread.
	public Object earlyReturn() {
		Log.d(TAG, (mResult == null ? "not skipping" : "skipping") + " user dialog");
		return mResult;
	}
}
