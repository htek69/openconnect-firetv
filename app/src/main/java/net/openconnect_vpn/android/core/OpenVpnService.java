/*
 * Adapted from OpenVPN for Android
 * Copyright (c) 2012-2013, Arne Schwabe
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

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.*;
import android.preference.PreferenceManager;
import android.util.Log;
import net.openconnect_vpn.android.MainActivity;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.api.GrantPermissionsActivity;
import net.openconnect_vpn.android.core.VPNLog.LogArrayAdapter;
import net.openconnect_vpn.android.fragments.FeedbackFragment;

import java.net.InetAddress;
import java.util.Date;
import java.util.Locale;

import org.infradead.libopenconnect.LibOpenConnect;
import org.infradead.libopenconnect.LibOpenConnect.VPNStats;

public class OpenVpnService extends VpnService {

	public static final String TAG = "OpenConnect";

	public static final String START_SERVICE = "net.openconnect_vpn.android.START_SERVICE";
	public static final String START_SERVICE_STICKY = "net.openconnect_vpn.android.START_SERVICE_STICKY";
	public static final String ALWAYS_SHOW_NOTIFICATION = "net.openconnect_vpn.android.NOTIFICATION_ALWAYS_VISIBLE";

	public static final String ACTION_VPN_STATUS = "net.openconnect_vpn.android.VPN_STATUS";
	public static final String EXTRA_CONNECTION_STATE = "net.openconnect_vpn.android.connectionState";
	public static final String EXTRA_UUID = "net.openconnect_vpn.android.UUID";

	// These are valid in the CONNECTED state
	public VpnProfile profile;
	public LibOpenConnect.IPInfo ipInfo;
	public String serverName;
	public Date startTime;

	private DeviceStateReceiver mDeviceStateReceiver;
	private SharedPreferences mPrefs;

	private KeepAlive mKeepAlive;
	private int mIdleTimeout;

	private final IBinder mBinder = new LocalBinder();

	private String mUUID;
	private int mStartId;

	private Thread mVPNThread;
	private OpenConnectManagementThread mVPN;

	private UserDialog mDialog;
	private Context mDialogContext;

	private final int NOTIFICATION_ID = 1;
	private int mActivityConnections;
	private boolean mNotificationActive;

	private int mConnectionState = OpenConnectManagementThread.STATE_DISCONNECTED;
	private String mConnectionStateNames[];
	private VPNStats mStats = new VPNStats();

	/**
	 * 裁定34: mConnectionState を生成したスレッドのプロファイル UUID。
	 *
	 * ブロードキャストは wakeUpActivity() が mHandler.post した Runnable の中で読むため、
	 * サービスの現在の mUUID を載せると「旧スレッドが出した状態通知に新候補の UUID が付く」
	 * ことが起きる（onStartCommand がメインスレッドで mUUID を差し替えた後に Runnable が
	 * 走るため、代入順序をどう変えても防げない）。フェイルオーバー層はこの UUID で
	 * 「どの接続試行の通知か」を照合するので、状態と UUID は必ず対で運ぶ。
	 */
	private String mStateUUID;

	private VPNLog mVPNLog = new VPNLog();
	private Handler mHandler = new Handler();

	public class LocalBinder extends Binder {
		public OpenVpnService getService() {
			// Return this instance of LocalService so clients can call public methods
			return OpenVpnService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		String action = intent.getAction();
		if( action !=null && action.equals(START_SERVICE))
			return mBinder;
		else
			return super.onBind(intent);
	}

	@Override
	public void onRevoke() {
		Log.i(TAG, "VPN access has been revoked");
		stopVPN();
	}

	@Override
	public void onCreate() {
		// Restore service state from disk if available
		// This gets overwritten if somebody calls startService()
		mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		mUUID = mPrefs.getString("service_mUUID", "");

		mVPNLog.restoreFromFile(getCacheDir().getAbsolutePath() + "/logdata.ser");
		mConnectionStateNames = getResources().getStringArray(R.array.connection_states);
	}

	@Override
	public void onDestroy() {
		killVPNThread(true);
		if (mDeviceStateReceiver != null) {
			this.unregisterReceiver(mDeviceStateReceiver);
		}
		mVPNLog.saveToFile(getCacheDir().getAbsolutePath() + "/logdata.ser");
	}

	private synchronized boolean doStopVPN() {
		if (mVPN != null) {
			mVPN.stopVPN();
			return true;
		}
		return false;
	}

	private void killVPNThread(boolean joinThread) {
		if (doStopVPN() && joinThread) {
			try {
				mVPNThread.join(1000);
			} catch (InterruptedException e) {
				Log.e(TAG, "OpenConnect thread did not exit");
			}
		}
	}

	private PendingIntent getMainActivityIntent() {
		// Touching "Configure" on the system VPN dialog will restore the app
		// (same as clicking the launcher icon)
		Intent intent = new Intent(getBaseContext(), MainActivity.class);
		intent.setAction(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_LAUNCHER);

		PendingIntent startLW = PendingIntent.getActivity(this, 0,
				intent, PendingIntent.FLAG_IMMUTABLE);
		return startLW;
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDeviceStateReceiver(OpenVPNManagement management) {
		// Registers BroadcastReceiver to track network connection changes.
		IntentFilter filter = new IntentFilter();
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(DeviceStateReceiver.PREF_CHANGED);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		mDeviceStateReceiver = new DeviceStateReceiver(management, mPrefs);
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			registerReceiver(mDeviceStateReceiver, filter, Context.RECEIVER_EXPORTED);
		else
			registerReceiver(mDeviceStateReceiver, filter);
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    private synchronized void registerKeepAlive() {
		String DNSServer = "8.8.8.8";
		try {
			String dns = ipInfo.DNS.get(0);
			if (InetAddress.getByName(dns) != null) {
				DNSServer = dns;
			}
		} catch (IndexOutOfBoundsException e) {
			/* empty DNS server list */
		} catch (Exception e) {
			Log.i(TAG, "server DNS IP is bogus, falling back to " + DNSServer + " for KeepAlive", e);
		}

		// set to 40% of the server's idle timeout value, to buy a little margin in case
		// the first 1-2 attempts fail
		int idle = this.mIdleTimeout;
		if (idle < 60 || idle > 7200)
			idle = 1800;
		idle = idle * 4 / 10;
		Log.d(TAG, "calculated KeepAlive interval: " + idle + " seconds");

		IntentFilter filter = new IntentFilter(KeepAlive.ACTION_KEEPALIVE_ALARM);
		mKeepAlive = new KeepAlive(idle, DNSServer, mDeviceStateReceiver);
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			registerReceiver(mKeepAlive, filter, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(mKeepAlive, filter);
		mKeepAlive.start(this);
	}

	private void unregisterReceivers() {
		try {
			if (mDeviceStateReceiver != null) {
				unregisterReceiver(mDeviceStateReceiver);
			}
			mDeviceStateReceiver = null;
		} catch (IllegalArgumentException iae) {
			// catch "Receiver not registered" error
			Log.w(TAG, "can't unregister DeviceStateReceiver", iae);
		}

		try {
			if (mKeepAlive != null) {
				mKeepAlive.stop(this);
				unregisterReceiver(mKeepAlive);
			}
			mKeepAlive = null;
		} catch (IllegalArgumentException iae) {
			Log.w(TAG, "can't unregister KeepAlive", iae);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if (intent == null) {
			Log.e(TAG, "OpenVpnService started with null intent");
			stopSelf();
			return START_NOT_STICKY;
		}

		String action = intent.getAction();
		if (START_SERVICE.equals(action)) {
			return START_NOT_STICKY;
		} else if (START_SERVICE_STICKY.equals(action)) {
			return START_REDELIVER_INTENT;
		}

		// Extract information from the intent.
		String intentUUID = intent.getStringExtra(EXTRA_UUID);
		String newUUID = intentUUID != null ? intentUUID : mUUID;

		if (newUUID == null) {
			return START_NOT_STICKY;
		}

		profile = ProfileManager.get(newUUID);
		if (profile == null) {
			return START_NOT_STICKY;
		}

		// 裁定31b: mUUID の差し替えは旧スレッドを止めた後、かつ新スレッドを
		// start() する前に行う（前半の順序自体はブロードキャストの UUID を
		// 変えない。wakeUpActivity() は mHandler.post() したランナブルの中で
		// 読むため、post が実行されるのは onStartCommand が return した後であり、
		// その時点では mUUID は既にどのみち新候補の値になっている。ブロード
		// キャストの UUID の正しさを保証しているのは裁定34（状態と対の
		// mStateUUID を運ぶ）である。欠陥15 を実際に捕まえているのは状態機械側の
		// 裁定31a の sawCoreConnecting である）。この代入順序を保つ理由は別にある:
		// mUUID = newUUID は killVPNThread(true) の後、かつ mVPNThread.start() の
		// 前でなければならない。後者が破れると新スレッドの STATE_CONNECTING が
		// 旧 UUID を載せてしまい、Ruling 13 の UUID 照合で捨てられて
		// sawCoreConnecting が永久に立たなくなる。
		killVPNThread(true);

		mUUID = newUUID;
		mPrefs.edit().putString("service_mUUID", mUUID).apply();

		// stopSelfResult(most_recent_startId) will kill the service
		// stopSelfResult(previous_startId) will not
		mStartId = startId;

        mVPN = new OpenConnectManagementThread(getApplicationContext(), profile, this);
        mVPNThread = new Thread(mVPN, "OpenVPNManagementThread");
        mVPNThread.start();

		unregisterReceivers();
		registerDeviceStateReceiver(mVPN);

		ProfileManager.setConnectedVpnProfile(profile);

        return START_NOT_STICKY;
    }

	public Builder getVpnServiceBuilder() {
		VpnService.Builder b = new VpnService.Builder();
		b.setSession(profile.mName);
		b.setConfigureIntent(getMainActivityIntent());
		return b;
	}

	// From: https://stackoverflow.com/questions/3758606/how-can-i-convert-byte-size-into-a-human-readable-format-in-java
	public static String humanReadableByteCount(long bytes, boolean mbit) {
		if(mbit)
			bytes = bytes *8;
		int unit = mbit ? 1000 : 1024;
		if (bytes < unit)
			return bytes + (mbit ? " bit" : " B");

		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (mbit ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (mbit ? "" : "");
		if(mbit)
			return String.format(Locale.getDefault(),"%.1f %sbit", bytes / Math.pow(unit, exp), pre);
		else
			return String.format(Locale.getDefault(),"%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static String formatElapsedTime(long startTime) {
		StringBuilder sb = new StringBuilder();
		startTime = (new Date().getTime() - startTime) / 1000;
		if (startTime >= 60 * 60 * 24) {
			// days
			sb.append(String.format(Locale.getDefault(),"%1$d:", startTime / (60 * 60 * 24)));
		}
		if (startTime >= 60 * 60) {
			// hours
			startTime %= 60 * 60 * 24;
			sb.append(String.format(Locale.getDefault(), "%1$02d:", startTime / (60 * 60)));
			startTime %= 60 * 60;
		}
		// minutes:seconds
		sb.append(String.format(Locale.getDefault(), "%1$02d:%2$02d", startTime / 60, startTime % 60));
		return sb.toString();
	}

	/* called from the activity on broadcast receipt, or startup */
	public synchronized void startActiveDialog(Context context) {
		if (mDialog != null && mDialogContext == null) {
			mDialogContext = context;
			mDialog.onStart(context);
		}
	}

	/* called when the activity shuts down (mDialog will be re-rendered when the activity starts again) */
	public synchronized void stopActiveDialog(Context context) {
		if (mDialogContext != context) {
			return;
		}
		if (mDialog != null) {
			mDialog.onStop(mDialogContext);
		}
		mDialogContext = null;
	}

	private synchronized void setDialog(Context context, UserDialog dialog) {
		mDialogContext = context;
		mDialog = dialog;
	}

	@SuppressWarnings("deprecation")
	private void updateNotification() {
		if (mDialog != null && mActivityConnections == 0 && !mNotificationActive) {
			mNotificationActive = true;

			Notification.Builder builder = new Notification.Builder(this)
		            .setSmallIcon(R.drawable.ic_stat_vpn)
		            .setContentTitle(getString(R.string.notification_input_needed))
		            .setContentText(getString(R.string.notification_touch_here))
		            .setContentIntent(getMainActivityIntent());

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, builder.getNotification());
            mNotificationActive = true;
		} else if ((mDialog == null || mActivityConnections > 0) && mNotificationActive) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.cancel(NOTIFICATION_ID);
            mNotificationActive = false;
		}
	}

	private void wakeUpActivity() {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				Intent vpnstatus = new Intent(ACTION_VPN_STATUS);
				vpnstatus.putExtra(EXTRA_CONNECTION_STATE, mConnectionState);
				// 裁定34: 現在の mUUID ではなく、この状態を生成したスレッドの UUID を載せる。
				vpnstatus.putExtra(EXTRA_UUID, mStateUUID != null ? mStateUUID : mUUID);
				sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);

				updateNotification();

				if (mConnectionState == OpenConnectManagementThread.STATE_CONNECTED &&
						mKeepAlive == null) {
					registerKeepAlive();
					FeedbackFragment.recordUse(getApplicationContext(), true);
				}
			}
		});
	}

	public void updateActivityRefcount(int num) {
		mActivityConnections += num;
		updateNotification();
	}

	/* called from the VPN thread; blocks until user responds */
	public Object promptUser(UserDialog dialog) {
		Object ret;

		ret = dialog.earlyReturn();
		if (ret != null) {
			return ret;
		}

		setDialog(null, dialog);
		wakeUpActivity();
		ret = mDialog.waitForResponse();

		setDialog(null, null);
		return ret;
	}

	public synchronized void threadDone() {
		final int startId = mStartId;

		Log.i(TAG, "VPN thread has terminated");
		mVPN = null;
		mHandler.post(new Runnable() {

			@Override
			public void run() {
				if (stopSelfResult(startId) == false) {
					Log.w(TAG, "not stopping service due to startId mismatch");
				} else {
					unregisterReceivers();
				}
			}
		});
	}

	public synchronized void setConnectionState(int state) {
		setConnectionState(state, mUUID);
	}

	public synchronized void setConnectionState(int state, String uuid) {
		if (state == OpenConnectManagementThread.STATE_CONNECTED &&
				mConnectionState != OpenConnectManagementThread.STATE_CONNECTED) {
			startTime = new Date();
		}
		mConnectionState = state;
		mStateUUID = uuid;
		wakeUpActivity();
	}

	public synchronized int getConnectionState() {
		return mConnectionState;
	}

	public String getConnectionStateName() {
		return mConnectionStateNames[getConnectionState()];
	}

	public void requestStats() {
		if (mVPN != null) {
			mVPN.requestStats();
		}
	}

	public synchronized void setStats(VPNStats stats) {
		if (stats != null) {
			mStats = stats;
		}
		wakeUpActivity();
	}

	public synchronized VPNStats getStats() {
		return mStats;
	}

	public synchronized void setIPInfo(LibOpenConnect.IPInfo ipInfo, String serverName, int idleTimeout) {
		this.ipInfo = ipInfo;
		this.serverName = serverName;
		this.mIdleTimeout = idleTimeout;
	}

	public LogArrayAdapter getArrayAdapter(Context context) {
		return mVPNLog.getArrayAdapter(context);
	}

	public void putArrayAdapter(LogArrayAdapter adapter) {
		if (adapter != null) {
			mVPNLog.putArrayAdapter(adapter);
		}
	}

	public void log(final int level, final String msg) {
		mHandler.post(new Runnable() {

			@Override
			public void run() {
				mVPNLog.add(level, msg);
			}
		});
	}

	public void clearLog() {
		mVPNLog.clear();
	}

	public String dumpLog() {
		return mVPNLog.dump();
	}

	public String getReconnectName() {
		VpnProfile p = ProfileManager.get(mUUID);
		return p == null ? null : p.getName();
	}

	public void startReconnectActivity(Context context) {
		Intent intent = new Intent(context, GrantPermissionsActivity.class);
		intent.putExtra(getPackageName() + GrantPermissionsActivity.EXTRA_UUID, mUUID);
		context.startActivity(intent);
	}

	public void stopVPN() {
		killVPNThread(false);
		ProfileManager.setConnectedVpnProfileDisconnected();
	}
}
