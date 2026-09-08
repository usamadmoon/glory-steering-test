package com.percherry.roundadas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private TextView angleView;
    private TextView rangeView;
    private TextView rawView;

    private Integer minAngle = null;
    private Integer maxAngle = null;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String raw = intent.getStringExtra("steering_wheel");

            if (raw == null) {
                rawView.setText("Broadcast received, but steering_wheel is null");
                return;
            }

            rawView.setText(raw);

            try {
                JSONObject json = new JSONObject(raw);

                int angle = Integer.parseInt(json.optString("angle", "0"));
                int speed = Integer.parseInt(json.optString("speed", "0"));

                if (minAngle == null || angle < minAngle) {
                    minAngle = angle;
                }

                if (maxAngle == null || angle > maxAngle) {
                    maxAngle = angle;
                }

                angleView.setText(
                    "Steering angle: " + angle +
                    "\nSteering speed: " + speed
                );

                rangeView.setText(
                    "Minimum: " + minAngle +
                    "\nMaximum: " + maxAngle
                );

            } catch (Exception e) {
                angleView.setText("Received steering data, but could not parse it");
            }
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

        IntentFilter filter =
            new IntentFilter("com.percherry.roundadas.LOOK_AROUND_360_CAN");

        registerReceiver(receiver, filter);

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
