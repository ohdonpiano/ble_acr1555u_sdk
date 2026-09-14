package com.ohdonpiano.ble_acr1555u_sdk;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Plugin Android per il reader BLE+NFC USR/ACS ACR1555U (PoC integrazione g2misuratori).
 * <p>
 * Espone via MethodChannel "ble_acr1555u_sdk":
 * - isBleSupported, startScan, stopScan, connect, disconnect
 * - iccPowerOn (ATR), iccPowerOff, getSlotStatus
 * - transceiveApdu (canale generico per FF CA/B0/D6/FB/C2 via XfrBlock)
 * - escape (comandi estesi E0.. via Escape Command)
 * - getBatteryLevel
 * <p>
 * Ed espone via EventChannel:
 * - "ble_acr1555u_sdk/scan"  -> dispositivi trovati durante lo scan {id, name, rssi}
 * - "ble_acr1555u_sdk/state" -> stato connessione: "disconnected"|"connecting"|"connected"|"ready"
 * - "ble_acr1555u_sdk/card"  -> presenza tag: bool (da RDR_to_PC_NotifySlotChange, pipe Card Notification)
 */
public class BleAcr1555uSdkPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private static final String TAG = "BleAcr1555uSdk";

    // UUID pipe Smart Card del reader (§5.2.2). Nota: il manuale non documenta un UUID di
    // servizio GATT distinto per il "Smart Card service" — solo gli UUID delle 3 pipe/
    // characteristics sotto. Il servizio che le contiene viene quindi individuato a runtime
    // cercando quale servizio scoperto possiede la characteristic CHR_COMMANDS_REQUEST
    // (si veda onServicesDiscovered), invece di usare getService(uuid) con un UUID atteso.
    private static final UUID CHR_COMMANDS_REQUEST = UUID.fromString("00003971-817C-48DF-8DB2-476A8134EDE0");
    private static final UUID CHR_COMMANDS_RESPONSE = UUID.fromString("00003972-817C-48DF-8DB2-476A8134EDE0");
    private static final UUID CHR_CARD_NOTIFICATION = UUID.fromString("00003973-817C-48DF-8DB2-476A8134EDE0");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SVC_BATTERY = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_BATTERY_LEVEL = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");

    private static final long COMMAND_TIMEOUT_MS = 6000;

    // Prefisso del nome BLE annunciato dal reader (es. "ACR1555U-A1-000719"), usato per
    // filtrare i risultati dello scan lato app: il ScanFilter nativo per Service UUID non è
    // affidabile perché il reader non include il service UUID Smart Card nel pacchetto di
    // advertising (viene esposto solo dopo la connessione GATT), quindi uno
    // ScanFilter basato su setServiceUuid non riceve mai risultati.
    private static final String READER_NAME_PREFIX = "ACR1555U";

    private MethodChannel methodChannel;
    private EventChannel scanChannel;
    private EventChannel stateChannel;
    private EventChannel cardChannel;

    private EventChannel.EventSink scanSink;
    private EventChannel.EventSink stateSink;
    private EventChannel.EventSink cardSink;

    private Context applicationContext;
    private Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt gatt;

    private BluetoothGattCharacteristic commandsRequestChar;
    private BluetoothGattCharacteristic commandsResponseChar;
    private BluetoothGattCharacteristic cardNotificationChar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int mtuPayloadSize = 20; // MTU(23) default - 3 byte header ATT, aggiornato dopo requestMtu
    private int seqCounter = 0;
    private int lastReaderSeq = 0;

    private final java.io.ByteArrayOutputStream responseAccumulator = new java.io.ByteArrayOutputStream();

    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;

    private static final class PendingCommand {
        final int seq;
        final MethodChannel.Result result;
        final Runnable timeout;

        PendingCommand(int seq, MethodChannel.Result result, Runnable timeout) {
            this.seq = seq;
            this.result = result;
            this.timeout = timeout;
        }
    }

    private PendingCommand pending;
    private final Object commandLock = new Object();

    // ---------------------------------------------------------------------
    // FlutterPlugin
    // ---------------------------------------------------------------------

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = binding.getApplicationContext();
        methodChannel = new MethodChannel(binding.getBinaryMessenger(), "ble_acr1555u_sdk");
        methodChannel.setMethodCallHandler(this);

        scanChannel = new EventChannel(binding.getBinaryMessenger(), "ble_acr1555u_sdk/scan");
        scanChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                scanSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                scanSink = null;
            }
        });

        stateChannel = new EventChannel(binding.getBinaryMessenger(), "ble_acr1555u_sdk/state");
        stateChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                stateSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                stateSink = null;
            }
        });

        cardChannel = new EventChannel(binding.getBinaryMessenger(), "ble_acr1555u_sdk/card");
        cardChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                cardSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                cardSink = null;
            }
        });

        BluetoothManager bluetoothManager =
                (BluetoothManager) applicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        methodChannel.setMethodCallHandler(null);
        scanChannel.setStreamHandler(null);
        stateChannel.setStreamHandler(null);
        cardChannel.setStreamHandler(null);
        closeGatt();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    // ---------------------------------------------------------------------
    // MethodCallHandler
    // ---------------------------------------------------------------------

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "isBleSupported":
                result.success(bluetoothAdapter != null
                        && applicationContext.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
                break;
            case "hasPermissions":
                result.success(hasScanPermissions() && hasConnectPermissions());
                break;
            case "startScan":
                startScan(result);
                break;
            case "stopScan":
                stopScan(result);
                break;
            case "connect": {
                String deviceId = call.argument("deviceId");
                connect(deviceId, result);
                break;
            }
            case "disconnect":
                disconnect(result);
                break;
            case "iccPowerOn":
                sendCcid(Acr1555uProtocol.buildIccPowerOn(nextSeq()), result, true);
                break;
            case "iccPowerOff":
                sendCcid(Acr1555uProtocol.buildIccPowerOff(nextSeq()), result, false);
                break;
            case "getSlotStatus":
                sendCcid(Acr1555uProtocol.buildGetSlotStatus(nextSeq()), result, false);
                break;
            case "transceiveApdu": {
                byte[] apdu = call.argument("apdu");
                if (apdu == null) {
                    result.error("BAD_ARGS", "apdu is required", null);
                    return;
                }
                sendCcid(Acr1555uProtocol.buildXfrBlock(nextSeq(), apdu), result, true);
                break;
            }
            case "escape": {
                byte[] data = call.argument("data");
                if (data == null) {
                    result.error("BAD_ARGS", "data is required", null);
                    return;
                }
                sendCcid(Acr1555uProtocol.buildEscape(nextSeq(), data), result, true);
                break;
            }
            case "getBatteryLevel":
                readBatteryLevel(result);
                break;
            default:
                result.notImplemented();
        }
    }

    private int nextSeq() {
        seqCounter = (seqCounter + 1) & 0xFF;
        return seqCounter;
    }

    // ---------------------------------------------------------------------
    // Permessi
    // ---------------------------------------------------------------------

    private boolean hasScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // BLUETOOTH/BLUETOOTH_ADMIN sono permessi normali, concessi automaticamente
    }

    // ---------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private void startScan(MethodChannel.Result result) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            result.error("BLUETOOTH_OFF", "Bluetooth non disponibile o disattivato", null);
            return;
        }
        if (!hasScanPermissions()) {
            result.error("PERMISSION_DENIED", "Permesso BLUETOOTH_SCAN/location mancante", null);
            return;
        }
        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            result.error("BLE_UNAVAILABLE", "BluetoothLeScanner non disponibile", null);
            return;
        }
        // Nessun ScanFilter nativo: il reader non pubblicizza il service UUID Smart Card
        // nell'advertising packet (lo espone solo dopo la connessione GATT), quindi un filtro
        // su setServiceUuid non produce mai match. Filtriamo invece per nome in onScanResult.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        Log.d(TAG, "startScan: avvio scan BLE senza filtro nativo (filtro per nome lato callback)");
        try {
            leScanner.startScan(new ArrayList<>(), settings, scanCallback);
            result.success(null);
        } catch (SecurityException e) {
            Log.e(TAG, "startScan: permesso mancante", e);
            result.error("PERMISSION_DENIED", e.getMessage(), null);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void stopScan(MethodChannel.Result result) {
        try {
            if (leScanner != null) {
                leScanner.stopScan(scanCallback);
            }
            result.success(null);
        } catch (SecurityException e) {
            result.error("PERMISSION_DENIED", e.getMessage(), null);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onScanResult(int callbackType, ScanResult scanResult) {
            BluetoothDevice device = scanResult.getDevice();
            String name = null;
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
                // permesso mancante: id sufficiente comunque per connect()
            }
            //Log.d(TAG, "onScanResult: name=" + name + " address=" + device.getAddress() + " rssi=" + scanResult.getRssi());
            if (name == null || !name.toUpperCase(java.util.Locale.ROOT).startsWith(READER_NAME_PREFIX)) {
                return; // non è il reader ACR1555U: ignorato lato callback
            }
            Map<String, Object> map = new HashMap<>();
            map.put("id", device.getAddress());
            map.put("name", name);
            map.put("rssi", scanResult.getRssi());
            mainHandler.post(() -> {
                if (scanSink != null) scanSink.success(map);
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "BLE scan failed, errorCode=" + errorCode);
            mainHandler.post(() -> {
                if (scanSink != null) scanSink.error("SCAN_FAILED", "errorCode=" + errorCode, null);
            });
        }
    };

    // ---------------------------------------------------------------------
    // Connessione GATT
    // ---------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private void connect(String deviceId, MethodChannel.Result result) {
        if (bluetoothAdapter == null) {
            result.error("BLUETOOTH_OFF", "Bluetooth non disponibile", null);
            return;
        }
        if (!hasConnectPermissions()) {
            result.error("PERMISSION_DENIED", "Permesso BLUETOOTH_CONNECT mancante", null);
            return;
        }
        closeGatt();
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceId);
            emitState("connecting");
            gatt = device.connectGatt(applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            result.success(null);
        } catch (IllegalArgumentException | SecurityException e) {
            result.error("CONNECT_FAILED", e.getMessage(), null);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void disconnect(MethodChannel.Result result) {
        closeGatt();
        emitState("disconnected");
        if (result != null) result.success(null);
    }

    @SuppressWarnings("MissingPermission")
    private void closeGatt() {
        failPendingCommand("DISCONNECTED", "Reader disconnesso");
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
        commandsRequestChar = null;
        commandsResponseChar = null;
        cardNotificationChar = null;
        mtuPayloadSize = 20;
        seqCounter = 0;
        lastReaderSeq = 0;
        responseAccumulator.reset();
        writeQueue.clear();
        writeInFlight = false;
    }

    private void emitState(String state) {
        mainHandler.post(() -> {
            if (stateSink != null) stateSink.success(state);
        });
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitState("connected");
                g.requestMtu(247);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitState("disconnected");
                failPendingCommand("DISCONNECTED", "Reader disconnesso (status=" + status + ")");
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mtuPayloadSize = Math.max(20, mtu - 3);
            g.discoverServices();
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered: status=" + status);
                emitState("disconnected");
                return;
            }
            logDiscoveredGatt(g);
            // Non usiamo g.getService(uuid atteso): il manuale ACR1555U (Table 7/8) non
            // documenta un UUID di servizio distinto dagli UUID delle characteristics
            // (3971/3972/3973), e nella pratica il servizio GATT che le contiene ha un UUID
            // diverso da quello atteso. Cerchiamo quindi la characteristic Commands Request
            // (3971) in tutti i servizi scoperti e usiamo il servizio che la contiene.
            BluetoothGattService svc = null;
            for (BluetoothGattService s : g.getServices()) {
                if (s.getCharacteristic(CHR_COMMANDS_REQUEST) != null) {
                    svc = s;
                    break;
                }
            }
            if (svc == null) {
                Log.e(TAG, "Servizio Smart Card (characteristic " + CHR_COMMANDS_REQUEST
                        + ") non trovato tra i " + g.getServices().size() + " servizi scoperti");
                emitState("disconnected");
                return;
            }
            Log.d(TAG, "Servizio Smart Card trovato: uuid=" + svc.getUuid());
            commandsRequestChar = svc.getCharacteristic(CHR_COMMANDS_REQUEST);
            commandsResponseChar = svc.getCharacteristic(CHR_COMMANDS_RESPONSE);
            cardNotificationChar = svc.getCharacteristic(CHR_CARD_NOTIFICATION);
            if (commandsResponseChar == null || cardNotificationChar == null) {
                Log.e(TAG, "Characteristic mancanti: response=" + commandsResponseChar
                        + " cardNotification=" + cardNotificationChar);
                emitState("disconnected");
                return;
            }
            Log.d(TAG, "commandsRequestChar props=0x" + Integer.toHexString(commandsRequestChar.getProperties())
                    + " permissions=0x" + Integer.toHexString(commandsRequestChar.getPermissions()));
            Log.d(TAG, "commandsResponseChar props=0x" + Integer.toHexString(commandsResponseChar.getProperties()));
            Log.d(TAG, "cardNotificationChar props=0x" + Integer.toHexString(cardNotificationChar.getProperties()));
            enableNotify(g, commandsResponseChar);
            enableNotify(g, cardNotificationChar);
            emitState("ready");
        }

        private void logDiscoveredGatt(BluetoothGatt g) {
            Log.d(TAG, "GATT servizi scoperti: " + g.getServices().size());
            for (BluetoothGattService s : g.getServices()) {
                Log.d(TAG, "  service " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    Log.d(TAG, "    char " + c.getUuid() + " props=" + c.getProperties());
                }
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite " + characteristic.getUuid() + " status=" + status);
            writeInFlight = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite fallita, status=" + status + " - svuoto coda scrittura");
                writeQueue.clear();
                return;
            }
            writeNextChunk();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite " + descriptor.getCharacteristic().getUuid() + " status=" + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            Log.d(TAG, "onCharacteristicChanged " + characteristic.getUuid() + " value=" + toHex(value));
            handleNotification(characteristic.getUuid(), value);
        }

        // Overload richiesto su API < 33 (deprecato ma necessario per compatibilità)
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            Log.d(TAG, "onCharacteristicChanged(legacy) " + characteristic.getUuid() + " value=" + toHex(value));
            handleNotification(characteristic.getUuid(), value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            Log.d(TAG, "onCharacteristicRead " + characteristic.getUuid() + " status=" + status + " value=" + toHex(value));
            handleCharacteristicRead(characteristic.getUuid(), value, status);
        }

        // Overload richiesto su API < 33 (deprecato ma necessario per compatibilità)
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            byte[] value = characteristic.getValue();
            Log.d(TAG, "onCharacteristicRead(legacy) " + characteristic.getUuid() + " status=" + status + " value=" + toHex(value));
            handleCharacteristicRead(characteristic.getUuid(), value, status);
        }
    };

    private void handleCharacteristicRead(UUID characteristicUuid, byte[] value, int status) {
        if (!CHR_BATTERY_LEVEL.equals(characteristicUuid)) return;
        MethodChannel.Result result = pendingBatteryResult;
        pendingBatteryResult = null;
        if (result == null) return;
        mainHandler.post(() -> {
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                result.success(value[0] & 0xFF);
            } else {
                result.error("READ_FAILED", "Lettura Battery Level fallita, status=" + status, null);
            }
        });
    }

    @SuppressWarnings("MissingPermission")
    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        boolean okLocal = g.setCharacteristicNotification(characteristic, true);
        Log.d(TAG, "enableNotify " + characteristic.getUuid() + ": setCharacteristicNotification=" + okLocal);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            Log.w(TAG, "enableNotify " + characteristic.getUuid() + ": CCCD descriptor non trovato");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int res = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            Log.d(TAG, "enableNotify " + characteristic.getUuid() + ": writeDescriptor result=" + res);
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean res = g.writeDescriptor(descriptor);
            Log.d(TAG, "enableNotify " + characteristic.getUuid() + ": writeDescriptor result=" + res);
        }
    }

    // ---------------------------------------------------------------------
    // Invio comandi CCID / gestione risposta
    // ---------------------------------------------------------------------

    private void sendCcid(byte[] ccidMessage, MethodChannel.Result result, boolean returnData) {
        if (gatt == null || commandsRequestChar == null) {
            Log.e(TAG, "sendCcid: reader non connesso (gatt=" + gatt + " commandsRequestChar=" + commandsRequestChar + ")");
            result.error("NOT_CONNECTED", "Reader non connesso", null);
            return;
        }
        int seq = ccidMessage[6] & 0xFF;
        Log.d(TAG, "sendCcid: messageType=0x" + Integer.toHexString(ccidMessage[0] & 0xFF)
                + " seq=" + seq + " ccidMessage=" + toHex(ccidMessage));
        synchronized (commandLock) {
            if (pending != null) {
                Log.w(TAG, "sendCcid: comando già in corso (seq=" + pending.seq + "), rifiuto nuovo comando seq=" + seq);
                result.error("BUSY", "Un comando è già in corso verso il reader", null);
                return;
            }
            Runnable timeoutRunnable = () -> {
                synchronized (commandLock) {
                    if (pending != null && pending.seq == seq) {
                        Log.e(TAG, "sendCcid: TIMEOUT seq=" + seq + " dopo " + COMMAND_TIMEOUT_MS + "ms senza risposta");
                        PendingCommand p = pending;
                        pending = null;
                        p.result.error("TIMEOUT", "Nessuna risposta dal reader entro " + COMMAND_TIMEOUT_MS + "ms", null);
                    }
                }
            };
            pending = new PendingCommand(seq, result, timeoutRunnable);
            mainHandler.postDelayed(timeoutRunnable, COMMAND_TIMEOUT_MS);
        }
        byte[] frame = Acr1555uProtocol.encodeFrame(ccidMessage, seq, lastReaderSeq);
        Log.d(TAG, "sendCcid: frame BLE codificato (" + frame.length + " byte) = " + toHex(frame));
        writeFrameChunked(frame);
    }

    private void writeFrameChunked(byte[] frame) {
        int offset = 0;
        int chunkCount = 0;
        while (offset < frame.length) {
            int chunkLen = Math.min(mtuPayloadSize, frame.length - offset);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(frame, offset, chunk, 0, chunkLen);
            writeQueue.add(chunk);
            offset += chunkLen;
            chunkCount++;
        }
        Log.d(TAG, "writeFrameChunked: " + chunkCount + " chunk in coda (mtuPayloadSize=" + mtuPayloadSize + ")");
        writeNextChunk();
    }

    @SuppressWarnings("MissingPermission")
    private void writeNextChunk() {
        if (writeInFlight) {
            Log.d(TAG, "writeNextChunk: scrittura già in corso, attendo onCharacteristicWrite");
            return;
        }
        if (writeQueue.isEmpty()) {
            Log.d(TAG, "writeNextChunk: coda vuota, nulla da scrivere");
            return;
        }
        if (gatt == null || commandsRequestChar == null) {
            Log.e(TAG, "writeNextChunk: gatt o commandsRequestChar nulli, impossibile scrivere");
            return;
        }
        byte[] chunk = writeQueue.poll();
        if (chunk == null) return;
        writeInFlight = true;
        int props = commandsRequestChar.getProperties();
        // Sceglie il write type in base alle proprietà reali della characteristic invece di
        // forzare sempre WRITE_TYPE_DEFAULT: se la characteristic supporta solo
        // WRITE_NO_RESPONSE, un writeCharacteristic con WRITE_TYPE_DEFAULT fallisce
        // silenziosamente (nessuna eccezione, nessun onCharacteristicWrite), lasciando
        // writeInFlight bloccato a true per sempre e causando il timeout del comando.
        boolean supportsWriteWithResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean supportsWriteNoResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int writeType = supportsWriteWithResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        Log.d(TAG, "writeNextChunk: invio chunk (" + chunk.length + " byte) = " + toHex(chunk)
                + " writeType=" + writeType + " (props=0x" + Integer.toHexString(props)
                + " write=" + supportsWriteWithResponse + " writeNoResponse=" + supportsWriteNoResponse + ")");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int res = gatt.writeCharacteristic(commandsRequestChar, chunk, writeType);
                Log.d(TAG, "writeNextChunk: writeCharacteristic (API33+) result=" + res
                        + " (BluetoothStatusCodes.SUCCESS=0)");
                if (res != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "writeNextChunk: scrittura fallita immediatamente, status=" + res);
                    writeInFlight = false;
                    failPendingCommand("WRITE_FAILED", "Scrittura verso il reader fallita (status=" + res + ")");
                    writeQueue.clear();
                }
            } else {
                commandsRequestChar.setWriteType(writeType);
                commandsRequestChar.setValue(chunk);
                boolean ok = gatt.writeCharacteristic(commandsRequestChar);
                Log.d(TAG, "writeNextChunk: writeCharacteristic (legacy) result=" + ok);
                if (!ok) {
                    Log.e(TAG, "writeNextChunk: scrittura fallita immediatamente (legacy API)");
                    writeInFlight = false;
                    failPendingCommand("WRITE_FAILED", "Scrittura verso il reader fallita");
                    writeQueue.clear();
                } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                    // Su WRITE_NO_RESPONSE l'callback onCharacteristicWrite potrebbe non arrivare
                    // in modo affidabile su tutte le versioni: sblocchiamo subito la coda.
                    writeInFlight = false;
                    writeNextChunk();
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "writeNextChunk: permesso mancante", e);
            writeInFlight = false;
            failPendingCommand("PERMISSION_DENIED", e.getMessage());
            writeQueue.clear();
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private void handleNotification(UUID characteristicUuid, byte[] value) {
        if (value == null) return;
        if (CHR_CARD_NOTIFICATION.equals(characteristicUuid)) {
            handleCardNotification(value);
            return;
        }
        if (!CHR_COMMANDS_RESPONSE.equals(characteristicUuid)) {
            Log.d(TAG, "handleNotification: characteristic " + characteristicUuid + " ignorata (non è commands response)");
            return;
        }

        synchronized (responseAccumulator) {
            responseAccumulator.write(value, 0, value.length);
            byte[] buffered = responseAccumulator.toByteArray();
            Log.d(TAG, "handleNotification: buffer risposta accumulato (" + buffered.length + " byte) = " + toHex(buffered));
            int consumed = 0;
            while (true) {
                byte[] remaining = java.util.Arrays.copyOfRange(buffered, consumed, buffered.length);
                if (remaining.length == 0) break;
                Acr1555uProtocol.DecodedFrame decoded;
                try {
                    decoded = Acr1555uProtocol.tryDecodeFrame(remaining);
                } catch (IllegalArgumentException malformed) {
                    Log.w(TAG, "Frame BLE malformato, scarto buffer: " + malformed.getMessage());
                    responseAccumulator.reset();
                    return;
                }
                if (decoded == null) break; // frame incompleto, attendo altri byte
                consumed += Acr1555uProtocol.frameTotalLength(remaining);
                lastReaderSeq = decoded.readerSeq;
                onFrameReceived(decoded);
            }
            byte[] leftover = java.util.Arrays.copyOfRange(buffered, consumed, buffered.length);
            responseAccumulator.reset();
            if (leftover.length > 0) {
                responseAccumulator.write(leftover, 0, leftover.length);
            }
        }
    }

    private void onFrameReceived(Acr1555uProtocol.DecodedFrame frame) {
        Log.d(TAG, "onFrameReceived: slot=" + frame.slot + " hostSeq=" + frame.hostSeq
                + " readerSeq=" + frame.readerSeq + " datablock=" + toHex(frame.datablock));
        Acr1555uProtocol.CcidResponse resp;
        try {
            resp = Acr1555uProtocol.parseCcidMessage(frame.datablock);
        } catch (Exception e) {
            Log.w(TAG, "Impossibile fare il parse del messaggio CCID: " + e.getMessage());
            return;
        }
        Log.d(TAG, "onFrameReceived: CCID messageType=0x" + Integer.toHexString(resp.messageType)
                + " seq=" + resp.seq + " status=0x" + Integer.toHexString(resp.status)
                + " error=0x" + Integer.toHexString(resp.error) + " data=" + toHex(resp.data));
        synchronized (commandLock) {
            if (pending == null || pending.seq != resp.seq) {
                Log.w(TAG, "Risposta CCID inattesa (seq=" + resp.seq + ", pending="
                        + (pending == null ? "null" : String.valueOf(pending.seq)) + "), scartata");
                return;
            }
            PendingCommand p = pending;
            pending = null;
            mainHandler.removeCallbacks(p.timeout);
            if (resp.isCommandFailed()) {
                Log.e(TAG, "onFrameReceived: comando fallito, bStatus=0x" + Integer.toHexString(resp.status)
                        + " bError=0x" + Integer.toHexString(resp.error));
                p.result.error("READER_ERROR", "bStatus=0x" + Integer.toHexString(resp.status)
                        + " bError=0x" + Integer.toHexString(resp.error), null);
            } else {
                Log.d(TAG, "onFrameReceived: comando completato con successo");
                p.result.success(resp.data);
            }
        }
    }

    private void handleCardNotification(byte[] value) {
        // La pipe "Card Notification" incapsula RDR_to_PC_NotifySlotChange nello stesso frame
        // BLE proprietario oppure, a seconda del firmware, invia direttamente il byte bmSlotICCState.
        // Si prova prima il decode del frame completo; in fallback si interpreta il payload grezzo.
        Log.d(TAG, "handleCardNotification: value=" + toHex(value));
        boolean present;
        try {
            Acr1555uProtocol.DecodedFrame decoded = Acr1555uProtocol.tryDecodeFrame(value);
            if (decoded != null && decoded.datablock.length > 0) {
                present = Acr1555uProtocol.isCardPresent(decoded.datablock[decoded.datablock.length - 1]);
            } else {
                present = value.length > 0 && Acr1555uProtocol.isCardPresent(value[value.length - 1]);
            }
        } catch (Exception e) {
            present = value.length > 0 && Acr1555uProtocol.isCardPresent(value[value.length - 1]);
        }
        Log.d(TAG, "handleCardNotification: present=" + present);
        boolean finalPresent = present;
        mainHandler.post(() -> {
            if (cardSink != null) cardSink.success(finalPresent);
        });
    }

    private void failPendingCommand(String code, String message) {
        synchronized (commandLock) {
            if (pending != null) {
                PendingCommand p = pending;
                pending = null;
                mainHandler.removeCallbacks(p.timeout);
                p.result.error(code, message, null);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Battery
    // ---------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private void readBatteryLevel(MethodChannel.Result result) {
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Reader non connesso", null);
            return;
        }
        BluetoothGattService svc = gatt.getService(SVC_BATTERY);
        BluetoothGattCharacteristic chr = svc != null ? svc.getCharacteristic(CHR_BATTERY_LEVEL) : null;
        if (chr == null) {
            result.error("NOT_AVAILABLE", "Battery Level characteristic non trovata", null);
            return;
        }
        pendingBatteryResult = result;
        gatt.readCharacteristic(chr);
    }

    private MethodChannel.Result pendingBatteryResult;
}

