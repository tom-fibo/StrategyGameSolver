package games;

import engine.AlphaBetaEngine;
import engine.Engine;
import engine.GameState;
import engine.RandomEngine;

import java.util.Arrays;
import java.util.Random;

/**
 * Tic-tac-toe: the reference example of a GameState.
 *
 * Players: 0 = X (moves first), 1 = O.
 * Moves: the cell index 0..8, typed as that digit.
 * <pre>
 *   0 1 2
 *   3 4 5
 *   6 7 8
 * </pre>
 */
public class TicTacToe extends GameState {
    private static final int CELLS = 9;
    private static final int EMPTY = -1;
    private static final char[] SYMBOL = {'X', 'O'};

    private static final int[][] LINES = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8},   // rows
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8},   // columns
        {0, 4, 8}, {2, 4, 6}               // diagonals
    };
    /** LINES_THROUGH[cell] = the lines containing that cell, so a win check only looks near the last move. */
    private static final int[][][] LINES_THROUGH = new int[CELLS][][];

    // Zobrist keys. static final and never modified, so they are safe to share between threads.
    //Random value for each piece type in each cell, + one for turn position (& start pos)
    private static final long[][] Z_PIECE = new long[2][CELLS];
    private static final long Z_SIDE;   // XOR-ed in while player 1 is to move
    private static final long Z_BASE;   // start value, so the empty board doesn't hash to 0 ("no hash")

    static {
        for (int c = 0; c < CELLS; c++) {
            final int cell = c;
            LINES_THROUGH[c] = Arrays.stream(LINES)
                    .filter(line -> line[0] == cell || line[1] == cell || line[2] == cell)
                    .toArray(int[][]::new);
        }
        Random r = new Random(20260924);
        for (long[] row : Z_PIECE) for (int i = 0; i < row.length; i++) row[i] = r.nextLong();
        Z_SIDE = r.nextLong();
        Z_BASE = r.nextLong();
    }

    // ---- Position ----
    private final int[] board = new int[CELLS];   // EMPTY, 0 or 1
    private int toMove = 0;
    private int winner = NONE;
    private long hash = Z_BASE;

    // ---- Undo stack: everything makeMove changes that undoMove can't recompute ----
    private final int[] moveStack = new int[CELLS];
    private final int[] winnerStack = new int[CELLS];
    private int ply = 0;

    public TicTacToe() {
        Arrays.fill(board, EMPTY);
    }

    private TicTacToe(TicTacToe other) {
        System.arraycopy(other.board, 0, board, 0, CELLS);
        System.arraycopy(other.moveStack, 0, moveStack, 0, CELLS);
        System.arraycopy(other.winnerStack, 0, winnerStack, 0, CELLS);
        toMove = other.toMove;
        winner = other.winner;
        hash = other.hash;
        ply = other.ply;
    }

    @Override public int currentPlayer() { return toMove; }

    @Override public int currentHalfMoveNumber() { return ply; }

    @Override
    public int legalMoves(int[] out) {
        if (winner != NONE) return 0;
        int n = 0;
        for (int c = 0; c < CELLS; c++) if (board[c] == EMPTY) out[n++] = c;
        return n;
    }

    @Override
    public void makeMove(int m) {
        assert winner == NONE && board[m] == EMPTY : "illegal move " + m + "\n" + this;
        board[m] = toMove;
        hash ^= Z_PIECE[toMove][m] ^ Z_SIDE;
        moveStack[ply] = m;
        winnerStack[ply] = winner;
        ply++;
        winner = computeWinner(m);
        toMove = 1 - toMove;
    }

    @Override
    public void undoMove() {
        ply--;
        int m = moveStack[ply];
        toMove = 1 - toMove;             // back to the player who made move m
        board[m] = EMPTY;
        hash ^= Z_PIECE[toMove][m] ^ Z_SIDE;
        winner = winnerStack[ply];
    }

    /** Called right after `lastMove` was placed: only lines through it can have just been completed. */
    private int computeWinner(int lastMove) {
        int p = board[lastMove];
        for (int[] line : LINES_THROUGH[lastMove]) {
            if (board[line[0]] == p && board[line[1]] == p && board[line[2]] == p) return p;
        }
        return ply == CELLS ? DRAW : NONE;
    }

    @Override public int winner() { return winner; }
    @Override public int maxMoves() { return CELLS; }
    @Override public int moveIdBound() { return CELLS; }
    @Override public GameState copy() { return new TicTacToe(this); }
    @Override public long hash() { return hash; }

    /** Centre first, then corners, then edges. */
    @Override
    public int orderHint(int move) {
        if (move == 4) return 2;
        return (move % 2 == 0) ? 1 : 0;   // 0, 2, 6, 8 are corners
    }

    @Override public String moveToString(int move) { return Integer.toString(move); }

    @Override
    public int parseMove(String text) {
        text = text.trim();
        if (text.length() != 1 || !Character.isDigit(text.charAt(0))) return -1;
        int m = text.charAt(0) - '0';
        if (m >= CELLS || board[m] != EMPTY || winner != NONE) return -1;
        return m;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int v = board[r * 3 + c];
                sb.append(v == EMPTY ? '.' : SYMBOL[v]).append(' ');
            }
            sb.append("   ");
            for (int c = 0; c < 3; c++) sb.append(r * 3 + c).append(' ');
            sb.append('\n');
        }
        if (winner == NONE) sb.append(SYMBOL[toMove]).append(" to move");
        else if (winner == DRAW) sb.append("Draw");
        else sb.append(SYMBOL[winner]).append(" wins");
        return sb.toString();
    }

    // ---- Demo: one printed game plus statistics, both engine vs engine ----

    public static void main(String[] args) {
        Engine engine1 = new AlphaBetaEngine();
        Engine engine2 = new RandomEngine();

        System.out.println("=== One sample game: " + engine1.name() + " vs " + engine2.name() + " ===");
        TicTacToe game = new TicTacToe();
        System.out.println(game + "\n");
        while (!game.isTerminal()) {
            int m = (game.toMove == 0 ? engine1 : engine2).search(game, 10).bestMove();
            requireLegal(game, m);
            System.out.println(SYMBOL[game.currentPlayer()] + " plays " + game.moveToString(m));
            game.makeMove(m);
            System.out.println(game + "\n");
        }

        int games = 1000;
        int xWins = 0, oWins = 0, draws = 0;
        for (int i = 0; i < games; i++) {
            TicTacToe g = new TicTacToe();
            while (!g.isTerminal()) {
                int m = (g.toMove == 0 ? engine1 : engine2).search(g, 10).bestMove();
                requireLegal(g, m);
                g.makeMove(m);
            }
            if (g.winner() == 0) xWins++;
            else if (g.winner() == 1) oWins++;
            else draws++;
        }
        System.out.printf("=== %,d games ===%n", games);
        System.out.printf("X wins %.1f%%   O wins %.1f%%   draws %.1f%%%n",
                100.0 * xWins / games, 100.0 * oWins / games, 100.0 * draws / games);
        System.out.println("(uniformly random play should give about 58.5% / 28.8% / 12.7%)");
    }

    private static void requireLegal(GameState s, int move) {
        int[] buf = new int[s.maxMoves()];
        int n = s.legalMoves(buf);
        for (int i = 0; i < n; i++) if (buf[i] == move) return;
        throw new IllegalStateException("Engine returned illegal move " + move + " in position:\n" + s);
    }
}
