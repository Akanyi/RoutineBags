package dev.lans.routinebagkkit;

import java.nio.charset.StandardCharsets;

final class Wire {
    static final String HELLO = "routinebags:hello";
    static final String SORT_REQUEST = "routinebags:sort_request";
    static final String SORT_RESULT = "routinebags:sort_result";
    static final String STORE_REQUEST_V3 = "routinebags:store_request_v3";
    static final String STORE_RESULT_V3 = "routinebags:store_result_v3";
    static final String TAKE_REQUEST = "routinebags:take_request_v3";
    static final String TAKE_RESULT = "routinebags:take_result_v3";

    static byte[] helloPayload(boolean serverSort, boolean serverStore) {
        Writer w = new Writer();
        w.writeString("routinebagkkit");
        w.writeBoolean(serverSort);
        w.writeBoolean(serverStore);
        return w.toByteArray();
    }

    static byte[] sortResult(boolean success, int moves, String messageKey) {
        Writer w = new Writer();
        w.writeBoolean(success);
        w.writeVarInt(moves);
        w.writeString(messageKey);
        return w.toByteArray();
    }

    static int readSortMode(byte[] payload) {
        return new Reader(payload).readVarInt();
    }

    static StoreRequestV3 readStoreRequestV3(byte[] payload) {
        Reader reader = new Reader(payload);
        StoreRequestV3 request = new StoreRequestV3(reader.readVarInt(), reader.readVarInt(),
                reader.readVarInt(), reader.readVarInt(), reader.readBytes(32));
        reader.requireEnd();
        return request;
    }

    static TakeRequest readTakeRequest(byte[] payload) {
        Reader reader = new Reader(payload);
        int requestId = reader.readVarInt();
        int containerId = reader.readVarInt();
        int destination = reader.readVarInt();
        int count = reader.readVarInt();
        if (count <= 0 || count > 128) {
            throw new IllegalArgumentException("Invalid take target count");
        }
        java.util.List<TakeTarget> targets = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(new TakeTarget(reader.readVarInt(), reader.readVarInt(), reader.readVarInt(), reader.readBytes(32)));
        }
        reader.requireEnd();
        return new TakeRequest(requestId, containerId, destination, java.util.List.copyOf(targets));
    }

    static byte[] storeResultV3(int requestId, boolean success, int moved, String messageKey) {
        Writer w = new Writer();
        w.writeVarInt(requestId);
        w.writeBoolean(success);
        w.writeVarInt(moved);
        w.writeString(messageKey);
        return w.toByteArray();
    }

    static byte[] takeResult(int requestId, boolean success, int moved, String messageKey) {
        Writer w = new Writer();
        w.writeVarInt(requestId);
        w.writeBoolean(success);
        w.writeVarInt(moved);
        w.writeString(messageKey);
        return w.toByteArray();
    }

    record StoreRequestV3(int requestId, int containerId, int sourceSlot, int amount, byte[] expectedHash) {
        StoreRequestV3 {
            if (expectedHash.length != 32) {
                throw new IllegalArgumentException("Invalid item identity hash");
            }
            expectedHash = expectedHash.clone();
        }

        @Override
        public byte[] expectedHash() {
            return expectedHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof StoreRequestV3 request
                    && requestId == request.requestId
                    && containerId == request.containerId
                    && sourceSlot == request.sourceSlot
                    && amount == request.amount
                    && java.util.Arrays.equals(expectedHash, request.expectedHash);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(requestId);
            result = 31 * result + Integer.hashCode(containerId);
            result = 31 * result + Integer.hashCode(sourceSlot);
            result = 31 * result + Integer.hashCode(amount);
            return 31 * result + java.util.Arrays.hashCode(expectedHash);
        }
    }

    record TakeRequest(int requestId, int containerId, int destination, java.util.List<TakeTarget> targets) {}

    record TakeTarget(int bagMenuSlot, int entryIndex, int amount, byte[] expectedHash) {
        TakeTarget {
            expectedHash = expectedHash.clone();
        }

        @Override
        public byte[] expectedHash() {
            return expectedHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof TakeTarget target
                    && bagMenuSlot == target.bagMenuSlot
                    && entryIndex == target.entryIndex
                    && amount == target.amount
                    && java.util.Arrays.equals(expectedHash, target.expectedHash);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(bagMenuSlot);
            result = 31 * result + Integer.hashCode(entryIndex);
            result = 31 * result + Integer.hashCode(amount);
            return 31 * result + java.util.Arrays.hashCode(expectedHash);
        }
    }

    static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int readVarInt() {
            int value = 0;
            int shift = 0;
            while (shift < 35) {
                if (this.pos >= this.data.length) {
                    throw new IllegalArgumentException("Unexpected end of VarInt");
                }
                byte b = this.data[this.pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("VarInt is too large");
        }

        String readString() {
            int length = readVarInt();
            if (length <= 0 || length > 1_048_576 || this.pos + length > this.data.length) {
                throw new IllegalArgumentException("Invalid string length");
            }
            String value = new String(this.data, this.pos, length, StandardCharsets.UTF_8);
            this.pos += length;
            return value;
        }

        byte[] readBytes(int length) {
            if (length <= 0 || this.pos + length > this.data.length) {
                throw new IllegalArgumentException("Invalid byte array length");
            }
            byte[] value = java.util.Arrays.copyOfRange(this.data, this.pos, this.pos + length);
            this.pos += length;
            return value;
        }

        void requireEnd() {
            if (this.pos != this.data.length) {
                throw new IllegalArgumentException("Unexpected trailing bytes");
            }
        }
    }

    static final class Writer {
        private byte[] data = new byte[64];
        private int pos;

        void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        void writeString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            writeBytes(bytes);
        }

        void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte(value);
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(this.data, this.pos);
        }

        private void writeBytes(byte[] bytes) {
            ensure(bytes.length);
            System.arraycopy(bytes, 0, this.data, this.pos, bytes.length);
            this.pos += bytes.length;
        }

        private void writeByte(int value) {
            ensure(1);
            this.data[this.pos++] = (byte) value;
        }

        private void ensure(int extra) {
            int needed = this.pos + extra;
            if (needed > this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, Math.max(needed, this.data.length * 2));
            }
        }
    }

    private Wire() {}
}
