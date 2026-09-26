package engine;

public abstract class GameState {
    public static final int DRAW = -1;   // winner() when the game ended in a draw
    public static final int NONE = -2;   // winner() while the game is still running

    /** Player to move: 0 or 1. */
    public abstract int currentPlayer();

    /** Distance in the game (0 = first turn, 1 = other player's first turn). Often abbreviated ply. */
    public abstract int currentHalfMoveNumber();

    /** Writes the legal moves into out[0..n-1] and returns n. */
    public abstract int legalMoves(int[] out);

    /** Plays a legal move (mutates this object). */
    public abstract void makeMove(int move);

    /** Reverts the most recent makeMove. */
    public abstract void undoMove();

    /** 0 or 1 if that player has won, DRAW, or NONE if the game is not over. */
    public abstract int winner();

    /** Upper bound on legalMoves' count in any position (size of move buffers). */
    public abstract int maxMoves();

    /** Every move int lies in [0, moveIdBound()). Used to index history / visit tables. */
    public abstract int moveIdBound();

    /** Fully independent deep copy (needed for threads). */
    public abstract GameState copy();

    public abstract String moveToString(int move);

    /** Parses text typed by a human; returns the move, or -1 if it is not legal here. */
    public abstract int parseMove(String text);

    // toString() should print the board. You will stare at it a lot.

    // ---- Optional hooks. The defaults keep every engine working without them. ----

    /** Heuristic score from currentPlayer()'s point of view. Keep it within ±WIN/2. */
    public int evaluate() { return 0; }

    /** Zobrist hash of the position INCLUDING side to move. 0 means "no hash" (TT off). */
    public long hash() { return 0; }

    /** Larger means "search this move earlier" (captures, centre, threats...). - keep within around ±1000 to prevent overflow*/
    public int orderHint(int move) { return 0; }

    /** True for "loud" moves (e.g. captures) that quiescence search should follow. */
    public boolean isNoisy(int move) { return false; }

    public boolean isTerminal() { return winner() != NONE; }

    /**
     * A random legal move, used by MCTS playouts. Override it (e.g. with rejection sampling) when
     * generating the full move list is expensive. `buf` has room for maxMoves() moves.
     */
    public int randomMove(java.util.SplittableRandom rng, int[] buf) {
        int n = legalMoves(buf);
        if (n == 0) throw new IllegalStateException("No legal moves but game not over:\n" + this);
        return buf[rng.nextInt(n)];
    }
}