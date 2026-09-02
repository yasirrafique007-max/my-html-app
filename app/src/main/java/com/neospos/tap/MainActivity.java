package com.neospos.tap;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.stripe.stripeterminal.Terminal;
import com.stripe.stripeterminal.external.callable.Callback;
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
    private static final int NAVY = Color.rgb(15, 23, 42);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int BLUE_DARK = Color.rgb(29, 78, 216);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int MUTED = Color.rgb(100, 116, 139);
    private static final int SURFACE = Color.WHITE;
    private static final int PAGE = Color.rgb(246, 249, 253);
    private static final int LINE = Color.rgb(226, 232, 240);

    private FrameLayout shell;
    private View drawerDim;
    private LinearLayout drawer;

    private TextView amountDisplay;
    private TextView frontHint;
    private TextView frontQueue;
    private TextView statusChip;
    private Button chargeButton;

    private EditText email;
    private EditText password;
    private TextView status;
    private TextView context;
    private TextView queueStatus;
    private TextView deviceStatus;
    private Button login;
    private Button connect;
    private Button reconnect;
    private Button checkEpos;

    private volatile boolean terminalReady = false;
    private volatile boolean readerConnected = false;
    private volatile boolean busy = false;
    private volatile boolean queueClaimInFlight = false;
    private volatile boolean reconnectRequested = false;
    private long amountMinorInput = 0;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        requestNeededPermissions();
        checkDeviceBasics();
        updateAmountUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Terminal.isInitialized()) {
            Reader current = Terminal.getInstance().getConnectedReader();
            if (current != null) adoptExistingReader("Tap to Pay is already connected.");
        }
    }

    @Override
    protected void onDestroy() {
        queueHandler.removeCallbacks(queuePoller);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else super.onBackPressed();
    }

    private View buildUi() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(PAGE);

        View main = buildMainPage();
        shell.addView(main, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawerDim = new View(this);
        drawerDim.setBackgroundColor(Color.argb(92, 15, 23, 42));
        drawerDim.setVisibility(View.GONE);
        drawerDim.setOnClickListener(v -> closeDrawer());
        shell.addView(drawerDim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawer = buildDrawer();
        int drawerWidth = Math.min((int)(getResources().getDisplayMetrics().widthPixels * 0.88f), dp(390));
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        drawer.setVisibility(View.GONE);
        shell.addView(drawer, drawerLp);

        return shell;
    }

    private View buildMainPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button menu = iconButton("☰");
        menu.setOnClickListener(v -> openDrawer());
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        TextView name = text("NEOSPOS", 21, true, NAVY);
        name.setLetterSpacing(0.04f);
        TextView sub = text("TAP TERMINAL", 10, true, MUTED);
        sub.setLetterSpacing(0.18f);
        brand.addView(name);
        brand.addView(sub);
        top.addView(brand, brandLp);

        statusChip = text("OFFLINE", 11, true, Color.rgb(153, 27, 27));
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(12), dp(8), dp(12), dp(8));
        style(statusChip, Color.rgb(254, 242, 242), 999, 0, Color.TRANSPARENT);
        top.addView(statusChip);
        root.addView(top);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(20), dp(26), dp(20), dp(24));
        style(hero, SURFACE, 24, 1.5f, LINE);
        LinearLayout.LayoutParams heroLp = match();
        heroLp.topMargin = dp(20);
        heroLp.bottomMargin = dp(16);
        root.addView(hero, heroLp);

        TextView amountLabel = text("AMOUNT", 11, true, MUTED);
        amountLabel.setLetterSpacing(0.16f);
        hero.addView(amountLabel);

        amountDisplay = text("£0.00", 50, true, NAVY);
        amountDisplay.setGravity(Gravity.CENTER);
        amountDisplay.setPadding(0, dp(3), 0, dp(2));
        hero.addView(amountDisplay, match());

        frontHint = text("Open ☰ to login and connect Tap to Pay", 13, false, MUTED);
        frontHint.setGravity(Gravity.CENTER);
        hero.addView(frontHint, match());

        LinearLayout keypadCard = new LinearLayout(this);
        keypadCard.setOrientation(LinearLayout.VERTICAL);
        keypadCard.setPadding(dp(8), dp(8), dp(8), dp(8));
        style(keypadCard, Color.rgb(248, 250, 252), 24, 0, LINE);
        root.addView(keypadCard, match());

        addKeyRow(keypadCard, "1", "2", "3");
        addKeyRow(keypadCard, "4", "5", "6");
        addKeyRow(keypadCard, "7", "8", "9");
        addKeyRow(keypadCard, "C", "0", "⌫");

        chargeButton = button("Charge £0.00", GREEN, Color.WHITE, 18, true);
        chargeButton.setTextSize(19);
        chargeButton.setEnabled(false);
        chargeButton.setOnClickListener(v -> takePayment());
        LinearLayout.LayoutParams chargeLp = match();
        chargeLp.height = dp(64);
        chargeLp.topMargin = dp(16);
        root.addView(chargeButton, chargeLp);

        frontQueue = text("EPOS queue • connect phone terminal", 12, true, MUTED);
        frontQueue.setGravity(Gravity.CENTER);
        frontQueue.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams queueLp = match();
        queueLp.topMargin = dp(10);
        style(frontQueue, Color.rgb(248, 250, 252), 14, 0, LINE);
        root.addView(frontQueue, queueLp);

        TextView footer = text("Fast • secure • card data handled by Stripe", 11, false, Color.rgb(148, 163, 184));
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = match();
        footerLp.topMargin = dp(13);
        root.addView(footer, footerLp);

        return scroll;
    }

    private LinearLayout buildDrawer() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(SURFACE);
        outer.setElevation(dp(16));

        LinearLayout drawerHeader = new LinearLayout(this);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        drawerHeader.setPadding(dp(20), dp(18), dp(14), dp(14));

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Terminal Control", 22, true, NAVY);
        TextView subtitle = text("NEOSPOS Tap • v1.2", 12, false, MUTED);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        drawerHeader.addView(titleStack, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button close = iconButton("×");
        close.setTextSize(28);
        close.setOnClickListener(v -> closeDrawer());
        drawerHeader.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        outer.addView(drawerHeader);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        outer.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(content);

        status = text("Ready for setup", 13, true, Color.rgb(30, 64, 175));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        style(status, Color.rgb(239, 246, 255), 14, 0, Color.TRANSPARENT);
        content.addView(status, match());

        content.addView(section("ACCOUNT"));
        email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText("yasirrafique007@gmail.com");
        content.addView(email, fieldLp());
        password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(password, fieldLp());
        login = button("Login to NEOSPOS", BLUE, Color.WHITE, 14, true);
        login.setOnClickListener(v -> doLogin());
        content.addView(login, buttonLp());

        context = text("Not logged in", 12, false, MUTED);
        LinearLayout.LayoutParams ctxLp = match();
        ctxLp.bottomMargin = dp(12);
        content.addView(context, ctxLp);

        content.addView(section("TAP TO PAY"));
        connect = button("Connect Tap to Pay", BLUE_DARK, Color.WHITE, 14, true);
        connect.setEnabled(false);
        connect.setOnClickListener(v -> connectTapToPay());
        content.addView(connect, buttonLp());

        reconnect = button("Reconnect reader safely", Color.rgb(241, 245, 249), NAVY, 14, true);
        reconnect.setEnabled(false);
        reconnect.setOnClickListener(v -> reconnectTapToPay());
        content.addView(reconnect, buttonLp());

        deviceStatus = text("Checking NFC and device security…", 12, false, MUTED);
        deviceStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        style(deviceStatus, Color.rgb(248, 250, 252), 12, 0, LINE);
        LinearLayout.LayoutParams deviceLp = match();
        deviceLp.bottomMargin = dp(16);
        content.addView(deviceStatus, deviceLp);

        content.addView(section("EPOS QUEUE"));
        queueStatus = text("Connect Tap to Pay and this phone will watch the Mac EPOS automatically.", 12, false, MUTED);
        queueStatus.setPadding(dp(12), dp(11), dp(12), dp(11));
        style(queueStatus, Color.rgb(248, 250, 252), 12, 0, LINE);
        content.addView(queueStatus, match());

        checkEpos = button("Check EPOS now", Color.rgb(241, 245, 249), NAVY, 14, true);
        checkEpos.setEnabled(false);
        checkEpos.setOnClickListener(v -> claimEposRequest(true));
        LinearLayout.LayoutParams checkLp = buttonLp();
        checkLp.topMargin = dp(10);
        content.addView(checkEpos, checkLp);

        TextView security = text("SECURITY\nCard details never enter NEOSPOS. Stripe Terminal controls the NFC payment flow. The screen stays awake while this terminal is open.", 11, false, MUTED);
        security.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams securityLp = match();
        securityLp.topMargin = dp(18);
        style(security, Color.rgb(248, 250, 252), 14, 0, LINE);
        content.addView(security, securityLp);

        outer.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return outer;
    }

    private void addKeyRow(LinearLayout parent, String a, String b, String c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] labels = {a, b, c};
        for (String label : labels) {
            Button key = numberKey(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(key, lp);
        }
        parent.addView(row, match());
    }

    private Button numberKey(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor("C".equals(label) ? Color.rgb(220, 38, 38) : NAVY);
        b.setTextSize(("C".equals(label) || "⌫".equals(label)) ? 21 : 27);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        style(b, SURFACE, 18, 1.5f, LINE);
        b.setOnClickListener(v -> onKey(label));
        return b;
    }

    private void onKey(String label) {
        if (busy) return;
        if ("C".equals(label)) amountMinorInput = 0;
        else if ("⌫".equals(label)) amountMinorInput /= 10;
        else {
            int digit = Integer.parseInt(label);
            long next = amountMinorInput * 10 + digit;
            if (next <= 100000) amountMinorInput = next;
        }
        updateAmountUi();
    }

    private void updateAmountUi() {
        String value = money(amountMinorInput);
        if (amountDisplay != null) amountDisplay.setText(value);
        if (chargeButton != null) {
            chargeButton.setText("Charge " + value);
            chargeButton.setEnabled(!busy && readerConnected && amountMinorInput >= 50);
            chargeButton.setAlpha(chargeButton.isEnabled() ? 1f : 0.48f);
        }
    }

    private void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        drawerDim.setAlpha(0f);
        drawerDim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.post(() -> {
            drawer.setTranslationX(-drawer.getWidth());
            drawer.animate().translationX(0).setDuration(180).start();
            drawerDim.animate().alpha(1f).setDuration(160).start();
        });
    }

    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(160).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        drawerDim.animate().alpha(0f).setDuration(140).withEndAction(() -> drawerDim.setVisibility(View.GONE)).start();
    }

    private void doLogin() {
        final String e = email.getText().toString().trim();
        final String p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) { setStatus("Enter email and password.", true); return; }
        setBusy(true, "Logging in and preparing terminal…");
        new Thread(() -> {
            try {
                Backend.Session s = Backend.get().loginAndPrepare(e, p);
                runOnUiThread(() -> {
                    context.setText("Signed in • " + s.storeName + " • " + s.tillName);
                    try {
                        initTerminal();
                        updateControls();
                        setBusy(false, "Logged in. Connect Tap to Pay.");
                        Reader current = Terminal.getInstance().getConnectedReader();
                        if (current != null) adoptExistingReader("Existing Tap to Pay connection restored.");
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
                @Override public void onConnectionStatusChange(ConnectionStatus connectionStatus) {
                    runOnUiThread(() -> {
                        if (connectionStatus == ConnectionStatus.CONNECTED) setReaderChip("TAP READY", false);
                    });
                }
                @Override public void onPaymentStatusChange(PaymentStatus paymentStatus) {
                    runOnUiThread(() -> {
                        if (paymentStatus != null && paymentStatus != PaymentStatus.NOT_READY) {
                            frontHint.setText("Stripe • " + paymentStatus.name().replace('_', ' ').toLowerCase(Locale.UK));
                        }
                    });
                }
            };
            Terminal.init(getApplicationContext(), LogLevel.VERBOSE, new NeosposConnectionTokenProvider(), listener, null);
        }
        terminalReady = true;
    }

    private void connectTapToPay() {
        if (!terminalReady || !Backend.get().isLoggedIn()) { setStatus("Login first.", true); return; }
        if (!permissionsGranted()) { requestNeededPermissions(); setStatus("Allow location and nearby-device permissions, then connect again.", true); return; }
        if (developerOptionsEnabled()) { setStatus("Turn Developer options OFF before real Tap to Pay.", true); return; }

        Reader existing = Terminal.getInstance().getConnectedReader();
        if (existing != null) {
            adoptExistingReader("Tap to Pay is already connected — discovery skipped.");
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

    private void reconnectTapToPay() {
        if (!terminalReady || !Backend.get().isLoggedIn()) { connectTapToPay(); return; }
        Reader current = Terminal.getInstance().getConnectedReader();
        if (current == null) { readerConnected = false; connectTapToPay(); return; }

        reconnectRequested = true;
        queueHandler.removeCallbacks(queuePoller);
        setBusy(true, "Disconnecting the existing reader safely…");
        Terminal.getInstance().disconnectReader(new Callback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    readerConnected = false;
                    reconnectRequested = false;
                    busy = false;
                    updateControls();
                    setStatus("Reader disconnected. Starting fresh discovery…", false);
                    connectTapToPay();
                });
            }
            @Override public void onFailure(TerminalException e) {
                runOnUiThread(() -> {
                    reconnectRequested = false;
                    setBusy(false, "Could not disconnect reader: " + e.getErrorMessage());
                });
            }
        });
    }

    private void startEasyConnect(String locationId) {
        Reader existing = Terminal.getInstance().getConnectedReader();
        if (existing != null) {
            adoptExistingReader("Tap to Pay already connected — using current reader.");
            return;
        }

        DiscoveryConfiguration.TapToPayDiscoveryConfiguration discovery = new DiscoveryConfiguration.TapToPayDiscoveryConfiguration(false);
        ConnectionConfiguration.TapToPayConnectionConfiguration connection = new ConnectionConfiguration.TapToPayConnectionConfiguration(
                new TapUseCase.Pay(locationId), true, this);
        EasyConnectConfiguration.TapToPayEasyConnectConfiguration config = new EasyConnectConfiguration.TapToPayEasyConnectConfiguration(discovery, connection);

        Terminal.getInstance().easyConnect(config, new ReaderCallback() {
            @Override public void onSuccess(Reader reader) {
                runOnUiThread(() -> adoptExistingReader("Tap to Pay ready. This phone is watching the EPOS queue."));
            }
            @Override public void onFailure(TerminalException e) {
                readerConnected = false;
                runOnUiThread(() -> setBusy(false, "Could not connect phone reader: " + e.getErrorMessage()));
            }
        });
    }

    private void adoptExistingReader(String message) {
        readerConnected = true;
        busy = false;
        setReaderChip("TAP READY", false);
        updateControls();
        updateAmountUi();
        setStatus(message, false);
        setQueueStatus("LIVE • Waiting for Mac EPOS payments.", false);
        startQueuePolling();
    }

    private void startQueuePolling() {
        queueHandler.removeCallbacks(queuePoller);
        queueHandler.post(queuePoller);
    }

    private void claimEposRequest(boolean userRequested) {
        if (!readerConnected) { if (userRequested) setQueueStatus("Connect Tap to Pay first.", true); return; }
        if (queueClaimInFlight || busy) return;
        queueClaimInFlight = true;
        if (userRequested) setQueueStatus("Checking EPOS queue…", false);
        new Thread(() -> {
            try {
                Backend.EposIntent request = Backend.get().claimNextEposRequest();
                if (request != null) {
                    runOnUiThread(() -> {
                        busy = true;
                        updateControls();
                        updateAmountUi();
                        setQueueStatus("EPOS request • " + money(request.amountMinor) + " • " + request.receiptNo, false);
                        processEposIntent(request);
                    });
                } else if (userRequested) {
                    runOnUiThread(() -> setQueueStatus("No waiting EPOS payment. Watching automatically…", false));
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
            @Override public void onSuccess(PaymentIntent paymentIntent) {
                CollectPaymentIntentConfiguration collectConfig = new CollectPaymentIntentConfiguration.Builder().build();
                ConfirmPaymentIntentConfiguration confirmConfig = new ConfirmPaymentIntentConfiguration.Builder().build();
                setStatus("EPOS payment • ask customer to tap card or phone.", false);
                setQueueStatus("TAKE PAYMENT • " + money(data.amountMinor), false);
                Terminal.getInstance().processPaymentIntent(paymentIntent, collectConfig, confirmConfig, new PaymentIntentCallback() {
                    @Override public void onSuccess(PaymentIntent processed) {
                        setStatus("Card accepted. Confirming the EPOS sale…", false);
                        setQueueStatus("Card accepted • confirming with Stripe…", false);
                        confirmEposBackend(data);
                    }
                    @Override public void onFailure(TerminalException e) { failEposRequest(data, e.getErrorMessage()); }
                });
            }
            @Override public void onFailure(TerminalException e) { failEposRequest(data, e.getErrorMessage()); }
        });
    }

    private void confirmEposBackend(Backend.EposIntent data) {
        new Thread(() -> {
            try {
                Backend.RequestState state = null;
                for (int i = 0; i < 30; i++) {
                    state = Backend.get().requestStatus(data.requestId);
                    if ("succeeded".equalsIgnoreCase(state.requestStatus) || "failed".equalsIgnoreCase(state.requestStatus)) break;
                    Thread.sleep(650);
                }
                Backend.RequestState finalState = state;
                runOnUiThread(() -> {
                    busy = false;
                    updateControls();
                    updateAmountUi();
                    if (finalState != null && "succeeded".equalsIgnoreCase(finalState.requestStatus)) {
                        String receipt = finalState.receiptNo == null || finalState.receiptNo.isEmpty() ? data.receiptNo : finalState.receiptNo;
                        setStatus("APPROVED ✓  " + receipt + " • " + money(data.amountMinor), false);
                        setQueueStatus("APPROVED ✓  EPOS updated.", false);
                    } else if (finalState != null && "failed".equalsIgnoreCase(finalState.requestStatus)) {
                        String why = finalState.errorMessage == null || finalState.errorMessage.isEmpty() ? "Payment failed" : finalState.errorMessage;
                        setStatus("Payment failed: " + why, true);
                        setQueueStatus("FAILED • Mac EPOS can retry.", true);
                    } else {
                        setStatus("Card processed; Stripe confirmation is still pending.", false);
                        setQueueStatus("Processing • EPOS is still checking Stripe.", false);
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    busy = false;
                    updateControls();
                    updateAmountUi();
                    setStatus("Card processed; EPOS confirmation error: " + ex.getMessage(), true);
                    setQueueStatus("Check Payments on the Mac EPOS.", true);
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
                updateAmountUi();
                setStatus("Payment failed: " + message, true);
                setQueueStatus("FAILED • Mac EPOS can send the payment again.", true);
            });
        }).start();
    }

    private void takePayment() {
        if (!readerConnected) { setStatus("Open ☰ and connect Tap to Pay first.", true); openDrawer(); return; }
        if (amountMinorInput < 50 || amountMinorInput > 100000) { setStatus("Enter an amount from £0.50 to £1,000.00.", true); return; }
        final long finalMinor = amountMinorInput;
        setBusy(true, "Creating " + money(finalMinor) + " card payment…");
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
            @Override public void onSuccess(PaymentIntent paymentIntent) {
                CollectPaymentIntentConfiguration collectConfig = new CollectPaymentIntentConfiguration.Builder().build();
                ConfirmPaymentIntentConfiguration confirmConfig = new ConfirmPaymentIntentConfiguration.Builder().build();
                setStatus("Hold the customer's card or NFC wallet to this phone…", false);
                Terminal.getInstance().processPaymentIntent(paymentIntent, collectConfig, confirmConfig, new PaymentIntentCallback() {
                    @Override public void onSuccess(PaymentIntent processed) {
                        setStatus("Card accepted. Confirming sale…", false);
                        new Thread(() -> confirmManualBackend(data)).start();
                    }
                    @Override public void onFailure(TerminalException e) {
                        runOnUiThread(() -> setBusy(false, "Payment failed: " + e.getErrorMessage()));
                    }
                });
            }
            @Override public void onFailure(TerminalException e) {
                runOnUiThread(() -> setBusy(false, "Could not load payment: " + e.getErrorMessage()));
            }
        });
    }

    private void confirmManualBackend(Backend.IntentData data) {
        try {
            String stripeStatus = null;
            for (int i = 0; i < 30; i++) {
                stripeStatus = Backend.get().paymentStatus(data.paymentIntentId);
                if ("succeeded".equalsIgnoreCase(stripeStatus) || "canceled".equalsIgnoreCase(stripeStatus)) break;
                Thread.sleep(500);
            }
            String finalStatus = stripeStatus;
            runOnUiThread(() -> {
                if ("succeeded".equalsIgnoreCase(finalStatus)) {
                    amountMinorInput = 0;
                    setBusy(false, "APPROVED ✓  " + data.receiptNo + " • " + money(data.amountMinor));
                    updateAmountUi();
                } else if ("canceled".equalsIgnoreCase(finalStatus)) {
                    setBusy(false, "Payment canceled • " + data.receiptNo);
                } else {
                    setBusy(false, "Stripe is still processing • " + data.receiptNo);
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
        if (!nfc) {
            deviceStatus.setText("NFC unavailable • this device cannot use Tap to Pay");
            setStatus("This Android phone has no NFC hardware.", true);
        } else if (developerOptionsEnabled()) {
            deviceStatus.setText("NFC ready • Developer options must be OFF for live Tap to Pay");
            setStatus("NFC found. Turn Developer options OFF before real Tap to Pay.", true);
        } else {
            deviceStatus.setText("NFC ready • device checks look good • screen keep-awake enabled");
            setStatus("NFC ready. Open ☰ to login and connect.", false);
        }
    }

    private boolean developerOptionsEnabled() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0; }
        catch (Exception ignored) { return false; }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        updateControls();
        updateAmountUi();
        setStatus(message, false);
    }

    private void updateControls() {
        if (login != null) login.setEnabled(!busy);
        if (connect != null) connect.setEnabled(!busy && Backend.get().isLoggedIn());
        if (reconnect != null) reconnect.setEnabled(!busy && Backend.get().isLoggedIn() && terminalReady);
        if (checkEpos != null) checkEpos.setEnabled(!busy && readerConnected);
        updateAmountUi();
    }

    private void setStatus(String message, boolean error) {
        if (status != null) {
            status.setText(message == null ? "" : message);
            status.setTextColor(error ? Color.rgb(153, 27, 27) : Color.rgb(30, 64, 175));
            style(status, error ? Color.rgb(254, 242, 242) : Color.rgb(239, 246, 255), 14, 0, Color.TRANSPARENT);
        }
        if (frontHint != null) {
            frontHint.setText(message == null ? "" : message);
            frontHint.setTextColor(error ? Color.rgb(185, 28, 28) : MUTED);
        }
        if (error) setReaderChip(readerConnected ? "TAP READY" : "ATTENTION", true);
    }

    private void setReaderChip(String label, boolean error) {
        if (statusChip == null) return;
        statusChip.setText(label);
        statusChip.setTextColor(error ? Color.rgb(153, 27, 27) : readerConnected ? Color.rgb(22, 101, 52) : Color.rgb(30, 64, 175));
        style(statusChip, error ? Color.rgb(254, 242, 242) : readerConnected ? Color.rgb(240, 253, 244) : Color.rgb(239, 246, 255), 999, 0, Color.TRANSPARENT);
    }

    private void setQueueStatus(String message, boolean error) {
        if (queueStatus != null) {
            queueStatus.setText(message == null ? "" : message);
            queueStatus.setTextColor(error ? Color.rgb(153, 27, 27) : Color.rgb(22, 101, 52));
            style(queueStatus, error ? Color.rgb(254, 242, 242) : Color.rgb(240, 253, 244), 12, 0, Color.TRANSPARENT);
        }
        if (frontQueue != null) {
            frontQueue.setText(message == null ? "" : message);
            frontQueue.setTextColor(error ? Color.rgb(153, 27, 27) : readerConnected ? Color.rgb(22, 101, 52) : MUTED);
            style(frontQueue, error ? Color.rgb(254, 242, 242) : readerConnected ? Color.rgb(240, 253, 244) : Color.rgb(248, 250, 252), 14, 0, LINE);
        }
    }

    private TextView section(String value) {
        TextView t = text(value, 11, true, MUTED);
        t.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams lp = match();
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(15);
        e.setTextColor(NAVY);
        e.setHintTextColor(Color.rgb(148, 163, 184));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        style(e, Color.rgb(248, 250, 252), 12, 0, LINE);
        return e;
    }

    private Button iconButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(NAVY);
        b.setTextSize(22);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        style(b, SURFACE, 14, 1f, LINE);
        return b;
    }

    private Button button(String label, int background, int textColor, int radius, boolean bold) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        if (bold) b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        style(b, background, radius, 0, background == Color.rgb(241, 245, 249) ? LINE : Color.TRANSPARENT);
        return b;
    }

    private void style(View view, int fill, int radiusDp, float elevationDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) d.setStroke(dp(1), strokeColor);
        view.setBackground(d);
        view.setElevation(dp((int)elevationDp));
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = match();
        lp.height = dp(52);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = match();
        lp.height = dp(52);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String money(long minor) { return String.format(Locale.UK, "£%.2f", minor / 100.0); }

    @Override
    public void onDisconnect(DisconnectReason reason) {
        readerConnected = false;
        queueHandler.removeCallbacks(queuePoller);
        runOnUiThread(() -> {
            updateControls();
            setReaderChip("OFFLINE", reconnectRequested);
            if (!reconnectRequested) {
                setStatus("Reader disconnected: " + reason.name(), true);
                setQueueStatus("OFFLINE • reconnect Tap to Pay.", true);
            }
        });
    }

    @Override
    public void onReaderReconnectStarted(Reader reader, Cancelable cancelReconnect, DisconnectReason reason) {
        runOnUiThread(() -> {
            setReaderChip("RECONNECTING", false);
            setStatus("Reader reconnecting…", false);
        });
    }

    @Override
    public void onReaderReconnectSucceeded(Reader reader) {
        readerConnected = true;
        runOnUiThread(() -> adoptExistingReader("Tap to Pay reconnected."));
    }

    @Override
    public void onReaderReconnectFailed(Reader reader) {
        readerConnected = false;
        queueHandler.removeCallbacks(queuePoller);
        runOnUiThread(() -> {
            updateControls();
            setReaderChip("OFFLINE", true);
            setStatus("Reader reconnect failed. Use ☰ → Reconnect reader safely.", true);
            setQueueStatus("OFFLINE • reconnect Tap to Pay.", true);
        });
    }
}
