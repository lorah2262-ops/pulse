package com.ollee.pulse;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Opened by Android when the phone reads the watch's tag (pulse://tap): runs the tap, then vanishes. */
public class TapActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        long written = getSharedPreferences("pulse", MODE_PRIVATE).getLong("tap_written", 0);
        if (System.currentTimeMillis() - written > 4000) {        // not the tap that just wrote the tag
            String r = NfcTap.run(getApplicationContext());
            NfcTap.buzz(this);
            Toast.makeText(getApplicationContext(), "Pulse: " + r, Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
