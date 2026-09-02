package com.neospos.tap;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.stripe.stripeterminal.Terminal;
import com.stripe.stripeterminal.external.callable.Cancelable;
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback;
import com.stripe.stripeterminal.external.callable.ReaderCallback;
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener;
import com.stripe.stripeterminal.external.callable.TerminalListener;
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration;
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration;
import com.stripe.stripeterminal.external.models.ConnectionConfiguration;
import com.stripe.stripeterminal.external.models.ConnectionStatus;
import com.stripe.stripeterminal.external.models.DisconnectReason;
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration;
import com.stripe.stripeterminal.external.models.EasyConnectConfiguration;
import com.stripe.stripeterminal.external.models.PaymentIntent;
import com.stripe.stripeterminal.external.models.PaymentStatus;
import com.stripe.stripeterminal.external.models.Reader;
import com.stripe.stripeterminal.external.models.TapUseCase;
import com.stripe.stripeterminal.external.models.TerminalException;
import com.stripe.stripeterminal.log.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TapToPayReaderListener {
    private static final int PERMISSION_REQUEST = 7001;

    private EditText email;
    private EditText password;
    private EditText amount;
    private TextView status;
    private TextView context;
    private TextView queueStatus;
    private Button login;
    private Button connect;
    private Button checkEpos;
    private Button pay;

    private volatile boolean terminalReady = false;
    private volatile boolean readerConnected = false;
    private volatile boolean busy = false;
    private volatile boolean queueClaimInFlight = false;
    private String locationId;

    private final Handler queueHandler = new Handler(Looper.getMainLooper());
    private final Runnable queuePoller = new Runnable() {
        @Override public void run() {
            if (readerConnected && Backend.get().isLoggedIn() && !busy && !queueClaimInFlight) {
                claimEposRequest(false);
            }
            queueHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNeededPermissions();
        checkDeviceBasics();
    }

    @Override
    protected void onDestroy() {
        queueHandler.removeCallbacks(queuePoller);
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(root);

        TextView badge = new TextView(this);
        badge.setText("N");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(30);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(Color.rgb(37, 99, 235));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        badgeLp.bottomMargin = dp(12);
        root.addView(badge, badgeLp);

        root.addView(text("NEOSPOS Tap", 29, true, Color.rgb(15, 23, 42)));
        TextView subtitle = text("Android Tap to Pay terminal for your NEOSPOS EPOS.", 15, false, Color.rgb(71, 85, 105));
        LinearLayout.LayoutParams subLp = match(); subLp.bottomMargin = dp(20); root.addView(subtitle, subLp);

        status = text("Checking phone…", 14, true, Color.rgb(30, 64, 175));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(239, 246, 255));
        LinearLayout.LayoutParams statusLp = match(); statusLp.bottomMargin = dp(18); root.addView(status, statusLp);

        root.addView(section("1. Login"));
        email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText("yasirrafique007@gmail.com");
        root.addView(email, fieldLp());
        password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password, fieldLp());
        login = button("Login to NEOSPOS");
        login.setOnClickListener(v -> doLogin());
        root.addView(login, buttonLp());

        context = text("Not logged in", 13, false, Color.rgb(100, 116, 139));
        LinearLayout.LayoutParams ctxLp = match(); ctxLp.bottomMargin = dp(20); root.addView(context, ctxLp);

        root.addView(section("2. Phone terminal"));
        connect = button("Connect Tap to Pay");
        connect.setEnabled(false);
        connect.setOnClickListener(v -> connectTapToPay());
        root.addView(connect, buttonLp());

        root.addView(section("3. EPOS payment queue"));
        queueStatus = text("Connect the phone and it will watch the Mac EPOS automatically.", 13, false, Color.rgb(71, 85, 105));
        queueStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        queueStatus.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams queueLp = match(); queueLp.bottomMargin = dp(10); root.addView(queueStatus, queueLp);
        checkEpos = button("Check EPOS now");
        checkEpos.setEnabled(false);
        checkEpos.setOnClickListener(v -> claimEposRequest(true));
        root.addView(checkEpos, buttonLp());

        root.addView(section("4. Manual amount test"));
        amount = field("Amount in GBP, e.g. 1.00", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText("1.00");
        root.addView(amount, fieldLp());
        pay = button("Tap to Pay £1.00");
        pay.setEnabled(false);
        pay.setBackgroundColor(Color.rgb(22, 163, 74));
        pay.setOnClickListener(v -> takePayment());
        root.addView(pay, buttonLp());
        amount.setOnFocusChangeListener((v, hasFocus) -> updatePayLabel());

        TextView note = text(
                "For real Tap to Pay: Android 13+, NFC on, Google Play Services present, current security updates, screen lock enabled, and Developer options OFF.",
                12, false, Color.rgb(100, 116, 139));
        root.addView(note);
        return scroll;
    }

    private void doLogin() {
        final String e = email.getText().toString().trim();
        final String p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) { setStatus("Enter email and password.", true); return; }
        setBusy(true, "Logging in and preparing store/till…");
        new Thread(() -> {
            try {
                Backend.Session s = Backend.get().loginAndPrepare(e, p);
                runOnUiThread(() -> {
                    context.setText("Merchant ready • " + s.storeName + " • " + s.tillName);
                    try {
                        initTerminal();
                        connect.setEnabled(true);
                        setBusy(false, "Logged in. Connect this phone as the reader.");
                    } catch (Exception ex) {
                        setBusy(false, "Terminal SDK error: " + ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> setBusy(false, "Login/setup failed: " + ex.getMessage()));
            }
        }).start();
    }

    private void initTerminal() throws TerminalException {
        if (!Terminal.isInitialized()) {
            TerminalListener listener = new TerminalListener() {
                @Override
                public void onConnectionStatusChange(ConnectionStatus connectionStatus) {
                    runOnUiThread(() -> setStatus("Reader connection: " + connectionStatus.name(), false));
                }

                @Override
                public void onPaymentStatusChange(PaymentStatus paymentStatus) {
                    runOnUiThread(() -> setStatus("Payment: " + paymentStatus.name(), false));
                }
            };
            Terminal.init(getApplicationContext(), LogLevel.VERBOSE, new NeosposConnectionTokenProvider(), listener, null);
        }
        terminalReady = true;
    }

    private void connectTapToPay() {
        if (!terminalReady || !Backend.get().isLoggedIn()) { setStatus("Login first.", true); return; }
        if (!permissionsGranted()) { requestNeededPermissions(); setStatus("Allow location and nearby-device permissions, then tap Connect again.", true); return; }
        if (developerOptionsEnabled()) {
            setStatus("Turn Developer options OFF before using real Tap to Pay.", true);
            return;
        }
        setBusy(true, "Preparing Stripe Tap to Pay…");
        new Thread(() -> {
            try {
                Backend.Bootstrap b = Backend.get().terminalBootstrap();
                locationId = b.locationId;
                runOnUiThread(() -> startEasyConnect(locationId));
            } catch (Exception ex) {
                runOnUiThread(() -> setBusy(false, "Stripe backend: " + ex.getMessage()));
            }
        }).start();
    }

    private void startEasyConnect(String locationId) {
        DiscoveryConfiguration.TapToPayDiscoveryConfiguration discovery =
                new DiscoveryConfiguration.TapToPayDiscoveryConfiguration(false);
        ConnectionConfiguration.TapToPayConnectionConfiguration connection =
                new ConnectionConfiguration.TapToPayConnectionConfiguration(
                        new TapUseCase.Pay(locationId),
                        true,
                        this
                );
        EasyConnectConfiguration.TapToPayEasyConnectConfiguration config =
                new EasyConnectConfiguration.TapToPayEasyConnectConfiguration(discovery, connection);

        Terminal.getInstance().easyConnect(config, new ReaderCallback() {
            @Override
            public void onSuccess(Reader reader) {
                readerConnected = true;
                runOnUiThread(() -> {
                    setBusy(false, "Tap to Pay ready. This phone is now watching the EPOS queue.");
                    setQueueStatus("LIVE • Waiting for a card payment from the Mac EPOS.", false);
                    startQueuePolling();
                });
            }

            @Override
            public void onFailure(TerminalException e) {
                readerConnected = false;
                runOnUiThread(() -> setBusy(false, "Could not connect phone reader: " + e.getErrorMessage()));
            }
        });
    }

    private void startQueuePolling() {
        queueHandler.removeCallbacks(queuePoller);
        queueHandler.post(queuePoller);
    }

    private void claimEposRequest(boolean userRequested) {
        if (!readerConnected) { if (userRequested) setQueueStatus("Connect Tap to Pay first.", true); return; }
        if (queueClaimInFlight || busy) return;
        queueClaimInFlight = true;
        if (userRequested) setQueueStatus("Checking the EPOS queue…", false);
        new Thread(() -> {
            try {
                Backend.EposIntent request = Backend.get().claimNextEposRequest();
                if (request != null) {
                    runOnUiThread(() -> {
                        busy = true;
                        updateControls();
                        setQueueStatus("EPOS request received • £" + String.format(Locale.UK, "%.2f", request.amountMinor / 100.0) + " • " + request.receiptNo, false);
                        processEposIntent(request);
                    });
                } else if (userRequested) {
                    runOnUiThread(() -> setQueueStatus("No waiting EPOS card payment yet. Watching automatically…", false));
                }
            } catch (Exception ex) {
                if (userRequested) runOnUiThread(() -> setQueueStatus("EPOS queue error: " + ex.getMessage(), true));
            } finally {
                queueClaimInFlight = false;
            }
        }).start();
    }

    private void processEposIntent(Backend.EposIntent data) {
        Terminal.getInstance().retrievePaymentIntent(data.clientSecret, new PaymentIntentCallback() {
            @Override
            public void onSuccess(PaymentIntent paymentIntent) {
                CollectPaymentIntentConfiguration collectConfig = new CollectPaymentIntentConfiguration.Builder().build();
                ConfirmPaymentIntentConfiguration confirmConfig = new ConfirmPaymentIntentConfiguration.Builder().build();
                setStatus("EPOS payment • Hold the customer's card or phone to this Android device…", false);
                setQueueStatus("TAKE PAYMENT • £" + String.format(Locale.UK, "%.2f", data.amountMinor / 100.0), false);
                Terminal.getInstance().processPaymentIntent(paymentIntent, collectConfig, confirmConfig, new PaymentIntentCallback() {
                    @Override
                    public void onSuccess(PaymentIntent processed) {
                        setStatus("Card accepted. Confirming the EPOS sale…", false);
                        setQueueStatus("Card accepted • confirming with Stripe…", false);
                        confirmEposBackend(data);
                    }

                    @Override
                    public void onFailure(TerminalException e) {
                        failEposRequest(data, e.getErrorMessage());
                    }
                });
            }

            @Override
            public void onFailure(TerminalException e) {
                failEposRequest(data, e.getErrorMessage());
            }
        });
    }

    private void confirmEposBackend(Backend.EposIntent data) {
        new Thread(() -> {
            try {
                Backend.RequestState state = null;
                for (int i = 0; i < 20; i++) {
                    state = Backend.get().requestStatus(data.requestId);
                    if ("succeeded".equalsIgnoreCase(state.requestStatus) || "failed".equalsIgnoreCase(state.requestStatus)) break;
                    Thread.sleep(750);
                }
                Backend.RequestState finalState = state;
                runOnUiThread(() -> {
                    busy = false;
                    updateControls();
                    if (finalState != null && "succeeded".equalsIgnoreCase(finalState.requestStatus)) {
                        String receipt = finalState.receiptNo == null || finalState.receiptNo.isEmpty() ? data.receiptNo : finalState.receiptNo;
                        setStatus("APPROVED ✓  " + receipt + " • £" + String.format(Locale.UK, "%.2f", data.amountMinor / 100.0), false);
                        setQueueStatus("APPROVED ✓  EPOS has been updated.", false);
                    } else if (finalState != null && "failed".equalsIgnoreCase(finalState.requestStatus)) {
                        String why = finalState.errorMessage == null || finalState.errorMessage.isEmpty() ? "Payment failed" : finalState.errorMessage;
                        setStatus("Payment failed: " + why, true);
                        setQueueStatus("FAILED • The Mac EPOS can retry the card payment.", true);
                    } else {
                        setStatus("Card processed; Stripe confirmation is still pending.", false);
                        setQueueStatus("Processing • EPOS is still checking Stripe.", false);
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    busy = false;
                    updateControls();
                    setStatus("Card processed; EPOS confirmation error: " + ex.getMessage(), true);
                    setQueueStatus("Check the Payments screen on the Mac EPOS.", true);
                });
            }
        }).start();
    }

    private void failEposRequest(Backend.EposIntent data, String reason) {
        final String message = reason == null || reason.isEmpty() ? "Tap to Pay failed" : reason;
        new Thread(() -> {
            try { Backend.get().failRequest(data.requestId, message); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                busy = false;
                updateControls();
                setStatus("Payment failed: " + message, true);
                setQueueStatus("FAILED • The Mac EPOS can send the card payment again.", true);
            });
        }).start();
    }

    private void takePayment() {
        if (!readerConnected) { setStatus("Connect Tap to Pay first.", true); return; }
        long minor;
        try {
            double pounds = Double.parseDouble(amount.getText().toString().trim());
            minor = Math.round(pounds * 100.0);
        } catch (Exception e) {
            setStatus("Enter a valid amount such as 1.00.", true); return;
        }
        if (minor < 50 || minor > 100000) { setStatus("Use an amount between £0.50 and £1,000.00.", true); return; }
        setBusy(true, "Creating £" + String.format(Locale.UK, "%.2f", minor / 100.0) + " manual card-present payment…");
        final long finalMinor = minor;
        new Thread(() -> {
            try {
                Backend.IntentData data = Backend.get().createTestIntent(finalMinor);
                runOnUiThread(() -> retrieveAndProcessManual(data));
            } catch (Exception ex) {
                runOnUiThread(() -> setBusy(false, "Could not create payment: " + ex.getMessage()));
            }
        }).start();
    }

    private void retrieveAndProcessManual(Backend.IntentData data) {
        Terminal.getInstance().retrievePaymentIntent(data.clientSecret, new PaymentIntentCallback() {
            @Override
            public void onSuccess(PaymentIntent paymentIntent) {
                CollectPaymentIntentConfiguration collectConfig = new CollectPaymentIntentConfiguration.Builder().build();
                ConfirmPaymentIntentConfiguration confirmConfig = new ConfirmPaymentIntentConfiguration.Builder().build();
                setStatus("Hold the customer's card or NFC wallet to this phone…", false);
                Terminal.getInstance().processPaymentIntent(paymentIntent, collectConfig, confirmConfig, new PaymentIntentCallback() {
                    @Override
                    public void onSuccess(PaymentIntent processed) {
                        setStatus("Card accepted. Confirming sale…", false);
                        new Thread(() -> confirmManualBackend(data)).start();
                    }

                    @Override
                    public void onFailure(TerminalException e) {
                        runOnUiThread(() -> setBusy(false, "Payment failed: " + e.getErrorMessage()));
                    }
                });
            }

            @Override
            public void onFailure(TerminalException e) {
                runOnUiThread(() -> setBusy(false, "Could not load payment: " + e.getErrorMessage()));
            }
        });
    }

    private void confirmManualBackend(Backend.IntentData data) {
        try {
            String s = Backend.get().paymentStatus(data.paymentIntentId);
            runOnUiThread(() -> {
                if ("succeeded".equalsIgnoreCase(s)) {
                    setBusy(false, "APPROVED ✓  Receipt " + data.receiptNo + " • £" + String.format(Locale.UK, "%.2f", data.amountMinor / 100.0));
                } else {
                    setBusy(false, "Stripe status: " + s + " • Receipt " + data.receiptNo);
                }
            });
        } catch (Exception ex) {
            runOnUiThread(() -> setBusy(false, "Card processed; backend check failed: " + ex.getMessage()));
        }
    }

    private void requestNeededPermissions() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private boolean permissionsGranted() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void checkDeviceBasics() {
        boolean nfc = getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
        if (!nfc) setStatus("This Android phone has no NFC hardware; Tap to Pay cannot run on it.", true);
        else if (developerOptionsEnabled()) setStatus("NFC found. Turn Developer options OFF before real Tap to Pay.", true);
        else setStatus("NFC found. Login to prepare Tap to Pay.", false);
    }

    private boolean developerOptionsEnabled() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0; }
        catch (Exception ignored) { return false; }
    }

    private void updatePayLabel() {
        String v = amount.getText().toString().trim();
        if (v.isEmpty()) v = "0.00";
        pay.setText("Tap to Pay £" + v);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        updateControls();
        setStatus(message, false);
    }

    private void updateControls() {
        login.setEnabled(!busy);
        connect.setEnabled(!busy && Backend.get().isLoggedIn());
        checkEpos.setEnabled(!busy && readerConnected);
        pay.setEnabled(!busy && readerConnected);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message == null ? "" : message);
        status.setTextColor(error ? Color.rgb(153, 27, 27) : Color.rgb(30, 64, 175));
        status.setBackgroundColor(error ? Color.rgb(254, 242, 242) : Color.rgb(239, 246, 255));
    }

    private void setQueueStatus(String message, boolean error) {
        queueStatus.setText(message == null ? "" : message);
        queueStatus.setTextColor(error ? Color.rgb(153, 27, 27) : Color.rgb(22, 101, 52));
        queueStatus.setBackgroundColor(error ? Color.rgb(254, 242, 242) : Color.rgb(240, 253, 244));
    }

    private TextView section(String value) {
        TextView t = text(value, 16, true, Color.rgb(15, 23, 42));
        LinearLayout.LayoutParams lp = match(); lp.topMargin = dp(4); lp.bottomMargin = dp(8); t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(16);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackgroundColor(Color.WHITE);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(37, 99, 235));
        return b;
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams fieldLp() { LinearLayout.LayoutParams lp = match(); lp.bottomMargin = dp(10); return lp; }
    private LinearLayout.LayoutParams buttonLp() { LinearLayout.LayoutParams lp = match(); lp.height = dp(52); lp.bottomMargin = dp(12); return lp; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    public void onDisconnect(DisconnectReason reason) {
        readerConnected = false;
        queueHandler.removeCallbacks(queuePoller);
        runOnUiThread(() -> { updateControls(); setStatus("Reader disconnected: " + reason.name(), true); setQueueStatus("OFFLINE • Reconnect Tap to Pay.", true); });
    }

    @Override
    public void onReaderReconnectStarted(Reader reader, Cancelable cancelReconnect, DisconnectReason reason) {
        runOnUiThread(() -> setStatus("Reader reconnecting…", false));
    }

    @Override
    public void onReaderReconnectSucceeded(Reader reader) {
        readerConnected = true;
        runOnUiThread(() -> { updateControls(); setStatus("Tap to Pay reconnected.", false); setQueueStatus("LIVE • Waiting for the Mac EPOS.", false); startQueuePolling(); });
    }

    @Override
    public void onReaderReconnectFailed(Reader reader) {
        readerConnected = false;
        queueHandler.removeCallbacks(queuePoller);
        runOnUiThread(() -> { updateControls(); setStatus("Reader reconnect failed. Tap Connect again.", true); setQueueStatus("OFFLINE • Tap Connect again.", true); });
    }
}
