package com.bridgelive.callassist;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TranslationHelper {
    public interface Callback { void onSuccess(String translated); void onError(String message); }
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private TranslationHelper() {}
    public static void translate(String text, String from, String to, Callback callback) {
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String pair = from + "|" + to;
                String endpoint = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name()) + "&langpair=" + URLEncoder.encode(pair, StandardCharsets.UTF_8.name());
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
                connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "BridgeLiveHonor/0.1");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("Translation service returned " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject json = new JSONObject(body.toString());
                String translated = json.optJSONObject("responseData") != null ? json.optJSONObject("responseData").optString("translatedText", "") : "";
                if (translated.trim().isEmpty()) throw new Exception("No translation returned");
                MAIN.post(() -> callback.onSuccess(translated));
            } catch (Exception e) {
                MAIN.post(() -> callback.onError(e.getMessage() == null ? "Translation failed" : e.getMessage()));
            } finally { if (connection != null) connection.disconnect(); }
        });
    }
}
