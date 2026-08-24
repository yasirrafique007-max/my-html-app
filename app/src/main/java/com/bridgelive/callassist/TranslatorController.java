package com.bridgelive.callassist;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.media.AudioAttributes;
import java.util.ArrayList;
import java.util.Locale;

public class TranslatorController implements RecognitionListener, TextToSpeech.OnInitListener {
    public interface Listener { void onState(String state); void onTranscript(String original, String translated, boolean arabicToEnglish); void onError(String message); }
    private final Context context; private final Listener listener; private SpeechRecognizer recognizer; private TextToSpeech tts; private boolean arabicToEnglish = true; private boolean ttsReady = false;
    public TranslatorController(Context context, Listener listener) { this.context = context; this.listener = listener; tts = new TextToSpeech(context.getApplicationContext(), this); }
    @Override public void onInit(int status) { if (status == TextToSpeech.SUCCESS) { ttsReady = true; tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); } }
    public void listenArabic() { begin(true); }
    public void listenEnglish() { begin(false); }
    private void begin(boolean arToEn) {
        this.arabicToEnglish = arToEn; stopListening();
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { listener.onError("Microphone permission is required. Open Bridge Live and grant microphone access."); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { listener.onError("Speech recognition is unavailable on this phone."); return; }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context); recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, arToEn ? "ar-SA" : "en-GB"); intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        listener.onState(arToEn ? "Listening for Arabic…" : "Listening to your English…"); recognizer.startListening(intent);
    }
    public void stopListening() { if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) {} try { recognizer.destroy(); } catch (Exception ignored) {} recognizer = null; } }
    public void stopSpeaking() { if (tts != null) tts.stop(); }
    private void handleFinal(String text) {
        if (text == null || text.trim().isEmpty()) return; listener.onState("Translating…"); String from = arabicToEnglish ? "ar" : "en"; String to = arabicToEnglish ? "en" : "ar";
        TranslationHelper.translate(text, from, to, new TranslationHelper.Callback() {
            @Override public void onSuccess(String translated) { listener.onTranscript(text, translated, arabicToEnglish); speak(translated, arabicToEnglish ? Locale.UK : new Locale("ar", "SA")); listener.onState(arabicToEnglish ? "English translation playing" : "Arabic translation playing"); }
            @Override public void onError(String message) { listener.onError(message); }
        });
    }
    private void speak(String text, Locale locale) {
        if (!ttsReady || tts == null) { listener.onError("Text-to-speech is not ready. Install an Arabic/English TTS voice in phone settings."); return; }
        int result = tts.setLanguage(locale); if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) { listener.onError("The selected TTS voice is not installed for " + locale.getDisplayLanguage() + "."); return; }
        tts.setSpeechRate(0.92f); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bridge-live-translation");
    }
    public void shutdown() { stopListening(); if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } }
    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { listener.onState("Processing speech…"); }
    @Override public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_CLIENT) return; String msg;
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: msg = "Audio capture failed. During a WhatsApp call, MagicOS may reserve the microphone for WhatsApp."; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Microphone permission is missing."; break;
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "Speech recognition network error."; break;
            case SpeechRecognizer.ERROR_NO_MATCH: msg = "I could not understand the speech. Try again or use speakerphone."; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "Speech recognizer is busy. Try again."; break;
            default: msg = "Speech recognition stopped (code " + error + ").";
        } listener.onError(msg);
    }
    @Override public void onResults(Bundle results) { ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (matches != null && !matches.isEmpty()) handleFinal(matches.get(0)); }
    @Override public void onPartialResults(Bundle partialResults) { ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (matches != null && !matches.isEmpty()) listener.onState("Heard: " + matches.get(0)); }
    @Override public void onEvent(int eventType, Bundle params) {}
}
