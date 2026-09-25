package engine;

final class TranspositionTable {
    static final int EXACT = 0, LOWER = 1, UPPER = 2;  // score is exact / a lower bound / an upper bound
    final long[] keys, data;                            // keys[i] = hash ^ data[i]  (§6.5 explains the XOR)
    final int mask;

    TranspositionTable(int log2Size) {                 // 22 → 4M entries → 64 MB
        keys = new long[1 << log2Size]; data = new long[1 << log2Size]; mask = (1 << log2Size) - 1;
    }
    static long pack(int move, int score, int depth, int flag) {
        return (move & 0xFFFFFFFFL)                    // bits  0-31
             | ((long) (score + (1 << 21)) << 32)      // bits 32-53 (22 bits, score in ±2M)
             | ((long) depth << 54)                    // bits 54-61
             | ((long) flag << 62);                    // bits 62-63
    }
    static int move(long d)  { return (int) d; }
    static int score(long d) { return (int) ((d >>> 32) & 0x3FFFFF) - (1 << 21); }
    static int depth(long d) { return (int) ((d >>> 54) & 0xFF); }
    static int flag(long d)  { return (int) (d >>> 62); }

    /** Returns the packed entry, or 0 if absent. */
    long probe(long hash) {
        int i = (int) hash & mask;
        long d = data[i];
        return (keys[i] ^ d) == hash ? d : 0;
    }
    void store(long hash, long d) { int i = (int) hash & mask; keys[i] = hash ^ d; data[i] = d; }
}