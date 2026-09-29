# ble_acr1555u_sdk

Flutter plugin for Android that communicates with **ACS ACR1555U** BLE/NFC
readers. It exposes reader discovery and connection, CCID commands, generic
PC/SC APDU transceiving, card-presence notifications, and battery status.

This package targets the ACR1555U protocol and is not a generic Bluetooth or
NFC abstraction. It currently supports Android only.

## Requirements

- Flutter 3.3.0 or newer
- Dart 3.0.0 or newer
- Android API 21 or newer
- An ACS ACR1555U reader with BLE enabled

The application must request the Bluetooth permissions declared by the plugin
at runtime. On Android 12 and newer these are `BLUETOOTH_SCAN` and
`BLUETOOTH_CONNECT`; on older Android versions, BLE scanning requires
`ACCESS_FINE_LOCATION`. The plugin exposes `hasPermissions` to check the
current state but does not display the permission prompt.

## Usage

```dart
import 'dart:typed_data';

import 'package:ble_acr1555u_sdk/ble_acr1555u_sdk.dart';

final subscription = BleAcr1555uSdk.scanForReaders().listen((reader) {
  print('Found ${reader.name} (${reader.id})');
});

await BleAcr1555uSdk.startScan();
// Select a reader from the stream, then:
final readerId = 'device-id-selected-from-the-stream';
await BleAcr1555uSdk.stopScan();
await BleAcr1555uSdk.connect(readerId);

await for (final state in BleAcr1555uSdk.connectionState()) {
  if (state == BleReaderConnectionState.ready) {
    final uid = await BleAcr1555uSdk.getUid();
    print('UID: ${uid.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join()}');
    break;
  }
}

await BleAcr1555uSdk.disconnect();
await subscription.cancel();
```

`transceiveApdu` accepts a PC/SC pseudo-APDU and returns the raw response,
including the status word. Application-specific tag commands should be encoded
by the consuming application and sent through this generic method. The
convenience methods `getUid` and `readBinary` remove the status word and throw
`BleReaderException` when the reader returns a non-success status.

## Limitations

- Reader discovery filters the advertised name by the `ACR1555U` prefix.
- The implementation relies on the ACR1555U BLE frame and CCID protocol.
- The package does not encode vendor-specific tag commands; use
  `transceiveApdu` for application-specific PC/SC pass-through commands.
- Hardware integration tests require a physical reader and tag.

## License

MIT. See [LICENSE](LICENSE).
