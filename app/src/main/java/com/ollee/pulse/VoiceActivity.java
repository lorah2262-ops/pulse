package com.ollee.pulse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * A small window that records one spoken question. Android only lets an app use the
 * microphone while it is on screen, so the watch opens this for a moment.
 *
 * Pulse records the question itself (no speech service): it measures the room, counts
 * anything above that as your voice (so a soft voice still counts), waits through pauses,
 * then raises the quiet recording to a normal loudness and hands it over as a WAV for
 * Gemini to hear. Bluetooth earbuds are the microphone when connected.
 *
 * The app can switch back to the phone's own speech service instead ("Listen with"),
 * which turns speech into text and hands that over.
 */
@SuppressWarnings("deprecation")
public class VoiceActivity extends Activity {

    /** The service hears about the voice through this. */
    interface Ear {
        void level(int level);          // 0..3, a few times a second
        void audio(byte[] wav);         // the recorded question (Pulse recorder)
        void heard(String text);        // the words (phone speech service)
        void failed(String why);        // nO MIC, nO SOUnd, ...
    }

    static volatile Ear ear;

    private final Handler h = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private TextView label;
    private volatile boolean done, finishing;
    private boolean sco, commDevice, earbuds;
    private int oldMode;
    private Thread worker;
    private SpeechRecognizer sr;
    private Intent ask;
    private int retries;
    private final StringBuilder heard = new StringBuilder();

