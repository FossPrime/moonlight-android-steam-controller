package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.UUID;

public class SteamController extends AbstractController {

    private final BluetoothDriverService mManager;
    private final BluetoothDevice mDevice;
    private BluetoothGatt mGatt;
    private final Callback mCallback;
    private final Handler mHandler;
    private final LinkedList<GattOperation> mOperations;
    private GattOperation mCurrentOperation = null;

    // Valve BLE GATT service and characteristics (shared across SC generations)
    static final UUID steamControllerService = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");

    // D0G (2015 SC) input characteristic
    static final UUID inputCharacteristicD0G = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3");

    // Triton (2026 SC) input characteristics
    static final UUID inputCharacteristicTriton_0x45 = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3");
    static final UUID inputCharacteristicTriton_0x47 = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3");

    // Report characteristic (for entering valve mode on D0G)
    static final UUID reportCharacteristic = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");

    // D0G valve mode command
    static final byte[] enterValveMode = new byte[] { (byte)0xC0, (byte)0x87, 0x03, 0x08, 0x07, 0x00 };

    private static final int D0G_BLE2_PID = 0x1106;
    private static final int TRITON_BLE_PID = 0x1303;

    private int mDetectedProductId = -1;
    private UUID mInputCharacteristic;
    private boolean mIsRegistered;
    private boolean mIsConnected;
    private int lastRawButtons = -1;


