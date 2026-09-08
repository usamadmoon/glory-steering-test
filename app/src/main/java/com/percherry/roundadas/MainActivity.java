package com.percherry.roundadas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Set;

public class MainActivity extends Activity {

    private TextView angleView;
    private TextView rangeView;
    private TextView rawView;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            StringBuilder out = new StringBuilder();

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

            angleView.setText("Broadcast received");
            rangeView.setText("Waiting for steering payload...");
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
        filter.addAction("com.percherry.roundadas");
        filter.addAction("com.percherry.roundadas.LOOK_AROUND_360_CAN");

        registerReceiver(receiver, filter);

        angleView.setText("Listening...");
        rangeView.setText(
            "Listening for:\n" +
            "com.percherry.roundadas\n" +
            "LOOK_AROUND_360_CAN"
        );

        rawView.setText("No broadcast received yet");

        Intent sync = new Intent("com.percherry.roundadas");
        sync.putExtra("cmd", "readyForSync");
        sendBroadcast(sync);
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}
    }
}
