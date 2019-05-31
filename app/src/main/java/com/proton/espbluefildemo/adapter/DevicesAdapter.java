package com.proton.espbluefildemo.adapter;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import com.wms.adapter.CommonViewHolder;
import com.wms.adapter.recyclerview.CommonAdapter;

import java.util.List;

/**
 * Created by yuxiongfeng.
 * Date: 2019/5/31
 */
public class DevicesAdapter extends CommonAdapter<BluetoothDevice> {

    public DevicesAdapter(Context context, List<BluetoothDevice> datas, int layoutId) {
        super(context, datas, layoutId);
    }

    @Override
    public void convert(CommonViewHolder holder, BluetoothDevice device) {
        holder.setText(android.R.id.text1,device.getName());
        holder.setText(android.R.id.text2,device.getAddress());
    }

}
