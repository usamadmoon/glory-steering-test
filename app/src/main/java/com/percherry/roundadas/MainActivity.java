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
import android.widget.TextView;

import java.util.Arrays;

public class MainActivity extends Activity {

    private TextView angleView;
    private TextView rangeView;
    private TextView rawView;

    private Messenger mcuMessenger;
    private boolean bound = false;

    private int packetCount = 0;
    private int lastTrackValue = -1;

    /*
     * Messages returned from McuServer arrive here.
     */
    private final Handler incomingHandler = new Handler(msg -> {

        StringBuilder out = new StringBuilder();

        out.append("Message.what: ")
           .append(msg.what)
           .append("\n\n");

        Bundle data = msg.getData();

        if (data != null) {

            int command = data.getInt("cmdcode", -1);
            byte[] payload = data.getByteArray("data");

            out.append("cmdcode: ")
               .append(command)
               .append("\n");

            if (payload != null) {

                packetCount++;

                out.append("packet count: ")
                   .append(packetCount)
                   .append("\n\n");

                out.append("raw bytes:\n")
                   .append(Arrays.toString(payload))
                   .append("\n\n");

                out.append("hex:\n");

                for (byte b : payload) {
                    out.append(
                        String.format("%02X ", b & 0xFF)
                    );
                }

                /*
                 * CKXBackCar2 uses byte index 2
                 * from command 235 for the track position.
                 */
                if (command == 235 && payload.length > 2) {

                    int track = payload[2] & 0xFF;
                    lastTrackValue = track;

                    angleView.setText(
                        "TRACK VALUE: " + track
                    );

                    String direction;

                    if (track == 0) {
                        direction = "STRAIGHT";
                    } else if (track >= 1 && track <= 36) {
                        direction = "RIGHT";
                    } else if (track >= 129 && track <= 164) {
                        direction = "LEFT";
                    } else {
                        direction = "UNKNOWN";
                    }

                    rangeView.setText(
                        "MCU command: 235 / 0xEB\n" +
                        "Direction: " + direction +
                        "\nPackets: " + packetCount
                    );
                }

            } else {
                out.append("\nNo byte[] payload.");
            }

        } else {
            out.append("No Bundle received.");
        }

        rawView.setText(out.toString());

        return true;
    });

    private final Messenger clientMessenger =
        new Messenger(incomingHandler);

    private final ServiceConnection connection =
        new ServiceConnection() {

            @Override
            public void onServiceConnected(
                ComponentName name,
                IBinder service
            ) {

                bound = true;
                mcuMessenger = new Messenger(service);

                angleView.setText("MCU SERVICE CONNECTED");
                rangeView.setText(
                    "Registering for track command 235 / 0xEB"
                );

                registerTrackCallback();
            }

            @Override
            public void onServiceDisconnected(
                ComponentName name
            ) {

                bound = false;
                mcuMessenger = null;

                angleView.setText(
                    "MCU SERVICE DISCONNECTED"
                );
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        angleView = findViewById(R.id.angle);
        rangeView = findViewById(R.id.range);
        rawView = findViewById(R.id.raw);

        angleView.setText("Connecting to McuServer...");
        rangeView.setText("Read-only command 0xEB listener");
        rawView.setText("No MCU packet received yet.");

        bindToMcuService();
    }

    private void bindToMcuService() {

        Intent intent = new Intent(
            "com.carocean.mcuservice"
        );

        intent.setPackage(
            "com.carocean.mcuserver"
        );

        boolean result = bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE
        );

        if (!result) {
            angleView.setText("MCU BIND FAILED");
            rawView.setText(
                "bindService() returned false"
            );
        }
    }

    private void registerTrackCallback() {

        if (mcuMessenger == null) {
            return;
        }

        try {

            /*
             * Factory McuService protocol:
             * what = 256 -> register callback
             */
            Message msg = Message.obtain(
                null,
                256
            );

            Bundle bundle = new Bundle();

            bundle.putIntArray(
                "cmdcode",
                new int[]{235}
            );

            msg.setData(bundle);

            /*
             * McuServer sends responses back here.
             */
            msg.replyTo = clientMessenger;

            mcuMessenger.send(msg);

            rawView.setText(
                "Registered for MCU command 235 / 0xEB.\n\n" +
                "Now turn the steering wheel slowly left and right."
            );

        } catch (RemoteException e) {

            angleView.setText(
                "MCU REGISTER FAILED"
            );

            rawView.setText(
                e.getClass().getName() +
                "\n" +
                e.getMessage()
            );
        }
    }

    @Override
    protected void onDestroy() {

        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
        }

        super.onDestroy();
    }
}
