package engine;

import games.TicTacToe;

public class AlphaBetaEngine implements Engine {
    static final int INF = 1_000_000;      // bigger than any real score
    static final int WIN = 100_000;        // score of a win found at the root; evaluate() stays far below
    static final int MAX_PLY = 128; //max depth

    final TranspositionTable tt = new TranspositionTable(22);

    long nodes; //total nodes searched
    int[][] moveBuf; //move buffer (avoids allocating lots of arrays)

    int rootBestMove; //best move from root position (actual move to suggest)

    long deadline; //time at which calculation needs to conclude
    boolean stopped; //if the search should return

    //Check order optimization
    int[] killers0 = new int[MAX_PLY], killers1 = new int[MAX_PLY];
    int[][] history = new int[2][moveIdBound];      // halve all entries between searches,
                                                    // and whenever one passes 1 << 28 (avoids int overflow)

    public String name() {
        return "Alpha-Beta Engine";
    }

    int terminalScore(GameState s, int winner, int ply) {
        if (winner == GameState.DRAW) {return 0;}
        // WIN - ply: prefer FASTER wins and SLOWER losses (see §4.4)
        return winner == s.currentPlayer() ? WIN - ply : -(WIN - ply);
    }

    int moveScore(GameState s, int m, int ttMove, int ply) {
        if (m == ttMove) return Integer.MAX_VALUE;
        int sc = s.orderHint(m) * (1 << 20);               // keep orderHint within about ±1000 so this can't overflow
        if (m == killers0[ply]) sc += 1 << 19;
        else if (m == killers1[ply]) sc += 1 << 18;
        return sc + Math.min(history[s.currentPlayer()][m], (1 << 18) - 1);
    }

    //Original function which alphabeta is based off
    int negamax(GameState s, int depth, int ply) {
        //System.out.println(s.toString());
        nodes++;
        int w = s.winner();
        if (w != GameState.NONE) return terminalScore(s, w, ply);
        if (depth == 0) return s.evaluate();

        int[] moves = moveBuf[ply];
        int n = s.legalMoves(moves);
        int best = -INF;
        for (int i = 0; i < n; i++) {
            s.makeMove(moves[i]);
            int score = -negamax(s, depth - 1, ply + 1);   // assumes turns alternate; see §4.8
            s.undoMove();
            if (score > best) {
                best = score;
                if (ply == 0) {rootBestMove = moves[i];};   // at ply 0 also remember moves[i]
            }
        }
        return best;
    }
    
    static int toTT(int s, int ply)   { return s >  WIN - MAX_PLY ? s + ply : s < -(WIN - MAX_PLY) ? s - ply : s; }
    static int fromTT(int s, int ply) { return s >  WIN - MAX_PLY ? s - ply : s < -(WIN - MAX_PLY) ? s + ply : s; }

    //ply = current move index (0 is move to be solved for)
    int alphaBeta(GameState s, int depth, int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0 && System.nanoTime() > deadline) stopped = true; if (stopped) return 0;

        //Check if search should end
        int w = s.winner();
        if (w != GameState.NONE) return terminalScore(s, w, ply);
        if (depth == 0) return s.evaluate();

        //Check if TranspositionTable has answer
        int alphaOrig = alpha;
        long h = s.hash();
        int ttMove = -1;
        if (h != 0) {
            long e = tt.probe(h);
            if (e != 0) {
                ttMove = TranspositionTable.move(e);
                if (ply > 0 && TranspositionTable.depth(e) >= depth) {      // never cut at the root: we need a move there
                    int sc = fromTT(TranspositionTable.score(e), ply);
                    int f = TranspositionTable.flag(e);
                    if (f == TranspositionTable.EXACT) return sc;
                    if (f == TranspositionTable.LOWER && sc >= beta) return sc;
                    if (f == TranspositionTable.UPPER && sc <= alpha) return sc;
                }
            }
        }

        //Check child positions, evaluate
        int[] moves = moveBuf[ply];
        int n = s.legalMoves(moves);
        int best = -INF;
        int bestMove = moves[0];
        for (int i = 0; i < n; i++) {
            s.makeMove(moves[i]);
            int score = -alphaBeta(s, depth - 1, -beta, -alpha, ply + 1);
            s.undoMove();
            if (score > best) {
                best = score;
                bestMove = moves[i];
            }
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;                      // cutoff
        }
        if (ply == 0) {rootBestMove = bestMove;};
        
        // Update best TT move if not yet set.
        if (!stopped && h != 0) {
            int flag = best <= alphaOrig ? TranspositionTable.UPPER : best >= beta ? TranspositionTable.LOWER : TranspositionTable.EXACT;
            tt.store(h, TranspositionTable.pack(bestMove, toTT(best, ply), depth, flag));
        }
        return best;
    }
    // root call: alphaBeta(state, depth, -INF, +INF, 0)

    public SearchResult search(GameState root, long millis) {
        if (moveBuf == null) {
            moveBuf = new int[MAX_PLY][root.maxMoves()];
        }
        //int bestScore = alphaBeta(root, 100, -INF, INF, 0);
        //return new SearchResult(rootBestMove, bestScore, 100, nodes, "Alpha-Beta");
        
        long start = System.nanoTime();
        deadline = start + millis * 1_000_000L;
        stopped = false; nodes = 0;
        int n = root.legalMoves(moveBuf[0]);
        int bestMove = moveBuf[0][0], bestScore = 0, doneDepth = 0;
        if (n == 1) return new SearchResult(bestMove, 0, 0, 0, "only move");
        for (int depth = 1; depth < MAX_PLY; depth++) {
            int score = alphaBeta(root, depth, -INF, INF, 0);
            if (stopped) break;                            // incomplete iteration: ignore it
            bestMove = rootBestMove; bestScore = score; doneDepth = depth;
            if (Math.abs(score) >= WIN - MAX_PLY) break;   // proven win/loss: deeper won't change it
            long elapsed = System.nanoTime() - start;
            if (elapsed > (deadline - start) / 2) break;   // next depth would not finish anyway
        }
        return new SearchResult(bestMove, bestScore, doneDepth, nodes, "");

    }

    public static void main(String[] args) {
        System.out.println("testing alphabeta from alphabeta");
        AlphaBetaEngine engine = new AlphaBetaEngine();
        TicTacToe tictactoe = new TicTacToe();
        engine.moveBuf = new int[MAX_PLY][tictactoe.maxMoves()];
        int bestScore = engine.alphaBeta(tictactoe, 100, -INF, INF, 0);
        System.out.println(tictactoe);
        System.out.println("Move: " + engine.rootBestMove + " score: " + bestScore + ", nodes: " + engine.nodes);
    }
}
