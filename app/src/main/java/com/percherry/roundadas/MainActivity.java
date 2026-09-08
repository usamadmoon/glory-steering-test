
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
                rawView.setText("Broadcast received\nsteering_wheel extra is null");
                return;
            }

            rawView.setText(raw);

            try {
                JSONObject json = new JSONObject(raw);
                int angle = json.getInt("steering_wheel_angle");

                if (minAngle == null || angle < minAngle)
                    minAngle = angle;

                if (maxAngle == null || angle > maxAngle)
                    maxAngle = angle;

                angleView.setText("Steering angle: " + angle);

                rangeView.setText(
                    "Minimum: " + minAngle +
                    "\nMaximum: " + maxAngle
                );

            } catch (Exception e) {
                angleView.setText("Received data - unable to parse angle");
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
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}
    }
}
