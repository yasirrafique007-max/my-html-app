package com.bridgelive.callassist;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class WhatsAppOverlayManager implements TranslatorController.Listener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WhatsAppOverlayManager instance;
    private static boolean callActive;

    private final Context context;
    private final WindowManager wm;
    private final TranslatorController translator;
    private View overlay;
    private TextView statusText;
    private TextView originalText;
    private TextView translatedText;
    private WindowManager.LayoutParams params;

    private WhatsAppOverlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.translator = new TranslatorController(this.context, this);
    }

    private static WhatsAppOverlayManager get(Context context) {
        if (instance == null) instance = new WhatsAppOverlayManager(context);
        return instance;
    }

    public static void setExternalCallActive(Context context, boolean active) {
        callActive = active;
        MAIN.post(() -> {
            WhatsAppOverlayManager m = get(context);
            if (active) m.show(); else m.hide();
        });
    }

    public static void showForTest(Context context) {
        MAIN.post(() -> get(context).show());
    }

    public static void hideNow() {
        MAIN.post(() -> { if (instance != null) instance.hide(); });
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(10, 6, 10, 6);
        return t;
    }

    private Button button(String label, int color) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(18);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 52, 1f);
        lp.setMargins(4, 4, 4, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private void show() {
        if (!Settings.canDrawOverlays(context)) return;
        if (overlay != null || wm == null) return;

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(12, 10, 12, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2182233);
        bg.setCornerRadius(28);
        bg.setStroke(2, 0xFF3C4B68);
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Bridge Live • WhatsApp Assist", 14, Color.WHITE);
        title.setTypeface(null, 1);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = button("×", 0xFF4B556A);
        close.getLayoutParams().width = 54;
        close.getLayoutParams().height = 46;
        top.addView(close);
        card.addView(top);

        statusText = text(callActive ? "WhatsApp call detected" : "Translator test", 12, 0xFFB8C5D9);
        originalText = text("", 13, 0xFF9EB0C9);
        translatedText = text("", 17, Color.WHITE);
        translatedText.setTypeface(null, 1);
        card.addView(statusText);
        card.addView(originalText);
        card.addView(translatedText);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button ar = button("Arabic → English", 0xFF315FD5);
        Button en = button("English → Arabic", 0xFF07866A);
        row.addView(ar);
        row.addView(en);
        card.addView(row);

        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = button("Stop", 0xFF7F3540);
        Button info = button("Help", 0xFF48566E);
        row2.addView(stop);
        row2.addView(info);
        card.addView(row2);

        ar.setOnClickListener(v -> translator.listenArabic());
        en.setOnClickListener(v -> translator.listenEnglish());
        stop.setOnClickListener(v -> {
            translator.stopListening();
            translator.stopSpeaking();
            onState("Stopped");
        });
        close.setOnClickListener(v -> hide());
        info.setOnClickListener(v -> Toast.makeText(context,
                "Android protects WhatsApp's digital call stream. If caller audio is not available, use WhatsApp speakerphone for Arabic → English.",
                Toast.LENGTH_LONG).show());

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 180;

        card.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean drag;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = params.x; oy = params.y; drag = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            drag = true;
                            params.x = ox + (int) dx;
                            params.y = oy + (int) dy;
                            wm.updateViewLayout(card, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return drag;
                    default:
                        return false;
                }
            }
        });

        overlay = card;
        try { wm.addView(card, params); }
        catch (Exception e) { overlay = null; }
    }

    private void hide() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
    }

    @Override public void onState(String state) {
        MAIN.post(() -> { if (statusText != null) statusText.setText(state); });
    }

    @Override public void onTranscript(String original, String translated, boolean arToEn) {
        MAIN.post(() -> {
            if (originalText != null) originalText.setText((arToEn ? "Arabic: " : "English: ") + original);
            if (translatedText != null) {
                translatedText.setGravity(arToEn ? Gravity.START : Gravity.END);
                translatedText.setText((arToEn ? "English: " : "العربية: ") + translated);
            }
        });
    }

    @Override public void onError(String message) {
        onState(message);
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
