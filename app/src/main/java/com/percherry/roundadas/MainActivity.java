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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
 * Glory 580 Pro unified vehicle-data probe
 *
 * Information used from the three factory APKs:
 *
 * 1) Autochips Car Event service
 *    vendor.autochips.hardware.car_event_monitor.V1_0.ICarEventMonitor
 *
 *    Known API:
 *      getService()
 *      init(ICarEventCallback)
 *      getCarInfo()
 *      deinit()
 *
 *    Known data classes / fields:
 *      CarEventInfo
 *      SpeedInfo.currentSpeed
 *      SpeedInfo.accelerateSpeed
 *      SteeringWheelInfo
 *      GearBoxInfo
 *      PowerInfo
 *      LightInfo
 *
 *    Broadcast keys seen in the factory service:
 *      speed_current_speed
 *      speed_accelerate_speed
 *      steering_wheel_angle
 *      steering_wheel_speed
 *
 *    Firmware broadcast properties:
 *      persist.vendor.cem.intent.action
 *      persist.vendor.cem.intent.package
 *
 * 2) CarOcean MCU server
 *      action:  com.carocean.mcuservice
 *      package: com.carocean.mcuserver
 *
 *    callback registration:
 *      Message.what = 256
 *      Bundle int[] "cmdcode"
 *      replyTo = callback Messenger
 *
 *    Passive MCU observations from our tests:
 *      0x9B B5 -> binary movement/state candidate
 *      0x9B B6 -> reverse-related candidate
 *      0xA6 B1 -> reverse/parking-related candidate
 *      0x9C B3..B6 -> parking/radar/state-like bytes
 *      0xEB -> factory rear-camera code references it, but our car has
 *              not delivered it as a continuous steering stream.
 *
 * 3) Rear-camera APK
 *      trace_left_%d.bin
 *      trace_middle.bin
 *      trace_right_%d.bin
 *
 *    This strongly suggests discrete dynamic-guideline positions.
 *
 * This app remains diagnostic/read-only. It polls CarEventInfo, listens
 * for factory broadcasts, and registers passive MCU callbacks. It does
 * not send vehicle-control commands.
 */
public class MainActivity extends Activity {

    // ---------- Autochips HAL ----------
    private static final String HAL_MONITOR =
            "vendor.autochips.hardware.car_event_monitor.V1_0.ICarEventMonitor";

    private static final String HAL_CALLBACK =
            "vendor.autochips.hardware.car_event_monitor.V1_0.ICarEventCallback";

    private static final String PROP_ACTION =
            "persist.vendor.cem.intent.action";

    private static final String PROP_PACKAGE =
            "persist.vendor.cem.intent.package";

    private static final String FALLBACK_ACTION =
            "com.percherry.roundadas.LOOK_AROUND_360_CAN";

    // ---------- CarOcean MCU ----------
    private static final String MCU_ACTION =
            "com.carocean.mcuservice";

    private static final String MCU_PACKAGE =
            "com.carocean.mcuserver";

    private static final int MCU_REGISTER_CALLBACK = 256;

    // Only commands we have actually observed / investigated.
    private static final int[] KNOWN_MCU_COMMANDS =
            new int[]{0x9A, 0x9B, 0x9C, 0xA6, 0xEB};

    // ---------- UI ----------
    private TextView steeringView;
    private TextView speedView;
    private TextView fuelView;
    private TextView statusView;
    private TextView rawView;

    private Button startButton;
    private Button clearButton;

    // ---------- State ----------
    private final Handler mainHandler = new Handler();

    private Object carEventMonitor = null;
    private Method getCarInfoMethod = null;
    private Method deinitMethod = null;

    private boolean halConnected = false;
    private boolean halPolling = false;
    private boolean callbackInitAttempted = false;
    private boolean callbackInitSucceeded = false;

    private String configuredBroadcastAction = "";
    private String configuredBroadcastPackage = "";
    private boolean broadcastReceiverRegistered = false;
    private long broadcastCount = 0;

