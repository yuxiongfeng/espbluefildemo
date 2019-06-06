package com.proton.espbluefildemo.utils;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import com.proton.espbluefildemo.BlufiApp;
import com.proton.espbluefildemo.BlufiConstants;
import com.proton.espbluefildemo.SettingsConstants;
import com.wms.logger.Logger;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import blufi.espressif.BlufiCallback;
import blufi.espressif.BlufiClient;
import blufi.espressif.params.BlufiConfigureParams;
import blufi.espressif.params.BlufiParameter;
import libs.espressif.app.SdkUtil;
import libs.espressif.ble.EspBleUtils;
import libs.espressif.ble.ScanListener;

/**
 * Created by yuxiongfeng.
 * Date: 2019/6/6
 */
public class BluFiUtil {

    private static final long TIMEOUT_SCAN = 2000L;
    private Context mContext;
    private String ssid;
    private String pwd;

    /**
     * 扫描设备
     */
    private List<BluetoothDevice> mBleList=new LinkedList<>();
    private Map<BluetoothDevice, Integer> mDeviceRssiMap;//为了根据信号强度排序
    private ExecutorService mThreadPool;//扫描设备时需要的线程池
    private ScanCallback mScanCallback;
    private Future mUpdateFuture;
    private long mScanStartTime;
    private String mBlufiFilter = "BLUFI";

    /**
     * 设备连接
     */
    private BluetoothGatt mGatt;
    private boolean mConnected;

    OnBluFiSetNetworkListener bluFiSetNetworkListener;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * 配网
     *
     * @param savedInstanceState
     */
    BlufiClient mBlufiClient;

    private static class Inner {
        private static final BluFiUtil instance = new BluFiUtil();
    }

    public static BluFiUtil getInstance() {
        return Inner.instance;
    }

    /**
     * 开始配网入口
     * @param context
     * @param ssid
     * @param pwd
     * @param listener
     */
    public void startConfigWifi(Context context, String ssid, String pwd, OnBluFiSetNetworkListener listener) {
        if (TextUtils.isEmpty(ssid)) {
            Logger.w("ssid is null");
            return;
        }

        if (TextUtils.isEmpty(pwd)) {
            Logger.w("pwd is null");
            return;
        }


        if (mBlufiClient != null) {
            mBlufiClient.close();
            mBlufiClient = null;
        }

        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }

        stopScan();

        this.mContext = context;
        this.ssid = ssid;
        this.pwd = pwd;
        this.bluFiSetNetworkListener = listener;

        mThreadPool = Executors.newSingleThreadExecutor();
        mDeviceRssiMap = new HashMap<>();

