package com.neospos.tap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * NEOSPOS Android backend client.
 *
 * Multi-tenant rule:
 * - The publishable Supabase key is safe in the app.
 * - No Stripe secret is embedded in the APK.
 * - Terminal tokens, quick charges and EPOS basket PaymentIntents are created by Supabase
 *   against the currently logged-in merchant's Stripe connected account.
 * - The NEOSPOS platform Stripe account is reserved for SaaS subscription billing.
 */
public final class Backend {
    private static final String BASE = "https://lvwypfbnfmqazepigaug.supabase.co";
    private static final String API_KEY = "sb_publishable_tW1YEDt_sac3qkiDXdB7CA_PeieIGpw";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Backend INSTANCE = new Backend();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .build();

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long accessTokenExpiresAtEpochSeconds;
    private volatile String userId;
    private volatile String merchantId;
    private volatile String storeId;
    private volatile String tillId;
    private volatile String storeName;
    private volatile String tillName;

    // Preserved across ambiguous network failures so a retry cannot create a second quick charge.
    private String pendingQuickChargeKey;
    private long pendingQuickChargeAmount = -1;

    public static Backend get() { return INSTANCE; }
    private Backend() {}

    public static final class Session {
        public final String merchantId;
        public final String storeId;
        public final String tillId;
        public final String storeName;
        public final String tillName;
        Session(String merchantId, String storeId, String tillId, String storeName, String tillName) {
            this.merchantId = merchantId;
            this.storeId = storeId;
            this.tillId = tillId;
            this.storeName = storeName;
            this.tillName = tillName;
        }
    }

    public static final class Bootstrap {
        public final String secret;
        public final String locationId;
        Bootstrap(String secret, String locationId) {
            this.secret = secret;
            this.locationId = locationId;
        }
    }

    public static class IntentData {
        public final String clientSecret;
        public final String paymentIntentId;
        public final String receiptNo;
        public final long amountMinor;
        IntentData(String clientSecret, String paymentIntentId, String receiptNo, long amountMinor) {
            this.clientSecret = clientSecret;
            this.paymentIntentId = paymentIntentId;
            this.receiptNo = receiptNo;
            this.amountMinor = amountMinor;
        }
    }

    public static final class EposIntent extends IntentData {
        public final String requestId;
        EposIntent(String requestId, String clientSecret, String paymentIntentId, String receiptNo, long amountMinor) {
            super(clientSecret, paymentIntentId, receiptNo, amountMinor);
            this.requestId = requestId;
        }
    }

    public static final class PendingRequest {
        public final String requestId;
        public final long amountMinor;
        PendingRequest(String requestId, long amountMinor) {
            this.requestId = requestId;
            this.amountMinor = amountMinor;
        }
    }

    public static final class RequestState {
        public final String requestStatus;
        public final String paymentIntentStatus;
        public final String saleStatus;
        public final String receiptNo;
        public final long amountMinor;
        public final String errorMessage;
        RequestState(String requestStatus, String paymentIntentStatus, String saleStatus, String receiptNo, long amountMinor, String errorMessage) {
            this.requestStatus = requestStatus;
            this.paymentIntentStatus = paymentIntentStatus;
            this.saleStatus = saleStatus;
            this.receiptNo = receiptNo;
            this.amountMinor = amountMinor;
            this.errorMessage = errorMessage;
        }
    }

    public Session loginAndPrepare(String email, String password) throws Exception {
        clearSession();
        try {
            JSONObject login = new JSONObject();
            login.put("email", email.trim());
            login.put("password", password);
            JSONObject auth = requestJson("POST", BASE + "/auth/v1/token?grant_type=password", login, false, false);
            adoptAuthSession(auth);
            JSONObject user = auth.getJSONObject("user");
            userId = require(user, "id");

            // A terminal session is tenant-scoped. Use the oldest active membership as the default.
            JSONArray memberships = requestArray("GET", BASE + "/rest/v1/merchant_users?select=merchant_id,role&user_id=eq." + enc(userId) + "&active=eq.true&order=created_at.asc&limit=1");
            if (memberships.length() == 0) throw new IOException("No active NEOSPOS merchant is linked to this login.");
            merchantId = memberships.getJSONObject(0).getString("merchant_id");

            ensureStore();
            ensureTill();
            return new Session(merchantId, storeId, tillId, storeName, tillName);
        } catch (Exception e) {
            clearSession();
            throw e;
        }
    }

