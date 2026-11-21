package com.ollee.pulse;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Opened by Android when the phone reads the watch's tag (pulse://tap, or the game link): runs the tap,
 * then vanishes. A game link from someone else's watch is passed on to the browser, so friends who
 * also have Pulse still land in that game.
 */
public class TapActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.net.Uri u = getIntent() != null ? getIntent().getData() : null;
        if (!NfcTap.ours(this, u)) {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u)
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            finish();
            overridePendingTransition(0, 0);
            return;
        }
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
