package com.proton.espbluefildemo;

import android.app.Application;

import com.wms.logger.Logger;

/**
 * Created by yuxiongfeng.
 * Date: 2019/5/31
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.newBuilder()
                .tag("configwifi")
                .saveFile(true)
                .context(this)
                .build();
    }
}
