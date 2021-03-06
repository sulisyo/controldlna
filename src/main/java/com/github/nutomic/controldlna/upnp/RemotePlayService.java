/*
Copyright (c) 2013, Felix Ableitner
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright
	  notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
	  notice, this list of conditions and the following disclaimer in the
	  documentation and/or other materials provided with the distribution.
 * Neither the name of the <organization> nor the
	  names of its contributors may be used to endorse or promote products
	  derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.github.nutomic.controldlna.upnp;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.github.nutomic.controldlna.R;

import org.teleal.cling.android.AndroidUpnpService;
import org.teleal.cling.android.AndroidUpnpServiceImpl;
import org.teleal.cling.model.action.ActionInvocation;
import org.teleal.cling.model.message.UpnpResponse;
import org.teleal.cling.model.meta.Device;
import org.teleal.cling.model.meta.LocalDevice;
import org.teleal.cling.model.meta.RemoteDevice;
import org.teleal.cling.model.meta.StateVariableAllowedValueRange;
import org.teleal.cling.model.types.ServiceType;
import org.teleal.cling.model.types.UDN;
import org.teleal.cling.registry.Registry;
import org.teleal.cling.registry.RegistryListener;
import org.teleal.cling.support.renderingcontrol.callback.GetVolume;

import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows UPNP playback from different apps by providing a proxy interface.
 * You can communicate to this service via RemotePlayServiceBinder.
 *
 * @author Felix Ableitner
 *
 */
public class RemotePlayService extends Service implements RegistryListener {

	private static final String TAG = "RemotePlayService";

	protected Messenger mListener;

	protected ConcurrentHashMap<String, Device<?, ?, ?>> mDevices =
			new ConcurrentHashMap<String, Device<?, ?, ?>>();

	protected AndroidUpnpService mUpnpService;

	private ServiceConnection mUpnpServiceConnection = new ServiceConnection() {

		/**
		 * Registers DeviceListener, adds known devices and starts search if requested.
		 */
		public void onServiceConnected(ComponentName className, IBinder service) {
			mUpnpService = (AndroidUpnpService) service;
			mUpnpService.getRegistry().addListener(RemotePlayService.this);
			for (Device<?, ?, ?> d : mUpnpService.getControlPoint().getRegistry().getDevices()) {
				if (d instanceof LocalDevice) {
					localDeviceAdded(mUpnpService.getRegistry(), (LocalDevice) d);
				}
				else {
					remoteDeviceAdded(mUpnpService.getRegistry(), (RemoteDevice) d);
				}
			}
			mUpnpService.getControlPoint().search();
		}

		public void onServiceDisconnected(ComponentName className) {
			mUpnpService = null;
		}
	};

	/**
	 * All active binders. The Hashmap value is unused.
	 */
	WeakHashMap<RemotePlayServiceBinder, Boolean> mBinders =
			new WeakHashMap<RemotePlayServiceBinder, Boolean>();

	@Override
	public IBinder onBind(Intent itnent) {
		RemotePlayServiceBinder b = new RemotePlayServiceBinder(this);
		mBinders.put(b, true);
		return b;
	}

	/**
	 * Binds to cling service, registers wifi state change listener.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		bindService(new Intent(this, AndroidUpnpServiceImpl.class),
				mUpnpServiceConnection,	Context.BIND_AUTO_CREATE);

		IntentFilter filter = new IntentFilter();
		filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		registerReceiver(mWifiReceiver, filter);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		unbindService(mUpnpServiceConnection);
		unregisterReceiver(mWifiReceiver);
	}

	/**
	 * Sends msg via Messenger to Provider.
	 */
	void sendMessage(Message msg) {
		if (mListener == null) {
			Log.w(TAG, "Listener is not initialized on send");
		}

		try {
			mListener.send(msg);
		}
		catch (RemoteException e) {
			Log.w(TAG, "Failed to send message", e);
		}
	}

	/**
	 * Sends the error as a message via Messenger.
	 * @param error
	 */
	void sendError(String error) {
		Message msg = Message.obtain(null, Provider.MSG_ERROR, 0, 0);
		msg.getData().putString("error", error);
		sendMessage(msg);
	}

	/**
	 * Starts device search on wifi connect, removes unreachable
	 * devices on wifi disconnect.
	 */
	private BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			ConnectivityManager connManager = (ConnectivityManager)
					getSystemService(CONNECTIVITY_SERVICE);
			NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

