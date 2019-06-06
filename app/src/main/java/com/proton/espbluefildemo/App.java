package com.proton.espbluefildemo;

import com.wms.logger.Logger;

/**
 * Created by yuxiongfeng.
 * Date: 2019/5/31
 */
public class App extends BlufiApp {
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.newBuilder()
                .tag("yxf")
                .showThreadInfo(false)
                .methodCount(1)
                .saveLogCount(7)
                .context(this)
                .deleteOnLaunch(false)
                .saveFile(BuildConfig.DEBUG)
                .isDebug(BuildConfig.DEBUG)
                .build();
    }
}
