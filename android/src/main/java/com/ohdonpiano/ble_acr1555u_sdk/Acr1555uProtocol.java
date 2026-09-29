package com.ohdonpiano.ble_acr1555u_sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementazione del protocollo proprietario BLE del reader USR/ACS ACR1555U
 * (Reference Manual V1.21, §5.2.3/§5.2.4) e dei messaggi CCID (USB-CCID class spec)
 * che vengono incapsulati nel campo "Datablock" del frame BLE.
 *
 * Frame BLE (§5.2.3):
 *   Start(1=0x55) | Slot(1) | Len(2, LSB first) | MutualAuth(1) | HostSeq(1) | ReaderSeq(1)
 *   | Datablock(Len byte, formato CCID) | Checksum(1, XOR di tutti i byte da Slot a fine Datablock) | Stop(1=0xAA)
 *
 * NOTE IMPLEMENTATIVE:
 *  - Len è big-endian (MSB first): confermato empiricamente sui frame di risposta reali del
 *    reader (es. byte "00 0A" = 10), a differenza dell'assunzione iniziale little-endian.
 *  - Il checksum è calcolato come XOR di [Slot, LenMSB, LenLSB, MutualAuth, HostSeq, ReaderSeq] + Datablock.
 *  - I messaggi CCID dentro il Datablock seguono lo standard USB CCID (bMessageType, dwLength LE,
 *    bSlot, bSeq, campi specifici, abData), essendo l'unico formato compatibile con i codici
 *    comando/risposta documentati (62h/63h/65h/6Fh/6Bh/61h -> 80h/81h/82h/83h/53h/50h/52h).
 */
final class Acr1555uProtocol {

    static final int START_BYTE = 0x55;
    static final int STOP_BYTE = 0xAA;

    static final int SLOT_PICC = 0x00;

    // Comandi PC -> Reader (bMessageType CCID)
    static final int PC_TO_RDR_ICC_POWER_ON = 0x62;
    static final int PC_TO_RDR_ICC_POWER_OFF = 0x63;
    static final int PC_TO_RDR_GET_SLOT_STATUS = 0x65;
    static final int PC_TO_RDR_XFR_BLOCK = 0x6F;
    static final int PC_TO_RDR_ESCAPE = 0x6B;

    // Risposte Reader -> PC (bMessageType CCID)
    static final int RDR_TO_PC_DATA_BLOCK = 0x80;
    static final int RDR_TO_PC_SLOT_STATUS = 0x81;
    static final int RDR_TO_PC_ESCAPE = 0x83;
    static final int RDR_TO_PC_ERROR = 0x53; // errore proprietario di livello frame BLE
    static final int RDR_TO_PC_NOTIFY_SLOT_CHANGE = 0x50;
    static final int RDR_TO_PC_SLEEP = 0x52;

    private Acr1555uProtocol() {}

    /** Costruisce il messaggio CCID PC_to_RDR_IccPowerOn (nessun dato). */
    static byte[] buildIccPowerOn(int seq) {
        return buildCcidHeaderOnly(PC_TO_RDR_ICC_POWER_ON, seq, new byte[]{0x00, 0x00, 0x00});
    }

    /** PC_to_RDR_IccPowerOff (nessun dato). */
    static byte[] buildIccPowerOff(int seq) {
        return buildCcidHeaderOnly(PC_TO_RDR_ICC_POWER_OFF, seq, new byte[]{0x00, 0x00, 0x00});
    }

    /** PC_to_RDR_GetSlotStatus (nessun dato). */
    static byte[] buildGetSlotStatus(int seq) {
        return buildCcidHeaderOnly(PC_TO_RDR_GET_SLOT_STATUS, seq, new byte[]{0x00, 0x00, 0x00});
    }

    /** PC_to_RDR_XfrBlock con l'APDU/pseudo-APDU (FF B0/D6/CA/FB/C2...) come payload. */
    static byte[] buildXfrBlock(int seq, byte[] apdu) {
        byte[] specific = new byte[]{0x00, 0x00, 0x00}; // bBWI=0x00, wLevelParameter=0x0000
        return buildCcidWithData(PC_TO_RDR_XFR_BLOCK, seq, specific, apdu);
    }

    /** PC_to_RDR_Escape con il comando esteso (E0 ..) come payload. */
    static byte[] buildEscape(int seq, byte[] escapeCommand) {
        byte[] specific = new byte[]{0x00, 0x00, 0x00};
        return buildCcidWithData(PC_TO_RDR_ESCAPE, seq, specific, escapeCommand);
    }

    private static byte[] buildCcidHeaderOnly(int messageType, int seq, byte[] specific) {
        return buildCcidWithData(messageType, seq, specific, new byte[0]);
    }