    /** The recording last sent to Gemini, kept so it can be played back in the app. */
    static File lastFile(android.content.Context c) { return new File(c.getFilesDir(), "last_question.wav"); }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = this;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        label = new TextView(this);
        label.setTextSize(22);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        int pad = Math.round(32 * getResources().getDisplayMetrics().density);
        label.setPadding(pad, pad, pad, pad);
        label.setText("Pulse is listening...\n\nAsk your question");
        setContentView(label);

        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        earbuds = useEarbuds();
        boolean phoneSpeech = getSharedPreferences("pulse", MODE_PRIVATE).getInt("voice_engine", 0) == 1;
        if (phoneSpeech && !SpeechRecognizer.isRecognitionAvailable(this)) { end("nO SPEECH"); return; }
        // the earbud link takes a moment to open; then a beep says "go"
        h.postDelayed(phoneSpeech ? this::listen : this::beepThenRecord, earbuds ? 900 : 150);
    }

    /** Routes the microphone to Bluetooth earbuds if any are connected. */
    private boolean useEarbuds() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        commDevice = audio.setCommunicationDevice(d);
                        return commDevice;
                    }
                }
                return false;
            }
            if (!audio.isBluetoothScoAvailableOffCall()) return false;
            oldMode = audio.getMode();
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audio.startBluetoothSco();
            audio.setBluetoothScoOn(true);
            sco = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseEarbuds() {
        try {
            if (commDevice && Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice();
            if (sco) {
                audio.setBluetoothScoOn(false);
                audio.stopBluetoothSco();
                audio.setMode(oldMode);
            }
        } catch (Exception ignored) { }
        commDevice = sco = false;
    }

    private void beepThenRecord() {
        if (done) return;
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70);
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            h.postDelayed(t::release, 400);
        } catch (Exception ignored) { }
        // start after the beep has faded, so the room measurement doesn't hear it
        h.postDelayed(() -> {
            if (done) return;
            worker = new Thread(this::record, "pulse-voice");
            worker.start();
        }, 350);
    }

    /** Runs on its own thread: records until the question ends, tap A, or cancel. */
    private void record() {
        int sens = getSharedPreferences("pulse", MODE_PRIVATE).getInt("mic_sens", 1);
        VoiceCut cut = new VoiceCut(sens);
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(VoiceCut.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, VoiceCut.FRAME * 2 * 10);
            // earbuds carry voice the way calls do; the phone mic uses the speech-tuned input
            int[] sources = earbuds
                    ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}
                    : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
            for (int src : sources) {
                try {
                    rec = new AudioRecord(src, VoiceCut.RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, size);
                    if (rec.getState() == AudioRecord.STATE_INITIALIZED) break;
                    rec.release();
                } catch (Exception ignored) { }
                rec = null;
            }
            if (rec == null) { post(() -> end("nO MIC")); return; }
            // no phone noise filter or auto gain: they muffle a soft voice; Pulse raises the level itself
            rec.startRecording();
            short[] frame = new short[VoiceCut.FRAME];
            int lastLevel = -1, sinceLevel = 0;
            while (!done && !finishing) {
                int got = 0;
                while (got < frame.length && !done) {
                    int n = rec.read(frame, got, frame.length - got);
                    if (n < 0) throw new IllegalStateException("read " + n);
                    got += n;
                }
                if (done) break;
                boolean ended = cut.feed(frame, got);
                int lv = cut.level();
                if (lv != lastLevel || ++sinceLevel >= 5) {          // at most every 100 ms unless it changed
                    lastLevel = lv;
                    sinceLevel = 0;
                    Ear e = ear;
                    if (e != null) e.level(lv);
                }
                if (ended) break;
            }
        } catch (Exception e) {
            if (!cut.heardVoice()) { post(() -> end("nO MIC")); cleanup(rec); return; }
        }
        cleanup(rec);
        if (done) return;                                     // cancelled
        final byte[] wav = cut.wav();
        if (wav != null) {
            try (FileOutputStream o = new FileOutputStream(lastFile(this))) { o.write(wav); } catch (Exception ignored) { }
        }
        post(() -> {
            if (wav == null) end("nO SOUnd");
            else deliver(wav);
        });
    }

    private static void cleanup(AudioRecord rec) {
        try { if (rec != null) { rec.stop(); } } catch (Exception ignored) { }
        try { if (rec != null) rec.release(); } catch (Exception ignored) { }
    }

    // ---------- the phone's own speech service ----------

    private void listen() {
        if (done) return;
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70);
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            h.postDelayed(t::release, 400);
        } catch (Exception ignored) { }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float db) {
                Ear e = ear;
                if (e != null) e.level(db < 1 ? 0 : db < 4 ? 1 : db < 7 ? 2 : 3);
            }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int code) {
                boolean quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if ((code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == SpeechRecognizer.ERROR_CLIENT)
                        && !finishing && retries++ < 3) {
                    h.postDelayed(() -> { if (!done) startRec(); }, 300);
                    return;
                }
                if (heard.length() > 0) { deliverText(); return; }
                end(quiet ? "nO SOUnd" : code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "nO MIC"
                        : code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        ? "VOICE nEt" : "VOICE " + code);
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = r == null || r.isEmpty() ? "" : r.get(0).trim();
                if (!text.isEmpty()) heard.append(heard.length() > 0 ? " " : "").append(text);
                if (finishing || heard.length() == 0 && text.isEmpty()) {
                    if (heard.length() > 0) deliverText(); else end("nO SOUnd");
                    return;
                }
                retries = 0;
                label.setText("Pulse is listening...\n\n" + heard);
                h.post(() -> { if (!done) startRec(); });       // a pause: keep listening for more
            }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }
        });
        ask = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ask.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        ask.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        ask.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        ask.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        ask.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        ask.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L);
        startRec();
        h.postDelayed(VoiceActivity::finishNow, 40000);
    }

    private void startRec() {
        try {
            sr.startListening(ask);
        } catch (Exception e) {
            if (heard.length() > 0) deliverText(); else end("nO MIC");
        }
    }

    private void deliverText() {
        if (done) return;
        done = true;
        Ear e = ear;
        if (e != null) e.heard(heard.toString());
        close();
    }

    private void post(Runnable r) { h.post(r); }

    private void deliver(byte[] wav) {
        if (done) return;
        done = true;
        Ear e = ear;
        if (e != null) e.audio(wav);
        close();
    }

    /** The watch says "I'm done talking" (tap A): stop and send what was recorded. */
    static void finishNow() {
        VoiceActivity a = current;
        if (a == null) return;
        a.finishing = true;                                   // the recorder stops on its next frame
        if (a.sr != null) {
            a.h.post(() -> {
                if (a.done || a.sr == null) return;
                try { a.sr.stopListening(); } catch (Exception ignored) { }
                a.h.postDelayed(() -> { if (a.heard.length() > 0) a.deliverText(); else a.end("nO SOUnd"); }, 2500);
            });
        }
    }

    private void end(String why) {
        if (done) return;
        done = true;
        Ear e = ear;
        if (e != null) e.failed(why);
        close();
    }

    private void close() {
        if (sr != null) {
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
        releaseEarbuds();
        finish();
    }

    /** The watch cancelled. */
    static void cancel() {
        VoiceActivity a = current;
        if (a != null) a.h.post(() -> a.end("CAnCEL"));
    }

    private static volatile VoiceActivity current;

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (!done) end("CAnCEL");
        releaseEarbuds();
        super.onDestroy();
    }
}