    private void ensureStore() throws Exception {
        JSONArray stores = requestArray("GET", BASE + "/rest/v1/stores?select=id,name&merchant_id=eq." + enc(merchantId) + "&order=created_at.asc&limit=1");
        if (stores.length() == 0) {
            JSONObject body = new JSONObject();
            body.put("merchant_id", merchantId);
            body.put("name", "Main Store");
            body.put("address_line1", "1 High Street");
            body.put("city", "London");
            body.put("postcode", "SW1A 1AA");
            body.put("country", "GB");
            JSONArray created = requestRestInsert("stores", body);
            if (created.length() == 0) throw new IOException("Could not create the store.");
            storeId = created.getJSONObject(0).getString("id");
            storeName = created.getJSONObject(0).optString("name", "Main Store");
        } else {
            storeId = stores.getJSONObject(0).getString("id");
            storeName = stores.getJSONObject(0).optString("name", "Main Store");
        }
    }

    private void ensureTill() throws Exception {
        JSONArray tills = requestArray("GET", BASE + "/rest/v1/tills?select=id,name&merchant_id=eq." + enc(merchantId) + "&store_id=eq." + enc(storeId) + "&order=created_at.asc&limit=1");
        if (tills.length() == 0) {
            JSONObject body = new JSONObject();
            body.put("merchant_id", merchantId);
            body.put("store_id", storeId);
            body.put("name", "Phone Till");
            body.put("status", "mobile_tap_to_pay");
            JSONArray created = requestRestInsert("tills", body);
            if (created.length() == 0) throw new IOException("Could not create the phone till.");
            tillId = created.getJSONObject(0).getString("id");
            tillName = created.getJSONObject(0).optString("name", "Phone Till");
        } else {
            tillId = tills.getJSONObject(0).getString("id");
            tillName = tills.getJSONObject(0).optString("name", "Phone Till");
        }
    }

