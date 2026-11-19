package com.ollee.pulse;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/**
 * The Bluetooth link to the Ollee watch. One instance for the whole app.
 * The phone is the central; it writes lines to the watch's Forge RX
 * characteristic in 20-byte pieces and listens for button commands on TX.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public class WatchLink {

    public interface Listener { void onStatus(String status); }

    public interface CommandHandler {
        void onCommand(char c);
        void onReady();
    }

    static final UUID SVC = UUID.fromString("c6fb0001-a37d-40ac-89be-887d25e1f5b3");
    static final UUID RX = UUID.fromString("c6fb0002-a37d-40ac-89be-887d25e1f5b3");
    static final UUID TX = UUID.fromString("c6fb0003-a37d-40ac-89be-887d25e1f5b3");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // the Ollee app's own channel (Nordic UART): installing faces goes over this one
    static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    /** Bytes from the watch on the Ollee channel (face installs read them). */
    public interface NusListener { void onNus(byte[] v); }

    /** One write: to the Forge face (0), to the Ollee channel (1), or switching its replies on (2). */
    private static final class Out {
        final int kind;
        final byte[] v;
        Out(int kind, byte[] v) { this.kind = kind; this.v = v; }
    }

    private static WatchLink inst;

    public static synchronized WatchLink get(Context c) {
        if (inst == null) inst = new WatchLink(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());

    // The link normally runs in Bluetooth's low-power mode (set when it connects). A long send
    // (an AI answer, search results, animation frames) switches to the fast mode for just as long
    // as it takes, then the link drops back to low power a moment after the queue empties.
    private boolean boosted;
    private final Runnable relax = () -> {
        if (!boosted || WatchLink.this.busy || !WatchLink.this.queue.isEmpty()) return;
        priority(false);
    };

    private void priority(boolean fast) {
        boosted = fast;
        if (gatt == null) return;
        try {
            gatt.requestConnectionPriority(fast ? BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    : BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
        } catch (SecurityException ignored) { }
    }

    /** Is the fast mode on right now (for the app's status line). */
    public boolean isBoosted() { return boosted; }
    private final ArrayDeque<Out> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChr, nusRx, nusTx;
    private boolean nusOn;
    private NusListener nus;
    private BluetoothDevice dev;
    private BluetoothLeScanner scanner;
    private boolean busy, ready, wanted, scanning, paused;
    private int writeSeq, retries;
    private String status = "Not connected";
    private Listener ui;
    private CommandHandler cmd;

    private WatchLink(Context c) {
        ctx = c;
        wanted = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getBoolean("wanted", false);
    }

    public void setListener(Listener l) {
        ui = l;
        if (l != null) l.onStatus(status);
    }

    public void setCommandHandler(CommandHandler c) { cmd = c; }

    public boolean isReady() { return ready; }

    /** The user asked for the link (it may still be connecting). */
    public boolean isWanted() { return wanted; }

    public boolean isPaused() { return paused; }

    /** Night link-off: drops the link but remembers that the user wants it (resume() brings it back). */
    public void pause(final String why) {
        h.post(() -> {
            if (!wanted || paused) return;
            paused = true;
            stopScan();
            h.removeCallbacks(keepAlive);
            ready = false;
            busy = false;
            queue.clear();
            dropNus();
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (SecurityException ignored) { }
                gatt = null;
            }
            status(why);
        });
    }

    public void resume() {
        h.post(() -> {
            if (!paused) return;
            paused = false;
            if (wanted) connect();
        });
    }

    public boolean hasCommandHandler() { return cmd != null; }

    public void setNusListener(NusListener l) { nus = l; }

    /** The watch has the Ollee channel (for installing faces). */
    public boolean hasNus() { return ready && nusRx != null && nusTx != null; }

    /** Sends one Ollee-channel frame in 20-byte writes; switches its replies on first if needed. */
    public void nusWrite(final byte[] frame) {
        h.post(() -> {
            if (!hasNus()) return;
            if (!nusOn) {
                nusOn = true;
                queue.add(new Out(2, ENABLE));
            }
            for (int i = 0; i < frame.length; i += 20)
                queue.add(new Out(1, Arrays.copyOfRange(frame, i, Math.min(frame.length, i + 20))));
            h.removeCallbacks(relax);
            if (!boosted) priority(true);
            pump();
        });
    }

    private static final byte[] ENABLE = {1, 0};

    private void dropNus() {
        nusRx = null;
        nusTx = null;
        nusOn = false;
    }

    private void status(final String s) {
        status = s;
        History.link(ctx, s);
        h.post(() -> { if (ui != null) ui.onStatus(s); });
    }

    private void save() {
        ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit().putBoolean("wanted", wanted).apply();
    }

    static boolean isWatch(String name) {
        return name != null && name.toUpperCase().startsWith("OLLEE");
    }

    /** Reconnects on its own if the user connected before (called when the app's service starts). */
    public void start() {
        if (wanted) connect();
    }

    public void connect() {
        h.post(() -> {
            wanted = true;
            paused = false;              // Connect by hand also ends a night pause
            save();
            if (gatt != null || scanning) return;
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) {
                status("Bluetooth is off");
                return;
            }
            try {
                if (dev == null) {
                    for (BluetoothDevice d : ad.getBondedDevices()) {
                        if (isWatch(d.getName())) { dev = d; break; }
                    }
                }
                if (dev != null) open(false);
                else scan(ad);
            } catch (SecurityException e) {
                status("Bluetooth permission missing (tap step 2)");
            }
        });
    }

    public void disconnect() {
        h.post(() -> {
            wanted = false;
            save();
            stopScan();
            h.removeCallbacks(keepAlive);
            ready = false;
            busy = false;
            queue.clear();
            dropNus();
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (SecurityException ignored) { }
                gatt = null;
            }
            status("Disconnected");
        });
    }

    // ---------- scanning (only if the watch isn't paired with the phone) ----------

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (n == null) {
                try { n = d.getName(); } catch (SecurityException ignored) { }
            }
            if (isWatch(n)) {
                stopScan();
                dev = d;
                open(false);
            }
        }
    };

    private void scan(BluetoothAdapter ad) {
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) {
            status("Can't scan for Bluetooth devices");
            return;
        }
        scanning = true;
        status("Searching for the watch...");
        ScanSettings set = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, set, scanCb);
        h.postDelayed(() -> {
            if (!scanning) return;
            stopScan();
            status("Watch not found. Close the Ollee app, then tap Connect again.");
        }, 15000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
    }

    // ---------- connection ----------

    private void open(boolean auto) {
        h.post(() -> {
            if (gatt != null || dev == null || !wanted || paused) return;
            status(auto ? "Waiting for the watch to come back..." : "Connecting...");
            try {
                gatt = dev.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                status("Bluetooth permission missing (tap step 2)");
            }
        });
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            h.post(() -> {
                if (g != gatt) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    status("Connected, setting up...");
                    h.postDelayed(() -> {
                        try { if (gatt == g) g.discoverServices(); } catch (SecurityException ignored) { }
                    }, 600);
                } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    ready = false;
                    busy = false;
                    queue.clear();
                    rxChr = null;
                    dropNus();
                    h.removeCallbacks(keepAlive);
                    try { g.close(); } catch (SecurityException ignored) { }
                    gatt = null;
                    if (wanted) {
                        // try hard for a few minutes, then wait quietly in the background
                        boolean fast = retries++ < 6;
                        status("Watch lost (" + reason(st) + "). Reconnecting...");
                        h.postDelayed(() -> open(!fast), fast ? 3000 : 5000);
                    } else {
                        status("Disconnected");
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SVC);
                if (s == null) {
                    status("Forge service not found on the watch");
                    return;
                }
                BluetoothGattCharacteristic rx = s.getCharacteristic(RX);
                BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
                if (rx == null || tx == null) {
                    status("Forge characteristics missing");
                    return;
                }
                rxChr = rx;
                BluetoothGattService ns = g.getService(NUS);
                if (ns != null) {
                    nusRx = ns.getCharacteristic(NUS_RX);
                    nusTx = ns.getCharacteristic(NUS_TX);
                }
                nusOn = false;
                try {
                    g.setCharacteristicNotification(tx, true);
                    BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
                    if (d == null) {
                        becomeReady();
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if (!g.writeDescriptor(d)) becomeReady();
                    }
                } catch (SecurityException e) {
                    status("Bluetooth permission missing (tap step 2)");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(() -> {
                if (!ready) { becomeReady(); return; }
                busy = false;                    // the Ollee channel's replies are on
                pump();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(() -> {
                busy = false;
                pump();
                if (boosted && queue.isEmpty()) {
                    h.removeCallbacks(relax);
                    h.postDelayed(relax, 1500);
                }
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (NUS_TX.equals(c.getUuid())) nusGot(v); else got(v);
        }

        // Android 12 and older
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (NUS_TX.equals(c.getUuid())) nusGot(c.getValue()); else got(c.getValue());
        }
    };

    private static String reason(int st) {
        switch (st) {
            case 8: return "out of range or watch asleep, code 8";
            case 147: return "the watch stopped answering, code 147";
            case 19: return "the watch hung up, code 19";
            case 22: return "phone ended it, code 22";
            case 133: return "connect failed, code 133";
            default: return "code " + st;
        }
    }

    /** Keep-alive gaps: shorter while the Pulse face is on screen, longer while it isn't. */
    static final long QUIET_FACE_ON = 20000, QUIET_FACE_OFF = 40000;

    private long lastTraffic;
    private boolean faceOn = true;

    /** The watch said its Pulse face came on screen (H, buttons) or left it (X). */
    public void setFaceOn(boolean on) { faceOn = on; }

    /**
     * A tiny empty line, but only when the link has been quiet for a while: any real
     * message (music, notifications, buttons) already keeps the connection alive.
     */
    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!ready) return;
            long quiet = android.os.SystemClock.elapsedRealtime() - lastTraffic;
            if (quiet >= (faceOn ? QUIET_FACE_ON : QUIET_FACE_OFF) && queue.isEmpty() && !busy) {
                queue.add(new Out(0, new byte[]{'\n'}));
                pump();
                Drain.keep(ctx);
                History.sent(ctx, "", faceOn);
            }
            h.postDelayed(this, 5000);
        }
    };

    private void becomeReady() {
        if (ready || gatt == null) return;
        ready = true;
        retries = 0;
        lastTraffic = android.os.SystemClock.elapsedRealtime();
        h.removeCallbacks(keepAlive);
        h.postDelayed(keepAlive, 5000);
        String n = "the watch";
        try { if (dev != null && dev.getName() != null) n = dev.getName(); } catch (SecurityException ignored) { }
        status("Connected to " + n);
        // battery: ask for Bluetooth's low-power connection, so the watch's radio wakes about every
        // 0.1 s instead of every 0.03-0.05 s (Android's default). Buttons feel the same.
        h.removeCallbacks(relax);
        priority(false);
        queue.addFirst(new Out(0, new byte[]{'\n'}));   // ends any half line the watch kept from before
        pump();
        if (cmd != null) cmd.onReady();
    }

    private void nusGot(byte[] v) {
        if (v == null) return;
        final byte[] b = v.clone();
        lastTraffic = android.os.SystemClock.elapsedRealtime();
        h.post(() -> { if (nus != null) nus.onNus(b); });
    }

    private void got(byte[] v) {
        if (v == null) return;
        final byte[] b = v.clone();
        lastTraffic = android.os.SystemClock.elapsedRealtime();
        h.post(() -> {
            for (byte x : b) {
                if (x == 'a' || x == 'A' || x == 'l' || x == 'L') Drain.wake(ctx);   // a button on the watch
                if (cmd != null) cmd.onCommand((char) (x & 255));
            }
        });
    }

    // ---------- sending ----------

    /** Sends one line to the watch (a \n is added), split into 20-byte writes. */
    public void sendLine(String line) {
        final byte[] b = (line + "\n").getBytes(StandardCharsets.ISO_8859_1);
        h.post(() -> {
            if (!ready) return;
            Drain.sent(ctx, line, faceOn);
            History.sent(ctx, line, faceOn);
            if (queue.size() > 200) queue.removeIf(o -> o.kind == 0);   // never drops a face install
            for (int i = 0; i < b.length; i += 20) queue.add(new Out(0, Arrays.copyOfRange(b, i, Math.min(b.length, i + 20))));
            h.removeCallbacks(relax);
            if (!boosted && queue.size() >= 5) priority(true);    // 80+ bytes waiting: go fast for it
            pump();
        });
    }

    private void pump() {
        if (busy || !ready || gatt == null || rxChr == null || queue.isEmpty()) return;
        Out o = queue.peek();
        if (o.kind != 0 && (nusRx == null || nusTx == null)) { queue.poll(); pump(); return; }
        try {
            if (o.kind == 2) {
                gatt.setCharacteristicNotification(nusTx, true);
                BluetoothGattDescriptor d = nusTx.getDescriptor(CCCD);
                if (d == null) { queue.poll(); pump(); return; }
                d.setValue(o.v);
                busy = gatt.writeDescriptor(d);
            } else {
                BluetoothGattCharacteristic c = o.kind == 1 ? nusRx : rxChr;
                boolean withResp = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                c.setWriteType(withResp ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(o.v);
                busy = gatt.writeCharacteristic(c);
            }
        } catch (SecurityException e) {
            busy = false;
        }
        if (busy) {
            lastTraffic = android.os.SystemClock.elapsedRealtime();
            queue.poll();
            final int seq = ++writeSeq;
            h.postDelayed(() -> {             // never stall if a write reply goes missing
                if (busy && writeSeq == seq) { busy = false; pump(); }
            }, 1500);
        } else {
            h.postDelayed(this::pump, 60);    // the stack was busy, try again shortly
        }
    }
}
