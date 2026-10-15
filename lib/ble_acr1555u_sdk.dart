import 'dart:async';

import 'package:flutter/services.dart';

/// Stato di connessione verso il reader BLE ACR1555U.
enum BleReaderConnectionState { disconnected, connecting, connected, ready }

/// Dispositivo BLE individuato durante lo scan (filtrato sul servizio Smart Card ACR1555U).
class BleReaderDevice {
  final String id;
  final String? name;
  final int rssi;

  const BleReaderDevice(
      {required this.id, required this.name, required this.rssi});

  factory BleReaderDevice.fromMap(Map<dynamic, dynamic> map) => BleReaderDevice(
        id: map['id'] as String,
        name: map['name'] as String?,
        rssi: (map['rssi'] as num).toInt(),
      );

  @override
  String toString() => '${name ?? "(sconosciuto)"} [$id] rssi=$rssi';
}

/// Eccezione sollevata per errori riportati dal reader o dal plugin BLE.
class BleReaderException implements Exception {
  final String code;
  final String? message;

  BleReaderException(this.code, this.message);

  @override
  String toString() => 'BleReaderException($code): ${message ?? ""}';
}

/// API Dart per il reader BLE+NFC USR/ACS ACR1555U.
///
/// Espone scan/connect, IccPowerOn (ATR), stato slot, transceive APDU generico (usato per
/// costruire Get UID `FF CA`, Read Binary `FF B0`, ecc.), Escape Command ed eventi di
/// presenza tag.
class BleAcr1555uSdk {
  static const MethodChannel _channel = MethodChannel('ble_acr1555u_sdk');
  static const EventChannel _scanChannel =
      EventChannel('ble_acr1555u_sdk/scan');
  static const EventChannel _stateChannel =
      EventChannel('ble_acr1555u_sdk/state');
  static const EventChannel _cardChannel =
      EventChannel('ble_acr1555u_sdk/card');

  static Future<bool> get isBleSupported async {
    final bool supported = await _channel.invokeMethod('isBleSupported');
    return supported;
  }

  static Future<bool> get hasPermissions async {
    final bool granted = await _channel.invokeMethod('hasPermissions');
    return granted;
  }

  static Stream<BleReaderDevice> scanForReaders() {
    return _scanChannel
        .receiveBroadcastStream()
        .map((event) => BleReaderDevice.fromMap(event));
  }

  static Future<void> startScan() async {
    await _channel
        .invokeMethod('startScan')
        .catchError((e) => throw _mapException(e));
  }

  static Future<void> stopScan() async {
    await _channel
        .invokeMethod('stopScan')
        .catchError((e) => throw _mapException(e));
  }

  /// Stream dello stato di connessione: disconnected -> connecting -> connected -> ready.
  /// "ready" indica che i servizi/pipe BLE sono stati scoperti e le notifiche abilitate:
  /// da questo momento è possibile inviare comandi (iccPowerOn, transceiveApdu, ecc.).
  static Stream<BleReaderConnectionState> connectionState() {
    return _stateChannel.receiveBroadcastStream().map((event) {
      switch (event as String) {
        case 'connecting':
          return BleReaderConnectionState.connecting;
        case 'connected':
          return BleReaderConnectionState.connected;
        case 'ready':
          return BleReaderConnectionState.ready;
        default:
          return BleReaderConnectionState.disconnected;
      }
    });
  }

  /// Stream di presenza tag (RDR_to_PC_NotifySlotChange sulla pipe Card Notification).
  static Stream<bool> cardPresence() {
    return _cardChannel.receiveBroadcastStream().map((event) => event as bool);
  }

  static Future<void> connect(String deviceId) async {
    await _channel.invokeMethod('connect', {'deviceId': deviceId}).catchError(
        (e) => throw _mapException(e));
  }

  static Future<void> disconnect() async {
    await _channel
        .invokeMethod('disconnect')
        .catchError((e) => throw _mapException(e));
  }

