package com.percherry.roundadas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import java.util.Set;

public class MainActivity extends Activity {

    private TextView angleView;
    private TextView rangeView;
    private TextView rawView;

    private final Handler handler = new Handler();
    private int receivedCount = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            receivedCount++;

            StringBuilder out = new StringBuilder();

            out.append("RECEIVED #").append(receivedCount).append("\n\n");
            out.append("ACTION:\n");
            out.append(intent.getAction()).append("\n\n");

            Bundle extras = intent.getExtras();

            if (extras == null || extras.isEmpty()) {
                out.append("No extras received.");
            } else {

                Set<String> keys = extras.keySet();

                for (String key : keys) {

                    Object value = extras.get(key);

                    out.append("KEY: ").append(key).append("\n");

                    if (value == null) {
                        out.append("TYPE: null\n");
                        out.append("VALUE: null\n\n");
                    } else {
                        out.append("TYPE: ")
                           .append(value.getClass().getName())
                           .append("\n");

                        out.append("VALUE: ")
                           .append(String.valueOf(value))
                           .append("\n\n");
                    }
                }
            }

            rawView.setText(out.toString());

            angleView.setText("Broadcast received: " + receivedCount);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        angleView = findViewById(R.id.angle);
        rangeView = findViewById(R.id.range);
        rawView = findViewById(R.id.raw);
    }

    @Override
    protected void onResume() {

        super.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(
            "com.percherry.roundadas.LOOK_AROUND_360_CAN"
        );

        registerReceiver(receiver, filter);

        angleView.setText("Listening...");
        rangeView.setText(
            "Sending readyForSync directly to\n" +
            "CarEventService"
        );

        rawView.setText(
            "No response yet.\n\n" +
            "Request #1 will be sent now.\n" +
            "Request #2 after 1 second.\n" +
            "Request #3 after 3 seconds."
        );

        sendSyncRequest();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendSyncRequest();
            }
        }, 1000);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendSyncRequest();
            }
        }, 3000);
    }

    private void sendSyncRequest() {

        Intent sync = new Intent(
            "com.percherry.roundadas"
        );

        /*
         * Critical difference:
         * send the request specifically to CarEventService.
         */
        sync.setPackage(
            "com.autochips.careventservice"
        );

        sync.putExtra(
            "cmd",
            "readyForSync"
        );

        sendBroadcast(sync);
    }

    @Override
    protected void onPause() {

        super.onPause();

        handler.removeCallbacksAndMessages(null);

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }
}
