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

    private TextView steeringView;
    private TextView speedView;
    private TextView fuelView;
    private TextView statusView;
    private TextView rawView;

    private Button sniffButton;
    private Button baselineButton;

    private Messenger mcuMessenger;

    private boolean bound = false;
    private boolean fullSniffer = false;

    private long totalPackets = 0;
    private long baselineNumber = 0;

    private static class ByteState {
        int baseline = -1;
        int min = 255;
        int max = 0;
        int current = -1;
        int previous = -1;
        long changes = 0;
        boolean changedSinceBaseline = false;
    }

    private static class CmdState {
        long rx = 0;
        long packetChanges = 0;
        byte[] last = null;
        byte[] baselinePayload = null;
        boolean changedSinceBaseline = false;
        ByteState[] bytes = null;
    }

    private final Map<Integer, CmdState> states = new TreeMap<>();

    private final Handler steeringHandler = new Handler(msg -> {
        Bundle bundle = msg.getData();

        if (bundle == null) {
            return true;
        }

        int cmd = bundle.getInt("cmdcode", -1);
        byte[] data = bundle.getByteArray("data");

        if (cmd == 235 && data != null && data.length > 2) {
            int track = data[2] & 0xFF;

            steeringView.setText(
                    "Steering 0xEB: "
                            + track
                            + "  "
                            + direction(track)
            );
        }

        return true;
    });

    private final Messenger steeringReply = new Messenger(steeringHandler);

    private final Handler sniffHandler = new Handler(msg -> {
        Bundle bundle = msg.getData();

        if (bundle == null) {
            return true;
        }

        int cmd = bundle.getInt("cmdcode", -1);
        byte[] data = bundle.getByteArray("data");

        if (cmd < 0 || data == null) {
            return true;
        }

        totalPackets++;

        CmdState state = states.get(cmd);

        if (state == null) {
            state = new CmdState();
            states.put(cmd, state);
        }

        state.rx++;

        if (state.bytes == null || state.bytes.length != data.length) {
            state.bytes = new ByteState[data.length];

            for (int i = 0; i < data.length; i++) {
                state.bytes[i] = new ByteState();
            }
        }

        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            ByteState bs = state.bytes[i];

            if (bs.current == -1) {
                bs.current = value;
                bs.previous = value;

                if (baselineNumber > 0) {
                    bs.baseline = value;
                    bs.min = value;
                    bs.max = value;
                }

            } else {
                bs.previous = bs.current;
                bs.current = value;

                if (bs.current != bs.previous) {
                    bs.changes++;

                    if (baselineNumber > 0) {
                        bs.changedSinceBaseline = true;
                    }
                }
            }

            if (baselineNumber > 0) {
                if (value < bs.min) {
                    bs.min = value;
                }

                if (value > bs.max) {
                    bs.max = value;
                }
            }
        }

        boolean packetChanged =
                state.last == null
                        || !Arrays.equals(state.last, data);

        if (packetChanged) {
            state.packetChanges++;

            if (baselineNumber > 0) {
                if (state.baselinePayload == null
                        || !Arrays.equals(state.baselinePayload, data)) {
                    state.changedSinceBaseline = true;
                }
            }

            state.last = Arrays.copyOf(data, data.length);

            renderTable();
        }

        if (cmd == 235 && data.length > 2) {
            int track = data[2] & 0xFF;

            steeringView.setText(
                    "Steering 0xEB: "
                            + track
                            + "  "
                            + direction(track)
            );
        }

        updateStatus();

        return true;
    });

    private final Messenger sniffReply = new Messenger(sniffHandler);

    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(
                ComponentName name,
                IBinder service
        ) {

            bound = true;

            mcuMessenger = new Messenger(service);

            steeringView.setText(
                    "MCU connected - 0xEB steering not seen yet"
            );

            register(
                    new int[]{235},
                    steeringReply
            );

            updateStatus();
        }

        @Override
        public void onServiceDisconnected(
                ComponentName name
        ) {

            bound = false;
            mcuMessenger = null;

            steeringView.setText(
                    "MCU disconnected"
            );

            updateStatus();
        }
    };

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        steeringView =
                findViewById(
                        R.id.steering
                );

        speedView =
                findViewById(
                        R.id.speed
                );

        fuelView =
                findViewById(
                        R.id.fuel
                );

        statusView =
                findViewById(
                        R.id.status
                );

        rawView =
                findViewById(
                        R.id.raw
                );

        sniffButton =
                findViewById(
                        R.id.sniff
                );

        baselineButton =
                findViewById(
                        R.id.clear
                );

        steeringView.setText(
                "Steering: waiting..."
        );

        speedView.setText(
                "Vehicle speed: not mapped yet"
        );

        fuelView.setText(
                "Fuel / tank / economy: not mapped yet"
        );

        rawView.setText(
                "Start sniffer.\n"
                        + "Wait 2 seconds.\n"
                        + "Press SET BASELINE.\n"
                        + "Then perform ONE test."
        );

        baselineButton.setText(
                "SET BASELINE"
        );

        sniffButton.setOnClickListener(
                v -> startSniffer()
        );

        baselineButton.setOnClickListener(
                v -> setBaseline()
        );

        bindMcu();
    }

    private void bindMcu() {

        Intent intent =
                new Intent(
                        "com.carocean.mcuservice"
                );

        intent.setPackage(
                "com.carocean.mcuserver"
        );

        try {

            boolean success =
                    bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE
                    );

            if (!success) {

                steeringView.setText(
                        "MCU bind failed"
                );
            }

        } catch (Throwable t) {

            steeringView.setText(
                    "MCU bind error: "
                            + t.getClass()
                            .getSimpleName()
            );
        }
    }

    private void startSniffer() {

        if (!bound || mcuMessenger == null) {

            rawView.setText(
                    "MCU is not connected."
            );

            return;
        }

        if (fullSniffer) {
            return;
        }

        int[] commands = new int[256];

        for (int i = 0; i < 256; i++) {
            commands[i] = i;
        }

        if (register(commands, sniffReply)) {

            fullSniffer = true;

            sniffButton.setText(
                    "MCU SNIFFER ACTIVE"
            );

            sniffButton.setEnabled(
                    false
            );

            rawView.setText(
                    "Collecting MCU commands...\n\n"
                            + "Wait about 2 seconds, then press SET BASELINE."
            );

            updateStatus();
        }
    }

    private boolean register(
            int[] commands,
            Messenger reply
    ) {

        if (mcuMessenger == null) {
            return false;
        }

        try {

            Message message =
                    Message.obtain(
                            null,
                            256
                    );

            Bundle bundle = new Bundle();

            bundle.putIntArray(
                    "cmdcode",
                    commands
            );

            message.setData(
                    bundle
            );

            message.replyTo =
                    reply;

            mcuMessenger.send(
                    message
            );

            return true;

        } catch (
                RemoteException
                        | RuntimeException e
        ) {

            rawView.setText(
                    "MCU registration failed: "
                            + e.getMessage()
            );

            return false;
        }
    }

    private void setBaseline() {

        baselineNumber++;

        for (CmdState state : states.values()) {

            state.changedSinceBaseline =
                    false;

            state.baselinePayload =
                    state.last == null
                            ? null
                            : Arrays.copyOf(
                            state.last,
                            state.last.length
                    );

            if (state.bytes != null) {

                for (ByteState bs : state.bytes) {

                    if (bs.current < 0) {
                        continue;
                    }

                    bs.baseline =
                            bs.current;

                    bs.min =
                            bs.current;

                    bs.max =
                            bs.current;

                    bs.changes =
                            0;

                    bs.changedSinceBaseline =
                            false;
                }
            }
        }

        rawView.setText(
                "BASELINE #"
                        + baselineNumber
                        + " SET\n\n"
                        + "Now perform ONE test.\n\n"
                        + "Example:\n"
                        + "0 -> 10 -> 20 km/h -> stop"
        );

        updateStatus();
    }

    private void renderTable() {

        if (baselineNumber == 0) {

            rawView.setText(
                    "Commands detected: "
                            + states.size()
                            + "\n\nPress SET BASELINE before testing."
            );

            return;
        }

        StringBuilder out =
                new StringBuilder();

        out.append(
                "CHANGED SINCE BASELINE #"
        );

        out.append(
                baselineNumber
        );

        out.append(
                "\n\n"
        );

        int shownCommands = 0;

        for (
                Map.Entry<Integer, CmdState> entry :
                states.entrySet()
        ) {

            int cmd =
                    entry.getKey();

            CmdState state =
                    entry.getValue();

            if (!state.changedSinceBaseline
                    || state.last == null) {

                continue;
            }

            shownCommands++;

            out.append(
                    String.format(
                            Locale.US,
                            "CMD 0x%02X   RX:%d   PKT-CHG:%d\n",
                            cmd & 0xFF,
                            state.rx,
                            state.packetChanges
                    )
            );

            out.append(
                    "LATEST: "
            );

            out.append(
                    hex(state.last)
            );

            out.append(
                    "\n"
            );

            out.append(
                    "BYTE  BASE MIN  MAX  NOW  CHG\n"
            );

            if (state.bytes != null) {

                for (
                        int i = 0;
                        i < state.bytes.length;
                        i++
                ) {

                    ByteState bs =
                            state.bytes[i];

                    if (!bs.changedSinceBaseline) {
                        continue;
                    }

                    out.append(
                            String.format(
                                    Locale.US,
                                    "B%-3d  %02X   %02X   %02X   %02X   %d\n",
                                    i,
                                    bs.baseline & 0xFF,
                                    bs.min & 0xFF,
                                    bs.max & 0xFF,
                                    bs.current & 0xFF,
                                    bs.changes
                            )
                    );
                }
            }

            out.append(
                    "\n"
            );
        }

        if (shownCommands == 0) {

            out.append(
                    "(no changes detected yet)"
            );
        }

        rawView.setText(
                out.toString()
        );
    }

    private void updateStatus() {

        statusView.setText(
                "MCU: "
                        + (
                        bound
                                ? "connected"
                                : "disconnected"
                )
                        + "\nPackets received: "
                        + totalPackets
                        + "   Unique commands: "
                        + states.size()
                        + "\nSniffer: "
                        + (
                        fullSniffer
                                ? "ACTIVE"
                                : "not started"
                )
                        + "   Baseline: #"
                        + baselineNumber
        );
    }

    private static String hex(
            byte[] data
    ) {

        StringBuilder output =
                new StringBuilder();

        for (byte b : data) {

            output.append(
                    String.format(
                            Locale.US,
                            "%02X ",
                            b & 0xFF
                    )
            );
        }

        return output
                .toString()
                .trim();
    }

    private static String direction(
            int track
    ) {

        if (track == 0) {
            return "STRAIGHT";
        }

        if (track >= 1 && track <= 36) {
            return "RIGHT";
        }

        if (track >= 129 && track <= 164) {
            return "LEFT";
        }

        return "UNKNOWN";
    }

    @Override
    protected void onDestroy() {

        if (bound) {

            try {

                unbindService(
                        connection
                );

            } catch (
                    Exception ignored
            ) {
            }
        }

        super.onDestroy();
    }
}
