package com.percherry.roundadas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    /*
     * Factory APK findings:
     *
     * vendor.autochips.hardware.car_event_monitor.V1_0.ICarEventMonitor
     *
     * Known extras:
     *   speed_current_speed
     *   speed_accelerate_speed
     *   steering_wheel_angle
     *   steering_wheel_speed
     *
     * Firmware-configurable broadcast destination:
     *   persist.vendor.cem.intent.action
     *   persist.vendor.cem.intent.package
     *
     * Fallback action found in the factory APK:
     *   com.percherry.roundadas.LOOK_AROUND_360_CAN
     */

    private static final String PROP_ACTION =
            "persist.vendor.cem.intent.action";

    private static final String PROP_PACKAGE =
            "persist.vendor.cem.intent.package";

    private static final String FALLBACK_ACTION =
            "com.percherry.roundadas.LOOK_AROUND_360_CAN";

    private TextView steeringView;
    private TextView speedView;
    private TextView fuelView;
    private TextView statusView;
    private TextView rawView;

    private Button startButton;
    private Button clearButton;

    private boolean receiverRegistered = false;

    private String configuredAction = "";
    private String configuredPackage = "";

    private long eventCount = 0;

    private String lastSpeed = "--";
    private String lastAcceleration = "--";
    private String lastSteeringAngle = "--";
    private String lastSteeringSpeed = "--";

    private final ArrayList<String> recentEvents = new ArrayList<>();

    private final BroadcastReceiver carEventReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {

                    if (intent == null) {
                        return;
                    }

                    eventCount++;

                    String action =
                            intent.getAction();

                    Bundle extras =
                            intent.getExtras();

                    StringBuilder log =
                            new StringBuilder();

                    log.append("#")
                            .append(eventCount)
                            .append(" ACTION=")
                            .append(action == null ? "<null>" : action)
                            .append("\n");

                    if (extras == null
                            || extras.isEmpty()) {

                        log.append("No extras");

                        addEvent(
                                log.toString()
                        );

                        updateHeader();

                        return;
                    }

                    ArrayList<String> keys =
                            new ArrayList<>(
                                    extras.keySet()
                            );

                    Collections.sort(keys);

                    for (String key : keys) {

                        Object value;

                        try {

                            value =
                                    extras.get(key);

                        } catch (Throwable t) {

                            value =
                                    "<error:"
                                            + t.getClass()
                                            .getSimpleName()
                                            + ">";
                        }

                        log.append(key)
                                .append("=")
                                .append(String.valueOf(value))
                                .append("\n");

                        processKnownField(
                                key,
                                value
                        );
                    }

                    addEvent(
                            log.toString()
                    );

                    updateHeader();
                }
            };


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

        startButton =
                findViewById(
                        R.id.sniff
                );

        clearButton =
                findViewById(
                        R.id.clear
                );


        startButton.setText(
                "START AUTOCHIPS EVENT PROBE"
        );

        clearButton.setText(
                "CLEAR EVENTS"
        );

        speedView.setText(
                "Vehicle speed: --"
        );

        steeringView.setText(
                "Steering angle: --"
        );

        fuelView.setText(
                "Fuel: not exposed by this Car Event service"
        );


        startButton.setOnClickListener(
                v -> startProbe()
        );


        clearButton.setOnClickListener(
                v -> clearEvents()
        );


        refreshConfiguration();

        showInitialInformation();
    }


    /*
     * -------------------------------------------------------------
     * START LISTENING
     * -------------------------------------------------------------
     */
    private void startProbe() {

        if (receiverRegistered) {

            rawView.setText(
                    "Probe is already active."
            );

            return;
        }

        refreshConfiguration();

        IntentFilter filter =
                new IntentFilter();

        boolean haveAction =
                false;


        /*
         * Listen to the actual firmware-configured action first.
         */
        if (
                configuredAction != null
                        && !configuredAction.trim().isEmpty()
        ) {

            filter.addAction(
                    configuredAction.trim()
            );

            haveAction = true;
        }


        /*
         * Also listen to the fallback action found in the factory APK.
         */
        if (
                configuredAction == null
                        || !FALLBACK_ACTION.equals(
                        configuredAction.trim()
                )
        ) {

            filter.addAction(
                    FALLBACK_ACTION
            );

            haveAction = true;
        }


        if (!haveAction) {

            rawView.setText(
                    "No usable broadcast action found."
            );

            return;
        }


        try {

            registerReceiver(
                    carEventReceiver,
                    filter
            );

            receiverRegistered =
                    true;

            startButton.setText(
                    "AUTOCHIPS PROBE ACTIVE"
            );

            startButton.setEnabled(
                    false
            );

            StringBuilder info =
                    new StringBuilder();

            info.append(
                    "Listening for factory Car Event broadcasts.\n\n"
            );

            info.append(
                    "Configured action:\n"
            );

            info.append(
                    safe(configuredAction)
            );

            info.append(
                    "\n\nConfigured package:\n"
            );

            info.append(
                    safe(configuredPackage)
            );

            info.append(
                    "\n\nOur package:\n"
            );

            info.append(
                    getPackageName()
            );

            info.append(
                    "\n\nFallback action:\n"
            );

            info.append(
                    FALLBACK_ACTION
            );

            info.append(
                    "\n\nNow drive slowly or turn the steering wheel."
            );

            rawView.setText(
                    info.toString()
            );

            updateHeader();

        } catch (Throwable t) {

            rawView.setText(
                    "Receiver registration failed:\n"
                            + t.getClass()
                            .getName()
                            + "\n"
                            + String.valueOf(
                            t.getMessage()
                    )
            );
        }
    }


    /*
     * -------------------------------------------------------------
     * KNOWN FACTORY FIELDS
     * -------------------------------------------------------------
     */
    private void processKnownField(
            String key,
            Object value
    ) {

        if (key == null) {
            return;
        }

        String normalized =
                key.toLowerCase(
                        Locale.US
                );

        if (
                normalized.equals(
                        "speed_current_speed"
                )
                        || normalized.equals(
                        "current_speed"
                )
        ) {

            lastSpeed =
                    valueText(value);

            speedView.setText(
                    "Vehicle speed: "
                            + lastSpeed
            );
        }


        if (
                normalized.equals(
                        "speed_accelerate_speed"
                )
                        || normalized.equals(
                        "accelerate_speed"
                )
        ) {

            lastAcceleration =
                    valueText(value);
        }


        if (
                normalized.equals(
                        "steering_wheel_angle"
                )
                        || normalized.equals(
                        "steering_wheel"
                )
        ) {

            lastSteeringAngle =
                    valueText(value);

            steeringView.setText(
                    "Steering angle: "
                            + lastSteeringAngle
            );
        }


        if (
                normalized.equals(
                        "steering_wheel_speed"
                )
        ) {

            lastSteeringSpeed =
                    valueText(value);
        }
    }


    /*
     * -------------------------------------------------------------
     * SYSTEM PROPERTY DISCOVERY
     * -------------------------------------------------------------
     *
     * android.os.SystemProperties is hidden from the public SDK,
     * so use reflection. On this head unit it may still be readable.
     */
    private void refreshConfiguration() {

        configuredAction =
                readSystemProperty(
                        PROP_ACTION,
                        ""
                );

        configuredPackage =
                readSystemProperty(
                        PROP_PACKAGE,
                        ""
                );
    }


    private static String readSystemProperty(
            String key,
            String defaultValue
    ) {

        try {

            Class<?> clazz =
                    Class.forName(
                            "android.os.SystemProperties"
                    );

            Method get =
                    clazz.getMethod(
                            "get",
                            String.class,
                            String.class
                    );

            Object result =
                    get.invoke(
                            null,
                            key,
                            defaultValue
                    );

            if (result == null) {
                return defaultValue;
            }

            return String.valueOf(
                    result
            );

        } catch (Throwable ignored) {

            return defaultValue;
        }
    }


    /*
     * -------------------------------------------------------------
     * DISPLAY
     * -------------------------------------------------------------
     */
    private void showInitialInformation() {

        String vendorHal;

        try {

            Class.forName(
                    "vendor.autochips.hardware.car_event_monitor.V1_0.ICarEventMonitor"
            );

            vendorHal =
                    "FOUND";

        } catch (Throwable t) {

            vendorHal =
                    "not visible to this app";
        }


        StringBuilder out =
                new StringBuilder();

        out.append(
                "AUTOCHIPS CAR EVENT PROBE\n\n"
        );

        out.append(
                "Vendor HAL class: "
        );

        out.append(
                vendorHal
        );

        out.append(
                "\n\n"
        );

        out.append(
                PROP_ACTION
        );

        out.append(
                " =\n"
        );

        out.append(
                safe(configuredAction)
        );

        out.append(
                "\n\n"
        );

        out.append(
                PROP_PACKAGE
        );

        out.append(
                " =\n"
        );

        out.append(
                safe(configuredPackage)
        );

        out.append(
                "\n\nOur package =\n"
        );

        out.append(
                getPackageName()
        );

        out.append(
                "\n\nPress START AUTOCHIPS EVENT PROBE."
        );

        rawView.setText(
                out.toString()
        );

        updateHeader();
    }


    private void updateHeader() {

        statusView.setText(
                "Autochips probe: "
                        + (
                        receiverRegistered
                                ? "ACTIVE"
                                : "not started"
                )
                        + "\nEvents received: "
                        + eventCount
                        + "\nAction: "
                        + shortText(
                        configuredAction
                )
                        + "\nTarget package: "
                        + shortText(
                        configuredPackage
                )
        );


        if (
                !"--".equals(
                        lastAcceleration
                )
        ) {

            speedView.setText(
                    "Speed: "
                            + lastSpeed
                            + "   Accel: "
                            + lastAcceleration
            );
        }


        if (
                !"--".equals(
                        lastSteeringSpeed
                )
        ) {

            steeringView.setText(
                    "Steering angle: "
                            + lastSteeringAngle
                            + "   Speed: "
                            + lastSteeringSpeed
            );
        }
    }


    private void addEvent(
            String event
    ) {

        recentEvents.add(
                0,
                event
        );

        while (
                recentEvents.size() > 20
        ) {

            recentEvents.remove(
                    recentEvents.size() - 1
            );
        }


        StringBuilder out =
                new StringBuilder();

        out.append(
                "LIVE CAR EVENTS\n\n"
        );

        for (String item : recentEvents) {

            out.append(
                    item
            );

            out.append(
                    "\n--------------------\n"
            );
        }


        rawView.setText(
                out.toString()
        );
    }


    private void clearEvents() {

        recentEvents.clear();

        eventCount = 0;

        lastSpeed = "--";
        lastAcceleration = "--";
        lastSteeringAngle = "--";
        lastSteeringSpeed = "--";

        speedView.setText(
                "Vehicle speed: --"
        );

        steeringView.setText(
                "Steering angle: --"
        );

        rawView.setText(
                "Events cleared.\n\n"
                        + (
                        receiverRegistered
                                ? "Probe is still active."
                                : "Press START AUTOCHIPS EVENT PROBE."
                )
        );

        updateHeader();
    }


    private static String valueText(
            Object value
    ) {

        if (value == null) {
            return "<null>";
        }

        return String.valueOf(
                value
        );
    }


    private static String safe(
            String text
    ) {

        if (
                text == null
                        || text.trim().isEmpty()
        ) {

            return "<empty / unreadable>";
        }

        return text;
    }


    private static String shortText(
            String text
    ) {

        if (
                text == null
                        || text.trim().isEmpty()
        ) {

            return "<empty>";
        }

        if (
                text.length() <= 42
        ) {

            return text;
        }

        return text.substring(
                0,
                39
        ) + "...";
    }


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

        super.onDestroy();
    }
}
