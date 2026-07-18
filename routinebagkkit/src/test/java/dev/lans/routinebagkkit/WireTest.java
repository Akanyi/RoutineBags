package dev.lans.routinebagkkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class WireTest {
    @Test
    void readsStoreRequestWithContainerSessionAndFixedHash() {
        byte[] expectedHash = hash(64);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 23);
        writeVarInt(payload, 7);
        writeVarInt(payload, 36);
        writeVarInt(payload, 48);
        payload.writeBytes(expectedHash);

        Wire.StoreRequestV3 request = Wire.readStoreRequestV3(payload.toByteArray());

        assertEquals(23, request.requestId());
        assertEquals(7, request.containerId());
        assertEquals(36, request.sourceSlot());
        assertEquals(48, request.amount());
        assertArrayEquals(expectedHash, request.expectedHash());

        byte[] exposed = request.expectedHash();
        exposed[0] = 99;
        assertArrayEquals(expectedHash, request.expectedHash());
    }

    @Test
    void readsTakeRequestWithContainerSessionAndFixedHashes() {
        byte[] firstHash = hash(0);
        byte[] secondHash = hash(32);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 17);
        writeVarInt(payload, 9);
        writeVarInt(payload, 1);
        writeVarInt(payload, 2);
        writeTarget(payload, 45, 3, 4, firstHash);
        writeTarget(payload, 12, 8, 2, secondHash);

        Wire.TakeRequest request = Wire.readTakeRequest(payload.toByteArray());

        assertEquals(17, request.requestId());
        assertEquals(9, request.containerId());
        assertEquals(1, request.destination());
        assertEquals(2, request.targets().size());
        assertEquals(45, request.targets().get(0).bagMenuSlot());
        assertEquals(3, request.targets().get(0).entryIndex());
        assertEquals(4, request.targets().get(0).amount());
        assertArrayEquals(firstHash, request.targets().get(0).expectedHash());
        assertArrayEquals(secondHash, request.targets().get(1).expectedHash());

        byte[] exposed = request.targets().get(0).expectedHash();
        exposed[0] = 99;
        assertArrayEquals(firstHash, request.targets().get(0).expectedHash());
    }

    @Test
    void rejectsTrailingBytes() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 1);
        writeVarInt(payload, 0);
        writeVarInt(payload, 1);
        writeVarInt(payload, 1);
        writeTarget(payload, 9, 0, 1, hash(0));
        payload.write(0);

        assertThrows(IllegalArgumentException.class, () -> Wire.readTakeRequest(payload.toByteArray()));
    }

    @Test
    void rejectsTrailingBytesInStoreRequest() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 1);
        writeVarInt(payload, 2);
        writeVarInt(payload, 9);
        writeVarInt(payload, 3);
        payload.writeBytes(hash(0));
        payload.write(0);

        assertThrows(IllegalArgumentException.class, () -> Wire.readStoreRequestV3(payload.toByteArray()));
    }

    private static void writeTarget(ByteArrayOutputStream output, int bagSlot, int entryIndex,
            int amount, byte[] expectedHash) {
        writeVarInt(output, bagSlot);
        writeVarInt(output, entryIndex);
        writeVarInt(output, amount);
        output.writeBytes(expectedHash);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static byte[] hash(int start) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (start + i);
        }
        return bytes;
    }
}
