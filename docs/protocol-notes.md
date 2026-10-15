# ACR1555U Transparent Exchange: protocol notes

This document records protocol facts used by this package. It does not claim
that every command listed here is supported by every ACR1555U firmware or by
every ISO15693 tag.

## ACR1555U PC/SC commands

The ACR1555U exposes the following PC/SC 2.0 Part 3 functions through
`FF C2`:

| APDU function | Purpose | Evidence |
|---|---|---|
| `FF C2 00 00` | Manage Transparent Session | ACR1555U Reference Manual, section 5.5.5; repository extract `g2misuratori/USR-ACS-Mobile-Card-Reader-Utility-V1.07-EN/API_rilevanti_ACR1555U.md`, section 6.1 |
| `FF C2 00 01` | Transparent Exchange | ACR1555U Reference Manual, section 5.5.5; repository extract, section 6.2 |
| `FF C2 00 02` | Switch Protocol | ACR1555U Reference Manual, section 5.5.5; repository extract, section 6.3 |

The package implements these commands in
[`lib/ble_acr1555u_sdk.dart`](../lib/ble_acr1555u_sdk.dart):

- `buildManageSessionApdu(0x81)` starts a session and `buildManageSessionApdu(0x82)`
  ends it.
- `buildSwitchProtocolApdu(highDataRate: false)` selects the ISO15693 `80h`
  baud selector and `highDataRate: true` selects `08h`.
- `buildTransparentExchangeApdu` wraps native tag bytes in TLV `95`.
- `parseTransparentResponse` validates reader TLV `96` and extracts TLV `97`.

The same reference manual documents the following Transparent Exchange TLVs:

- `95`: bytes transmitted to the tag;
- `92`: number of valid received bits;
- `96`: reader transceive status;
- `97`: bytes received from the tag.

The package does not add ISO15693 CRC bytes to commands passed through
Transparent Exchange. CRC/parity handling is delegated to the reader
configuration described by the ACR1555U manual.

## ISO15693 and ST25 custom commands

The consumer project identifies the target as an ST25DV64K-family tag and
records these ISO15693 facts in
[`g2misuratori/docs/Comunicazione_NFC_100.md`](../../g2misuratori/docs/Comunicazione_NFC_100.md):

- STMicroelectronics manufacturer code: `02h`;
- the UID is included when addressed mode is used;
- the option flag is not used by the application;
- ISO15693 CRC16 is part of the RF protocol.

The ST25DV datasheet identifies `Present Password` as the RF password
authentication command with command code `B3h`:

- [ST25DV04K/ST25DV16K/ST25DV64KC datasheet (STMicroelectronics)](https://www.st.com/resource/en/datasheet/st25dv64kc.pdf)

For a raw addressed ST25 custom command, this package constructs the bytes in
this order:

```text
[ISO15693 flags] [command code] [ST manufacturer code] [UID] [command data]
```

For `Present Password`, the resulting command is:

```text
22 B3 02 [8-byte UID] [password number] [8-byte password]
```

This layout is implemented by
`BleAcr1555uSdk.buildAddressedSt25Command` and covered by
`test/transparent_exchange_test.dart`. The `22h` flags select addressed mode
and high data rate in the command used by this package; the `02h` immediately
after `B3h` is the ST manufacturer code, not the password number.

The Android/iOS NFC package in the consumer project exposes password data as
one password-number byte followed by eight password bytes:

- [`nfc_st_25_sdk/ios/Classes/NfcSt25SdkPlugin.swift`](../../nfc_st_25_sdk/ios/Classes/NfcSt25SdkPlugin.swift),
  `presentPassword`
- [`g2misuratori/lib/providers/ble_reader_manager.dart`](../../g2misuratori/lib/providers/ble_reader_manager.dart),
  `presentPassword`

The NFC SDK's `customCommandWithFlags` API receives the command code and
password payload separately, so that stack handles protocol-specific framing
internally. The BLE package uses raw Transparent Exchange and therefore builds
the ISO15693/ST25 framing explicitly.

## Error handling implemented here

For TLV `97`, the first byte is interpreted as the ISO15693 response flags. If
the error flag is set, the next byte is reported as the tag error code. For
example, the package reports `flag=0x01, error=0x15` and
`flag=0x01, error=0x02` without converting either value into an assumed
vendor-specific description.

This behavior is implemented and tested in
[`parseTransparentResponse`](../lib/ble_acr1555u_sdk.dart) and
[`transparent_exchange_test.dart`](../test/transparent_exchange_test.dart).
The numeric meaning of an error code is not generalized here because it is
defined by the tag command implementation and must be checked against the
specific tag datasheet.

## External references consulted

These references were consulted while implementing the raw command path:

1. [ACR1555U Reference Manual extract in the consumer repository](../../g2misuratori/USR-ACS-Mobile-Card-Reader-Utility-V1.07-EN/API_rilevanti_ACR1555U.md),
   sections 5.5.4 and 5.5.5.
2. [ST25DV64KC datasheet](https://www.st.com/resource/en/datasheet/st25dv64kc.pdf),
   for RF password protection and the `Present Password (B3h)` command.
3. [ST Community: ISO15693 custom command framing for `0xB3`](https://community.st.com/st25-nfc-rfid-tags-and-readers-54/i-had-a-problem-sending-the-0xb3-custom-command-using-the-handler-customcommand-function-118875),
   for the command-code/manufacturer-code/UID ordering and implementation
   discussion.
4. [STMicroelectronics' official STM32 ST25DV driver](https://github.com/STMicroelectronics/stm32-st25dv),
   as the vendor-maintained reference repository for ST25DV device support.