        saveStartListener();
        scanDevice();

    }

    /**
     * 扫描设备
     */
    /**
     * 扫描设备
     */
    private void scanDevice() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            saveFailListener();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!locationGPS && !locationNetwork) {
                    saveFailListener();
                    return;
                }
            }
        }

        mScanCallback = new ScanCallback();
        EspBleUtils.startScanBle(mScanCallback);
        Logger.v("开始扫描设备。。。");
        mDeviceRssiMap.clear();
        mBleList.clear();

        mScanStartTime = SystemClock.elapsedRealtime();
        mUpdateFuture = mThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                Logger.v("current thread is :  id:", Thread.currentThread().getId(), "   name : ", Thread.currentThread().getName()
                        , "  current thread interrupted :", Thread.currentThread().isInterrupted());

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                    long scanCost = SystemClock.elapsedRealtime() - mScanStartTime;
                    if (scanCost > TIMEOUT_SCAN) {
                        connect();
                        break;
                    }
                    onIntervalScanUpdate();
                }
            }
        });
    }

    /**
     * 获取peripheral设备
     */
    private void onIntervalScanUpdate() {
        final List<BluetoothDevice> devices = new LinkedList<>(mDeviceRssiMap.keySet());
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice dev1, BluetoothDevice dev2) {
                Integer rssi1 = mDeviceRssiMap.get(dev1);
                Integer rssi2 = mDeviceRssiMap.get(dev2);
                return rssi2.compareTo(rssi1);
            }
        });
        mBleList.clear();
        mBleList.addAll(devices);
    }

    /**
     * 停止扫描设备
     */
    private void stopScan() {
        if (mScanCallback != null) {
            EspBleUtils.stopScanBle(mScanCallback);
        }
        if (mUpdateFuture != null) {
            mUpdateFuture.cancel(true);
        }
        Logger.v("stop ble scan");
    }

    /**
     * 根据信号强度rssi
     * 连接设备
     */
    /**
     * 连接设备
     */
    private void connect() {
        if (mBlufiClient != null) {
            mBlufiClient.close();
            mBlufiClient = null;
        }
        if (mGatt != null) {
            mGatt.close();
        }

        if (mBleList == null || mBleList.size() == 0) {
            saveNotFoundListener();
            return;
        }

        GattCallback callback = new GattCallback();
        if (SdkUtil.isAtLeastM_23()) {
            mGatt = mBleList.get(0).connectGatt(mContext, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mGatt = mBleList.get(0).connectGatt(mContext, false, callback);
        }
    }

    /**
     * 配置网络
     */
    private void configWifi() {
        Logger.v("开始配置wifi。。。");
        if (!mConnected) {
            saveFailListener();
            return;
        }
        String wifiName = ssid.replace('"', ' ').replace('"', ' ').trim();
        BlufiConfigureParams params = new BlufiConfigureParams();
        params.setOpMode(BlufiParameter.OP_MODE_STA);
        params.setStaSSIDBytes(wifiName.getBytes());
        params.setStaPassword(pwd);
        if (mBlufiClient == null) {
            Logger.e("blufiClient 为 null");
            return;
        }
        mBlufiClient.configure(params);
    }


    /**
     * 扫描设备的回调
     */
    private class ScanCallback implements ScanListener {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String name = device.getName();
            if (!TextUtils.isEmpty(mBlufiFilter)) {
                if (name == null || !name.startsWith(mBlufiFilter)) {
                    return;
                }
            }
            Logger.v("扫描到的设备: name = " + device.getName(), "address = " + device.getAddress());
            mDeviceRssiMap.put(device, rssi);
        }
    }


    /**
     * 连接设备的回调
     */
    private class GattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        Logger.v("设备连接成功。。。");
                        gatt.discoverServices();//获取BlutoothGattService
                        mConnected = true;
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        gatt.close();
                        saveFailListener();
                        mConnected = false;
                        break;
                }
            } else {
                gatt.close();
                saveFailListener();
                Logger.v("设备连接失败。。。");
            }
        }

        /**
         * 连接成功后获取BluetoothGattService   gatt.discoverServices();的回调
         *
         * @param gatt
         * @param status
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BlufiConstants.UUID_SERVICE);
                if (service == null) {
                    Logger.v("discover services fail ");
                    gatt.disconnect();
                    saveFailListener();
                }
                //获得 BluetoothGattCharacteristic  用于app向设备写数据
                BluetoothGattCharacteristic writeCharact = service.getCharacteristic(BlufiConstants.UUID_WRITE_CHARACTERISTIC);
                if (writeCharact == null) {
                    Logger.v("get writeCharact fail");
                    gatt.disconnect();
                    saveFailListener();
                }

                //接收device向app推送的消息
                BluetoothGattCharacteristic notifyCharact = service.getCharacteristic(BlufiConstants.UUID_NOTIFICATION_CHARACTERISTIC);
                if (notifyCharact == null) {
                    Logger.v("get notification characteristic fail");
                    gatt.disconnect();
                    saveFailListener();
                }

                /**
                 * 配网客户端
                 */
                if (mBlufiClient != null) {
                    mBlufiClient.close();
                }
                mBlufiClient = new BlufiClient(gatt, writeCharact, notifyCharact, new BlufiCallbackMain());
                gatt.setCharacteristicNotification(notifyCharact, true);

                if (SdkUtil.isAtLeastL_21()) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    int mtu = (int) BlufiApp.getInstance().settingsGet(
                            SettingsConstants.PREF_SETTINGS_KEY_MTU_LENGTH, BlufiConstants.DEFAULT_MTU_LENGTH);
                    boolean requestMtu = gatt.requestMtu(mtu);
                    if (!requestMtu) {
                        Logger.w("Request mtu failed");
                    }
                }

                mThreadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        configWifi();
                    }
                });

            } else {
                gatt.disconnect();
                saveFailListener();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            mBlufiClient.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            mBlufiClient.onCharacteristicChanged(gatt, characteristic);
        }


        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
        }
    }


    private class BlufiCallbackMain extends BlufiCallback {

        // 发送配置信息的结果
        public void onConfigureResult(BlufiClient client, int status) {
            switch (status) {
                case STATUS_SUCCESS:
                    saveSuccessListener(mBleList.get(0).getAddress(), ssid);
                    break;
                default:
                    saveFailListener();
                    break;
            }
        }

    }

    /**
     * 开始配网的回调
     */
    private void saveStartListener() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bluFiSetNetworkListener.onStart();
            }
        });
    }

    /**
     * 配网成功的回调
     *
     * @param macaddress
     * @param bssid
     */
    private void saveSuccessListener(final String macaddress, final String bssid) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bluFiSetNetworkListener.onSuccess(macaddress, bssid);
            }
        });
    }

    /**
     * 配网失败的回调
     */
    private void saveFailListener() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bluFiSetNetworkListener.onFail();
            }
        });
    }

    /**
     * 没有搜到可用设备的回调
     */
    private void saveNotFoundListener() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bluFiSetNetworkListener.onNotFound();
            }
        });
    }


    public interface OnBluFiSetNetworkListener {
        /**
         * 开始配网
         */
        void onStart();

        /**
         * 配网成功
         */
        void onSuccess(String macaddress, String bssid);

        /**
         * 配网失败
         */
        void onFail();

        /**
         * 没搜索到设备
         */
        void onNotFound();
    }

}
