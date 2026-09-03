package com.neospos.tap;

import android.app.Application;

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
        // PaymentWakeService is deliberately started by MainActivity only after a successful
        // NEOSPOS login, so a logged-out app does not keep an unnecessary foreground service alive.
    }
}
