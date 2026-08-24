package com.bridgelive.callassist;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements TranslatorController.Listener {
    private TextView micStatus, accessStatus, notifStatus, waStatus, liveStatus, transcript;
    private TranslatorController translator;
    private final int REQ_MIC=501;

    @Override protected void onCreate(Bundle b){super.onCreate(b);translator=new TranslatorController(this,this);buildUi();requestNeededPermissions();}
    private TextView tv(String text,int sp,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,7,0,7);return t;}
    private Button btn(String label,int color){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(14);GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(22);b.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,58);p.setMargins(0,6,0,6);b.setLayoutParams(p);return b;}
    private void buildUi(){
        ScrollView sv=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(22,36,22,38);root.setBackgroundColor(0xFF08101D);sv.addView(root);
        TextView title=tv("Bridge Live",30,Color.WHITE);title.setTypeface(null,1);root.addView(title);root.addView(tv("HONOR X6c • Arabic ↔ English WhatsApp Call Assist",15,0xFF78B8FF));
        root.addView(tv("When WhatsApp posts an active-call notification, Bridge Live can show translation controls over WhatsApp. Android may still block direct digital access to the remote call audio; speakerphone assist is the fallback.",13,0xFF9FAFC6));
        root.addView(section("SETUP"));micStatus=tv("",14,Color.WHITE);accessStatus=tv("",14,Color.WHITE);notifStatus=tv("",14,Color.WHITE);waStatus=tv("",14,Color.WHITE);root.addView(micStatus);root.addView(accessStatus);root.addView(notifStatus);root.addView(waStatus);
        Button accessibility=btn("1. Enable Bridge Live Accessibility",0xFF315FD5);root.addView(accessibility);accessibility.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button notifications=btn("2. Allow Notification Access",0xFF315FD5);root.addView(notifications);notifications.setOnClickListener(v->startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        Button appNotif=btn("3. WhatsApp / notification settings",0xFF48566E);root.addView(appNotif);appNotif.setOnClickListener(v->{Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);i.putExtra(Settings.EXTRA_APP_PACKAGE,"com.whatsapp");try{startActivity(i);}catch(Exception e){Toast.makeText(this,"Open WhatsApp notification settings manually.",Toast.LENGTH_LONG).show();}});
        Button testOverlay=btn("Test Floating WhatsApp Translator",0xFF087F68);root.addView(testOverlay);testOverlay.setOnClickListener(v->{if(isAccessibilityEnabled()){WhatsAppAccessibilityService.showForTest();Toast.makeText(this,"Overlay requested. Open WhatsApp to position it.",Toast.LENGTH_LONG).show();}else Toast.makeText(this,"Enable Accessibility first.",Toast.LENGTH_LONG).show();});
        root.addView(section("TRANSLATOR TEST"));liveStatus=tv("Ready",13,0xFF9FAFC6);transcript=tv("",18,Color.WHITE);root.addView(liveStatus);root.addView(transcript);
        Button ar=btn("👂 Listen Arabic → play English",0xFF315FD5);Button en=btn("🎙 Speak English → play Arabic",0xFF087F68);Button stop=btn("■ Stop",0xFF7F3540);root.addView(ar);root.addView(en);root.addView(stop);ar.setOnClickListener(v->translator.listenArabic());en.setOnClickListener(v->translator.listenEnglish());stop.setOnClickListener(v->{translator.stopListening();translator.stopSpeaking();onState("Stopped");});
        root.addView(section("IMPORTANT"));root.addView(tv("• Keep WhatsApp notifications enabled so call detection can work.\n• Grant microphone permission to Bridge Live.\n• If Arabic → English cannot hear the caller during the WhatsApp call, Android/MagicOS is reserving call audio for WhatsApp. Turn on WhatsApp speakerphone and retry.\n• English → Arabic is spoken by Bridge Live; whether WhatsApp transmits that local TTS cleanly depends on the phone's echo cancellation.\n• This prototype does not root or modify your HONOR phone.",13,0xFFD5C59A));
        setContentView(sv);refreshStatus();
    }
    private TextView section(String s){TextView t=tv(s,12,0xFF64C9FF);t.setTypeface(null,1);t.setPadding(0,24,0,6);return t;}
    private void requestNeededPermissions(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},502);}
    @Override protected void onResume(){super.onResume();refreshStatus();}
    private void refreshStatus(){if(micStatus==null)return;micStatus.setText((checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"✅":"❌")+" Microphone permission");accessStatus.setText((isAccessibilityEnabled()?"✅":"❌")+" Accessibility overlay service");notifStatus.setText((isNotificationAccessEnabled()?"✅":"❌")+" WhatsApp call notification access");waStatus.setText((isWhatsAppInstalled()?"✅":"⚠️")+" WhatsApp installed");}
    private boolean isAccessibilityEnabled(){String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null)return false;String mine=new ComponentName(this,WhatsAppAccessibilityService.class).flattenToString();return enabled.toLowerCase().contains(mine.toLowerCase());}
    private boolean isNotificationAccessEnabled(){String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");return enabled!=null&&enabled.contains(getPackageName());}
    private boolean isWhatsAppInstalled(){try{getPackageManager().getPackageInfo("com.whatsapp",0);return true;}catch(Exception e){try{getPackageManager().getPackageInfo("com.whatsapp.w4b",0);return true;}catch(Exception e2){return false;}}}
    @Override public void onState(String state){if(liveStatus!=null)liveStatus.setText(state);}
    @Override public void onTranscript(String original,String translated,boolean arToEn){if(transcript!=null){transcript.setGravity(arToEn?Gravity.START:Gravity.END);transcript.setText((arToEn?"Arabic: ":"English: ")+original+"\n\n"+(arToEn?"English: ":"العربية: ")+translated);}}
    @Override public void onError(String message){onState(message);Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(translator!=null)translator.shutdown();super.onDestroy();}
}
