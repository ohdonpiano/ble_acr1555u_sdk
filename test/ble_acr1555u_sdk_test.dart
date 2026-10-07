import 'package:ble_acr1555u_sdk/ble_acr1555u_sdk.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('ble_acr1555u_sdk');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('writeBinary sends FF D6 and validates a successful status word',
      () async {
    MethodCall? call;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (value) async {
      call = value;
      return Uint8List.fromList([0x90, 0x00]);
    });

    await BleAcr1555uSdk.writeBinary(
      0x345,
      Uint8List.fromList([1, 2, 3, 4]),
    );

    expect(call?.method, 'transceiveApdu');
    expect(
      call?.arguments['apdu'],
      Uint8List.fromList([0xFF, 0xD6, 0x03, 0x45, 0x04, 1, 2, 3, 4]),
    );
  });

  test('writeBinary rejects a non-success status word', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      channel,
      (_) async => Uint8List.fromList([0x69, 0x00]),
    );

    expect(
      () => BleAcr1555uSdk.writeBinary(0, Uint8List.fromList([0, 0, 0, 0])),
      throwsA(
        isA<BleReaderException>().having(
          (error) => error.code,
          'code',
          'APDU_ERROR',
        ),
      ),
    );
  });

  test('writeBinary validates address and payload length', () {
    expect(
      () => BleAcr1555uSdk.writeBinary(-1, Uint8List.fromList([0])),
      throwsArgumentError,
    );
    expect(
      () => BleAcr1555uSdk.writeBinary(0, Uint8List(0)),
      throwsArgumentError,
    );
    expect(
      () => BleAcr1555uSdk.writeBinary(0, Uint8List(256)),
      throwsArgumentError,
    );
  });
}
