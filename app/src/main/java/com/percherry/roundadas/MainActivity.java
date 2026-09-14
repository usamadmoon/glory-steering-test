package com.percherry.roundadas;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends Activity {

    private TextView steeringView, speedView, fuelView, statusView, rawView;
    private Button sniffButton, clearButton;

    private Messenger mcuMessenger;
    private boolean bound = false;
    private boolean fullSniffer = false;
    private long totalPackets = 0;
    private long baselineNumber = 0;

    private static class CmdState {
        long rx;
        long changes;
        byte[] last;
        byte[] baseline;
        boolean changedSinceBaseline;
    }

    private final Map<Integer, CmdState> states = new TreeMap<>();

    /* Dedicated steering listener. This stays separate from the full sniffer. */
    private final Handler steeringHandler = new Handler(msg -> {
        Bundle b = msg.getData();
        if (b == null) return true;
        int cmd = b.getInt("cmdcode", -1);
        byte[] data = b.getByteArray("data");
        if (cmd == 235 && data != null && data.length > 2) {
            int track = data[2] & 0xFF;
            steeringView.setText("Steering 0xEB: " + track + "  " + direction(track));
        }
        return true;
    });
    private final Messenger steeringReply = new Messenger(steeringHandler);

    /* Full sniffer: stores one state per command instead of repeating rows. */
    private final Handler sniffHandler = new Handler(msg -> {
        Bundle b = msg.getData();
        if (b == null) return true;
        int cmd = b.getInt("cmdcode", -1);
        byte[] data = b.getByteArray("data");
        if (cmd < 0 || data == null) return true;

        totalPackets++;
        CmdState s = states.get(cmd);
        if (s == null) {
            s = new CmdState();
            states.put(cmd, s);
        }
        s.rx++;

        boolean changed = s.last == null || !Arrays.equals(s.last, data);
        if (changed) {
            s.changes++;
            s.last = Arrays.copyOf(data, data.length);
            if (s.baseline == null || !Arrays.equals(s.baseline, data)) {
                s.changedSinceBaseline = true;
            }
            renderTable();
        }

        if (cmd == 235 && data.length > 2) {
            int track = data[2] & 0xFF;
            steeringView.setText("Steering 0xEB: " + track + "  " + direction(track));
        }

        updateStatus();
        return true;
    });
    private final Messenger sniffReply = new Messenger(sniffHandler);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            mcuMessenger = new Messenger(service);
            steeringView.setText("MCU connected - waiting for 0xEB steering");
            register(new int[]{235}, steeringReply);
            updateStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            mcuMessenger = null;
            steeringView.setText("MCU disconnected");
            updateStatus();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        steeringView = findViewById(R.id.steering);
        speedView = findViewById(R.id.speed);
        fuelView = findViewById(R.id.fuel);
        statusView = findViewById(R.id.status);
        rawView = findViewById(R.id.raw);
        sniffButton = findViewById(R.id.sniff);
        clearButton = findViewById(R.id.clear);

        speedView.setText("Vehicle speed: not mapped yet");
        fuelView.setText("Fuel / tank / economy: not mapped yet");
        rawView.setText("Start sniffer, wait 2 seconds, then SET BASELINE.\nOnly new/changed command states will remain visible.");
        clearButton.setText("SET BASELINE");

        sniffButton.setOnClickListener(v -> startSniffer());
        clearButton.setOnClickListener(v -> setBaseline());
        bindMcu();
    }

    private void bindMcu() {
        Intent i = new Intent("com.carocean.mcuservice");
        i.setPackage("com.carocean.mcuserver");
        try {
            if (!bindService(i, connection, Context.BIND_AUTO_CREATE)) {
                steeringView.setText("MCU bind failed");
            }
        } catch (Throwable t) {
            steeringView.setText("MCU bind error: " + t.getClass().getSimpleName());
        }
    }

    private void startSniffer() {
        if (!bound || mcuMessenger == null) {
            rawView.setText("MCU is not connected.");
            return;
        }
        if (fullSniffer) return;

        int[] cmds = new int[256];
        for (int i = 0; i < 256; i++) cmds[i] = i;
        if (register(cmds, sniffReply)) {
            fullSniffer = true;
            sniffButton.setText("MCU SNIFFER ACTIVE");
            sniffButton.setEnabled(false);
            rawView.setText("Collecting unique commands...\nWait ~2 seconds, then press SET BASELINE.");
            updateStatus();
        }
    }

    private boolean register(int[] cmds, Messenger reply) {
        try {
            Message m = Message.obtain(null, 256);
            Bundle b = new Bundle();
            b.putIntArray("cmdcode", cmds);
            m.setData(b);
            m.replyTo = reply;
            mcuMessenger.send(m);
            return true;
        } catch (RemoteException | RuntimeException e) {
            rawView.setText("MCU registration failed: " + e.getMessage());
            return false;
        }
    }

    private void setBaseline() {
        baselineNumber++;
        for (CmdState s : states.values()) {
            s.baseline = s.last == null ? null : Arrays.copyOf(s.last, s.last.length);
            s.changedSinceBaseline = false;
        }
        rawView.setText("BASELINE #" + baselineNumber + " SET\nNow perform ONE action (for example P -> D).\nOnly commands that change after this baseline will appear.");
        updateStatus();
    }

    private void renderTable() {
        StringBuilder out = new StringBuilder();
        out.append("CHANGED SINCE BASELINE #").append(baselineNumber).append("\n");
        out.append("CMD    RX     CHG    LATEST\n");

        int shown = 0;
        for (Map.Entry<Integer, CmdState> e : states.entrySet()) {
            CmdState s = e.getValue();
            if (baselineNumber > 0 && !s.changedSinceBaseline) continue;
            if (s.last == null) continue;
            out.append(String.format(Locale.US, "0x%02X  %-6d %-6d %s\n",
                    e.getKey() & 0xFF, s.rx, s.changes, hex(s.last)));
            shown++;
        }
        if (shown == 0) out.append("(no changes yet)\n");
        rawView.setText(out.toString());
    }

    private void updateStatus() {
        statusView.setText(
                "MCU: " + (bound ? "connected" : "disconnected") +
                "\nPackets received: " + totalPackets +
                "   Unique commands: " + states.size() +
                "\nSniffer: " + (fullSniffer ? "ACTIVE" : "not started") +
                "   Baseline: #" + baselineNumber);
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.US, "%02X ", b & 0xFF));
        return s.toString().trim();
    }

    private static String direction(int track) {
        if (track == 0) return "STRAIGHT";
        if (track >= 1 && track <= 36) return "RIGHT";
        if (track >= 129 && track <= 164) return "LEFT";
        return "UNKNOWN";
    }

    @Override protected void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
