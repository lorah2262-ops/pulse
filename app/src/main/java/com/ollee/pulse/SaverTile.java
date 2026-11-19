package com.ollee.pulse;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: watch saver mode on now, or back to the way it was set. */
public class SaverTile extends TileService {

    private static final int ALWAYS = Saver.VOLT_NAME.length - 1;

    @Override
    public void onStartListening() {
        show();
    }

    @Override
    public void onClick() {
        SharedPreferences p = getSharedPreferences("pulse", MODE_PRIVATE);
        int now = p.getInt(Saver.VOLT, 0);
        if (now == ALWAYS) p.edit().putInt(Saver.VOLT, p.getInt("sv_volt_prev", 0)).apply();
        else p.edit().putInt("sv_volt_prev", now).putInt(Saver.VOLT, ALWAYS).apply();
        NotifService.saverChanged();
        show();
    }

    private void show() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = getSharedPreferences("pulse", MODE_PRIVATE).getInt(Saver.VOLT, 0) == ALWAYS;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(on ? "Watch saver on" : "Watch saver");
        t.updateTile();
    }
}
