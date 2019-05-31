package com.proton.espbluefildemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.proton.espbluefildemo.adapter.DevicesAdapter;
import com.tbruyelle.rxpermissions2.RxPermissions;
import com.wms.adapter.recyclerview.OnItemClickListener;
import com.wms.logger.Logger;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import blufi.espressif.BlufiClient;
import io.reactivex.functions.Consumer;
import libs.espressif.app.SdkUtil;
import libs.espressif.ble.EspBleUtils;
import libs.espressif.ble.ScanListener;

public class ConfigNetActivity extends AppCompatActivity implements View.OnClickListener, SwipeRefreshLayout.OnRefreshListener {
    private static final long TIMEOUT_SCAN = 4000L;
    final RxPermissions rxPermissions = new RxPermissions(this);
    EditText etSsid, etPwd;
    Button btnConfig;


    /**
     * 扫描设备
     */
    RecyclerView recyclerview;
    private List<BluetoothDevice> mBleList;
    private Map<BluetoothDevice, Integer> mDeviceRssiMap;//为了根据信号强度排序
    private ExecutorService mThreadPool;//扫描设备时需要的线程池
    private ScanCallback mScanCallback;
    private Future mUpdateFuture;
    private long mScanStartTime;
    private DevicesAdapter adapter;
    SwipeRefreshLayout mRefreshLayout;
    private String mBlufiFilter="BLUFI";
    private BluetoothDevice mDevice;

    /**
     * 设备连接
     */
    private BluetoothGatt mGatt;
    private boolean mConnected;

    /**
     * 配网
     * @param savedInstanceState
     */
    BlufiClient mBlufiClient;


    @SuppressLint("CheckResult")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_net_layout);
        initView();
        mThreadPool = Executors.newSingleThreadExecutor();
        mDeviceRssiMap = new HashMap<>();

        rxPermissions
                .request(Manifest.permission.ACCESS_COARSE_LOCATION)
                .subscribe(new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean granted) {
                        if (granted) {
                            scanDevice();
                        } else {
                            Toast.makeText(ConfigNetActivity.this, "没有权限", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    }
                });
    }

    private void initView() {
        etSsid = findViewById(R.id.et_wifi_ssid);
        etPwd = findViewById(R.id.et_wifi_password);
        btnConfig = findViewById(R.id.btn_config);
        recyclerview = findViewById(R.id.recyclerview);
        mRefreshLayout = findViewById(R.id.swipefreshview);
        btnConfig.setOnClickListener(this);
        mRefreshLayout.setOnRefreshListener(this);

        mBleList = new LinkedList<>();
        adapter = new DevicesAdapter(this, mBleList, android.R.layout.simple_list_item_2);
        recyclerview.setAdapter(adapter);
        recyclerview.setLayoutManager(new LinearLayoutManager(this));


        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(ViewGroup parent, View view, Object o, int position) {
                mDevice=mBleList.get(position);
                connect();
            }

            @Override
            public boolean onItemLongClick(ViewGroup parent, View view, Object o, int position) {
                return false;
            }
        });
    }

    private void onIntervalScanUpdate(final boolean over) {

        final List<BluetoothDevice> devices = new LinkedList<>(mDeviceRssiMap.keySet());
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice dev1, BluetoothDevice dev2) {
                Integer rssi1 = mDeviceRssiMap.get(dev1);
                Integer rssi2 = mDeviceRssiMap.get(dev2);
                return rssi2.compareTo(rssi1);
            }
        });

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBleList.clear();
                mBleList.addAll(devices);
                adapter.notifyDataSetChanged();
                if (over) {
                    mRefreshLayout.setRefreshing(false);
                }
            }
        });
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_config:
                break;
        }
    }

    @Override
    public void onRefresh() {
        scanDevice();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }

    /**
     * 扫描设备
     */
    private void scanDevice() {


        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            Toast.makeText(this, "bluetooot is unEnabled", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager != null) {
                boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!locationGPS && !locationNetwork) {
                    Toast.makeText(this, "location is disable", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }

        mScanCallback = new ScanCallback();
        EspBleUtils.startScanBle(mScanCallback);
        mDeviceRssiMap.clear();
        mBleList.clear();
        adapter.notifyDataSetChanged();

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
                        break;
                    }
                    onIntervalScanUpdate(false);
                }
                onIntervalScanUpdate(true);

            }
        });

    }

    /**
     * 停止扫描设备
     */
    private void stopScan() {
        EspBleUtils.stopScanBle(mScanCallback);
        if (mUpdateFuture!=null) {
            mUpdateFuture.cancel(true);
        }
        Logger.v("stop ble scan");
    }

    /**
     * 连接设备
     */
    private void connect() {
        GattCallback callback = new GattCallback();
        if (SdkUtil.isAtLeastM_23()) {
            mGatt = mDevice.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mGatt = mDevice.connectGatt(this, false, callback);
        }
    }

    /**
     * 配置网络
     */
    private void configWifi() {

    }


    private class ScanCallback implements ScanListener {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String name = device.getName();
            if (!TextUtils.isEmpty(mBlufiFilter)) {
                if (name == null || !name.startsWith(mBlufiFilter)) {
                    return;
                }
            }

            mDeviceRssiMap.put(device, rssi);
        }
    }

    private class GattCallback extends BluetoothGattCallback{

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String devAddr = gatt.getDevice().getAddress();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        gatt.discoverServices();//获取BlutoothGattService
                        mConnected = true;
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        gatt.close();
                        mConnected=false;
                        break;
                }
            }else {
                gatt.close();
            }
        }

        /**
         *   连接成功后获取BluetoothGattService   gatt.discoverServices();的回调
         * @param gatt
         * @param status
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status==BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BlufiConstants.UUID_SERVICE);
                if (service==null) {
                    Logger.v("discover services fail ");
                    gatt.disconnect();
                }
                //获得 BluetoothGattCharacteristic  用于app向设备写数据
                BluetoothGattCharacteristic writeCharact = service.getCharacteristic(BlufiConstants.UUID_WRITE_CHARACTERISTIC);
                if (writeCharact==null) {
                    Logger.v("get writeCharact fail");
                    gatt.disconnect();
                }

                //接收device向app推送的消息
                BluetoothGattCharacteristic notifyCharact = service.getCharacteristic(BlufiConstants.UUID_NOTIFICATION_CHARACTERISTIC);
                if (notifyCharact==null) {
                    Logger.v("get notification characteristic fail");
                    gatt.disconnect();
                }


                /**
                 * 配网客户端
                 */
                if (mBlufiClient != null) {
                    mBlufiClient.close();
                }
//                mBlufiClient = new BlufiClient(gatt, writeCharact, notifyCharact, blufiCallback);
                gatt.setCharacteristicNotification(notifyCharact,true);

                if (SdkUtil.isAtLeastL_21()) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    int mtu = (int) BlufiApp.getInstance().settingsGet(
                            SettingsConstants.PREF_SETTINGS_KEY_MTU_LENGTH, BlufiConstants.DEFAULT_MTU_LENGTH);
                    boolean requestMtu = gatt.requestMtu(mtu);
                    if (!requestMtu) {
                        Logger.w("Request mtu failed");
//                        onGattServiceCharacteristicDiscovered();
                    }
                }

            }else {
                gatt.disconnect();
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

}