  /// PC_to_RDR_IccPowerOn (62h): attiva lo slot e ritorna l'ATR del tag.
  static Future<Uint8List> iccPowerOn() async {
    final Uint8List atr = await _channel
        .invokeMethod('iccPowerOn')
        .catchError((e) => throw _mapException(e));
    return atr;
  }

  static Future<void> iccPowerOff() async {
    await _channel
        .invokeMethod('iccPowerOff')
        .catchError((e) => throw _mapException(e));
  }

  /// PC_to_RDR_GetSlotStatus (65h).
  static Future<Uint8List> getSlotStatus() async {
    final Uint8List status = await _channel
        .invokeMethod('getSlotStatus')
        .catchError((e) => throw _mapException(e));
    return status;
  }

  /// PC_to_RDR_XfrBlock (6Fh): invia una pseudo-APDU PC/SC e ritorna la risposta
  /// grezza del tag (dati + SW1SW2 finali per le APDU standard).
  static Future<Uint8List> transceiveApdu(Uint8List apdu) async {
    final Uint8List response = await _channel.invokeMethod('transceiveApdu',
        {'apdu': apdu}).catchError((e) => throw _mapException(e));
    return response;
  }

  /// PC_to_RDR_Escape (6Bh): comandi estesi (E0 00 ..) per stato RF/PICC, batteria, ecc.
  static Future<Uint8List> escape(Uint8List data) async {
    final Uint8List response = await _channel.invokeMethod(
        'escape', {'data': data}).catchError((e) => throw _mapException(e));
    return response;
  }

  static Future<int> get batteryLevel async {
    final int level = await _channel
        .invokeMethod('getBatteryLevel')
        .catchError((e) => throw _mapException(e));
    return level;
  }

  // ---- Helper pseudo-APDU PC/SC di alto livello (§5.5.3) ----

  /// FF CA 00 00 00 -> UID/PUPI/SN del tag connesso.
  static Future<Uint8List> getUid() async {
    final res = await transceiveApdu(
        Uint8List.fromList([0xFF, 0xCA, 0x00, 0x00, 0x00]));
    return _stripStatusWord(res);
  }

  /// FF B0 [mode/address MSB] [address LSB] [length] -> Read Binary Blocks.
  ///
  /// For ISO15693 the reader encodes the 11-bit block address across the low
  /// nibble of P1 and all of P2. A length of 0 means 256 bytes.
  static Future<Uint8List> readBinary(int address, int length) async {
    if (address < 0 || address > 0x7FF) {
      throw ArgumentError(
          'address deve essere compreso tra 0 e 2047 (indirizzo ISO15693 a 11 bit)');
    }
    if (length < 0 || length > 256) {
      throw ArgumentError('length deve essere compreso tra 0 e 256 byte');
    }
    final res = await transceiveApdu(
      Uint8List.fromList([
        0xFF,
        0xB0,
        (address >> 8) & 0x0F,
        address & 0xFF,
        length == 256 ? 0 : length,
      ]),
    );
    return _stripStatusWord(res);
  }

  /// FF D6 [mode/address MSB] [address LSB] [length] [data] -> Update
  /// Binary Blocks.
  ///
  /// For ISO15693 [address] is the first block address. The caller is
  /// responsible for supplying complete blocks of the size required by the
  /// selected tag. A short APDU can carry at most 255 data bytes.
  static Future<void> writeBinary(int address, Uint8List data) async {
    if (address < 0 || address > 0x7FF) {
      throw ArgumentError(
          'address deve essere compreso tra 0 e 2047 (indirizzo ISO15693 a 11 bit)');
    }
    if (data.isEmpty || data.length > 255) {
      throw ArgumentError('data deve contenere tra 1 e 255 byte');
    }
    final res = await transceiveApdu(
      Uint8List.fromList([
        0xFF,
        0xD6,
        (address >> 8) & 0x0F,
        address & 0xFF,
        data.length,
        ...data,
      ]),
    );
    _stripStatusWord(res);
  }