    @SuppressLint("NewApi")
    private class Callback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == 2) {
                mIsConnected = true;
                mHandler.post(new Runnable() {
                    @SuppressLint("MissingPermission")
                    @Override
                    public void run() {
                        if (mGatt != null) {
                            mGatt.discoverServices();
                        }
                    }
                });
            } else if (newState == 0) {
                mIsConnected = false;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mIsRegistered) {
                            notifyDeviceRemoved();
                            mIsRegistered = false;
                        }
                    }
                });
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == 0) {
                if (gatt.getServices().size() == 0) {
                    LimeLog.warning("BLE services empty, reconnecting...");
                    gatt.disconnect();
                    mGatt = connectGatt(false);
                } else {
                    // Request large MTU for Triton DLE support
                    if (isTriton()) {
                        mGatt.requestMtu(517);
                    }
                    probeService();
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            finishCurrentGattOperation();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.getUuid().equals(reportCharacteristic)) {
                // D0G: register after writing enterValveMode
                if (!mIsRegistered) {
                    LimeLog.info("Registering D0G Steam Controller: " + getIdentifier());
                    mIsRegistered = true;
                    notifyDeviceAdded();
                }
            }
            finishCurrentGattOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (mInputCharacteristic != null && characteristic.getUuid().equals(mInputCharacteristic)) {
                handleRead(ByteBuffer.wrap(characteristic.getValue()));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            BluetoothGattCharacteristic chr = descriptor.getCharacteristic();
            if (mInputCharacteristic != null && chr.getUuid().equals(mInputCharacteristic)) {
                enableValveMode();
            }
            finishCurrentGattOperation();
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            LimeLog.info("BLE MTU changed to " + mtu + " status=" + status);
        }
    }

    private boolean isTriton() {
        if (mDetectedProductId == TRITON_BLE_PID) return true;
        try {
            @SuppressLint("MissingPermission")
            String name = mDevice.getName();
            return name != null && name.startsWith("Steam Ctrl");
        } catch (SecurityException e) {
            return false;
        }
    }

    private void enableValveMode() {
        if (mDetectedProductId == TRITON_BLE_PID) {
            // Triton doesn't need enterValveMode — just register
            if (!mIsRegistered) {
                LimeLog.info("Registering Triton Steam Controller: " + getIdentifier());
                mIsRegistered = true;
                notifyDeviceAdded();
            }
        } else {
            // D0G: write enterValveMode to reportCharacteristic
            LimeLog.info("Writing enterValveMode for D0G controller");
            writeCharacteristic(reportCharacteristic, enterValveMode);
        }
    }

    public SteamController(UsbDriverListener listener, BluetoothDriverService manager, BluetoothDevice device) {
        super(device.getAddress().hashCode(), listener, 0x28de, TRITON_BLE_PID);
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;

        mManager = manager;
        mDevice = device;
        mCallback = new Callback();
        mHandler = new Handler(Looper.getMainLooper());
        mOperations = new LinkedList<>();

        mGatt = connectGatt(false);
    }

    @SuppressLint("NewApi")
    private void probeService() {
        if (mIsRegistered) return;
        if (!mIsConnected) return;

        for (BluetoothGattService service : mGatt.getServices()) {
            if (service.getUuid().equals(steamControllerService)) {
                LimeLog.info("Found Valve Steam Controller GATT service");

                for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
                    if (chr.getUuid().equals(inputCharacteristicTriton_0x45)) {
                        LimeLog.info("Found Triton input characteristic 0x45");
                        mDetectedProductId = TRITON_BLE_PID;
                        mInputCharacteristic = chr.getUuid();
                    } else if (chr.getUuid().equals(inputCharacteristicTriton_0x47)) {
                        LimeLog.info("Found Triton input characteristic 0x47");
                        mDetectedProductId = TRITON_BLE_PID;
                        mInputCharacteristic = chr.getUuid();
                    } else if (chr.getUuid().equals(inputCharacteristicD0G)) {
                        LimeLog.info("Found D0G input characteristic");
                        mDetectedProductId = D0G_BLE2_PID;
                        mInputCharacteristic = chr.getUuid();
                    }
                }

                if (mInputCharacteristic != null) {
                    LimeLog.info("Enabling notifications on input characteristic");
                    enableNotification(mInputCharacteristic);
                } else {
                    LimeLog.warning("No known input characteristic found in Valve service");
                }
                return;
            }
        }
        LimeLog.warning("Valve Steam Controller GATT service not found among " + mGatt.getServices().size() + " services");
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    private BluetoothGatt connectGatt(boolean managed) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return mDevice.connectGatt(mManager, managed, mCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) {
                return mDevice.connectGatt(mManager, managed, mCallback);
            }
        } else {
            return mDevice.connectGatt(mManager, managed, mCallback);
        }
    }

    public String getIdentifier() {
        return String.format("SteamController.%s", mDevice.getAddress());
    }

    protected void handleRead(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 1) return;

        // For Triton over BLE, the report ID is NOT in the data — it's implied by the characteristic.
        // For D0G, the first byte is a chunk-type indicator.
        if (mDetectedProductId == TRITON_BLE_PID) {
            handleTritonReport(buffer);
        } else {
            handleD0GReport(buffer);
        }
    }

    private void handleTritonReport(ByteBuffer buffer) {
        // Triton 0x45 report: the data starts at offset 0 (no report ID byte over BLE)
        // Layout matches SteamController.h from SteamlessController
        if (buffer.remaining() < 17) return;

        // Keep a copy of the raw bytes for logging
        buffer.mark();
        byte[] rawReport = new byte[buffer.remaining()];
        buffer.get(rawReport);
        buffer.reset();

        buffer.get(); // sequence counter

        int b0 = Byte.toUnsignedInt(buffer.get());
        int b1 = Byte.toUnsignedInt(buffer.get());
        int b2 = Byte.toUnsignedInt(buffer.get());
        int b3 = Byte.toUnsignedInt(buffer.get()); // read 4th byte (formerly ignored flags)

        int rawButtons = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);

        if (rawButtons != lastRawButtons) {
            lastRawButtons = rawButtons;
            
            java.util.ArrayList<String> pressedButtons = new java.util.ArrayList<>();
            if ((b0 & 0x01) != 0) pressedButtons.add("A");
            if ((b0 & 0x02) != 0) pressedButtons.add("B");
            if ((b0 & 0x04) != 0) pressedButtons.add("X");
            if ((b0 & 0x08) != 0) pressedButtons.add("Y");
            if ((b0 & 0x10) != 0) pressedButtons.add("SDL_GAMEPAD_BUTTON_MISC1");
            if ((b0 & 0x20) != 0) pressedButtons.add("RS Click");
            if ((b0 & 0x40) != 0) pressedButtons.add("Start");
            if ((b0 & 0x80) != 0) pressedButtons.add("Right Paddle 1");
            if ((b1 & 0x01) != 0) pressedButtons.add("Right Paddle 2");
            if ((b1 & 0x02) != 0) pressedButtons.add("RB");
            if ((b1 & 0x04) != 0) pressedButtons.add("DPAD Down");
            if ((b1 & 0x08) != 0) pressedButtons.add("DPAD Right");
            if ((b1 & 0x10) != 0) pressedButtons.add("DPAD Left");
            if ((b1 & 0x20) != 0) pressedButtons.add("DPAD Up");
            if ((b1 & 0x40) != 0) pressedButtons.add("Back");
            if ((b1 & 0x80) != 0) pressedButtons.add("LS Click");
            if ((b2 & 0x01) != 0) pressedButtons.add("Guide");
            if ((b2 & 0x02) != 0) pressedButtons.add("Left Paddle 1");
            if ((b2 & 0x04) != 0) pressedButtons.add("Left Paddle 2");
            if ((b2 & 0x08) != 0) pressedButtons.add("LB");

            LimeLog.info("Triton Buttons Change: " + pressedButtons.toString());
        }

        setButtonFlag(ControllerPacket.A_FLAG, b0 & 0x01);
        setButtonFlag(ControllerPacket.B_FLAG, b0 & 0x02);
        setButtonFlag(ControllerPacket.X_FLAG, b0 & 0x04);
        setButtonFlag(ControllerPacket.Y_FLAG, b0 & 0x08);
        setButtonFlag(ControllerPacket.MISC_FLAG, b0 & 0x10);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b0 & 0x20);
        setButtonFlag(ControllerPacket.PLAY_FLAG, b0 & 0x40);
        setButtonFlag(ControllerPacket.PADDLE1_FLAG, b0 & 0x80);

        setButtonFlag(ControllerPacket.PADDLE3_FLAG, b1 & 0x01);
        setButtonFlag(ControllerPacket.RB_FLAG, b1 & 0x02);
        setButtonFlag(ControllerPacket.DOWN_FLAG, b1 & 0x04);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b1 & 0x08);
        setButtonFlag(ControllerPacket.LEFT_FLAG, b1 & 0x10);
        setButtonFlag(ControllerPacket.UP_FLAG, b1 & 0x20);
        setButtonFlag(ControllerPacket.BACK_FLAG, b1 & 0x40);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b1 & 0x80);

        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b2 & 0x01);
        setButtonFlag(ControllerPacket.PADDLE2_FLAG, b2 & 0x02);
        setButtonFlag(ControllerPacket.PADDLE4_FLAG, b2 & 0x04);
        setButtonFlag(ControllerPacket.LB_FLAG, b2 & 0x08);

        short ltVal = buffer.getShort();
        short rtVal = buffer.getShort();
        leftTrigger = Math.max(0, (float) ltVal / (float) 0x7FFF);
        rightTrigger = Math.max(0, (float) rtVal / (float) 0x7FFF);

        short lsX = buffer.getShort();
        short lsY = buffer.getShort();
        leftStickX = (float) lsX / (float) Short.MAX_VALUE;
        leftStickY = -(float) lsY / (float) Short.MAX_VALUE;

        short rsX = buffer.getShort();
        short rsY = buffer.getShort();
        rightStickX = (float) rsX / (float) Short.MAX_VALUE;
        rightStickY = -(float) rsY / (float) Short.MAX_VALUE;

        reportInput();
    }

    private void handleD0GReport(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return;
        int type = Byte.toUnsignedInt(buffer.get()) | Byte.toUnsignedInt(buffer.get()) << 8;

        if ((type & 0x10) != 0) {
            if (buffer.remaining() < 3) return;
            byte[] buttons = new byte[3];
            buffer.get(buttons);
            long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, (int) (b & 0x01));
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, (int) (b & 0x02));
            setButtonFlag(ControllerPacket.RB_FLAG, (int) (b & 0x04));
            setButtonFlag(ControllerPacket.LB_FLAG, (int) (b & 0x08));
            setButtonFlag(ControllerPacket.Y_FLAG, (int) (b & 0x10));
            setButtonFlag(ControllerPacket.B_FLAG, (int) (b & 0x20));
            setButtonFlag(ControllerPacket.X_FLAG, (int) (b & 0x40));
            setButtonFlag(ControllerPacket.A_FLAG, (int) (b & 0x80));
            setButtonFlag(ControllerPacket.UP_FLAG, (int) (b & 0x100));
            setButtonFlag(ControllerPacket.RIGHT_FLAG, (int) (b & 0x200));
            setButtonFlag(ControllerPacket.LEFT_FLAG, (int) (b & 0x400));
            setButtonFlag(ControllerPacket.DOWN_FLAG, (int) (b & 0x800));
            setButtonFlag(ControllerPacket.BACK_FLAG, (int) (b & 0x1000));
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (int) (b & 0x2000));
            setButtonFlag(ControllerPacket.PLAY_FLAG, (int) (b & 0x4000));
        }
        if ((type & 0x20) != 0) {
            if (buffer.remaining() < 2) return;
            leftTrigger = Byte.toUnsignedInt(buffer.get()) / 255.0f;
            rightTrigger = Byte.toUnsignedInt(buffer.get()) / 255.0f;
        }
        if ((type & 0x40) != 0) {
            if (buffer.remaining() < 3) return;
            buffer.get(); buffer.get(); buffer.get(); // skip
        }
        if ((type & 0x80) != 0) {
            if (buffer.remaining() < 4) return;
            leftStickX = buffer.getShort() / (float) Short.MAX_VALUE;
            leftStickY = ~buffer.getShort() / (float) Short.MAX_VALUE;
        }
        if ((type & 0x100) != 0) {
            if (buffer.remaining() < 4) return;
            buffer.getShort(); buffer.getShort(); // skip trackpad
        }
        if ((type & 0x200) != 0) {
            if (buffer.remaining() < 4) return;
            rightStickX = buffer.getShort() / (float) Short.MAX_VALUE;
            rightStickY = ~buffer.getShort() / (float) Short.MAX_VALUE;
        }
        reportInput();
    }

    @Override
    public boolean start() { return true; }

    @SuppressLint("MissingPermission")
    @Override
    public void stop() {
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {}

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {}

    public void reconnect() {
        stop();
        mGatt = connectGatt(false);
    }

    // --- GATT operation queue ---

    static class GattOperation {
        enum Operation { CHR_READ, CHR_WRITE, ENABLE_NOTIFICATION }
        Operation mOp;
        UUID mUuid;
        byte[] mValue;
        BluetoothGatt mGatt;
        boolean mResult = true;

        private GattOperation(BluetoothGatt gatt, Operation op, UUID uuid) { mGatt = gatt; mOp = op; mUuid = uuid; }
        private GattOperation(BluetoothGatt gatt, Operation op, UUID uuid, byte[] value) { mGatt = gatt; mOp = op; mUuid = uuid; mValue = value; }

        @SuppressLint({"NewApi", "MissingPermission"})
        public void run() {
            BluetoothGattCharacteristic chr;
            switch (mOp) {
                case CHR_READ:
                    chr = getCharacteristic(mUuid);
                    if (chr == null || !mGatt.readCharacteristic(chr)) { mResult = false; }
                    break;
                case CHR_WRITE:
                    chr = getCharacteristic(mUuid);
                    if (chr == null) { mResult = false; break; }
                    chr.setValue(mValue);
                    if (!mGatt.writeCharacteristic(chr)) { mResult = false; }
                    break;
                case ENABLE_NOTIFICATION:
                    chr = getCharacteristic(mUuid);
                    if (chr != null) {
                        BluetoothGattDescriptor cccd = chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null) {
                            int props = chr.getProperties();
                            byte[] value;
                            if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                            } else if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            } else { mResult = false; return; }
                            mGatt.setCharacteristicNotification(chr, true);
                            cccd.setValue(value);
                            if (!mGatt.writeDescriptor(cccd)) { mResult = false; return; }
                            mResult = true;
                        }
                    }
                    break;
            }
        }

        public boolean finish() { return mResult; }

        @SuppressLint("NewApi")
        private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
            BluetoothGattService svc = mGatt.getService(steamControllerService);
            return svc == null ? null : svc.getCharacteristic(uuid);
        }

        static GattOperation readCharacteristic(BluetoothGatt gatt, UUID uuid) { return new GattOperation(gatt, Operation.CHR_READ, uuid); }
        static GattOperation writeCharacteristic(BluetoothGatt gatt, UUID uuid, byte[] value) { return new GattOperation(gatt, Operation.CHR_WRITE, uuid, value); }
        static GattOperation enableNotification(BluetoothGatt gatt, UUID uuid) { return new GattOperation(gatt, Operation.ENABLE_NOTIFICATION, uuid); }
    }

    private void finishCurrentGattOperation() {
        GattOperation op = null;
        synchronized (mOperations) {
            if (mCurrentOperation != null) { op = mCurrentOperation; mCurrentOperation = null; }
        }
        if (op != null && !op.finish()) { mOperations.addFirst(op); }
        executeNextGattOperation();
    }

    private void executeNextGattOperation() {
        synchronized (mOperations) {
            if (mCurrentOperation != null || mOperations.isEmpty()) return;
            mCurrentOperation = mOperations.removeFirst();
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mOperations) {
                    if (mCurrentOperation != null) mCurrentOperation.run();
                }
            }
        });
    }

    private void queueGattOperation(GattOperation op) {
        synchronized (mOperations) { mOperations.add(op); }
        executeNextGattOperation();
    }

    private void enableNotification(UUID chrUuid) { queueGattOperation(GattOperation.enableNotification(mGatt, chrUuid)); }
    public void writeCharacteristic(UUID uuid, byte[] value) { queueGattOperation(GattOperation.writeCharacteristic(mGatt, uuid, value)); }
    public void readCharacteristic(UUID uuid) { queueGattOperation(GattOperation.readCharacteristic(mGatt, uuid)); }
}
