package com.ollee.pulse;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: connect to the watch, or let it go. */
public class LinkTile extends TileService {

    @Override
    public void onStartListening() {
        show(WatchLink.get(this).isWanted());
    }

    @Override
    public void onClick() {
        WatchLink w = WatchLink.get(this);
        boolean on = !w.isWanted();
        if (on) w.connect(); else w.disconnect();
        show(on);
    }

    private void show(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(on ? (WatchLink.get(this).isReady() ? "Watch linked" : "Watch: linking") : "Watch off");
        t.updateTile();
    }
}
