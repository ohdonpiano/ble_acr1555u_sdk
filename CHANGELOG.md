## 0.2.1

- Fixed ISO15693 `FF B0` Read Binary address encoding for ACR1555U readers.
- Added support for the full 11-bit ISO15693 block address range (0-2047).
- Added validation for read lengths up to 256 bytes, using `00h` for 256-byte reads.
- Fixed reassembly of large `FF B0` responses split across multiple BLE frames.
- Documented the extended read compatibility behavior used by the ACR1555U plugin.

## 0.1.0

- Initial public release for Android.
- Added ACR1555U BLE discovery and connection.
- Added CCID power, slot-status, APDU, escape, card-presence, and battery APIs.
