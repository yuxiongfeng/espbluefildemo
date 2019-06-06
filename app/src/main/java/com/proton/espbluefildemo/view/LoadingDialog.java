package com.proton.espbluefildemo.view;

import android.app.Dialog;
import android.content.Context;
import android.support.annotation.NonNull;
import android.view.WindowManager;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;

import com.proton.espbluefildemo.R;

/**
 * Created by yuxiongfeng.
 * Date: 2019/6/6
 */
public class LoadingDialog extends Dialog {

    ImageView ivLoading;
    public LoadingDialog(@NonNull Context context) {
        this(context,R.style.Loading_dialog);
    }

    public LoadingDialog(@NonNull Context context, int themeResId) {
        super(context, themeResId);
        setContentView(R.layout.dialog_loading_layout);
        WindowManager windowManager = getWindow().getWindowManager();
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.width=screenWidth/3;
        attributes.height=screenWidth/3;
        getWindow().setAttributes(attributes);
        setCancelable(true);

        ivLoading = this.findViewById(R.id.iv_loading);
        ivLoading.measure(0,0);
        RotateAnimation rotateAnimation=new RotateAnimation(0,360,ivLoading.getMeasuredWidth()/2,ivLoading.getMeasuredHeight()/2);
        rotateAnimation.setDuration(3000);
        rotateAnimation.setRepeatCount(-1);
        ivLoading.setAnimation(rotateAnimation);
    }

    @Override
    public void dismiss() {
        super.dismiss();
        ivLoading.clearAnimation();
    }
}
