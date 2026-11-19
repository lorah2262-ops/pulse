package com.ollee.pulse;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;

/**
 * A small window that records 10 s of the music around you. Android only lets an app
 * use the microphone while it is on screen, so the watch opens this for a moment.
 */
@SuppressWarnings("deprecation")
public class ListenActivity extends Activity {

    static final int SECONDS = 10;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaRecorder rec;
    private File file;
    private TextView label;
    private int left = SECONDS;
    private boolean done;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
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
        setContentView(label);

        file = new File(getCacheDir(), "listen.aac");
        try {
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(22050);
            rec.setAudioEncodingBitRate(48000);
            rec.setOutputFile(file.getAbsolutePath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            finishWith(false);
            return;
        }
        tick();
    }

    private void tick() {
        if (done) return;
        label.setText("Pulse is listening...\n" + left + " s\n\nHold the phone toward the music");
        if (left-- <= 0) {
            finishWith(true);
            return;
        }
        h.postDelayed(this::tick, 1000);
    }

    /** Stop recording and hand the clip (or the failure) to the service. */
    private void finishWith(boolean ok) {
        if (done) return;
        done = true;
        boolean good = ok;
        if (rec != null) {
            try { rec.stop(); } catch (Exception e) { good = false; }
            rec.release();
            rec = null;
        }
        NotifService.recorded(good && file.length() > 1000 ? file : null);
        finish();
    }

    @Override
    protected void onDestroy() {
        // closed early: use what was recorded so far if it's at least a few seconds
        if (!done) finishWith(left < SECONDS - 3);
        super.onDestroy();
    }
}
