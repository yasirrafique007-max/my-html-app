package com.neospos.tap;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

import com.stripe.stripeterminal.TerminalApplicationDelegate;
import com.stripe.stripeterminal.taptopay.TapToPay;

public class NeosposApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (TapToPay.isInTapToPayProcess()) {
            return;
        }
        TerminalApplicationDelegate.onCreate(this);
        try {
            Intent listener = new Intent(this, PaymentWakeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(listener);
            else startService(listener);
        } catch (Exception ignored) {
        }
    }
}
