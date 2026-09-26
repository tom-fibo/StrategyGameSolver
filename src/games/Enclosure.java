package games;

import engine.AlphaBetaEngine;
import engine.Engine;
import engine.GameState;
import engine.ManualEngine;
import engine.MctsEngine;
import engine.SearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enclosure (tournament game).
 *
 * Board: 19x19 lattice points (x, y), 0..18. Players: 0 = Blue (moves first), 1 = Red.
 * Start: Blue edge (0,9)-(3,9), Red edge (15,9)-(18,9).
 * Turns: Blue 1 move, then Red 2, Blue 2, ... 120 moves in total (the last is a single Blue move).
 * A move: from an own node, draw an edge at Chebyshev distance 1..3 to a new node or to another own node
 *  (not already joined by an edge).
 *  - A new node may not lie on an own edge; the edge may not pass through an own node.
 *    (Own edges may cross elsewhere, including at grid points that are not nodes.)
 *  - Opposing things hit by the move are removed: a node the new node lands on or the edge passes through
 *    (together with its attached edges), or an edge the new node lands on or the new edge touches/crosses.
 *    A node left with no edges after a removal is removed too.
 *  - Illegal: hitting 2+ things, removing more than one edge (so a node with 2+ edges can't be hit),
 *    or touching an edge the opponent created in their previous turn (invincible), or a node attached to one.
 * Scoring: after every turn (61 times), each player gains the geometric area enclosed by their own edges.
 * Higher total after move 120 wins.
 *
 * Move encoding: from * 361 + to (cells are y * 19 + x); PASS only when no other move exists.
 * Text: "x1,y1>x2,y2" (any separators are accepted when typing, e.g. "3 9 5 12"), or "pass".
 */
public class Enclosure extends GameState {
    public static final int N = 19;
    private static final int CELLS = N * N;
    private static final int S = N - 1;               // unit squares per side
    private static final int REACH = 3;               // max Chebyshev length of an edge
    public static final int TOTAL_MOVES = 120;
    private static final int MAX_EDGES = 2 + TOTAL_MOVES;
    private static final int MAX_NODES = 2 + TOTAL_MOVES / 2;
    public static final int PASS = CELLS * CELLS;
    private static final int EMPTY = -1;
    private static final char[] SYMBOL = {'B', 'R'};
    private static final String[] NAME = {"Blue", "Red"};
    private static final double EPS = 1e-9;

    /** The 48 offsets within Chebyshev distance 3. */
    private static final int[] DX = new int[48], DY = new int[48];

    // Zobrist keys (read-only after init, safe to share between threads)
    private static final long[][] Z_NODE = new long[2][CELLS];
    private static final long[][] Z_EDGE = new long[2][CELLS * 49];
    private static final long[] Z_MOVE = new long[TOTAL_MOVES + 1];

    /** REMAINING_ENDS[k] = number of scoring events (turn ends) from move k onward. */
    private static final int[] REMAINING_ENDS = new int[TOTAL_MOVES + 1];

    static {
        int k = 0;
        for (int dy = -REACH; dy <= REACH; dy++)
            for (int dx = -REACH; dx <= REACH; dx++)
                if (dx != 0 || dy != 0) { DX[k] = dx; DY[k] = dy; k++; }
        Random r = new Random(20260926);
        for (int p = 0; p < 2; p++) {
            for (int i = 0; i < CELLS; i++) Z_NODE[p][i] = r.nextLong();
            for (int i = 0; i < CELLS * 49; i++) Z_EDGE[p][i] = r.nextLong();
        }
        for (int i = 0; i <= TOTAL_MOVES; i++) Z_MOVE[i] = r.nextLong();
        for (int m = TOTAL_MOVES - 1; m >= 0; m--) REMAINING_ENDS[m] = REMAINING_ENDS[m + 1] + (endsTurn(m) ? 1 : 0);
    }

    /** Player making move number k (0-based). */
    static int playerOf(int k) { return k == 0 ? 0 : ((k - 1) / 2) % 2 == 0 ? 1 : 0; }
    /** First move number of the turn containing move k. */
    static int turnStart(int k) { return k == 0 ? 0 : 1 + 2 * ((k - 1) / 2); }
    /** True if move k is the last move of its turn (scoring happens after it). */
    static boolean endsTurn(int k) { return k == TOTAL_MOVES - 1 || k % 2 == 0; }

    // ---- Position ----
    private final int[] nodeOwner = new int[CELLS];            // EMPTY, 0 or 1
    private final int[] degree = new int[CELLS];               // alive edges attached to the node at this point
    private final int[][] onEdge = new int[2][CELLS];          // alive edges of player whose INTERIOR passes this point
    private final int[] edgeA = new int[MAX_EDGES], edgeB = new int[MAX_EDGES];
    private final int[] ex1 = new int[MAX_EDGES], ey1 = new int[MAX_EDGES], ex2 = new int[MAX_EDGES], ey2 = new int[MAX_EDGES];
    private final int[] edgeOwner = new int[MAX_EDGES], edgeBirth = new int[MAX_EDGES];
    private final boolean[] edgeAlive = new boolean[MAX_EDGES];
    private int edgeCount;
    private int moveCount;
    private final double[] score = new double[2];
    private final double[] area = new double[2];
    private int winner = NONE;
    private long hash;

    // ---- Undo stack (one entry per move) ----
    private final int[] uMove = new int[TOTAL_MOVES];
    private final int[] uRemovedNode = new int[TOTAL_MOVES], uRemovedEdge = new int[TOTAL_MOVES];
    private final int[][] uOrphans = new int[TOTAL_MOVES][2];      // nodes removed for having no edges left (-1 = none)
    private final boolean[] uNewNode = new boolean[TOTAL_MOVES];   // did the move create its end node?
    private final double[][] uScore = new double[TOTAL_MOVES][2], uArea = new double[TOTAL_MOVES][2];
    private final long[] uHash = new long[TOTAL_MOVES];

    // ---- Scratch (per instance, so each thread's copy has its own) ----
    private int hitNode, hitEdge;          // results of analyze()
    private boolean crossesOwn;
    private final int[] nodeBuf = new int[CELLS];
    private final int[] nearEdges = new int[MAX_EDGES];

    public Enclosure() {
        Arrays.fill(nodeOwner, EMPTY);
        hash = Z_MOVE[0];
        addNode(0, cell(0, 9));
        addNode(0, cell(3, 9));
        addEdge(cell(0, 9), cell(3, 9), 0, -1);
        addNode(1, cell(15, 9));
        addNode(1, cell(18, 9));
        addEdge(cell(15, 9), cell(18, 9), 1, -1);
    }

    private Enclosure(Enclosure o) {
        System.arraycopy(o.nodeOwner, 0, nodeOwner, 0, CELLS);
        System.arraycopy(o.degree, 0, degree, 0, CELLS);
        for (int p = 0; p < 2; p++) System.arraycopy(o.onEdge[p], 0, onEdge[p], 0, CELLS);
        System.arraycopy(o.edgeA, 0, edgeA, 0, MAX_EDGES);
        System.arraycopy(o.edgeB, 0, edgeB, 0, MAX_EDGES);
        System.arraycopy(o.ex1, 0, ex1, 0, MAX_EDGES);
        System.arraycopy(o.ey1, 0, ey1, 0, MAX_EDGES);
        System.arraycopy(o.ex2, 0, ex2, 0, MAX_EDGES);
        System.arraycopy(o.ey2, 0, ey2, 0, MAX_EDGES);
        System.arraycopy(o.edgeOwner, 0, edgeOwner, 0, MAX_EDGES);
        System.arraycopy(o.edgeBirth, 0, edgeBirth, 0, MAX_EDGES);
        System.arraycopy(o.edgeAlive, 0, edgeAlive, 0, MAX_EDGES);
        edgeCount = o.edgeCount;
        moveCount = o.moveCount;
        score[0] = o.score[0]; score[1] = o.score[1];
        area[0] = o.area[0]; area[1] = o.area[1];
        winner = o.winner;
        hash = o.hash;
        System.arraycopy(o.uMove, 0, uMove, 0, TOTAL_MOVES);
        System.arraycopy(o.uRemovedNode, 0, uRemovedNode, 0, TOTAL_MOVES);
        System.arraycopy(o.uRemovedEdge, 0, uRemovedEdge, 0, TOTAL_MOVES);
        for (int i = 0; i < TOTAL_MOVES; i++) { uOrphans[i][0] = o.uOrphans[i][0]; uOrphans[i][1] = o.uOrphans[i][1]; }
        System.arraycopy(o.uNewNode, 0, uNewNode, 0, TOTAL_MOVES);
        System.arraycopy(o.uHash, 0, uHash, 0, TOTAL_MOVES);
        for (int i = 0; i < TOTAL_MOVES; i++) {
            uScore[i][0] = o.uScore[i][0]; uScore[i][1] = o.uScore[i][1];
            uArea[i][0] = o.uArea[i][0]; uArea[i][1] = o.uArea[i][1];
        }
    }

    static int cell(int x, int y) { return y * N + x; }

    // ------------------------------------------------------------------------------------------
    // Board edits (hash kept in sync)
    // ------------------------------------------------------------------------------------------

    private void addNode(int p, int c) {
        nodeOwner[c] = p;
        hash ^= Z_NODE[p][c];
    }

    private void removeNode(int c) {
        hash ^= Z_NODE[nodeOwner[c]][c];
        nodeOwner[c] = EMPTY;
    }

    private void addEdge(int a, int b, int p, int birth) {
        int e = edgeCount++;
        edgeA[e] = a; edgeB[e] = b; edgeOwner[e] = p; edgeBirth[e] = birth;
        ex1[e] = a % N; ey1[e] = a / N; ex2[e] = b % N; ey2[e] = b / N;
        setEdgeAlive(e, true);
    }

    private void setEdgeAlive(int e, boolean alive) {
        edgeAlive[e] = alive;
        int d = alive ? 1 : -1;
        int a = edgeA[e], b = edgeB[e], p = edgeOwner[e];
        degree[a] += d;
        degree[b] += d;
        int ax = a % N, ay = a / N, bx = b % N, by = b / N;
        int g = gcd(Math.abs(bx - ax), Math.abs(by - ay));
        int sx = (bx - ax) / g, sy = (by - ay) / g;
        for (int k = 1; k < g; k++) onEdge[p][cell(ax + k * sx, ay + k * sy)] += d;
        hash ^= edgeKey(p, a, b);
    }

    private static long edgeKey(int p, int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        int dx = hi % N - lo % N, dy = hi / N - lo / N;   // dy in 0..3, dx in -3..3
        return Z_EDGE[p][lo * 49 + (dy + 3) * 7 + (dx + 3)];
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a;
    }

    /** Edge e is invincible during move k if its owner created it in the turn right before the current one. */
    private boolean isProtected(int e) {
        int t = turnStart(moveCount);
        if (t == 0) return false;
        int prevStart = turnStart(t - 1);
        return edgeBirth[e] >= prevStart && edgeBirth[e] <= t - 1;
    }

    // ------------------------------------------------------------------------------------------
    // Move legality: analyze() checks one candidate edge and records what it would remove
    // ------------------------------------------------------------------------------------------

    /**
     * Is "player p draws from -> to" legal? (Caller guarantees: nodeOwner[from] == p, `to` on the board,
     * Chebyshev distance 1..3.) On success sets hitNode / hitEdge (what gets removed, -1 if nothing)
     * and crossesOwn (the new edge touches own edges, so the enclosed area may change).
     */
    private boolean analyze(int p, int from, int to) {
        return analyze(p, from, to, null, edgeCount);
    }

    /** As above, but only checks the edges listed in cand[0..candCount) (all edges if cand == null). */
    private boolean analyze(int p, int from, int to, int[] cand, int candCount) {
        if (onEdge[p][to] > 0) return false;           // a new node may not lie on an own edge
        int opp = 1 - p;
        int x1 = from % N, y1 = from / N, x2 = to % N, y2 = to / N;
        int dx = x2 - x1, dy = y2 - y1;
        int g = gcd(Math.abs(dx), Math.abs(dy));
        int sx = dx / g, sy = dy / g;
        hitNode = -1;
        hitEdge = -1;
        crossesOwn = false;
        for (int k = 1; k <= g; k++) {                  // grid points on the new edge after `from`
            int c = cell(x1 + k * sx, y1 + k * sy);
            if (nodeOwner[c] == p && k < g) return false;   // through an own node (ending on one is fine)
            if (nodeOwner[c] == opp) {
                if (hitNode >= 0) return false;         // two opposing nodes
                hitNode = c;
            }
        }
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        for (int ci = 0; ci < candCount; ci++) {
            int e = cand == null ? ci : cand[ci];
            if (!edgeAlive[e]) continue;
            int ax = ex1[e], ay = ey1[e], bx = ex2[e], by = ey2[e];
            if (Math.max(ax, bx) < minX || Math.min(ax, bx) > maxX || Math.max(ay, by) < minY || Math.min(ay, by) > maxY) continue;
            int a = edgeA[e], b = edgeB[e];
            if (!segmentsIntersect(x1, y1, x2, y2, ax, ay, bx, by)) continue;
            if (edgeOwner[e] == p) {
                if ((a == from && b == to) || (a == to && b == from)) return false;   // already joined
                if (a != from && b != from) crossesOwn = true;   // edges sharing `from` only meet there
            } else {
                if (hitNode >= 0 && (a == hitNode || b == hitNode)) continue;   // removed with that node anyway
                if (hitEdge >= 0) return false;         // two opposing edges
                hitEdge = e;
            }
        }
        if (hitNode >= 0) {
            if (hitEdge >= 0) return false;             // a node AND a separate edge: two removals
            int d = degree[hitNode];
            if (d >= 2) return false;                   // would remove 2+ edges
            if (d == 1) hitEdge = attachedEdge(hitNode);
        }
        return hitEdge < 0 || !isProtected(hitEdge);
    }

    private int attachedEdge(int c) {
        for (int e = 0; e < edgeCount; e++) if (edgeAlive[e] && (edgeA[e] == c || edgeB[e] == c)) return e;
        throw new IllegalStateException("degree/edge mismatch at " + c);
    }

    private static int orient(int ax, int ay, int bx, int by, int cx, int cy) {
        return Integer.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
    }

    private static boolean onSegment(int ax, int ay, int bx, int by, int cx, int cy) {
        return Math.min(ax, bx) <= cx && cx <= Math.max(ax, bx) && Math.min(ay, by) <= cy && cy <= Math.max(ay, by);
    }

    /** Closed segments p1p2 and q1q2 share at least one point (exact integer arithmetic). */
    static boolean segmentsIntersect(int p1x, int p1y, int p2x, int p2y, int q1x, int q1y, int q2x, int q2y) {
        int o1 = orient(p1x, p1y, p2x, p2y, q1x, q1y), o2 = orient(p1x, p1y, p2x, p2y, q2x, q2y);
        int o3 = orient(q1x, q1y, q2x, q2y, p1x, p1y), o4 = orient(q1x, q1y, q2x, q2y, p2x, p2y);
        if (o1 != o2 && o3 != o4 && o1 * o2 <= 0 && o3 * o4 <= 0) return true;
        if (o1 == 0 && onSegment(p1x, p1y, p2x, p2y, q1x, q1y)) return true;
        if (o2 == 0 && onSegment(p1x, p1y, p2x, p2y, q2x, q2y)) return true;
        if (o3 == 0 && onSegment(q1x, q1y, q2x, q2y, p1x, p1y)) return true;
        if (o4 == 0 && onSegment(q1x, q1y, q2x, q2y, p2x, p2y)) return true;
        return false;
    }

    private boolean legal(int p, int move) {
        if (move < 0 || move >= PASS) return false;
        int from = move / CELLS, to = move % CELLS;
        if (nodeOwner[from] != p) return false;
        int dx = Math.abs(to % N - from % N), dy = Math.abs(to / N - from / N);
        int cheb = Math.max(dx, dy);
        if (cheb < 1 || cheb > REACH) return false;
        return analyze(p, from, to);
    }

    // ------------------------------------------------------------------------------------------
    // GameState
    // ------------------------------------------------------------------------------------------

    @Override public int currentPlayer() { return playerOf(moveCount); }
    @Override public int currentHalfMoveNumber() { return moveCount; }
    @Override public int winner() { return winner; }
    @Override public int maxMoves() { return MAX_NODES * 48 + 1; }
    @Override public int moveIdBound() { return PASS + 1; }
    @Override public GameState copy() { return new Enclosure(this); }

    @Override
    public long hash() {
        // Positions with the same board but different running scores are different positions.
        long s = Double.doubleToLongBits(score[0]) * 0x9E3779B97F4A7C15L ^ Double.doubleToLongBits(score[1]);
        s ^= s >>> 29; s *= 0xBF58476D1CE4E5B9L; s ^= s >>> 32;
        return hash ^ s;
    }

    @Override
    public int legalMoves(int[] out) {
        if (winner != NONE) return 0;
        int p = currentPlayer();
        int n = 0;
        for (int from = 0; from < CELLS; from++) {
            if (nodeOwner[from] != p) continue;
            int fx = from % N, fy = from / N;
            // Only edges near this node can touch any of its 48 candidate edges.
            int near = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (!edgeAlive[e]) continue;
                if (Math.max(ex1[e], ex2[e]) < fx - REACH || Math.min(ex1[e], ex2[e]) > fx + REACH
                        || Math.max(ey1[e], ey2[e]) < fy - REACH || Math.min(ey1[e], ey2[e]) > fy + REACH) continue;
                nearEdges[near++] = e;
            }
            for (int d = 0; d < 48; d++) {
                int tx = fx + DX[d], ty = fy + DY[d];
                if (tx < 0 || ty < 0 || tx >= N || ty >= N) continue;
                int to = cell(tx, ty);
                if (analyze(p, from, to, nearEdges, near)) out[n++] = from * CELLS + to;
            }
        }
        if (n == 0) out[n++] = PASS;
        return n;
    }

    /** Rejection sampling: random own node + random offset until legal (much cheaper than listing all moves). */
    @Override
    public int randomMove(SplittableRandom rng, int[] buf) {
        int p = currentPlayer();
        int cnt = 0;
        for (int c = 0; c < CELLS; c++) if (nodeOwner[c] == p) nodeBuf[cnt++] = c;
        for (int tries = 0; tries < 200 && cnt > 0; tries++) {
            int from = nodeBuf[rng.nextInt(cnt)];
            int d = rng.nextInt(48);
            int tx = from % N + DX[d], ty = from / N + DY[d];
            if (tx < 0 || ty < 0 || tx >= N || ty >= N) continue;
            int to = cell(tx, ty);
            if (analyze(p, from, to)) return from * CELLS + to;
        }
        return super.randomMove(rng, buf);   // nearly stuck: enumerate everything (may return PASS)
    }

    @Override
    public void makeMove(int move) {
        assert winner == NONE : "game is over";
        int p = currentPlayer(), opp = 1 - p;
        int k = moveCount;
        uMove[k] = move;
        uHash[k] = hash;
        uScore[k][0] = score[0]; uScore[k][1] = score[1];
        uArea[k][0] = area[0]; uArea[k][1] = area[1];
        uRemovedNode[k] = -1;
        uRemovedEdge[k] = -1;
        uOrphans[k][0] = -1;
        uOrphans[k][1] = -1;

        if (move != PASS) {
            boolean ok = legal(p, move);
            assert ok : "illegal move " + moveToString(move) + "\n" + this;
            int from = move / CELLS, to = move % CELLS;
            int rNode = hitNode, rEdge = hitEdge;
            boolean own = crossesOwn;
            if (rEdge >= 0) setEdgeAlive(rEdge, false);
            if (rNode >= 0) removeNode(rNode);
            uRemovedNode[k] = rNode;
            uRemovedEdge[k] = rEdge;
            if (rEdge >= 0) {                          // endpoints left without edges disappear
                int orphans = 0;
                for (int c : new int[] {edgeA[rEdge], edgeB[rEdge]}) {
                    if (nodeOwner[c] == opp && degree[c] == 0) {
                        removeNode(c);
                        uOrphans[k][orphans++] = c;
                    }
                }
            }
            uNewNode[k] = nodeOwner[to] != p;          // false when joining two own nodes
            if (uNewNode[k]) addNode(p, to);
            addEdge(from, to, p, k);
            if (own) area[p] = computeArea(p);
            if (rEdge >= 0) area[opp] = computeArea(opp);
        }
        if (endsTurn(k)) {
            score[0] += area[0];
            score[1] += area[1];
        }
        hash ^= Z_MOVE[k] ^ Z_MOVE[k + 1];
        moveCount = k + 1;
        if (moveCount == TOTAL_MOVES) {
            double diff = score[0] - score[1];
            winner = Math.abs(diff) < 1e-6 ? DRAW : diff > 0 ? 0 : 1;
        }
    }

    @Override
    public void undoMove() {
        int k = --moveCount;
        int move = uMove[k];
        int opp = 1 - playerOf(k);
        if (move != PASS) {
            int e = --edgeCount;                       // the edge added by this move is always the last one
            setEdgeAlive(e, false);
            if (uNewNode[k]) removeNode(move % CELLS);
            if (uRemovedNode[k] >= 0) addNode(opp, uRemovedNode[k]);
            for (int c : uOrphans[k]) if (c >= 0) addNode(opp, c);
            if (uRemovedEdge[k] >= 0) setEdgeAlive(uRemovedEdge[k], true);
        }
        score[0] = uScore[k][0]; score[1] = uScore[k][1];
        area[0] = uArea[k][0]; area[1] = uArea[k][1];
        hash = uHash[k];
        winner = NONE;
    }

    // ------------------------------------------------------------------------------------------
    // Enclosed area (exact): split unit squares by the player's edges, flood-fill from outside
    // ------------------------------------------------------------------------------------------

    // Scratch for computeArea (per instance: each thread's copy has its own)
    private final boolean[] blockedH = new boolean[N * S];   // unit edge (i,j)-(i+1,j): index j*S + i
    private final boolean[] blockedV = new boolean[N * S];   // unit edge (i,j)-(i,j+1): index i*S + j
    private final int[][] chords = new int[S * S][4];
    private final int[] chordCount = new int[S * S];
    private final int[] firstPiece = new int[S * S + 1];
    private int[] pSq = new int[1024];
    private double[] pArea = new double[1024], pLo = new double[4096], pHi = new double[4096];
    private boolean[] reached = new boolean[1024];
    private int[] queue = new int[1024];
    private int pieceCount;

    /**
     * Every edge runs between grid points, so inside a unit square each crossing edge is a full chord.
     * The chords cut the square into convex pieces. Pieces in neighbouring squares connect where their
     * boundaries overlap on the shared side (with positive length) and that side is not covered by an
     * axis-aligned edge. Area = total - area reachable from outside the board.
     */
    double computeArea(int p) {
        Arrays.fill(blockedH, false);
        Arrays.fill(blockedV, false);
        Arrays.fill(chordCount, 0);
        for (int e = 0; e < edgeCount; e++) {
            if (!edgeAlive[e] || edgeOwner[e] != p) continue;
            int ax = ex1[e], ay = ey1[e], bx = ex2[e], by = ey2[e];
            if (ay == by) {
                for (int i = Math.min(ax, bx); i < Math.max(ax, bx); i++) blockedH[ay * S + i] = true;
            } else if (ax == bx) {
                for (int j = Math.min(ay, by); j < Math.max(ay, by); j++) blockedV[ax * S + j] = true;
            } else {
                for (int j = Math.min(ay, by); j < Math.max(ay, by); j++) {
                    for (int i = Math.min(ax, bx); i < Math.max(ax, bx); i++) {
                        if (!crossesSquareInterior(ax, ay, bx, by, i, j)) continue;
                        int sq = j * S + i;
                        if (chordCount[sq] == chords[sq].length) chords[sq] = Arrays.copyOf(chords[sq], chordCount[sq] * 2);
                        chords[sq][chordCount[sq]++] = e;
                    }
                }
            }
        }

        // Pieces: side intervals lo/hi per side (0=bottom, 1=top: x range; 2=left, 3=right: y range)
        pieceCount = 0;
        for (int sq = 0; sq < S * S; sq++) {
            firstPiece[sq] = pieceCount;
            int i = sq % S, j = sq / S;
            if (chordCount[sq] == 0) {
                int q = newPiece(sq, 1.0);
                pLo[4 * q] = i; pHi[4 * q] = i + 1;
                pLo[4 * q + 1] = i; pHi[4 * q + 1] = i + 1;
                pLo[4 * q + 2] = j; pHi[4 * q + 2] = j + 1;
                pLo[4 * q + 3] = j; pHi[4 * q + 3] = j + 1;
                continue;
            }
            List<double[][]> cur = new ArrayList<>();
            cur.add(new double[][] {{i, i + 1, i + 1, i}, {j, j, j + 1, j + 1}});
            for (int k = 0; k < chordCount[sq]; k++) {
                int e = chords[sq][k];
                List<double[][]> next = new ArrayList<>();
                for (double[][] poly : cur) splitPolygon(poly, ex1[e], ey1[e], ex2[e], ey2[e], next);
                cur = next;
            }
            for (double[][] poly : cur) {
                int q = newPiece(sq, polygonArea(poly));
                for (int s = 0; s < 4; s++) { pLo[4 * q + s] = Double.POSITIVE_INFINITY; pHi[4 * q + s] = Double.NEGATIVE_INFINITY; }
                for (int v = 0; v < poly[0].length; v++) {
                    double x = poly[0][v], y = poly[1][v];
                    if (Math.abs(y - j) < EPS) extend(q, 0, x);
                    if (Math.abs(y - (j + 1)) < EPS) extend(q, 1, x);
                    if (Math.abs(x - i) < EPS) extend(q, 2, y);
                    if (Math.abs(x - (i + 1)) < EPS) extend(q, 3, y);
                }
            }
        }
        firstPiece[S * S] = pieceCount;

        // Flood fill from outside the board
        int n = pieceCount;
        Arrays.fill(reached, 0, n, false);
        int head = 0, tail = 0;
        for (int q = 0; q < n; q++) {
            int sq = pSq[q], i = sq % S, j = sq / S;
            boolean out = (j == 0 && !blockedH[i] && len(q, 0) > EPS)
                    || (j == S - 1 && !blockedH[S * S + i] && len(q, 1) > EPS)
                    || (i == 0 && !blockedV[j] && len(q, 2) > EPS)
                    || (i == S - 1 && !blockedV[S * S + j] && len(q, 3) > EPS);
            if (out) { reached[q] = true; queue[tail++] = q; }
        }
        while (head < tail) {
            int q = queue[head++];
            int sq = pSq[q], i = sq % S, j = sq / S;
            for (int side = 0; side < 4; side++) {
                if (len(q, side) <= EPS) continue;
                int ni = i, nj = j, nside;
                boolean blocked;
                switch (side) {
                    case 0 -> { nj = j - 1; nside = 1; blocked = blockedH[j * S + i]; }
                    case 1 -> { nj = j + 1; nside = 0; blocked = blockedH[(j + 1) * S + i]; }
                    case 2 -> { ni = i - 1; nside = 3; blocked = blockedV[i * S + j]; }
                    default -> { ni = i + 1; nside = 2; blocked = blockedV[(i + 1) * S + j]; }
                }
                if (blocked || ni < 0 || nj < 0 || ni >= S || nj >= S) continue;
                int nsq = nj * S + ni;
                for (int r = firstPiece[nsq]; r < firstPiece[nsq + 1]; r++) {
                    if (reached[r]) continue;
                    double overlap = Math.min(pHi[4 * q + side], pHi[4 * r + nside]) - Math.max(pLo[4 * q + side], pLo[4 * r + nside]);
                    if (overlap > EPS) { reached[r] = true; queue[tail++] = r; }
                }
            }
        }
        double enclosed = 0;
        for (int q = 0; q < n; q++) if (!reached[q]) enclosed += pArea[q];
        return enclosed;
    }

    private int newPiece(int sq, double area) {
        if (pieceCount == pSq.length) {
            int cap = pSq.length * 2;
            pSq = Arrays.copyOf(pSq, cap);
            pArea = Arrays.copyOf(pArea, cap);
            pLo = Arrays.copyOf(pLo, 4 * cap);
            pHi = Arrays.copyOf(pHi, 4 * cap);
            reached = Arrays.copyOf(reached, cap);
            queue = Arrays.copyOf(queue, cap);
        }
        pSq[pieceCount] = sq;
        pArea[pieceCount] = area;
        return pieceCount++;
    }

    private void extend(int q, int side, double v) {
        if (v < pLo[4 * q + side]) pLo[4 * q + side] = v;
        if (v > pHi[4 * q + side]) pHi[4 * q + side] = v;
    }

    private double len(int q, int side) { return pHi[4 * q + side] - pLo[4 * q + side]; }

    /** Does the (non axis-aligned) segment pass through the open unit square [i,i+1]x[j,j+1]? */
    private static boolean crossesSquareInterior(int ax, int ay, int bx, int by, int i, int j) {
        double t0 = 0, t1 = 1, dx = bx - ax, dy = by - ay;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - i, i + 1 - ax, ay - j, j + 1 - ay};
        for (int k = 0; k < 4; k++) {
            double t = q[k] / p[k];            // p[k] != 0 because the segment is not axis-aligned
            if (p[k] < 0) { if (t > t0) t0 = t; } else { if (t < t1) t1 = t; }
        }
        return t1 - t0 > EPS;
    }

    /** Split a convex polygon by the line through a-b; adds the non-degenerate parts to `out`. */
    private static void splitPolygon(double[][] poly, double ax, double ay, double bx, double by, List<double[][]> out) {
        int n = poly[0].length;
        double[] side = new double[n];
        boolean pos = false, neg = false;
        for (int v = 0; v < n; v++) {
            side[v] = (bx - ax) * (poly[1][v] - ay) - (by - ay) * (poly[0][v] - ax);
            if (side[v] > EPS) pos = true;
            if (side[v] < -EPS) neg = true;
        }
        if (!pos || !neg) { out.add(poly); return; }
        double[] px = new double[n + 2], py = new double[n + 2], nx = new double[n + 2], ny = new double[n + 2];
        int pc = 0, nc = 0;
        for (int v = 0; v < n; v++) {
            int w = (v + 1) % n;
            double sv = side[v], sw = side[w];
            if (sv >= -EPS) { px[pc] = poly[0][v]; py[pc++] = poly[1][v]; }
            if (sv <= EPS) { nx[nc] = poly[0][v]; ny[nc++] = poly[1][v]; }
            if ((sv > EPS && sw < -EPS) || (sv < -EPS && sw > EPS)) {
                double t = sv / (sv - sw);
                double ix = poly[0][v] + t * (poly[0][w] - poly[0][v]), iy = poly[1][v] + t * (poly[1][w] - poly[1][v]);
                px[pc] = ix; py[pc++] = iy;
                nx[nc] = ix; ny[nc++] = iy;
            }
        }
        out.add(new double[][] {Arrays.copyOf(px, pc), Arrays.copyOf(py, pc)});
        out.add(new double[][] {Arrays.copyOf(nx, nc), Arrays.copyOf(ny, nc)});
    }

    private static double polygonArea(double[][] poly) {
        double a = 0;
        int n = poly[0].length;
        for (int v = 0; v < n; v++) {
            int w = (v + 1) % n;
            a += poly[0][v] * poly[1][w] - poly[0][w] * poly[1][v];
        }
        return Math.abs(a) / 2;
    }

    // ------------------------------------------------------------------------------------------
    // Heuristics
    // ------------------------------------------------------------------------------------------

    /** Projected final score difference (current area kept until the end), from the side to move. */
    @Override
    public int evaluate() {
        int p = currentPlayer();
        int rem = REMAINING_ENDS[moveCount];
        double v = (score[p] + area[p] * rem) - (score[1 - p] + area[1 - p] * rem);
        return (int) Math.round(Math.max(-40000, Math.min(40000, v)));
    }

    /** Moves that may close an enclosure or remove something come first; longer edges before shorter. */
    @Override
    public int orderHint(int move) {
        if (move == PASS) return 0;
        int p = currentPlayer();
        if (!legal(p, move)) return 0;
        int from = move / CELLS, to = move % CELLS;
        int len = Math.max(Math.abs(to % N - from % N), Math.abs(to / N - from / N));
        return (crossesOwn ? 20 : 0) + (hitEdge >= 0 || hitNode >= 0 ? 10 : 0) + len;
    }

    @Override public boolean isNoisy(int move) { return false; }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    public double score(int p) { return score[p]; }
    public double area(int p) { return area[p]; }

    @Override
    public String moveToString(int move) {
        if (move == PASS) return "pass";
        int from = move / CELLS, to = move % CELLS;
        return (from % N) + "," + (from / N) + ">" + (to % N) + "," + (to / N);
    }

    private static final Pattern INT = Pattern.compile("\\d+");

    @Override
    public int parseMove(String text) {
        if (winner != NONE) return -1;
        int p = currentPlayer();
        if (text.trim().equalsIgnoreCase("pass")) {
            int[] buf = new int[maxMoves()];
            return legalMoves(buf) == 1 && buf[0] == PASS ? PASS : -1;
        }
        Matcher m = INT.matcher(text);
        int[] v = new int[4];
        int k = 0;
        while (m.find()) {
            if (k == 4) return -1;
            v[k++] = Integer.parseInt(m.group());
        }
        if (k != 4) return -1;
        for (int x : v) if (x >= N) return -1;
        int move = cell(v[0], v[1]) * CELLS + cell(v[2], v[3]);
        return legal(p, move) ? move : -1;
    }

    @Override
    public String toString() {
        char[][] g = new char[N][N];
        for (char[] row : g) Arrays.fill(row, '.');
        for (int c = 0; c < CELLS; c++) {
            if (onEdge[0][c] > 0) g[c / N][c % N] = 'b';
            if (onEdge[1][c] > 0) g[c / N][c % N] = g[c / N][c % N] == 'b' ? '+' : 'r';
            if (nodeOwner[c] != EMPTY) g[c / N][c % N] = SYMBOL[nodeOwner[c]];
        }
        StringBuilder sb = new StringBuilder("    ");
        for (int x = 0; x < N; x++) sb.append(x % 10).append(' ');
        sb.append("  (x; row = y, y=0 at top)\n");
        for (int y = 0; y < N; y++) {
            sb.append(String.format("%2d  ", y));
            for (int x = 0; x < N; x++) sb.append(g[y][x]).append(' ');
            sb.append('\n');
        }
        for (int p = 0; p < 2; p++) {
            sb.append(String.format("%-4s score %.1f  area %.1f  edges:", NAME[p], score[p], area[p]));
            for (int e = 0; e < edgeCount; e++) {
                if (!edgeAlive[e] || edgeOwner[e] != p) continue;
                sb.append(' ').append(edgeA[e] % N).append(',').append(edgeA[e] / N).append('>')
                  .append(edgeB[e] % N).append(',').append(edgeB[e] / N);
                if (winner == NONE && edgeOwner[e] != currentPlayer() && isProtected(e)) sb.append('*');
            }
            sb.append('\n');
        }
        if (winner == NONE) {
            int k = moveCount;
            int inTurn = k - turnStart(k) + 1, turnLen = (k == 0 || k == TOTAL_MOVES - 1) ? 1 : 2;
            sb.append(String.format("Move %d of %d: %s to move (%d of %d this turn). * = invincible this turn",
                    k + 1, TOTAL_MOVES, NAME[currentPlayer()], inTurn, turnLen));
        } else if (winner == DRAW) {
            sb.append("Game over: draw");
        } else {
            sb.append("Game over: ").append(NAME[winner]).append(" wins");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Self-test / demo
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        // Rules example: Red (15,9)>(15,11), (15,11)>(17,11), later (17,11)>(17,8) encloses a 2x2 square.
        /*Enclosure g = new Enclosure();
          String[] script = {"0,9>0,12", "15,9>15,11", "15,11>17,11", "0,12>0,15", "0,15>0,18", "17,11>17,8"};
        for (String s : script) {
            int m = g.parseMove(s);
            if (m < 0) throw new IllegalStateException("example move rejected: " + s + "\n" + g);
            g.makeMove(m);
        }
        System.out.println(g);
        System.out.printf("Red area after the example: %.2f (expected 4.00) %s%n%n", g.area(1), Math.abs(g.area(1) - 4) < 1e-9 ? "OK" : "FAIL");
        */
        
        
        // Engine vs engine demo
        //Engine blue = new MctsEngine(Runtime.getRuntime().availableProcessors(), 1.0, false, 6, 100);
        //Engine red = new MctsEngine(Runtime.getRuntime().availableProcessors(), 1.0, false, 6, 100);
        
        // You enter the OPPONENT's moves (ManualEngine); the engine plays your side.
        boolean engineFirst;
        while (true) {
            String a = ManualEngine.readLine("Do we go first (Blue) or second (Red)? [1/2]: ").toLowerCase();
            if (a.equals("1") || a.startsWith("f") || a.startsWith("b")) { engineFirst = true; break; }
            if (a.equals("2") || a.startsWith("s") || a.startsWith("r")) { engineFirst = false; break; }
            System.out.println("Please type 1 or 2.");
        }
        Engine engine = new AlphaBetaEngine();
        Engine manual = new ManualEngine();
        Engine blue = engineFirst ? engine : manual;
        Engine red = engineFirst ? manual : engine;
        System.out.println("Engine plays " + (engineFirst ? "Blue" : "Red") + "; type the opponent's moves (e.g. 3,9>5,12).");
        Enclosure game = new Enclosure();
        while (!game.isTerminal()) {
            SearchResult r = (game.currentPlayer() == 0 ? blue : red).search(game, 3000);
            System.out.println(NAME[game.currentPlayer()] + " plays " + game.moveToString(r.bestMove()) + "   " + r.info() + "    [Evaluation: " + r.score() + ", Depth: " + r.depth() + ", Nodes: " + r.nodes() + "]");
            game.makeMove(r.bestMove());
        }
        System.out.println(game);
    }
}
