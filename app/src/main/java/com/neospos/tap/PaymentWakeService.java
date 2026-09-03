package com.neospos.tap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.stripe.stripeterminal.Terminal;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PaymentWakeService extends Service {
    public static final String ACTION_PAYMENT_WAKE = "com.neospos.tap.PAYMENT_WAKE";
    private static final String CHANNEL_ACTIVE = "neospos_terminal_active";
    private static final String CHANNEL_PAYMENT = "neospos_payment_request";
    private static final int NOTIFICATION_ID = 2201;

    private ScheduledExecutorService scheduler;
    private volatile String lastRequestId = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTIFICATION_ID, activeNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollQueue, 0, 3, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void pollQueue() {
        try {
            if (!Backend.get().isLoggedIn()) return;
            if (!Terminal.isInitialized() || Terminal.getInstance().getConnectedReader() == null) {
                lastRequestId = "";
                return;
            }
            Backend.PendingRequest pending = Backend.get().peekPendingEposRequest();
            if (pending == null) {
                lastRequestId = "";
                return;
            }
            if (pending.requestId.equals(lastRequestId)) return;
            lastRequestId = pending.requestId;
            wakeForPayment(pending);
        } catch (Exception ignored) {
            // The foreground app also polls while connected. A transient background failure
            // should not crash or restart the service.
        }
    }

    private void wakeForPayment(Backend.PendingRequest pending) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wake = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                            PowerManager.ACQUIRE_CAUSES_WAKEUP |
                            PowerManager.ON_AFTER_RELEASE,
                    "neospos:payment-wake");
            wake.acquire(12000);
        }

        Intent activity = new Intent(this, MainActivity.class)
                .setAction(ACTION_PAYMENT_WAKE)
                .putExtra("request_id", pending.requestId)
                .putExtra("amount_minor", pending.amountMinor)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                pending.requestId.hashCode(),
                activity,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_PAYMENT)
                    : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("NEOSPOS payment ready")
                    .setContentText("Take " + money(pending.amountMinor) + " on this phone")
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setVisibility(Notification.VISIBILITY_PRIVATE);
            nm.notify(NOTIFICATION_ID + 1, builder.build());
        }

        try {
            startActivity(activity);
        } catch (Exception ignored) {
            // Android may block background activity launches; the high-priority notification remains available.
        }
    }

    private Notification activeNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ACTIVE)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("NEOSPOS payment listener active")
                .setContentText("Ready to monitor this store once Tap to Pay is connected")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(open)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel active = new NotificationChannel(CHANNEL_ACTIVE, "NEOSPOS terminal", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("Keeps the authenticated NEOSPOS payment listener active");
        active.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(active);
        NotificationChannel payment = new NotificationChannel(CHANNEL_PAYMENT, "Payment requests", NotificationManager.IMPORTANCE_HIGH);
        payment.setDescription("Alerts the terminal when an EPOS payment arrives");
        payment.enableVibration(true);
        payment.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(payment);
    }

    private String money(long minor) {
        return String.format(Locale.UK, "£%.2f", minor / 100.0);
    }
}