    private Messenger mcuMessenger = null;
    private boolean mcuBound = false;
    private boolean mcuRegistered = false;
    private long mcuPacketCount = 0;

    private long pollCount = 0;

    private String lastCurrentSpeed = "--";
    private String lastAcceleration = "--";
    private String lastSteeringAngle = "--";
    private String lastSteeringSpeed = "--";
    private String lastGear = "--";

    private final ArrayDeque<String> eventLog = new ArrayDeque<>();

    private final Map<Integer, byte[]> lastMcuPayload =
            new LinkedHashMap<>();

    private final Map<Integer, Long> mcuChangeCount =
            new LinkedHashMap<>();


    // =============================================================
    // ACTIVITY
    // =============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        steeringView = findViewById(R.id.steering);
        speedView = findViewById(R.id.speed);
        fuelView = findViewById(R.id.fuel);
        statusView = findViewById(R.id.status);
        rawView = findViewById(R.id.raw);

        startButton = findViewById(R.id.sniff);
        clearButton = findViewById(R.id.clear);

        startButton.setText("START UNIFIED VEHICLE PROBE");
        clearButton.setText("CLEAR / RESET VIEW");

        steeringView.setText("Steering: --");
        speedView.setText("Vehicle speed: --");
        fuelView.setText("Fuel: not found in these three factory APIs");

        startButton.setOnClickListener(v -> startUnifiedProbe());

        clearButton.setOnClickListener(v -> clearView());

        discoverFactoryEnvironment();
        updateStatus();

