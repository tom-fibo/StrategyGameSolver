package games;

import engine.AlphaBetaEngine;
import engine.Engine;
import engine.GameState;
import engine.ManualEngine;
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
public class Othello extends GameState {
    private static final int CELLS = 64; // Top: 01234567 Second: 89...... Final: 56 57 58 59 60 61 62 63
    private static final int EMPTY = -1;
    private static final char[] SYMBOL = {'W', 'B'};

    // Zobrist keys. static final and never modified, so they are safe to share between threads.
    //Random value for each piece type (2 being both together for a swap) in each cell, + one for turn position (& start pos)
    private static final long[][] Z_PIECE = new long[3][CELLS];
    private static final long Z_SIDE;   // XOR-ed in while player 1 is to move
    private static final long Z_BASE;   // start value, so the empty board doesn't hash to 0 ("no hash")

    static {
        Random r = new Random(20260924);
        for (long[] row : Z_PIECE) {
            for (int i = 0; i < 2; i++) {
                row[i] = r.nextLong();
            }
            row[2] = row[0] ^ row[1];
        }
        Z_SIDE = r.nextLong();
        Z_BASE = r.nextLong();
    }

    // ---- Position ----
    private final int[] board = new int[CELLS];   // EMPTY, 0 or 1
    private int toMove = 0; //0 or 1
    private int winner = NONE;
    private long hash = Z_BASE;

    private int[] captureBuf = new int[18]; //Stores potential captures for a move

    // ---- Undo stack: everything makeMove changes that undoMove can't recompute ----
    private final int[] moveStack = new int[CELLS];
    private final int[][] captureStack = new int[CELLS][18]; // (cells captured - not possible to capture more than 18)
    private final int[] captureCountStack = new int[CELLS]; // (amount of cells captured - used to know how many indexes of capturestack to check)
    private final int[] winnerStack = new int[CELLS];
    private int ply = 0;

    public Othello() {
        Arrays.fill(board, EMPTY);
        board[27] = 0;
        board[28] = 1;
        board[35] = 1;
        board[36] = 0;
    }

    private Othello(Othello other) {
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

    /* captureBuf  */
    public int findCaptures(int[] captureBuf, int move) {
        int captures = 0;
        for (int dir : new int[] {-9,-8,-7, -1, 1, 7,8,9}) {
            int check = move;
            int potentialCaptures = 0;
            while (!(
                (check%8 == 0 && (dir+16)%8 == 7) ||
                (check%8 == 7 && (dir+16)%8 == 1) ||
                (check < 8 && dir < -4) ||
                (check >= 56 && dir > 4)
            )) {
                check += dir;
                if (board[check] == toMove) {
                    captures += potentialCaptures;
                    break;
                } else if (board[check] == -1) {
                    break;
                }
                captureBuf[captures + potentialCaptures] = check;
                potentialCaptures++;
            }
        }
        return captures;
    }

    @Override
    public int legalMoves(int[] out) {
        if (winner != NONE) return 0;
        int n = 0;
        for (int c = 0; c < CELLS; c++) { 
            if ((board[c] == EMPTY) && (findCaptures(captureBuf, c) > 0)) {
                out[n++] = c;
            };
        }
        return n;
    }

    @Override
    public void makeMove(int m) {
        assert winner == NONE && board[m] == EMPTY : "illegal move (position) " + m + "\n" + this;
        int captureCount = findCaptures(captureBuf, m);
        assert captureCount > 0 : "illegal move (no captures) " + m + "\n" + this;
        board[m] = toMove;
        hash ^= Z_PIECE[toMove][m] ^ Z_SIDE;
        for (int i=0; i<captureCount; i++) {
            board[captureBuf[i]] = toMove;
            hash ^= Z_PIECE[2][m];
        }
        moveStack[ply] = m;
        winnerStack[ply] = winner;
        captureCountStack[ply] = captureCount;
        captureStack[ply] = captureBuf.clone();
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
        for (int i=0; i<captureCountStack[ply]; i++) {
            board[captureStack[ply][i]] = 1 - toMove;
            hash ^= Z_PIECE[2][m];
        }
        winner = winnerStack[ply];
    }

    /** Called right after `lastMove` was placed: only lines through it can have just been completed. */
    private int computeWinner(int lastMove) {
        if (ply < CELLS-4) {
            for (int i=0; i<CELLS; i++) {
                if (board[i] == toMove) {
                    return NONE;
                }
            }
            return 1 - toMove; //Win by claiming all opponent's cells
        }
        int[] scores = new int[2];
        for (int i=0; i<CELLS; i++) {
            scores[board[i]] += 1;
        }
        return (scores[0] > scores[1]) ? 0 : ((scores[1] > scores[0]) ? 1 : DRAW);
    }

    @Override public int winner() { return winner; }
    @Override public int maxMoves() { return CELLS; }
    @Override public int moveIdBound() { return CELLS; }
    @Override public GameState copy() { return new Othello(this); }
    @Override public long hash() { return hash; }

    /** Centre first, then top */
    @Override
    public int orderHint(int move) {
        return (findCaptures(captureBuf, move));
    }

    @Override
    public int evaluate() {
        int[] scores = new int[2];
        for (int i=0; i<CELLS; i++) {
            int color = board[i];
            if (color >= 0) {
                scores[color] += 1;
            }
        }
        return scores[toMove] - scores[1 - toMove];
    }

    @Override public String moveToString(int move) { return Integer.toString(move); }

    @Override
    public int parseMove(String text) {
        text = text.trim();
        int m;
        try {
            m = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (m < 0 || m >= CELLS || board[m] != EMPTY || winner != NONE || findCaptures(captureBuf, m) == 0) return -1;
        return m;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int cell = r * 8 + c;
                int v = board[cell];
                if (v == EMPTY) {
                    if (cell < 10) {sb.append(' ');}
                    sb.append(cell).append(' ');
                } else {
                    sb.append(SYMBOL[v]).append(SYMBOL[v]).append(SYMBOL[v]);
                }
                sb.append("  ");
            }
            sb.append("   ");
            for (int c = 0; c < 8; c++) {
            }
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
        Othello game = new Othello();
        System.out.println(game + "\n");
        while (!game.isTerminal()) {
            int m = (game.toMove == 0 ? engine1 : engine2).search(game, 1000).bestMove();
            requireLegal(game, m);
            System.out.println(SYMBOL[game.currentPlayer()] + " plays " + game.moveToString(m));
            game.makeMove(m);
            System.out.println(game + "\n");
        }

        int games = 20;
        int xWins = 0, oWins = 0, draws = 0;
        for (int i = 0; i < games; i++) {
            Othello g = new Othello();
            while (!g.isTerminal()) {
                int m = (g.toMove == 0 ? engine1 : engine2).search(g, 1000).bestMove();
                requireLegal(g, m);
                g.makeMove(m);
            }
            if (g.winner() == 0) xWins++;
            else if (g.winner() == 1) oWins++;
            else draws++;
        }
        System.out.printf("=== %,d games ===%n", games);
        System.out.printf("Y wins %.1f%%   R wins %.1f%%   draws %.1f%%%n",
                100.0 * xWins / games, 100.0 * oWins / games, 100.0 * draws / games);
    }

    private static void requireLegal(GameState s, int move) {
        int[] buf = new int[s.maxMoves()];
        int n = s.legalMoves(buf);
        for (int i = 0; i < n; i++) if (buf[i] == move) return;
        throw new IllegalStateException("Engine returned illegal move " + move + " in position:\n" + s);
    }
}
