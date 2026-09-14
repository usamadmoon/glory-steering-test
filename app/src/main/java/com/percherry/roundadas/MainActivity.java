package com.percherry.roundadas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    /*
     * Broadcast used by the factory / Autochips car-event layer.
     *
     * One of the head-unit APKs we inspected uses this vehicle-data path.
     */
    private static final String CAR_EVENT_ACTION =
            "com.percherry.roundadas.LOOK_AROUND_360_CAN";

    private TextView steeringView;
    private TextView speedView;
    private TextView fuelView;
    private TextView statusView;
    private TextView rawView;

    private Button sniffButton;
    private Button clearButton;

    private Messenger mcuMessenger;
    private boolean mcuBound = false;

    private boolean fullSnifferRegistered = false;
    private boolean receiverRegistered = false;

    private int mcuPacketCount = 0;
    private int carEventCount = 0;

    private int lastTrackValue = -1;
    private Double lastSpeed = null;

    /*
     * Keep only the newest messages so the screen does not grow forever.
     */
    private final ArrayDeque<String> recentLog = new ArrayDeque<>();


    /*
     * ------------------------------------------------------------------
     * AUTOCHIPS / VEHICLE EVENT RECEIVER
     * ------------------------------------------------------------------
     */
    private final BroadcastReceiver carEventReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {

                    if (intent == null) {
                        return;
                    }

                    carEventCount++;

                    Bundle extras = intent.getExtras();

                    if (extras == null) {

                        addLog(
                                "CAR EVENT: no extras"
                        );

                        updateStatus();
                        return;
                    }

                    StringBuilder line =
                            new StringBuilder("CAR EVENT: ");

                    Set<String> keys = extras.keySet();

                    for (String key : keys) {

                        Object value;

                        try {
                            value = extras.get(key);
                        } catch (Throwable t) {
                            value = "<unreadable>";
                        }

                        line.append(key)
                                .append("=")
                                .append(String.valueOf(value))
                                .append("  ");

                        handleVehicleField(
                                key,
                                value
                        );
                    }

                    addLog(
                            line.toString()
                    );

                    updateStatus();
                }
            };


    /*
     * ------------------------------------------------------------------
     * MCU CALLBACK HANDLER
     * ------------------------------------------------------------------
     *
     * Factory McuServer sends registered MCU packets here.
     */
    private final Handler incomingHandler =
            new Handler(msg -> {

                Bundle data = msg.getData();

                int command = -1;
                byte[] payload = null;

                if (data != null) {

                    command =
                            data.getInt(
                                    "cmdcode",
                                    -1
                            );

                    payload =
                            data.getByteArray(
                                    "data"
                            );
                }

                if (payload != null) {

                    mcuPacketCount++;

                    String hex =
                            bytesToHex(payload);

                    addLog(
                            "MCU "
                                    + command
                                    + " / 0x"
                                    + String.format(
                                    Locale.US,
                                    "%02X",
                                    command & 0xFF
                            )
                                    + " len="
                                    + payload.length
                                    + " : "
                                    + hex
                    );


                    /*
                     * Existing steering discovery.
                     *
                     * Factory CKXBackCar2 uses:
                     *
                     * command = 235 / 0xEB
                     * byte[2] = steering track value
                     */
                    if (
                            command == 235
                                    && payload.length > 2
                    ) {

                        int track =
                                payload[2] & 0xFF;

                        lastTrackValue =
                                track;

                        steeringView.setText(
                                "Steering track: "
                                        + track
                                        + "  "
                                        + getTrackDirection(track)
                        );
                    }

                } else {

                    addLog(
                            "MCU message"
                                    + " what="
                                    + msg.what
                                    + " cmd="
                                    + command
                                    + " no payload"
                    );
                }

                updateStatus();

                return true;
            });


    private final Messenger clientMessenger =
            new Messenger(
                    incomingHandler
            );


    /*
     * ------------------------------------------------------------------
     * MCU SERVICE CONNECTION
     * ------------------------------------------------------------------
     */
    private final ServiceConnection mcuConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder service
                ) {

                    mcuBound = true;

                    mcuMessenger =
                            new Messenger(service);

                    steeringView.setText(
                            "MCU connected"
                    );

                    addLog(
                            "Connected to com.carocean.mcuserver"
                    );


                    /*
                     * Register the steering command immediately.
                     */
                    registerMcuCallbacks(
                            new int[]{235},
                            "steering 235 / 0xEB"
                    );

                    updateStatus();
                }


                @Override
                public void onServiceDisconnected(
                        ComponentName name
                ) {

                    mcuBound = false;

                    mcuMessenger = null;

                    steeringView.setText(
                            "MCU disconnected"
                    );

                    addLog(
                            "MCU service disconnected"
                    );

                    updateStatus();
                }
            };


    /*
     * ------------------------------------------------------------------
     * ACTIVITY
     * ------------------------------------------------------------------
     */
    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(
                savedInstanceState
        );

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

        clearButton =
                findViewById(
                        R.id.clear
                );


        steeringView.setText(
                "Steering: waiting..."
        );

        speedView.setText(
                "Vehicle speed: --"
        );

        fuelView.setText(
                "Fuel / tank / economy: not mapped"
        );


        sniffButton.setOnClickListener(
                v -> startFullMcuSniffer()
        );


        clearButton.setOnClickListener(
                v -> {

                    recentLog.clear();

                    mcuPacketCount = 0;
                    carEventCount = 0;

                    rawView.setText(
                            "Log cleared"
                    );

                    updateStatus();
                }
        );


        /*
         * Listen for factory vehicle events.
         */
        try {

            IntentFilter filter =
                    new IntentFilter(
                            CAR_EVENT_ACTION
                    );

            registerReceiver(
                    carEventReceiver,
                    filter
            );

            receiverRegistered = true;

            addLog(
                    "Car-event listener registered"
            );

        } catch (Throwable t) {

            addLog(
                    "Car-event receiver error: "
                            + t.getClass()
                            .getSimpleName()
            );
        }


        bindToMcuService();

        updateStatus();
    }


    /*
     * ------------------------------------------------------------------
     * VEHICLE FIELD PARSING
     * ------------------------------------------------------------------
     */
    private void handleVehicleField(
            String key,
            Object value
    ) {

        if (key == null) {
            return;
        }

        String k =
                key.toLowerCase(
                        Locale.US
                );


        /*
         * SPEED
         *
         * We saw current_speed in the factory Autochips interface.
         */
        if (
                k.equals("speed_current_speed")
                        || k.equals("current_speed")
                        || k.equals("vehicle_speed")
                        || k.equals("speed")
        ) {

            if (value instanceof Number) {

                lastSpeed =
                        ((Number) value)
                                .doubleValue();

                speedView.setText(
                        "Vehicle speed: "
                                + formatNumber(
                                lastSpeed
                        )
                );
            }
        }


        /*
         * STEERING
         *
         * Keep this generic because different firmware versions
         * may use slightly different key names.
         */
        if (
                k.contains("steering")
                        && value instanceof Number
        ) {

            steeringView.setText(
                    "Steering event: "
                            + key
                            + " = "
                            + value
            );
        }


        /*
         * IMPORTANT:
         *
         * We do not yet know the exact fuel key on this firmware.
         *
         * If ANY undocumented field contains likely fuel-related
         * wording, show it immediately.
         */
        if (
                k.contains("fuel")
                        || k.contains("tank")
                        || k.contains("consum")
                        || k.contains("econom")
                        || k.contains("mileage")
                        || k.contains("range")
                        || k.contains("remaining")
        ) {

            fuelView.setText(
                    "Fuel candidate: "
                            + key
                            + " = "
                            + String.valueOf(value)
            );
        }
    }


    /*
     * ------------------------------------------------------------------
     * MCU BINDING
     * ------------------------------------------------------------------
     */
    private void bindToMcuService() {

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
                            mcuConnection,
                            Context.BIND_AUTO_CREATE
                    );

            if (!success) {

                steeringView.setText(
                        "MCU bind failed"
                );

                addLog(
                        "bindService returned false"
                );
            }

        } catch (Throwable t) {

            steeringView.setText(
                    "MCU bind error"
            );

            addLog(
                    "MCU bind exception: "
                            + t.getClass()
                            .getSimpleName()
                            + " "
                            + t.getMessage()
            );
        }
    }


    /*
     * ------------------------------------------------------------------
     * READ-ONLY MCU SNIFFER
     * ------------------------------------------------------------------
     *
     * This registers callbacks.
     *
     * It DOES NOT transmit commands to the car.
     */
    private void startFullMcuSniffer() {

        if (
                !mcuBound
                        || mcuMessenger == null
        ) {

            addLog(
                    "Cannot start sniffer: MCU not connected"
            );

            return;
        }


        if (fullSnifferRegistered) {

            addLog(
                    "MCU sniffer already active"
            );

            return;
        }


        /*
         * MCU command IDs appear to be one byte.
         *
         * Register callback listeners for all 0..255.
         */
        int[] commands =
                new int[256];

        for (
                int i = 0;
                i < commands.length;
                i++
        ) {

            commands[i] = i;
        }


        boolean success =
                registerMcuCallbacks(
                        commands,
                        "all MCU commands 0..255"
                );


        if (success) {

            fullSnifferRegistered = true;

            sniffButton.setText(
                    "MCU SNIFFER ACTIVE"
            );

            sniffButton.setEnabled(
                    false
            );

            addLog(
                    "FULL MCU SNIFFER ACTIVE"
            );

            addLog(
                    "Watch which packets change with speed / fuel / ignition"
            );
        }


        updateStatus();
    }


    /*
     * Factory McuService protocol:
     *
     * Message.what = 256
     *
     * Bundle:
     *     int[] \"cmdcode\"
     *
     * replyTo:
     *     callback Messenger
     */
    private boolean registerMcuCallbacks(
            int[] commands,
            String description
    ) {

        if (mcuMessenger == null) {

            return false;
        }


        try {

            Message msg =
                    Message.obtain(
                            null,
                            256
                    );


            Bundle bundle =
                    new Bundle();


            bundle.putIntArray(
                    "cmdcode",
                    commands
            );


            msg.setData(
                    bundle
            );


            msg.replyTo =
                    clientMessenger;


            mcuMessenger.send(
                    msg
            );


            addLog(
                    "Registered callback: "
                            + description
            );

            return true;


        } catch (RemoteException e) {

            addLog(
                    "MCU registration failed: "
                            + e.getMessage()
            );

            return false;


        } catch (Throwable t) {

            addLog(
                    "MCU registration error: "
                            + t.getClass()
                            .getSimpleName()
                            + " "
                            + t.getMessage()
            );

            return false;
        }
    }


    /*
     * ------------------------------------------------------------------
     * DISPLAY HELPERS
     * ------------------------------------------------------------------
     */
    private void updateStatus() {

        if (statusView == null) {
            return;
        }


        String text =
                "MCU: "
                        + (
                        mcuBound
                                ? "connected"
                                : "disconnected"
                )
                        + "\nPackets: "
                        + mcuPacketCount
                        + "   Car events: "
                        + carEventCount
                        + "\nSniffer: "
                        + (
                        fullSnifferRegistered
                                ? "0..255 ACTIVE"
                                : "235 / 0xEB only"
                );


        if (lastTrackValue >= 0) {

            text +=
                    "\nTrack: "
                            + lastTrackValue;
        }


        if (lastSpeed != null) {

            text +=
                    "   Speed: "
                            + formatNumber(
                            lastSpeed
                    );
        }


        statusView.setText(
                text
        );
    }


    private void addLog(
            String line
    ) {

        recentLog.addFirst(
                line
        );


        while (
                recentLog.size() > 100
        ) {

            recentLog.removeLast();
        }


        StringBuilder out =
                new StringBuilder();


        for (String item : recentLog) {

            out.append(item)
                    .append("\n");
        }


        if (rawView != null) {

            rawView.setText(
                    out.toString()
            );
        }
    }


    private static String bytesToHex(
            byte[] payload
    ) {

        StringBuilder out =
                new StringBuilder();


        for (byte b : payload) {

            out.append(
                    String.format(
                            Locale.US,
                            "%02X ",
                            b & 0xFF
                    )
            );
        }


        return out
                .toString()
                .trim();
    }


    private static String getTrackDirection(
            int track
    ) {

        if (track == 0) {

            return "STRAIGHT";
        }


        if (
                track >= 1
                        && track <= 36
        ) {

            return "RIGHT";
        }


        if (
                track >= 129
                        && track <= 164
        ) {

            return "LEFT";
        }


        return "UNKNOWN";
    }


    private static String formatNumber(
            double value
    ) {

        if (
                Math.rint(value)
                        == value
        ) {

            return String.format(
                    Locale.US,
                    "%.0f",
                    value
            );
        }


        return String.format(
                Locale.US,
                "%.2f",
                value
        );
    }


    /*
     * ------------------------------------------------------------------
     * CLEANUP
     * ------------------------------------------------------------------
     */
    @Override
    protected void onDestroy() {

        if (receiverRegistered) {

            try {

                unregisterReceiver(
                        carEventReceiver
                );

            } catch (Exception ignored) {
            }
        }


        if (mcuBound) {

            try {

                unbindService(
                        mcuConnection
                );

            } catch (Exception ignored) {
            }
        }


        super.onDestroy();
    }
}