    /** Fetches a Stripe Terminal connection token for this merchant's connected Stripe account. */
    public Bootstrap terminalBootstrap() throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("store_id", storeId);
        JSONObject out = function("terminal-connection-token", body);
        return new Bootstrap(require(out, "secret"), require(out, "location_id"));
    }

    /** Manual front-keypad charge, created on this merchant's connected Stripe account. */
    public IntentData createTestIntent(long amountMinor) throws Exception {
        requireSession();
        final String idempotencyKey = quickChargeKey(amountMinor);
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("store_id", storeId);
        body.put("till_id", tillId);
        body.put("amount_minor", amountMinor);
        body.put("idempotency_key", idempotencyKey);
        try {
            JSONObject out = function("merchant-quick-charge", body);
            clearQuickChargeKey(idempotencyKey);
            return new IntentData(
                    require(out, "client_secret"),
                    require(out, "payment_intent_id"),
                    require(out, "receipt_no"),
                    out.getLong("amount_minor")
            );
        } catch (Exception e) {
            // Keep the key. A later retry of the same amount will safely replay the same server request.
            throw e;
        }
    }

    public PendingRequest peekPendingEposRequest() throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("store_id", storeId);
        JSONObject out = function("tap-peek-request", body);
        if (!out.optBoolean("pending", false) || !out.has("request") || out.isNull("request")) return null;
        JSONObject r = out.getJSONObject("request");
        return new PendingRequest(require(r, "id"), r.optLong("amount_minor", 0));
    }

    /** Claims an EPOS basket. The backend creates its PaymentIntent on the merchant connected account. */
    public EposIntent claimNextEposRequest() throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("store_id", storeId);
        JSONObject out = function("tap-claim-request", body);
        if (!out.has("request") || out.isNull("request")) return null;
        JSONObject r = out.getJSONObject("request");
        return new EposIntent(
                require(r, "request_id"),
                require(r, "client_secret"),
                require(r, "payment_intent_id"),
                require(r, "receipt_no"),
                r.getLong("amount_minor")
        );
    }

    public RequestState requestStatus(String requestId) throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("request_id", requestId);
        JSONObject out = function("tap-request-status", body);
        return new RequestState(
                out.optString("request_status", ""),
                out.optString("payment_intent_status", ""),
                out.optString("sale_status", ""),
                out.optString("receipt_no", ""),
                out.optLong("amount_minor", 0),
                out.optString("error_message", "")
        );
    }

    public void failRequest(String requestId, String reason) throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("request_id", requestId);
        body.put("reason", reason == null ? "Tap to Pay failed" : reason);
        function("tap-fail-request", body);
    }

    public String failManualPayment(String paymentIntentId, String reason) throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("payment_intent_id", paymentIntentId);
        body.put("reason", reason == null ? "Tap to Pay failed" : reason);
        JSONObject out = function("mobile-fail-payment", body);
        return out.optString("status", "failed");
    }

    /** Final status is retrieved from the same merchant connected account. */
    public String paymentStatus(String paymentIntentId) throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("merchant_id", merchantId);
        body.put("payment_intent_id", paymentIntentId);
        JSONObject out = function("mobile-payment-status", body);
        return require(out, "status");
    }

    public String getMerchantId() { return merchantId; }
    public String getStoreId() { return storeId; }
    public String getTillId() { return tillId; }
    public boolean isLoggedIn() { return accessToken != null && refreshToken != null && merchantId != null; }

    public synchronized void clearSession() {
        accessToken = null;
        refreshToken = null;
        accessTokenExpiresAtEpochSeconds = 0;
        userId = null;
        merchantId = null;
        storeId = null;
        tillId = null;
        storeName = null;
        tillName = null;
        pendingQuickChargeKey = null;
        pendingQuickChargeAmount = -1;
    }

    private JSONObject function(String slug, JSONObject body) throws Exception {
        return requestJson("POST", BASE + "/functions/v1/" + slug, body, true, false);
    }

    private JSONArray requestRestInsert(String table, JSONObject body) throws Exception {
        Request request = baseRequest(BASE + "/rest/v1/" + table, true)
                .header("Prefer", "return=representation")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return new JSONArray(execute(request));
    }

    private JSONArray requestArray(String method, String url) throws Exception {
        Request.Builder b = baseRequest(url, true);
        if ("GET".equals(method)) b.get();
        return new JSONArray(execute(b.build()));
    }

    private JSONObject requestJson(String method, String url, JSONObject body, boolean authenticated, boolean prefer) throws Exception {
        Request.Builder b = baseRequest(url, authenticated);
        if (prefer) b.header("Prefer", "return=representation");
        if ("POST".equals(method)) b.post(RequestBody.create(body.toString(), JSON));
        else if ("GET".equals(method)) b.get();
        return new JSONObject(execute(b.build()));
    }

    private Request.Builder baseRequest(String url, boolean authenticated) throws Exception {
        if (authenticated) refreshAccessToken(false);
        Request.Builder b = new Request.Builder().url(url).header("apikey", API_KEY).header("Accept", "application/json");
        if (authenticated && accessToken != null) b.header("Authorization", "Bearer " + accessToken);
        return b;
    }

    private String execute(Request request) throws Exception {
        return execute(request, true);
    }

    private String execute(Request request, boolean allowAuthRetry) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (response.code() == 401 && allowAuthRetry && request.header("Authorization") != null && refreshToken != null) {
                refreshAccessToken(true);
                Request retry = request.newBuilder().header("Authorization", "Bearer " + accessToken).build();
                return execute(retry, false);
            }
            if (!response.isSuccessful()) throw new IOException(errorMessage(text, response.code()));
            return text;
        }
    }

    private synchronized void refreshAccessToken(boolean force) throws Exception {
        if (refreshToken == null || refreshToken.isEmpty()) return;
        long now = System.currentTimeMillis() / 1000L;
        if (!force && accessToken != null && now + 90 < accessTokenExpiresAtEpochSeconds) return;

        JSONObject body = new JSONObject();
        body.put("refresh_token", refreshToken);
        Request request = new Request.Builder()
                .url(BASE + "/auth/v1/token?grant_type=refresh_token")
                .header("apikey", API_KEY)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        String text = execute(request, false);
        adoptAuthSession(new JSONObject(text));
    }

    private synchronized void adoptAuthSession(JSONObject auth) throws Exception {
        accessToken = require(auth, "access_token");
        String newRefresh = auth.optString("refresh_token", "");
        if (!newRefresh.isEmpty()) refreshToken = newRefresh;
        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = auth.optLong("expires_at", 0);
        long expiresIn = auth.optLong("expires_in", 3600);
        accessTokenExpiresAtEpochSeconds = expiresAt > now ? expiresAt : now + Math.max(60, expiresIn);
    }

    private synchronized String quickChargeKey(long amountMinor) {
        if (pendingQuickChargeKey == null || pendingQuickChargeAmount != amountMinor) {
            pendingQuickChargeKey = UUID.randomUUID().toString();
            pendingQuickChargeAmount = amountMinor;
        }
        return pendingQuickChargeKey;
    }

    private synchronized void clearQuickChargeKey(String key) {
        if (key != null && key.equals(pendingQuickChargeKey)) {
            pendingQuickChargeKey = null;
            pendingQuickChargeAmount = -1;
        }
    }

    private String errorMessage(String text, int responseCode) {
        String message = text;
        try {
            JSONObject e = new JSONObject(text);
            if (e.has("error")) message = e.getString("error");
            else if (e.has("message")) message = e.getString("message");
            else if (e.has("msg")) message = e.getString("msg");
        } catch (Exception ignored) {}
        return message == null || message.isEmpty() ? ("Request failed: " + responseCode) : message;
    }

    private void requireSession() throws IOException {
        if (!isLoggedIn() || storeId == null || tillId == null) throw new IOException("Login to NEOSPOS first.");
    }

    private static String require(JSONObject o, String key) throws Exception {
        String value = o.optString(key, "");
        if (value.isEmpty()) throw new IOException("Missing " + key + " from backend response.");
        return value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
