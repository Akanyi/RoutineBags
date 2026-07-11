package dev.lans.routinebagkkit;

import java.nio.charset.StandardCharsets;

final class Wire {
    static final String HELLO = "routinebags:hello";
    static final String SORT_REQUEST = "routinebags:sort_request";
    static final String SORT_RESULT = "routinebags:sort_result";
    static final String STORE_REQUEST = "routinebags:store_request";
    static final String STORE_RESULT = "routinebags:store_result";

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

    static byte[] storeResult(boolean success, int moved, String messageKey) {
        Writer w = new Writer();
        w.writeBoolean(success);
        w.writeVarInt(moved);
        w.writeString(messageKey);
        return w.toByteArray();
    }

    static int readSortMode(byte[] payload) {
        return new Reader(payload).readVarInt();
    }

    static int readStoreSlot(byte[] payload) {
        return new Reader(payload).readVarInt();
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