  /// Extended ISO15693 Read Multiple Blocks (0x33), in chunks suitable for the
  /// ACR1555U APDU/ATT limits. [blockCount] is encoded as N-1 as required by
  /// ISO15693, and each block is four bytes for the G2 tag.
  static Future<Uint8List> extendedReadBlocks(
    int firstBlock,
    int blockCount, {
    Uint8List? uid,
  }) async {
    if (firstBlock < 0 || firstBlock > 0xFFFFFF) {
      throw ArgumentError('firstBlock fuori dal range ISO15693 esteso');
    }
    if (blockCount <= 0 || blockCount > 64) {
      throw ArgumentError('blockCount deve essere compreso tra 1 e 64');
    }
    if (uid != null && uid.length != 8) {
      throw ArgumentError('uid deve essere lungo 8 byte');
    }
    final flags = uid == null ? 0x00 : 0x22;
    final payload = <int>[
      0x33,
      if (uid != null) ...uid,
      firstBlock & 0xFF,
      (firstBlock >> 8) & 0xFF,
      (firstBlock >> 16) & 0xFF,
      (blockCount - 1) & 0xFF,
      ((blockCount - 1) >> 8) & 0xFF,
    ];
    final apdu = Uint8List.fromList(
      [0xFF, 0xFB, 0x00, flags, payload.length, ...payload],
    );
    final response = _stripStatusWord(await transceiveApdu(apdu));
    final expectedLength = blockCount * 4;
    if (response.length != expectedLength) {
      throw BleReaderException(
        'INVALID_LENGTH',
        'lettura estesa incompleta: ${response.length}/$expectedLength byte',
      );
    }
    return response;
  }

  /// Reads ISO15693 blocks through PC/SC 2.0 Part 3 Transparent Exchange.
  ///
  /// Unlike FF B0, this sends the native ISO15693 command to the tag and
  /// therefore does not apply the reader's Read Binary address restrictions.
  static Future<Uint8List> transparentReadBlocks(int firstBlock, int blockCount,
      {Uint8List? uid}) async {
    if (firstBlock < 0 || firstBlock > 0xFFFFFF) {
      throw ArgumentError('firstBlock fuori dal range ISO15693 esteso');
    }
    if (blockCount <= 0 || blockCount > 64) {
      throw ArgumentError('blockCount deve essere compreso tra 1 e 64');
    }

    final tagUid = uid ?? await getUid();
    if (tagUid.length != 8) {
      throw ArgumentError('uid deve essere lungo 8 byte');
    }
    final command = Uint8List.fromList([
      0x22, // addressed, high data rate, one subcarrier
      0x33, // Extended Read Multiple Blocks
      ...tagUid,
      firstBlock & 0xFF,
      (firstBlock >> 8) & 0xFF,
      (blockCount - 1) & 0xFF,
      ((blockCount - 1) >> 8) & 0xFF,
    ]);
    final response = await _transparentExchangeSession(command);
    final expectedLength = blockCount * 4;
    if (response.length != expectedLength) {
      throw BleReaderException(
        'INVALID_LENGTH',
        'lettura trasparente incompleta: ${response.length}/$expectedLength byte',
      );
    }
    return response;
  }

  /// Starts a transparent exchange session and disables reader auto-polling.
  ///
  /// The session remains active until [endTransparentSession] is called. This
  /// is useful when several commands must share the same PICC authentication
  /// state.
  static Future<void> startTransparentSession() async {
    _stripStatusWord(await transceiveApdu(buildManageSessionApdu(0x81)));
  }

  /// Ends a transparent exchange session and restores the reader polling state.
  static Future<void> endTransparentSession() async {
    _stripStatusWord(await transceiveApdu(buildManageSessionApdu(0x82)));
  }

