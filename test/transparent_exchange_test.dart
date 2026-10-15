import 'dart:typed_data';

import 'package:ble_acr1555u_sdk/ble_acr1555u_sdk.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds Manage Session APDUs', () {
    expect(
      BleAcr1555uSdk.buildManageSessionApdu(0x81),
      [0xFF, 0xC2, 0x00, 0x00, 0x02, 0x81, 0x00],
    );
    expect(
      BleAcr1555uSdk.buildManageSessionApdu(0x82),
      [0xFF, 0xC2, 0x00, 0x00, 0x02, 0x82, 0x00],
    );
  });

  test('builds ISO15693 Switch Protocol APDUs for both baud rates', () {
    expect(
      BleAcr1555uSdk.buildSwitchProtocolApdu(highDataRate: false),
      [
        0xFF,
        0xC2,
        0x00,
        0x02,
        0x0A,
        0xFF,
        0x6E,
        0x03,
        0x05,
        0x01,
        0x80,
        0x8F,
        0x02,
        0x02,
        0x03,
        0x00,
      ],
    );
    expect(
      BleAcr1555uSdk.buildSwitchProtocolApdu(highDataRate: true),
      [
        0xFF,
        0xC2,
        0x00,
        0x02,
        0x0A,
        0xFF,
        0x6E,
        0x03,
        0x05,
        0x01,
        0x08,
        0x8F,
        0x02,
        0x02,
        0x03,
        0x00,
      ],
    );
  });

  test('builds a Transparent Exchange APDU for a native command', () {
    final apdu = BleAcr1555uSdk.buildTransparentExchangeApdu(
      Uint8List.fromList([
        0x22,
        0x33,
        0xFE,
        0xD7,
        0xAF,
        0x46,
        0x04,
        0x26,
        0x02,
        0xE0,
        0x40,
        0x01,
        0x3F,
        0x00,
      ]),
    );

    expect(apdu, [
      0xFF,
      0xC2,
      0x00,
      0x01,
      0x10,
      0x95,
      0x0E,
      0x22,
      0x33,
      0xFE,
      0xD7,
      0xAF,
      0x46,
      0x04,
      0x26,
      0x02,
      0xE0,
      0x40,
      0x01,
      0x3F,
      0x00,
    ]);
  });

  test('builds an addressed high-data-rate ST25 custom command', () {
    final command = BleAcr1555uSdk.buildAddressedSt25Command(
      0xB3,
      Uint8List.fromList([
        0xFE,
        0xD7,
        0xAF,
        0x46,
        0x04,
        0x26,
        0x02,
        0xE0,
      ]),
      Uint8List.fromList([0x02, ...List.filled(8, 0x01)]),
    );

    expect(command, [
      0x22,
      0xB3,
      0x02,
      0xFE,
      0xD7,
      0xAF,
      0x46,
      0x04,
      0x26,
      0x02,
      0xE0,
      0x02,
      0x01,
      0x01,
      0x01,
      0x01,
      0x01,
      0x01,
      0x01,
      0x01,
    ]);
  });

  test('parses an extended response data object', () {
    final response = Uint8List.fromList([
      0xC0,
      0x03,
      0x00,
      0x90,
      0x00,
      0x92,
      0x01,
      0x00,
      0x96,
      0x02,
      0x00,
      0x00,
      0x97,
      0x81,
      0x01,
      0x00,
      ...List.filled(256, 0x5A),
    ]);

    expect(
      BleAcr1555uSdk.parseTransparentResponse(response),
      hasLength(256),
    );
  });

  test('parses the final shorter extended response data object', () {
    final data = List<int>.filled(180, 0xA5);
    final response = Uint8List.fromList([
      0xC0,
      0x03,
      0x00,
      0x90,
      0x00,
      0x92,
      0x01,
      0x00,
      0x96,
      0x02,
      0x00,
      0x00,
      0x97,
      0x81,
      0xB5,
      0x00,
      ...data,
    ]);

    expect(BleAcr1555uSdk.parseTransparentResponse(response), data);
  });

  test('reports the ISO15693 response error code', () {
    expect(
      () => BleAcr1555uSdk.parseTransparentResponse(
        Uint8List.fromList([
          0xC0,
          0x03,
          0x00,
          0x90,
          0x00,
          0x97,
          0x02,
          0x01,
          0x15,
        ]),
      ),
      throwsA(
        isA<BleReaderException>().having(
          (error) => error.message,
          'message',
          contains('error=0x15'),
        ),
      ),
    );
  });
}
