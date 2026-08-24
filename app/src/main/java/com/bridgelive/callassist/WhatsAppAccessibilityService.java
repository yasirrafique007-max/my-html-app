package com.bridgelive.callassist;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class WhatsAppAccessibilityService extends AccessibilityService implements TranslatorController.Listener {
    private static WhatsAppAccessibilityService instance;
    private static boolean externalCallActive = false;
    private WindowManager wm; private View overlay; private TextView statusText, originalText, translatedText; private TranslatorController translator; private WindowManager.LayoutParams params; private final Handler main = new Handler(Looper.getMainLooper());
    public static void setExternalCallActive(boolean active) { externalCallActive = active; WhatsAppAccessibilityService s = instance; if (s != null) s.main.post(() -> { if (active) s.showOverlay(); else s.hideOverlay(); }); }
    public static void showForTest() { WhatsAppAccessibilityService s = instance; if (s != null) s.main.post(s::showOverlay); }
    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; wm = (WindowManager)getSystemService(WINDOW_SERVICE); translator = new TranslatorController(this, this); if (externalCallActive) showOverlay(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { CharSequence p = event.getPackageName(); if (externalCallActive && p != null && ("com.whatsapp".contentEquals(p) || "com.whatsapp.w4b".contentEquals(p))) showOverlay(); }
    @Override public void onInterrupt() { if (translator != null) translator.stopListening(); }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(10,6,10,6); return t; }
    private Button button(String label, int color) { Button b = new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(12); GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(18); b.setBackground(bg); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,52,1f); lp.setMargins(4,4,4,4); b.setLayoutParams(lp); return b; }
    private void showOverlay() {
        if (overlay != null || wm == null) return;
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(12,10,12,10); GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF2182233); bg.setCornerRadius(28); bg.setStroke(2,0xFF3C4B68); card.setBackground(bg);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); TextView title = text("Bridge Live • WhatsApp Assist",14,Color.WHITE); title.setTypeface(null,1); top.addView(title,new LinearLayout.LayoutParams(0,-2,1f)); Button close = button("×",0xFF4B556A); close.getLayoutParams().width=54; close.getLayoutParams().height=46; top.addView(close); card.addView(top);
        statusText=text("Call detected — choose who is speaking",12,0xFFB8C5D9); card.addView(statusText); originalText=text("",13,0xFF9EB0C9); card.addView(originalText); translatedText=text("",17,Color.WHITE); translatedText.setTypeface(null,1); card.addView(translatedText);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); Button ar = button("👂 Arabic → English",0xFF315FD5); Button en = button("🎙 English → Arabic",0xFF07866A); row.addView(ar); row.addView(en); card.addView(row);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); Button stop = button("■ Stop",0xFF7F3540); Button info = button("ⓘ Help",0xFF48566E); row2.addView(stop); row2.addView(info); card.addView(row2);
        ar.setOnClickListener(v -> translator.listenArabic()); en.setOnClickListener(v -> translator.listenEnglish()); stop.setOnClickListener(v -> { translator.stopListening(); translator.stopSpeaking(); onState("Stopped"); }); close.setOnClickListener(v -> hideOverlay()); info.setOnClickListener(v -> Toast.makeText(this,"Direct digital WhatsApp call audio is protected by Android. If Arabic listening fails, enable WhatsApp speakerphone and try Arabic → English again.",Toast.LENGTH_LONG).show());
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT); params.gravity=Gravity.TOP|Gravity.START; params.x=20; params.y=180;
        card.setOnTouchListener(new View.OnTouchListener(){ float sx,sy; int ox,oy; boolean drag; @Override public boolean onTouch(View v, MotionEvent e){ switch(e.getActionMasked()){ case MotionEvent.ACTION_DOWN:sx=e.getRawX();sy=e.getRawY();ox=params.x;oy=params.y;drag=false;return true; case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)>8||Math.abs(dy)>8){drag=true;params.x=ox+(int)dx;params.y=oy+(int)dy;wm.updateViewLayout(card,params);}return true; case MotionEvent.ACTION_UP:return drag; default:return false; } } });
        overlay=card; wm.addView(card,params);
    }
    private void hideOverlay() { if (overlay != null && wm != null) { try { wm.removeView(overlay); } catch(Exception ignored) {} overlay=null; } }
    @Override public void onState(String state) { if(statusText!=null)statusText.setText(state); }
    @Override public void onTranscript(String original,String translated,boolean arToEn) { if(originalText!=null)originalText.setText((arToEn?"Arabic: ":"English: ")+original); if(translatedText!=null){ translatedText.setGravity(arToEn?Gravity.START:Gravity.END); translatedText.setText((arToEn?"English: ":"العربية: ")+translated); } }
    @Override public void onError(String message) { onState(message); Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onDestroy() { hideOverlay(); if(translator!=null)translator.shutdown(); if(instance==this)instance=null; super.onDestroy(); }
}