  /// Switches the ISO15693 baud rate used by the transparent session.
  ///
  /// `FF FB` pass-through commands are limited to 26 kbps by the reader,
  /// whereas the native extended read uses 53 kbps.
  static Future<void> switchTransparentProtocol({
    required bool highDataRate,
  }) async {
    _stripStatusWord(
      await transceiveApdu(
        buildSwitchProtocolApdu(highDataRate: highDataRate),
      ),
    );
  }

  /// Reads blocks using an already-started transparent session.
  static Future<Uint8List> transparentReadBlocksInSession(
    int firstBlock,
    int blockCount, {
    Uint8List? uid,
  }) async {
    if (firstBlock < 0 || firstBlock > 0xFFFFFF) {
      throw ArgumentError('firstBlock fuori dal range ISO15693 esteso');
    }
    if (blockCount <= 0 || blockCount > 64) {
      throw ArgumentError('blockCount deve essere compreso tra 1 e 64');
    }

    final tagUid = uid ?? await getUid();
    if (tagUid.length != 8) {
      throw ArgumentError('uid deve essere lungo 8 byte');
    }
    final command = Uint8List.fromList([
      0x22,
      0x33,
      ...tagUid,
      firstBlock & 0xFF,
      (firstBlock >> 8) & 0xFF,
      (blockCount - 1) & 0xFF,
      ((blockCount - 1) >> 8) & 0xFF,
    ]);
    final response = await _transparentExchange(command);
    final expectedLength = blockCount * 4;
    if (response.length != expectedLength) {
      throw BleReaderException(
        'INVALID_LENGTH',
        'lettura trasparente incompleta: ${response.length}/$expectedLength byte',
      );
    }
    return response;
  }

  /// Sends a native ISO15693 command through an already-started session.
  ///
  /// The command must include the ISO15693 request flags as its first byte.
  /// The returned value is the native response payload without the response
  /// flags byte; ISO15693 error responses are reported as [BleReaderException].
  static Future<Uint8List> transparentExchangeInSession(
    Uint8List command,
  ) async {
    if (command.length < 2 || command.length > 255) {
      throw ArgumentError('il comando trasparente deve contenere 2-255 byte');
    }
    return _transparentExchange(command);
  }

  /// Builds an addressed ST25 custom command with high data rate enabled.
  ///
  /// ST25 places its manufacturer code after the custom command code.
  static Uint8List buildAddressedSt25Command(
    int command,
    Uint8List uid,
    Uint8List payload,
  ) {
    if (command < 0 || command > 0xFF) {
      throw ArgumentError('codice comando ISO15693 non valido');
    }
    if (uid.length != 8) {
      throw ArgumentError('uid deve essere lungo 8 byte');
    }
    if (payload.length > 244) {
      throw ArgumentError('payload ISO15693 troppo lungo');
    }
    return Uint8List.fromList([0x22, command, 0x02, ...uid, ...payload]);
  }

  /// Builds the FF C2 Transparent Exchange APDU for a native tag command.
  static Uint8List buildTransparentExchangeApdu(Uint8List command) {
    if (command.isEmpty || command.length > 255) {
      throw ArgumentError('il comando trasparente deve contenere 1-255 byte');
    }
    return Uint8List.fromList([
      0xFF,
      0xC2,
      0x00,
      0x01,
      command.length + 2,
      0x95,
      command.length,
      ...command,
    ]);
  }

  /// Builds a Manage Session APDU for Start (`81`) or End (`82`).
  static Uint8List buildManageSessionApdu(int command) {
    if (command != 0x81 && command != 0x82) {
      throw ArgumentError('comando Manage Session non valido');
    }
    return Uint8List.fromList([
      0xFF,
      0xC2,
      0x00,
      0x00,
      0x02,
      command,
      0x00,
    ]);
  }

  /// Builds the ISO15693 Switch Protocol APDU with an explicit baud rate.
  static Uint8List buildSwitchProtocolApdu({
    required bool highDataRate,
  }) {
    final baud = highDataRate ? 0x08 : 0x80;
    return Uint8List.fromList([
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
      baud,
      0x8F,
      0x02,
      0x02,
      0x03,
      0x00,
    ]);
  }

