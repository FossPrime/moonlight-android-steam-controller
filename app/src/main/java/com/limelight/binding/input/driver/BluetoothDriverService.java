package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.limelight.LimeLog;

import java.util.HashMap;
import java.util.List;

public class BluetoothDriverService extends Service {

    private BluetoothManager mBluetoothManager;
    private final BroadcastReceiver mBluetoothBroadcast = new BluetoothEventReceiver();
    private final BluetoothDriverBinder binder = new BluetoothDriverBinder();

    private UsbDriverListener listener;
    private final HashMap<BluetoothDevice, SteamController> mBluetoothDevices = new HashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class BluetoothDriverBinder extends Binder {
        public void start() {
            BluetoothDriverService.this.initializeBluetooth();
        }

        public void stop() {
            BluetoothDriverService.this.shutdownBluetooth();
        }

        public void setListener(UsbDriverListener listener) {
            BluetoothDriverService.this.listener = listener;

            if (listener != null) {
                for (AbstractController controller : mBluetoothDevices.values()) {
                    listener.deviceAdded(controller);
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void initializeBluetooth() {
        LimeLog.info("BluetoothDriverService: initializing");

        if (Build.VERSION.SDK_INT <= 30 &&
                getPackageManager().checkPermission(android.Manifest.permission.BLUETOOTH, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            LimeLog.warning("Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH");
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || (Build.VERSION.SDK_INT < 18)) {
            LimeLog.warning("Couldn't initialize Bluetooth, BLE not supported");
            return;
        }

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            LimeLog.warning("BluetoothManager is null");
            return;
        }

        BluetoothAdapter btAdapter = mBluetoothManager.getAdapter();
        if (btAdapter == null) {
            LimeLog.warning("BluetoothAdapter is null");
            return;
        }

        // Check bonded devices first
        try {
            for (BluetoothDevice device : btAdapter.getBondedDevices()) {
                LimeLog.info("BluetoothDriverService: checking bonded device: " + device.getName() + " type=" + device.getType());
                if (isSteamController(device)) {
                    LimeLog.info("BluetoothDriverService: found bonded Steam Controller: " + device.getName());
                    connectBluetoothDevice(device);
                }
            }
        } catch (SecurityException e) {
            LimeLog.warning("Missing Bluetooth Connect permission: " + e.getMessage());
        }

        // Also check currently connected GATT devices — the SC may be connected but not "bonded"
        try {
            List<BluetoothDevice> connectedDevices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
            for (BluetoothDevice device : connectedDevices) {
                LimeLog.info("BluetoothDriverService: checking connected GATT device: " + device.getName() + " type=" + device.getType());
                if (isSteamController(device)) {
                    LimeLog.info("BluetoothDriverService: found connected Steam Controller: " + device.getName());
                    connectBluetoothDevice(device);
                }
            }
        } catch (SecurityException e) {
            LimeLog.warning("SecurityException enumerating connected GATT devices: " + e.getMessage());
        }

        // Also check devices connected via the HID profile
        try {
            // Profile 4 = HID_DEVICE, though we use the raw constant since not all APIs expose it
            for (int profile : new int[]{BluetoothProfile.GATT, 4 /* HID_DEVICE */}) {
                try {
                    List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(profile);
                    for (BluetoothDevice device : devices) {
                        if (isSteamController(device) && !mBluetoothDevices.containsKey(device)) {
                            LimeLog.info("BluetoothDriverService: found connected Steam Controller via profile " + profile + ": " + device.getName());
                            connectBluetoothDevice(device);
                        }
                    }
                } catch (Exception e) {
                    // Some profiles may not be available
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(mBluetoothBroadcast, filter);

        LimeLog.info("BluetoothDriverService: initialization complete, " + mBluetoothDevices.size() + " Steam Controller(s) found");
    }

    private void shutdownBluetooth() {
        try {
            unregisterReceiver(mBluetoothBroadcast);
        } catch (Exception e) {
            // We may not have registered
        }
        
        synchronized (this) {
            for (SteamController device : mBluetoothDevices.values()) {
                device.stop();
            }
            mBluetoothDevices.clear();
        }
    }

    public boolean connectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            if (mBluetoothDevices.containsKey(bluetoothDevice)) {
                LimeLog.info("Steam Controller already tracked: " + bluetoothDevice);
                SteamController device = mBluetoothDevices.get(bluetoothDevice);
                if (device != null) {
                    device.reconnect();
                }
                return false;
            }
            LimeLog.info("BluetoothDriverService: creating SteamController for " + bluetoothDevice);
            SteamController device = new SteamController(listener, this, bluetoothDevice);
            mBluetoothDevices.put(bluetoothDevice, device);
        }
        return true;
    }

    public void disconnectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            SteamController device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null) return;
            mBluetoothDevices.remove(bluetoothDevice);
            device.stop();
        }
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    public boolean isSteamController(BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null) return false;
        
        String name = null;
        try {
            name = bluetoothDevice.getName();
        } catch (SecurityException e) {
            return false;
        }
        
        if (name == null) return false;

        // Match: "Steam Controller", "SteamController", "Steam Ctrl (BT) ...", "Steam Link ..."
        String normalized = name.toLowerCase().replaceAll("\\s+", "");
        boolean nameMatch = normalized.contains("steamcontroller") || 
                            normalized.contains("steamctrl") || 
                            normalized.contains("steamlnk") ||
                            normalized.startsWith("steamctrl");
        
        if (!nameMatch) return false;

        // Accept LE or DUAL type devices
        int deviceType = bluetoothDevice.getType();
        return deviceType == BluetoothDevice.DEVICE_TYPE_LE || 
               deviceType == BluetoothDevice.DEVICE_TYPE_DUAL;
    }

    @Override
    public void onDestroy() {
        shutdownBluetooth();
        super.onDestroy();
    }

    public class BluetoothEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            
            if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                LimeLog.info("BluetoothDriverService: ACL_CONNECTED: " + (device != null ? device.getName() : "null"));
                if (isSteamController(device)) {
                    connectBluetoothDevice(device);
                }
            }

            if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    disconnectBluetoothDevice(device);
                }
            }
        }
    }
}
