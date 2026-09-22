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

  /// PC_to_RDR_XfrBlock (6Fh): invia una pseudo-APDU PC/SC (FF CA/B0/D6/FB/C2) e ritorna la
  /// risposta grezza del tag (dati + SW1SW2 finali per le APDU standard).
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

  /// FF B0 00 [address] [length] -> Read Binary Blocks (length 0x00 = 256 byte).
  static Future<Uint8List> readBinary(int address, int length) async {
    if (address < 0 || address > 0xFF) {
      throw ArgumentError(
          'address deve essere compreso tra 0 e 255 (P2 a 1 byte)');
    }
    final res = await transceiveApdu(
      Uint8List.fromList([0xFF, 0xB0, 0x00, address & 0xFF, length & 0xFF]),
    );
    return _stripStatusWord(res);
  }

  /// Present Password: comando proprietario ST (ISO15693 Custom Command 0xB3, manufacturer
  /// code ST 0x02), inviato tramite il Pass-Through Command ISO15693 `FF FB ..` (§5.5.4.2).
  /// Stesso comando usato da `TagManager.presentPassword` via NFC telefono (ST25 SDK
  /// `ST25DVTag.presentPassword`), necessario per sbloccare la lettura dell'area di
  /// configurazione protetta (es. password #2 per l'area 0x0000-0x03FF).
  ///
  /// Il frame indirizzato (Flags=0x22 + UID) viene usato quando [uid] è fornito,
  /// passando l'UID così come restituito da [getUid] (già in ordine di trasmissione
  /// ISO15693, LSB per primo).
  static Future<void> presentPassword(int passwordNumber, Uint8List password,
      {Uint8List? uid}) async {
    if (password.length != 8) {
      throw ArgumentError('password deve essere di 8 byte (16 cifre hex)');
    }
    final int flags = uid != null ? 0x22 : 0x00;
    final data = Uint8List.fromList([
      0xB3, // Command Code: Present Password (custom ST)
      0x02, // Manufacturer Code: ST
      if (uid != null) ...uid,
      passwordNumber & 0xFF,
      ...password,
    ]);
    final apdu = Uint8List.fromList(
        [0xFF, 0xFB, 0x00, flags, data.length & 0xFF, ...data]);
    final res = await transceiveApdu(apdu);
    _stripStatusWord(res);
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