			if (wifi.isConnected()) {
				if (mUpnpService != null) {
					for (Device<?, ?, ?> d :
							mUpnpService.getControlPoint().getRegistry().getDevices()) {
						deviceAdded(d);
					}
					mUpnpService.getControlPoint().search();
				}
			}
			else {
				for (Entry<String, Device<?, ?, ?>> d : mDevices.entrySet()) {
					if (mUpnpService.getControlPoint().getRegistry()
							.getDevice(new UDN(d.getKey()), false) == null) {
						deviceRemoved(d.getValue());
						for (RemotePlayServiceBinder b : mBinders.keySet()) {
							if (b.mCurrentRenderer != null &&
									b.mCurrentRenderer.equals(d.getValue())) {
								b.mSubscriptionCallback.end();
								b.mCurrentRenderer = null;
							}
						}
					}
				}
			}
		}
	};

	/**
	 * Returns a device service by name for direct queries.
	 */
	org.teleal.cling.model.meta.Service<?, ?> getService(
			Device<?, ?, ?> device, String name) {
		return device.findService(new ServiceType("schemas-upnp-org", name));
	}

	/**
	 * Gather device data and send it to Provider.
	 */
	private void deviceAdded(final Device<?, ?, ?> device) {
		if (mDevices.containsValue(device))
			return;

		final org.teleal.cling.model.meta.Service<?, ?> rc = getService(device, "RenderingControl");
		if (rc == null || mListener == null)
			return;

		if (device.getType().getType().equals("MediaRenderer") &&
				device instanceof RemoteDevice) {
			mDevices.put(device.getIdentity().getUdn().toString(), device);

			try {
				mUpnpService.getControlPoint().execute(new GetVolume(rc) {

					@SuppressWarnings("rawtypes")
					@Override
					public void failure(ActionInvocation invocation,
							UpnpResponse operation, String defaultMessage) {
						Log.w(TAG, "Failed to get current Volume: " + defaultMessage);
						sendError("Failed to get current Volume: " + defaultMessage);
					}

					@SuppressWarnings("rawtypes")
					@Override
					public void received(ActionInvocation invocation, int currentVolume) {
						int maxVolume = 100;
						if (rc.getStateVariable("Volume") != null) {
							StateVariableAllowedValueRange volumeRange =
									rc.getStateVariable("Volume")
											.getTypeDetails().getAllowedValueRange();
							maxVolume = (int) volumeRange.getMaximum();
						}

						Message msg = Message.obtain(null, Provider.MSG_RENDERER_ADDED, 0, 0);

						String routeName = device.getDetails().getFriendlyName();
						if (getPackageName().endsWith(".debug")) {
							routeName = routeName + " (" + getString(R.string.debug) + ")";
						}
						msg.getData().putParcelable("device", new Provider.Device(
								device.getIdentity().getUdn().toString(),
								routeName,
								device.getDisplayString(),
								currentVolume,
								maxVolume));
						sendMessage(msg);
					}
				});
			}
			catch (IllegalArgumentException e) {
				e.printStackTrace();
				return;
			}
		}
	}

	/**
	 * Remove the device from Provider.
	 */
	private void deviceRemoved(Device<?, ?, ?> device) {
		if (device.getType().getType().equals("MediaRenderer") &&
				device instanceof RemoteDevice) {
			Message msg = Message.obtain(null, Provider.MSG_RENDERER_REMOVED, 0, 0);

			String udn = device.getIdentity().getUdn().toString();
			msg.getData().putString("id", udn);
			mDevices.remove(udn);
			sendMessage(msg);
		}
	}

	/**
	 * If a device was updated, we just add it again (devices are stored in
	 * maps, so adding the same one again just overwrites the old one).
	 */
	private void deviceUpdated(Device<?, ?, ?> device) {
		deviceAdded(device);
	}

	@Override
	public void afterShutdown() {
	}

	@Override
	public void beforeShutdown(Registry registry) {
	}

	@Override
	public void localDeviceAdded(Registry registry, LocalDevice device) {
		deviceAdded(device);
	}

	@Override
	public void localDeviceRemoved(Registry registry, LocalDevice device) {
		deviceRemoved(device);
	}

	@Override
	public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
		deviceAdded(device);
	}

	@Override
	public void remoteDeviceDiscoveryFailed(Registry registry, RemoteDevice device,
			Exception exception) {
		Log.w(TAG, "Remote device discovery failed", exception);
	}

	@Override
	public void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice device) {
	}

	@Override
	public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
		deviceRemoved(device);
	}

	@Override
	public void remoteDeviceUpdated(Registry registry, RemoteDevice device) {
		deviceUpdated(device);
	}

}