  static Future<Uint8List> _transparentExchangeSession(
    Uint8List command,
  ) async {
    await startTransparentSession();
    try {
      await switchTransparentProtocol(highDataRate: true);
      return await _transparentExchange(command);
    } finally {
      await endTransparentSession();
    }
  }

  static Future<Uint8List> _transparentExchange(Uint8List command) async {
    final response = _stripStatusWord(
      await transceiveApdu(buildTransparentExchangeApdu(command)),
    );
    return parseTransparentResponse(response);
  }

  static Uint8List parseTransparentResponse(Uint8List response) {
    var index = 0;
    Uint8List? data;
    while (index < response.length) {
      final tag = response[index++];
      if (index >= response.length) {
        throw BleReaderException(
            'INVALID_RESPONSE', 'TLV trasparente troncato');
      }
      final lengthByte = response[index++];
      final int length;
      if (lengthByte == 0x81) {
        if (index >= response.length) {
          throw BleReaderException(
              'INVALID_RESPONSE', 'lunghezza TLV trasparente troncata');
        }
        final extendedLength = response[index++];
        if (extendedLength == 0x01 &&
            index < response.length &&
            response[index] == 0x00) {
          // ACR encodes the 257-byte reply (flags + 256 data bytes) as
          // 97 81 01 00, leaving the response flag as the first value byte.
          length = 0x101;
        } else {
          // For shorter replies, 97 81 xx is followed by the value bytes.
          length = extendedLength;
        }
      } else {
        length = lengthByte;
      }
      if (index + length > response.length) {
        throw BleReaderException(
            'INVALID_RESPONSE', 'TLV trasparente non valido');
      }
      final value = response.sublist(index, index + length);
      index += length;
      if (tag == 0xC0 && (value.length != 3 || value[0] != 0x00)) {
        throw BleReaderException(
            'TRANSPARENT_ERROR', 'errore generico del reader');
      }
      if (tag == 0x96 &&
          (value.length != 2 || value[0] != 0x00 || value[1] != 0x00)) {
        throw BleReaderException(
            'TRANSPARENT_ERROR', 'errore di risposta ISO15693');
      }
      if (tag == 0x97) {
        if (value.isEmpty || value[0] != 0x00) {
          final responseFlag = value.isEmpty
              ? 'missing'
              : '0x${value[0].toRadixString(16).padLeft(2, '0')}';
          final errorCode = value.length < 2
              ? 'missing'
              : '0x${value[1].toRadixString(16).padLeft(2, '0')}';
          throw BleReaderException(
            'TRANSPARENT_ERROR',
            'il tag ha restituito un errore ISO15693 '
                '(flag=$responseFlag, error=$errorCode)',
          );
        }
        data = value.sublist(1);
      }
    }
    if (data == null) {
      throw BleReaderException('INVALID_RESPONSE', 'dati ISO15693 assenti');
    }
    return data;
  }

  /// Rimuove gli ultimi 2 byte di status word (SW1 SW2) da una risposta APDU PC/SC,
  /// verificando che sia 90 00 (successo). Solleva [BleReaderException] altrimenti.
  static Uint8List _stripStatusWord(Uint8List response) {
    if (response.length < 2) {
      throw BleReaderException(
          'APDU_TOO_SHORT', 'risposta troppo corta: $response');
    }
    final sw1 = response[response.length - 2];
    final sw2 = response[response.length - 1];
    if (sw1 != 0x90 || sw2 != 0x00) {
      throw BleReaderException(
        'APDU_ERROR',
        'status word ${sw1.toRadixString(16)}${sw2.toRadixString(16)}',
      );
    }
    return response.sublist(0, response.length - 2);
  }
}

Exception _mapException(dynamic error) {
  if (error is PlatformException) {
    return BleReaderException(error.code, error.message);
  }
  return error is Exception ? error : Exception(error.toString());
}