        rawView.setText(
                "Unified probe ready.\n\n" +
                "It will try, in this order:\n" +
                "1. Autochips ICarEventMonitor.getService()\n" +
                "2. Poll getCarInfo() every 250 ms\n" +
                "3. Listen for factory Car Event broadcasts\n" +
                "4. Passively listen to known MCU commands\n\n" +
                "No vehicle-control command is transmitted."
        );
    }


    // =============================================================
    // START EVERYTHING
    // =============================================================

    private void startUnifiedProbe() {
        startButton.setEnabled(false);
        startButton.setText("VEHICLE PROBE ACTIVE");

        addLog("=== STARTING UNIFIED PROBE ===");

        refreshSystemProperties();

        startBroadcastProbe();
        connectAutochipsHal();
        bindMcuServer();

        updateStatus();
    }


    // =============================================================
    // AUTOCHIPS HAL
    // =============================================================

    private void discoverFactoryEnvironment() {
        refreshSystemProperties();

        try {
            Class.forName(HAL_MONITOR);
            addLog("Autochips monitor class: FOUND");
        } catch (Throwable t) {
            addLog("Autochips monitor class: NOT VISIBLE - "
                    + simpleError(t));
        }

        try {
            Class.forName(HAL_CALLBACK);
            addLog("Autochips callback interface: FOUND");
        } catch (Throwable t) {
            addLog("Autochips callback interface: NOT VISIBLE - "
                    + simpleError(t));
        }

        addLog("Broadcast action property: "
                + emptyText(configuredBroadcastAction));

        addLog("Broadcast package property: "
                + emptyText(configuredBroadcastPackage));
    }


    private void connectAutochipsHal() {
        try {
            Class<?> monitorClass =
                    Class.forName(HAL_MONITOR);

            Method getService =
                    findGetServiceMethod(monitorClass);

            if (getService == null) {
                addLog("HAL getService(): method not found");
                return;
            }

            Object service =
                    invokeGetService(getService);

            if (service == null) {
                addLog("HAL getService(): returned null");
                return;
            }

            carEventMonitor = service;
            halConnected = true;

            addLog("HAL CONNECTED: "
                    + service.getClass().getName());

            getCarInfoMethod =
                    findNoArgMethod(
                            service.getClass(),
                            "getCarInfo"
                    );

            deinitMethod =
                    findNoArgMethod(
                            service.getClass(),
                            "deinit"
                    );

            Method initMethod =
                    findMethodByName(
                            service.getClass(),
                            "init"
                    );

            if (getCarInfoMethod != null) {
                addLog("HAL getCarInfo(): FOUND");
                startCarInfoPolling();
            } else {
                addLog("HAL getCarInfo(): NOT FOUND");
            }

            /*
             * Try the real factory init(ICarEventCallback) path as an
             * additional source. Reflection Proxy may or may not be accepted
             * by this HIDL build. Failure is harmless because polling remains
             * active.
             */
            if (initMethod != null) {
                tryInitCallback(initMethod);
            } else {
                addLog("HAL init(callback): NOT FOUND");
            }

        } catch (Throwable t) {
            addLog("HAL connection ERROR: "
                    + fullError(t));
        }

        updateStatus();
    }


    private Method findGetServiceMethod(
            Class<?> monitorClass
    ) {
        // Prefer exactly the same zero-arg getService() used by factory code.
        try {
            return monitorClass.getMethod("getService");
        } catch (Throwable ignored) {
        }

        for (Method method : monitorClass.getMethods()) {
            if (method.getName().equals("getService")
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }

        return null;
    }


    private Object invokeGetService(
            Method method
    ) throws Exception {

        Class<?>[] params =
                method.getParameterTypes();

        if (params.length == 0) {
            return method.invoke(null);
        }

        if (params.length == 1
                && params[0] == String.class) {
            return method.invoke(
                    null,
                    "default"
            );
        }

        if (params.length == 2
                && params[0] == String.class
                && (params[1] == boolean.class
                || params[1] == Boolean.class)) {
            return method.invoke(
                    null,
                    "default",
                    true
            );
        }

        throw new IllegalStateException(
                "Unsupported getService signature: "
                        + method.toString()
        );
    }


    private void tryInitCallback(
            Method initMethod
    ) {
        callbackInitAttempted = true;

        try {
            Class<?>[] params =
                    initMethod.getParameterTypes();

            if (params.length != 1) {
                addLog("HAL init(): unexpected parameter count "
                        + params.length);
                return;
            }

            Class<?> callbackType =
                    params[0];

            if (!callbackType.isInterface()) {
                addLog("HAL callback type is not a Java interface; "
                        + "polling getCarInfo() only");
                return;
            }

            InvocationHandler handler =
                    (proxy, method, args) -> {

                        String name =
                                method.getName();

                        if ("onEvent".equals(name)) {
                            if (args != null
                                    && args.length > 0
                                    && args[0] != null) {
                                addLog("HAL CALLBACK onEvent:\n"
                                        + dumpObject(args[0], 2));
                            } else {
                                addLog("HAL CALLBACK onEvent");
                            }
                        }

                        /*
                         * Common IBase / binder-ish calls on generated HIDL
                         * interfaces. Return safe defaults for methods that
                         * might be invoked on the proxy locally.
                         */
                        Class<?> returnType =
                                method.getReturnType();

                        return defaultValue(returnType);
                    };

            Object callbackProxy =
                    Proxy.newProxyInstance(
                            callbackType.getClassLoader(),
                            new Class<?>[]{callbackType},
                            handler
                    );

            Object result =
                    initMethod.invoke(
                            carEventMonitor,
                            callbackProxy
                    );

            callbackInitSucceeded = true;

            addLog("HAL init(callback): invoked successfully"
                    + (result == null
                    ? ""
                    : " -> " + String.valueOf(result)));

        } catch (Throwable t) {
            callbackInitSucceeded = false;

            addLog("HAL init(callback) not usable from reflection: "
                    + fullError(t)
                    + "\nPolling getCarInfo() continues.");
        }
    }


    private void startCarInfoPolling() {
        if (halPolling
                || carEventMonitor == null
                || getCarInfoMethod == null) {
            return;
        }

        halPolling = true;

        mainHandler.post(
                carInfoPollRunnable
        );

        addLog("HAL getCarInfo() polling STARTED (250 ms)");
    }


    private final Runnable carInfoPollRunnable =
            new Runnable() {

                @Override
                public void run() {
                    if (!halPolling
                            || carEventMonitor == null
                            || getCarInfoMethod == null) {
                        return;
                    }

                    try {
                        Object carInfo =
                                getCarInfoMethod.invoke(
                                        carEventMonitor
                                );

                        pollCount++;

                        if (carInfo != null) {
                            processCarInfoObject(carInfo);
                        }

                    } catch (Throwable t) {
                        addLog("getCarInfo() ERROR: "
                                + fullError(t));

                        /*
                         * Don't hammer a permanently rejected HAL.
                         */
                        halPolling = false;
                        updateStatus();
                        return;
                    }

                    updateStatus();

                    mainHandler.postDelayed(
                            this,
                            250
                    );
                }
            };


    /*
     * We do not assume the exact generated field layout. Instead, walk the
     * returned CarEventInfo object and extract known field names discovered
     * in the factory APK.
     */
    private void processCarInfoObject(
            Object carInfo
    ) {

        Map<String, String> values =
                new LinkedHashMap<>();

        collectFields(
                carInfo,
                "",
                0,
                4,
                values
        );

        String speed =
                firstValue(
                        values,
                        "currentSpeed",
                        "current_speed"
                );

        String accel =
                firstValue(
                        values,
                        "accelerateSpeed",
                        "accelerate_speed"
                );

        String steerAngle =
                firstValue(
                        values,
                        "steeringWheelAngle",
                        "steering_wheel_angle",
                        "angle"
                );

        String steerSpeed =
                firstValue(
                        values,
                        "steeringWheelSpeed",
                        "steering_wheel_speed"
                );

        String gear =
                firstValueContaining(
                        values,
                        "gear"
                );

        boolean screenChanged =
                false;

        if (speed != null
                && !speed.equals(lastCurrentSpeed)) {
            lastCurrentSpeed = speed;
            screenChanged = true;
        }

        if (accel != null
                && !accel.equals(lastAcceleration)) {
            lastAcceleration = accel;
            screenChanged = true;
        }

        if (steerAngle != null
                && !steerAngle.equals(lastSteeringAngle)) {
            lastSteeringAngle = steerAngle;
            screenChanged = true;
        }

        if (steerSpeed != null
                && !steerSpeed.equals(lastSteeringSpeed)) {
            lastSteeringSpeed = steerSpeed;
            screenChanged = true;
        }

        if (gear != null
                && !gear.equals(lastGear)) {
            lastGear = gear;
            screenChanged = true;
        }

        speedView.setText(
                "Speed: " + lastCurrentSpeed
                        + "   Accel: " + lastAcceleration
        );

        steeringView.setText(
                "Steering angle: "
                        + lastSteeringAngle
                        + "   speed: "
                        + lastSteeringSpeed
        );

        /*
         * Only dump CarEventInfo when a known value changed, otherwise
         * 4-Hz polling would flood the screen.
         */
        if (screenChanged) {
            StringBuilder out =
                    new StringBuilder();

            out.append("HAL CarEventInfo CHANGED\n");

            if (speed != null) {
                out.append("currentSpeed = ")
                        .append(speed)
                        .append("\n");
            }

            if (accel != null) {
                out.append("accelerateSpeed = ")
                        .append(accel)
                        .append("\n");
            }

            if (steerAngle != null) {
                out.append("steeringAngle = ")
                        .append(steerAngle)
                        .append("\n");
            }

            if (steerSpeed != null) {
                out.append("steeringSpeed = ")
                        .append(steerSpeed)
                        .append("\n");
            }

            if (gear != null) {
                out.append("gear candidate = ")
                        .append(gear)
                        .append("\n");
            }

            out.append("\nRAW FIELDS:\n");

            for (Map.Entry<String, String> entry
                    : values.entrySet()) {

                out.append(entry.getKey())
                        .append(" = ")
                        .append(entry.getValue())
                        .append("\n");
            }

            addLog(out.toString());
        }
    }


    // =============================================================
    // FACTORY BROADCAST LISTENER
    // =============================================================

    private final BroadcastReceiver factoryReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {
                    if (intent == null) {
                        return;
                    }

                    broadcastCount++;

                    StringBuilder out =
                            new StringBuilder();

                    out.append("FACTORY BROADCAST #")
                            .append(broadcastCount)
                            .append("\n");

                    out.append("action=")
                            .append(intent.getAction())
                            .append("\n");

                    Bundle extras =
                            intent.getExtras();

                    if (extras != null) {
                        ArrayList<String> keys =
                                new ArrayList<>(
                                        extras.keySet()
                                );

                        Collections.sort(keys);

                        for (String key : keys) {
                            Object value;

                            try {
                                value = extras.get(key);
                            } catch (Throwable t) {
                                value = "<error>";
                            }

                            out.append(key)
                                    .append("=")
                                    .append(String.valueOf(value))
                                    .append("\n");

                            processBroadcastField(
                                    key,
                                    value
                            );
                        }
                    }

                    addLog(out.toString());
                    updateStatus();
                }
            };


    private void startBroadcastProbe() {
        if (broadcastReceiverRegistered) {
            return;
        }

        IntentFilter filter =
                new IntentFilter();

        if (configuredBroadcastAction != null
                && !configuredBroadcastAction.trim().isEmpty()) {
            filter.addAction(
                    configuredBroadcastAction.trim()
            );
        }

        if (configuredBroadcastAction == null
                || !FALLBACK_ACTION.equals(
                configuredBroadcastAction.trim()
        )) {
            filter.addAction(
                    FALLBACK_ACTION
            );
        }

        try {
            registerReceiver(
                    factoryReceiver,
                    filter
            );

            broadcastReceiverRegistered = true;

            addLog("Factory broadcast listener ACTIVE");

        } catch (Throwable t) {
            addLog("Factory broadcast listener ERROR: "
                    + simpleError(t));
        }
    }


    private void processBroadcastField(
            String key,
            Object value
    ) {
        if (key == null) {
            return;
        }

        String k =
                key.toLowerCase(Locale.US);

        if (k.equals("speed_current_speed")
                || k.equals("current_speed")) {
            lastCurrentSpeed =
                    String.valueOf(value);
        }

        if (k.equals("speed_accelerate_speed")
                || k.equals("accelerate_speed")) {
            lastAcceleration =
                    String.valueOf(value);
        }

        if (k.equals("steering_wheel_angle")) {
            lastSteeringAngle =
                    String.valueOf(value);
        }

        if (k.equals("steering_wheel_speed")) {
            lastSteeringSpeed =
                    String.valueOf(value);
        }
    }


    // =============================================================
    // MCU SERVER PASSIVE LISTENER
    // =============================================================

    private final Handler mcuHandler =
            new Handler(msg -> {

                Bundle data =
                        msg.getData();

                if (data == null) {
                    return true;
                }

                int command =
                        data.getInt(
                                "cmdcode",
                                -1
                        );

                byte[] payload =
                        data.getByteArray(
                                "data"
                        );

                if (command < 0
                        || payload == null) {
                    return true;
                }

                mcuPacketCount++;

                byte[] previous =
                        lastMcuPayload.get(
                                command
                        );

                boolean changed =
                        previous == null
                                || !Arrays.equals(
                                previous,
                                payload
                        );

                if (changed) {
                    long count =
                            mcuChangeCount.containsKey(command)
                                    ? mcuChangeCount.get(command) + 1
                                    : 1;

                    mcuChangeCount.put(
                            command,
                            count
                    );

                    lastMcuPayload.put(
                            command,
                            Arrays.copyOf(
                                    payload,
                                    payload.length
                            )
                    );

                    addLog(
                            describeMcuChange(
                                    command,
                                    previous,
                                    payload,
                                    count
                            )
                    );
                }

                updateStatus();
                return true;
            });


    private final Messenger mcuReply =
            new Messenger(
                    mcuHandler
            );


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

                    addLog("MCU server CONNECTED");

                    registerKnownMcuCallbacks();
                    updateStatus();
                }

                @Override
                public void onServiceDisconnected(
                        ComponentName name
                ) {
                    mcuBound = false;
                    mcuMessenger = null;
                    mcuRegistered = false;

                    addLog("MCU server DISCONNECTED");
                    updateStatus();
                }
            };


    private void bindMcuServer() {
        if (mcuBound) {
            return;
        }

        Intent intent =
                new Intent(MCU_ACTION);

        intent.setPackage(
                MCU_PACKAGE
        );

        try {
            boolean result =
                    bindService(
                            intent,
                            mcuConnection,
                            Context.BIND_AUTO_CREATE
                    );

            if (!result) {
                addLog("MCU bindService(): false");
            }

        } catch (Throwable t) {
            addLog("MCU bind ERROR: "
                    + simpleError(t));
        }
    }


    private void registerKnownMcuCallbacks() {
        if (mcuMessenger == null) {
            return;
        }

        try {
            Message msg =
                    Message.obtain(
                            null,
                            MCU_REGISTER_CALLBACK
                    );

            Bundle bundle =
                    new Bundle();

            bundle.putIntArray(
                    "cmdcode",
                    KNOWN_MCU_COMMANDS
            );

            msg.setData(
                    bundle
            );

            msg.replyTo =
                    mcuReply;

            mcuMessenger.send(
                    msg
            );

            mcuRegistered = true;

            addLog(
                    "MCU passive callbacks registered: "
                            + "0x9A,0x9B,0x9C,0xA6,0xEB"
            );

        } catch (RemoteException e) {
            addLog("MCU callback registration ERROR: "
                    + e.getMessage());
        }
    }


    private String describeMcuChange(
            int command,
            byte[] oldData,
            byte[] newData,
            long changeNo
    ) {
        StringBuilder out =
                new StringBuilder();

        out.append(
                String.format(
                        Locale.US,
                        "MCU 0x%02X CHANGE #%d\n",
                        command & 0xFF,
                        changeNo
                )
        );

        out.append("NOW: ")
                .append(hex(newData))
                .append("\n");

        if (oldData != null
                && oldData.length == newData.length) {

            out.append("Changed bytes: ");

            boolean any =
                    false;

            for (int i = 0;
                 i < newData.length;
                 i++) {

                int oldValue =
                        oldData[i] & 0xFF;

                int newValue =
                        newData[i] & 0xFF;

                if (oldValue != newValue) {
                    any = true;

                    out.append(
                            String.format(
                                    Locale.US,
                                    "B%d %02X>%02X  ",
                                    i,
                                    oldValue,
                                    newValue
                            )
                    );
                }
            }

            if (!any) {
                out.append("none");
            }

            out.append("\n");
        }

        if (command == 0x9B) {
            out.append(
                    "Known clues: B5=binary movement/state candidate; "
                            + "B6=reverse-related candidate.\n"
            );
        }

        if (command == 0xA6) {
            out.append(
                    "Known clue: B1=reverse/parking-related candidate.\n"
            );
        }

        if (command == 0x9C) {
            out.append(
                    "Known clue: B3..B6 behave like parking/radar/state channels.\n"
            );
        }

        if (command == 0xEB) {
            out.append(
                    "0xEB observed by rear-camera code; "
                            + "not yet proven as continuous steering data.\n"
            );
        }

        return out.toString();
    }


    // =============================================================
    // REFLECTION / OBJECT INSPECTION
    // =============================================================

    private static Method findNoArgMethod(
            Class<?> type,
            String name
    ) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == 0) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }


    private static Method findMethodByName(
            Class<?> type,
            String name
    ) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }


    private static void collectFields(
            Object object,
            String prefix,
            int depth,
            int maxDepth,
            Map<String, String> out
    ) {
        if (object == null
                || depth > maxDepth) {
            return;
        }

        Class<?> type =
                object.getClass();

        if (isSimpleType(type)) {
            out.put(
                    prefix,
                    String.valueOf(object)
            );
            return;
        }

        /*
         * Public fields are common on generated HIDL structs.
         */
        Field[] fields =
                type.getFields();

        Arrays.sort(
                fields,
                Comparator.comparing(Field::getName)
        );

        for (Field field : fields) {
            if (Modifier.isStatic(
                    field.getModifiers()
            )) {
                continue;
            }

            try {
                Object value =
                        field.get(object);

                String path =
                        prefix.isEmpty()
                                ? field.getName()
                                : prefix + "."
                                + field.getName();

                if (value == null) {
                    out.put(
                            path,
                            "<null>"
                    );
                    continue;
                }

                if (isSimpleType(
                        value.getClass()
                )) {
                    out.put(
                            path,
                            String.valueOf(value)
                    );
                } else if (value instanceof Iterable) {
                    int index =
                            0;

                    for (Object item
                            : (Iterable<?>) value) {
                        collectFields(
                                item,
                                path + "[" + index + "]",
                                depth + 1,
                                maxDepth,
                                out
                        );
                        index++;
                    }

                    if (index == 0) {
                        out.put(path, "[]");
                    }

                } else if (value.getClass().isArray()) {
                    int length =
                            java.lang.reflect.Array.getLength(
                                    value
                            );

                    for (int i = 0;
                         i < length;
                         i++) {
                        Object item =
                                java.lang.reflect.Array.get(
                                        value,
                                        i
                                );

                        collectFields(
                                item,
                                path + "[" + i + "]",
                                depth + 1,
                                maxDepth,
                                out
                        );
                    }

                    if (length == 0) {
                        out.put(path, "[]");
                    }

                } else {
                    collectFields(
                            value,
                            path,
                            depth + 1,
                            maxDepth,
                            out
                    );
                }

            } catch (Throwable ignored) {
            }
        }

        /*
         * If there are no public fields, at least expose toString().
         */
        if (fields.length == 0
                && !prefix.isEmpty()) {
            out.put(
                    prefix,
                    String.valueOf(object)
            );
        }
    }


    private static boolean isSimpleType(
            Class<?> type
    ) {
        return type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Enum.class.isAssignableFrom(type);
    }


    private static String firstValue(
            Map<String, String> values,
            String... endings
    ) {
        for (Map.Entry<String, String> entry
                : values.entrySet()) {

            String key =
                    entry.getKey()
                            .toLowerCase(Locale.US);

            for (String ending : endings) {
                String e =
                        ending.toLowerCase(Locale.US);

                if (key.equals(e)
                        || key.endsWith("." + e)
                        || key.endsWith("_" + e)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }


    private static String firstValueContaining(
            Map<String, String> values,
            String text
    ) {
        String lower =
                text.toLowerCase(Locale.US);

        for (Map.Entry<String, String> entry
                : values.entrySet()) {

            if (entry.getKey()
                    .toLowerCase(Locale.US)
                    .contains(lower)) {
                return entry.getKey()
                        + "="
                        + entry.getValue();
            }
        }

        return null;
    }


    private static String dumpObject(
            Object object,
            int maxDepth
    ) {
        Map<String, String> values =
                new LinkedHashMap<>();

        collectFields(
                object,
                "",
                0,
                maxDepth,
                values
        );

        StringBuilder out =
                new StringBuilder();

        for (Map.Entry<String, String> entry
                : values.entrySet()) {
            out.append(entry.getKey())
                    .append("=")
                    .append(entry.getValue())
                    .append("\n");
        }

        if (out.length() == 0) {
            return String.valueOf(object);
        }

        return out.toString();
    }


    private static Object defaultValue(
            Class<?> type
    ) {
        if (type == void.class
                || type == Void.class) {
            return null;
        }

        if (type == boolean.class
                || type == Boolean.class) {
            return false;
        }

        if (type == byte.class
                || type == Byte.class) {
            return (byte) 0;
        }

        if (type == short.class
                || type == Short.class) {
            return (short) 0;
        }

        if (type == int.class
                || type == Integer.class) {
            return 0;
        }

        if (type == long.class
                || type == Long.class) {
            return 0L;
        }

        if (type == float.class
                || type == Float.class) {
            return 0f;
        }

        if (type == double.class
                || type == Double.class) {
            return 0d;
        }

        if (type == char.class
                || type == Character.class) {
            return '\0';
        }

        return null;
    }


    // =============================================================
    // SYSTEM PROPERTIES
    // =============================================================

    private void refreshSystemProperties() {
        configuredBroadcastAction =
                readSystemProperty(
                        PROP_ACTION,
                        ""
                );

        configuredBroadcastPackage =
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

            return result == null
                    ? defaultValue
                    : String.valueOf(result);

        } catch (Throwable ignored) {
            return defaultValue;
        }
    }


    // =============================================================
    // UI / LOGGING
    // =============================================================

    private void updateStatus() {
        if (statusView == null) {
            return;
        }

        statusView.setText(
                "HAL: "
                        + (halConnected
                        ? "CONNECTED"
                        : "not connected")
                        + "   poll:"
                        + pollCount
                        + "\nCallback init: "
                        + (callbackInitSucceeded
                        ? "OK"
                        : callbackInitAttempted
                        ? "not available"
                        : "not tried")
                        + "\nBroadcasts: "
                        + broadcastCount
                        + "   MCU packets: "
                        + mcuPacketCount
                        + "\nMCU: "
                        + (mcuBound
                        ? (mcuRegistered
                        ? "CONNECTED/LISTENING"
                        : "CONNECTED")
                        : "not connected")
                        + "\nGear candidate: "
                        + lastGear
        );
    }


    private void addLog(
            String text
    ) {
        if (text == null
                || text.trim().isEmpty()) {
            return;
        }

        eventLog.addFirst(
                text.trim()
        );

        while (eventLog.size() > 35) {
            eventLog.removeLast();
        }

        StringBuilder out =
                new StringBuilder();

        for (String item : eventLog) {
            out.append(item)
                    .append("\n\n--------------------\n\n");
        }

        if (rawView != null) {
            rawView.setText(
                    out.toString()
            );
        }
    }


    private void clearView() {
        eventLog.clear();
        broadcastCount = 0;
        mcuPacketCount = 0;

        rawView.setText(
                "View cleared.\n\n"
                        + "The active probes continue running."
        );

        updateStatus();
    }


    private static String hex(
            byte[] data
    ) {
        StringBuilder out =
                new StringBuilder();

        for (byte b : data) {
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


    private static String emptyText(
            String value
    ) {
        return value == null
                || value.trim().isEmpty()
                ? "<empty/unreadable>"
                : value;
    }


    private static String simpleError(
            Throwable t
    ) {
        Throwable shown =
                t.getCause() != null
                        ? t.getCause()
                        : t;

        return shown.getClass()
                .getSimpleName()
                + ": "
                + String.valueOf(
                shown.getMessage()
        );
    }


    private static String fullError(
            Throwable t
    ) {
        Throwable shown =
                t.getCause() != null
                        ? t.getCause()
                        : t;

        return shown.getClass()
                .getName()
                + ": "
                + String.valueOf(
                shown.getMessage()
        );
    }


    // =============================================================
    // CLEANUP
    // =============================================================

    @Override
    protected void onDestroy() {
        halPolling = false;

        mainHandler.removeCallbacks(
                carInfoPollRunnable
        );

        if (broadcastReceiverRegistered) {
            try {
                unregisterReceiver(
                        factoryReceiver
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

        if (carEventMonitor != null
                && deinitMethod != null
                && callbackInitSucceeded) {
            try {
                deinitMethod.invoke(
                        carEventMonitor
                );
            } catch (Throwable ignored) {
            }
        }

        carEventMonitor = null;
        mcuMessenger = null;

        super.onDestroy();
    }
}