    private static byte[] buildCcidWithData(int messageType, int seq, byte[] specific, byte[] data) {
        int len = data.length;
        byte[] msg = new byte[10 + len];
        msg[0] = (byte) messageType;
        msg[1] = (byte) (len & 0xFF);
        msg[2] = (byte) ((len >> 8) & 0xFF);
        msg[3] = (byte) ((len >> 16) & 0xFF);
        msg[4] = (byte) ((len >> 24) & 0xFF);
        msg[5] = (byte) SLOT_PICC;
        msg[6] = (byte) (seq & 0xFF);
        msg[7] = specific.length > 0 ? specific[0] : 0;
        msg[8] = specific.length > 1 ? specific[1] : 0;
        msg[9] = specific.length > 2 ? specific[2] : 0;
        System.arraycopy(data, 0, msg, 10, len);
        return msg;
    }

    /** Risultato del parsing di un messaggio CCID di risposta (contenuto nel Datablock del frame BLE). */
    static final class CcidResponse {
        final int messageType;
        final int seq;
        final int status; // bStatus (bit 6-7 icc status, bit 0 command status: 0=ok,1=failed,2=timeout)
        final int error; // bError
        final byte[] data; // abData (es. ATR o risposta APDU con SW1SW2 finali)

        CcidResponse(int messageType, int seq, int status, int error, byte[] data) {
            this.messageType = messageType;
            this.seq = seq;
            this.status = status;
            this.error = error;
            this.data = data;
        }

        boolean isCommandFailed() {
            // bStatus bit 0-1: 00=ok, 01=failed (con bError valorizzato), 10=timeout
            int commandStatus = status & 0x03;
            return commandStatus != 0;
        }
    }

