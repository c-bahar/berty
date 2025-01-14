package tech.berty.gobridge.bledriver;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;
import static android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY;

public class GattServer {
    // BLE protocol reserves 3 bytes out of MTU_SIZE for metadata
    public static final int ATT_HEADER_SIZE = 3;
    private static final long OP_TIMEOUT = 10000;
    // GATT service UUID
    static final UUID SERVICE_UUID = UUID.fromString("00004240-0000-1000-8000-00805F9B34FB");
    // GATT characteristic used for peer ID exchange
    static final UUID PID_UUID = UUID.fromString("00004241-0000-1000-8000-00805F9B34FB");
    // GATT characteristic used for data exchange
    static final UUID WRITER_UUID = UUID.fromString("00004242-0000-1000-8000-00805F9B34FB");
    // Client Characteristic Configuration (CCC) descriptor of the characteristic
    private final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid P_SERVICE_UUID = new ParcelUuid(SERVICE_UUID);
    private final String TAG = "bty.ble.GattServer";


    // GATT service objects
    private BluetoothGattService mService;

    private final Context mContext;
    private final BluetoothManager mBluetoothManager;
    private CountDownLatch mDoneSignal;
    private GattServerCallback mGattServerCallback;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothServerSocket mBluetoothServerSocket;
    private int PSM = 0;
    private volatile boolean mInit = false;
    private volatile boolean mStarted = false;
    private final Lock mLock = new ReentrantLock();
    private BluetoothGattCharacteristic mWriterCharacteristic;
    private CountDownLatch countDownLatch;
    private CountDownLatch mWriteLatch;

    public GattServer(Context context, BluetoothManager bluetoothManager) {
        mContext = context;
        mBluetoothManager = bluetoothManager;
    }

    public BluetoothServerSocket getBluetoothServerSocket() {
        return mBluetoothServerSocket;
    }

    private boolean initGattService() {
        Log.i(TAG, "initGattService called");

        mService = new BluetoothGattService(SERVICE_UUID, SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic mPIDCharacteristic = new BluetoothGattCharacteristic(PID_UUID, PROPERTY_READ | PROPERTY_WRITE, PERMISSION_READ | PERMISSION_WRITE);
        mWriterCharacteristic = new BluetoothGattCharacteristic(WRITER_UUID, PROPERTY_WRITE | PROPERTY_NOTIFY, PERMISSION_WRITE);
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(CCC_DESCRIPTOR_UUID, PERMISSION_READ | PERMISSION_WRITE);
        descriptor.setValue(new byte[] { 0, 0 });
        mWriterCharacteristic.addDescriptor(descriptor);

        if (!mPIDCharacteristic.setValue("") || !mWriterCharacteristic.setValue("")) {
            Log.e(TAG, "setupService failed: setValue error");
            return false;
        }

        if (!mService.addCharacteristic(mPIDCharacteristic) || !mService.addCharacteristic(mWriterCharacteristic)) {
            Log.e(TAG, "setupService failed: can't add characteristics to service");
            return false;
        }

        mDoneSignal = new CountDownLatch(1);
        mGattServerCallback = new GattServerCallback(mContext, this, mDoneSignal);

        mInit = true;
        return true;
    }

    // After adding a new service, the success of this operation will be given to the callback
    // BluetoothGattServerCallback#onServiceAdded. It's only after this callback that the server
    // will be ready.
    public boolean start(final String peerID) {
        Log.i(TAG, "start called");

        if (!mInit && !initGattService()) {
            Log.e(TAG, "start: GATT service not init");
            return false;
        }
        if (isStarted()) {
            Log.i(TAG, "start: GATT service already started");
            return true;
        }

        mGattServerCallback.setLocalPID(peerID);

        mBluetoothGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);

        // listen for l2cap connections
        // TODO: fix l2cap
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            try {
//                Log.d(TAG, "start: listenUsingL2capChannel");
//                mBluetoothServerSocket = mBluetoothManager.getAdapter().listenUsingInsecureL2capChannel();
//                PSM = mBluetoothServerSocket.getPsm();
//                Log.d(TAG, String.format("start: listenUsingL2capChannel: PSM=%d", PSM));
//            } catch (IOException e) {
//                Log.e(TAG, "start error: listenUsingL2capChannel: ", e);
//                mBluetoothServerSocket = null;
//            }
//        }

        if (!mBluetoothGattServer.addService(mService)) {
            Log.e(TAG, "setupGattServer error: cannot add a new service");
            mBluetoothGattServer.close();
            mBluetoothGattServer = null;
            return false;
        }

        // wait that service starts
        try {
            mDoneSignal.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "start: interrupted exception:", e);
        }

        // mStarted is updated by GattServerCallback
        return isStarted();
    }

