import 'dart:typed_data';

import 'package:ble_acr1555u_sdk/ble_acr1555u_sdk.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
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
}