    /** Effettua il parse di un messaggio CCID generico (DataBlock/SlotStatus/Escape). */
    static CcidResponse parseCcidMessage(byte[] msg) {
        if (msg.length < 10) {
            throw new IllegalArgumentException("CCID message too short: " + msg.length);
        }
        int messageType = msg[0] & 0xFF;
        int len = (msg[1] & 0xFF) | ((msg[2] & 0xFF) << 8) | ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 24);
        int seq = msg[6] & 0xFF;
        int status = msg[7] & 0xFF;
        int error = msg[8] & 0xFF;
        byte[] data = new byte[len];
        if (len > 0) {
            System.arraycopy(msg, 10, data, 0, Math.min(len, msg.length - 10));
        }
        return new CcidResponse(messageType, seq, status, error, data);
    }

    /** Notifica presenza/assenza PICC (RDR_to_PC_NotifySlotChange, 0x50): messaggio corto non-CCID standard. */
    static boolean isCardPresent(byte bmSlotIccState) {
        // bit0 = ICC present, bit1 = changed (§ tabella: 02h assente, 03h presente)
        return (bmSlotIccState & 0x01) != 0;
    }

    /**
     * Incapsula un messaggio CCID nel frame BLE proprietario (§5.2.3).
     *
     * @param hostSeq   sequence number lato host, incrementato ad ogni frame inviato
     * @param readerSeq ultimo sequence number noto lato reader (echo, 0 se non ancora noto)
     */
    static byte[] encodeFrame(byte[] ccidMessage, int hostSeq, int readerSeq) {
        int len = ccidMessage.length;
        List<Byte> header = new ArrayList<>();
        header.add((byte) SLOT_PICC);
        // Len è big-endian (MSB first): confermato osservando i frame di risposta reali del
        // reader (es. "00 0A" = 10), non little-endian come ipotizzato inizialmente.
        header.add((byte) ((len >> 8) & 0xFF));
        header.add((byte) (len & 0xFF));
        header.add((byte) 0x00); // Mutual authentication: 00h plain text (default)
        header.add((byte) (hostSeq & 0xFF));
        header.add((byte) (readerSeq & 0xFF));

        byte[] out = new byte[1 + header.size() + len + 1 + 1];
        int idx = 0;
        out[idx++] = (byte) START_BYTE;
        int checksum = 0;
        for (byte b : header) {
            out[idx++] = b;
            checksum ^= (b & 0xFF);
        }
        for (byte b : ccidMessage) {
            out[idx++] = b;
            checksum ^= (b & 0xFF);
        }
        out[idx++] = (byte) checksum;
        out[idx] = (byte) STOP_BYTE;
        return out;
    }

    /** Frame BLE decodificato dal flusso di notifiche del reader. */
    static final class DecodedFrame {
        final int slot;
        final int mutualAuth;
        final int hostSeq;
        final int readerSeq;
        final byte[] datablock;
        final int frameLength;

        DecodedFrame(int slot, int mutualAuth, int hostSeq, int readerSeq,
                     byte[] datablock, int frameLength) {
            this.slot = slot;
            this.mutualAuth = mutualAuth;
            this.hostSeq = hostSeq;
            this.readerSeq = readerSeq;
            this.datablock = datablock;
            this.frameLength = frameLength;
        }
    }

    /**
     * Prova a estrarre un frame completo dal buffer accumulato di notifiche BLE.
     * Ritorna null se il buffer non contiene ancora un frame completo.
     * Solleva IllegalArgumentException se il frame è malformato (checksum/stop byte errati):
     * in tal caso il chiamante deve scartare il buffer per non restare bloccato.
     */
    static DecodedFrame tryDecodeFrame(byte[] buffer) {
        if (buffer.length < 8) return null; // header(7) + checksum(1) minimo, senza contare lo start
        if ((buffer[0] & 0xFF) != START_BYTE) {
            throw new IllegalArgumentException("Start byte non valido: 0x" + Integer.toHexString(buffer[0] & 0xFF));
        }
        // Len è big-endian (MSB first): verificato sui frame di risposta reali del reader
        // (es. byte[2..3]="00 0A" -> len=10), non little-endian come assunto inizialmente.
        int len = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        int declaredFrameLength = 1 + 6 + len + 1 + 1;
        int totalLen = declaredFrameLength;
        if (buffer.length < totalLen) {
            // Some ACR1555U firmware versions split a large CCID response into
            // multiple BLE frames. Each fragment repeats the BLE header and has
            // its own checksum/stop byte, while Len remains the full CCID length.
            totalLen = findValidShortFrameLength(buffer);
            if (totalLen < 0) return null;
        } else if ((buffer[totalLen - 1] & 0xFF) != STOP_BYTE
                || !hasValidChecksum(buffer, totalLen)) {
            totalLen = findValidShortFrameLength(buffer);
            if (totalLen < 0) {
                throw new IllegalArgumentException("Frame BLE malformato");
            }
        }
        int slot = buffer[1] & 0xFF;
        int mutualAuth = buffer[4] & 0xFF;
        int hostSeq = buffer[5] & 0xFF;
        int readerSeq = buffer[6] & 0xFF;
        int dataLength = totalLen - 9;
        byte[] datablock = new byte[dataLength];
        System.arraycopy(buffer, 7, datablock, 0, dataLength);
        return new DecodedFrame(slot, mutualAuth, hostSeq, readerSeq, datablock, totalLen);
    }

    private static boolean hasValidChecksum(byte[] buffer, int frameLength) {
        if (frameLength < 9 || (buffer[frameLength - 1] & 0xFF) != STOP_BYTE) return false;
        int checksum = 0;
        for (int i = 1; i < frameLength - 2; i++) checksum ^= (buffer[i] & 0xFF);
        return checksum == (buffer[frameLength - 2] & 0xFF);
    }

    private static int findValidShortFrameLength(byte[] buffer) {
        for (int i = 8; i < buffer.length; i++) {
            if ((buffer[i] & 0xFF) != STOP_BYTE) continue;
            int candidateLength = i + 1;
            if (hasValidChecksum(buffer, candidateLength)) return candidateLength;
        }
        return -1;
    }

    // ---- Costruttori pseudo-APDU PC/SC (§5.5.3) usati da XfrBlock ----

    /** FF CA 00 00 00 -> UID/PUPI/SN del tag. */
    static byte[] apduGetUid() {
        return new byte[]{(byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00};
    }

    /**
     * FF B0 [mode/address MSB] [address LSB] [length] -> Read Binary Blocks.
     * ISO15693 uses the low nibble of P1 plus P2 for its 11-bit block address.
     */
    static byte[] apduReadBinary(int address, int length) {
        if (address < 0 || address > 0x7FF) {
            throw new IllegalArgumentException("address must fit in 11-bit ISO15693 range (0-2047)");
        }
        if (length < 0 || length > 256) {
            throw new IllegalArgumentException("length must be between 0 and 256 bytes");
        }
        return new byte[]{
                (byte) 0xFF,
                (byte) 0xB0,
                (byte) ((address >> 8) & 0x0F),
                (byte) (address & 0xFF),
                (byte) (length == 256 ? 0 : length)
        };
    }

    /** FF D6 00 [address] [Lc] [data] -> Update Binary Blocks. */
    static byte[] apduWriteBinary(int address, byte[] data) {
        if (address < 0 || address > 0xFF) {
            throw new IllegalArgumentException("address must fit in P2 (0-255)");
        }
        byte[] apdu = new byte[5 + data.length];
        apdu[0] = (byte) 0xFF;
        apdu[1] = (byte) 0xD6;
        apdu[2] = 0x00;
        apdu[3] = (byte) (address & 0xFF);
        apdu[4] = (byte) data.length;
        System.arraycopy(data, 0, apdu, 5, data.length);
        return apdu;
    }
}