    public int getL2capPSM() {
        return PSM;
    }

    public BluetoothGattServer getGattServer() {
        BluetoothGattServer gattServer;
        mLock.lock();
        try {
            gattServer = mBluetoothGattServer;
        } finally {
            mLock.unlock();
        }
        return gattServer;
    }

    public boolean isStarted() {
        boolean started;
        mLock.lock();
        try {
            started = mStarted;
        } finally {
            mLock.unlock();
        }
        return started;
    }

    public void setStarted(boolean started) {
        mLock.lock();
        try {
            mStarted = started;
        } finally {
            mLock.unlock();
        }
    }

    public void stop() {
        Log.i(TAG, "stop() called");
        if (isStarted()) {
            setStarted(false);
            if (mBluetoothServerSocket != null) {
                try {
                    Log.d(TAG, "stop BluetoothServerSocket (L2cap)");
                    mBluetoothServerSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "stop error: cannot close BluetoothServerSocket (L2cap)");
                } finally {
                    mBluetoothServerSocket = null;
                }
            }
            mBluetoothGattServer.close();
            mLock.lock();
            try {
                mBluetoothGattServer = null;
            } finally {
                mLock.unlock();
            }
        }
        mInit = false;
    }

    public void countDownWriteLatch() {
        if (mWriteLatch != null) {
            mWriteLatch.countDown();
        } else {
            Log.e(TAG, "countDownWriteLatch error: object is null");
        }
    }

    private boolean _writeAndNotify(PeerDevice device, byte[] payload) {
        Log.v(TAG, String.format("writeAndNotify: writing chunk of data: device=%s base64=%s value=%s length=%d", device.getMACAddress(), Base64.encodeToString(payload, Base64.DEFAULT), BleDriver.bytesToHex(payload), payload.length));

        if (!mWriterCharacteristic.setValue(payload)) {
            Log.e(TAG, "writeAndNotify: set characteristic failed");
            return false;
        }

        mWriteLatch = new CountDownLatch(1);

        if (!mBluetoothGattServer.notifyCharacteristicChanged(device.getBluetoothDevice(), mWriterCharacteristic, true)) {
            Log.e(TAG, String.format("writeAndNotify: notifyCharacteristicChanged failed for device=%s", device.getMACAddress()));
            return false;
        }

        try {
            mWriteLatch.await(OP_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("writeAndNotify: device=%s: await failed", device.getMACAddress()));
            return false;
        } finally {
            mWriteLatch = null;
        }

        return true;
    }

    public boolean writeAndNotify(PeerDevice device, byte[] payload) {
        Log.v(TAG, String.format("writeAndNotify: device=%s base64=%s value=%s length=%d", device.getMACAddress(), Base64.encodeToString(payload, Base64.DEFAULT), BleDriver.bytesToHex(payload), payload.length));

        if (mBluetoothGattServer == null) {
            Log.e(TAG, "writeAndNotify: GATT server is not running");
            return false;
        }

        if (device.isServerDisconnected()) {
            Log.e(TAG, "writeAndNotify: server is disconnected");
            return false;
        }

        if (mWriterCharacteristic == null) {
            Log.e(TAG, "writeAndNotify: writer characteristic is null");
            return false;
        }

        byte[] toWrite;
        int minOffset = 0;
        int maxOffset;

        // Send data to fit with MTU value
        while (minOffset < payload.length) {
            maxOffset = minOffset + device.getMtu() - ATT_HEADER_SIZE > payload.length ? payload.length : minOffset + device.getMtu() - ATT_HEADER_SIZE;
            toWrite = Arrays.copyOfRange(payload, minOffset, maxOffset);
            minOffset = maxOffset;

            if (!_writeAndNotify(device, toWrite)) {
                return false;
            }
        }

        return true;
    }
}
