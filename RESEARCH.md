# Strategy Game Engine: Research and Build Guide

This is preparation for a tournament where the rules of an unknown two-player game are revealed and you get 60 minutes before play starts. The idea is to build a **game-agnostic engine now**. On the day, you then write only one class for the new game (its rules plus a heuristic).

How to use this guide:
- Sections 1–3 explain the ideas and the architecture.
- Sections 4–6 are **staged exercises**. Each stage has pseudocode close to real Java and a **checkpoint**, a concrete test that tells you the stage works.
- Sections 7–10 cover speed, test tools, practice games and the tournament-day playbook.
- Section 12 lists sources.

Contents
1. [The big picture](#1-the-big-picture)
2. [Choosing an algorithm](#2-choosing-an-algorithm)
3. [Architecture: the GameState contract](#3-architecture-the-gamestate-contract)
4. [Alpha-beta track](#4-alpha-beta-track)
5. [MCTS track](#5-mcts-track)
6. [Multithreading from zero](#6-multithreading-from-zero)
7. [Making it fast in Java](#7-making-it-fast-in-java)
8. [Tools to build](#8-tools-to-build)
9. [Practice games](#9-practice-games)
10. [Tournament day](#10-tournament-day)
11. [Suggested schedule](#11-suggested-schedule)
12. [Sources and further reading](#12-sources-and-further-reading)

---

## 1. The big picture

Every position is a node in a **game tree**, and every legal move is an edge to a child. If a position has on average *b* legal moves (the **branching factor**) and you look *d* moves ahead (the **depth**, counted in plies, i.e. single moves by one player), the full tree has about **b^d** leaves.

| Game          | Typical branching factor | Notes 
|---------------|--------------------------|-----------------------------------------------------------
| Tic-tac-toe   | ≤ 9                      | Whole tree is 549,946 nodes, so it can be solved instantly
| Connect Four  | 7                        | Deep (up to 42 plies), solved: the first player wins
| Othello       | ~10                      | Needs a real evaluation
| Checkers      | ~8 (captures forced)     | Solved (a draw)
| Chess         | ~35                      |
| Hex 11×11     | up to 121                | Weak heuristics; MCTS territory
| Go 19×19      | ~250                     | MCTS (plus neural nets) territory
| Amazons 10×10 | ~2,000 at the start      | Huge; the kind of game that makes engines "difficult"

Two families of ideas beat the b^d explosion:

1. **Prune it (minimax + alpha-beta).** Look at every move, but prove early that most branches cannot change the answer. With good move ordering, alpha-beta examines about **b^(d/2)** nodes, which roughly **doubles** the depth you can reach. It needs an **evaluation function** that scores a non-final position.
2. **Sample it (Monte Carlo Tree Search).** Play thousands of fast, mostly random games ("playouts") from the current position and grow a tree towards the moves that win most often. It needs **no evaluation function** and copes with huge branching factors, but it is weaker at sharp tactics.

### What "using an engine will be difficult" probably means
The organizers likely picked a game with one or more of these properties. Each one has a counter:

| Difficulty                                             | Counter
|--------------------------------------------------------|
| Huge branching factor                | MCTS, progressive widening (§5.4), strong move ordering, and splitting compound moves into sub-moves (§4.8) |
| Long games, so random playouts are slow or meaningless | Playout cutoff plus evaluation (§5.3), or alpha-beta |
| No obvious evaluation                                  | MCTS with pure random playouts |
| Complex rules, where the risk is bugs                  | Keep the rules simple and correct; run the Fuzzer (§8.1) |
| Only 60 minutes                                        | Everything except the rules class is prepared **now** |

Even a weak engine is valuable as a **safety net**. It can find immediate wins you missed and check that your planned move doesn't lose in 2–4 plies. Plan to use the engine as an advisor, a centaur-style partnership, not only as an autopilot.

---

## 2. Choosing an algorithm

Build **both** engines behind one `Engine` interface, so the choice becomes a measurement on the day rather than a guess.

| On reveal day you find… | Use |
|-------------------------|-----|
| Branching factor under ~30, you can write a meaningful evaluation in about 10 minutes (material, mobility, distance to goal), and the game is tactical | **Alpha-beta** (§4) |
| Branching factor over ~50, no good evaluation idea, and random play reaches the end of the game quickly (placement or connection games) | **MCTS** (§5) |
| "Sudden death" games where one bad move loses                             | Alpha-beta, or **MCTS-Solver** (§5.2) |
| Random playouts don't end or mean nothing (pieces shuffle back and forth) | MCTS **with playout cutoff + evaluation** (§5.3), or alpha-beta |
| Unsure                                                  | Run `Match` (§8.2): AB vs MCTS, about 1 s per move, 20 games, and keep the winner |

The Fuzzer (§8.1) should print the **average branching factor and game length** under random play. Those two numbers feed this table.

---

## 3. Architecture: the GameState contract

### 3.1 `GameState`, the only class you write on tournament day

```java
package engine;

public abstract class GameState {
    public static final int DRAW = -1;   // winner() when the game ended in a draw
    public static final int NONE = -2;   // winner() while the game is still running

    /** Player to move: 0 or 1. */
    public abstract int currentPlayer();

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

    /** Larger means "search this move earlier" (captures, centre, threats...). */
    public int orderHint(int move) { return 0; }

    /** True for "loud" moves (e.g. captures) that quiescence search should follow. */
    public boolean isNoisy(int move) { return false; }

    public boolean isTerminal() { return winner() != NONE; }
}
```

```java
package engine;

public interface Engine {
    String name();
    /** Think for about `millis` ms on `state` (which must be left unchanged) and return a move. */
    SearchResult search(GameState state, long millis);
}

public record SearchResult(int bestMove, int score, int depth, long nodes, String info) {}
```

### 3.2 Why it looks like this
- **Moves are `int`s.** There is no allocation, they are cheap to compare and store in tables, and they are easy to encode: a cell index, `from * cells + to`, a column number, and so on. Choose the encoding so that `moveIdBound()` stays small, for example ≤ 1 << 16.
- **Mutable state with `makeMove`/`undoMove`, not "return a new state".** Copying a state at every node is the main speed killer in Java, because of allocation and garbage collection. Inside the class, keep an undo stack: a preallocated `int[]` of moves plus whatever else must be restored (captured pieces, previous hash, previous winner, and so on).
- **`winner()` returns NONE, DRAW, 0 or 1**, so one call answers both "is it over?" and "who won?". Compute it **incrementally** in `makeMove`: check only the lines through the last move, store the result in a field, and restore it on undo.
- **Evaluation is from the side to move's point of view.** This is the *negamax* convention: whatever is good for me is equally bad for you, so a single function serves both players. The most common bug in the whole project is an evaluation with the wrong sign. Checkpoint: in a symmetric position `evaluate()` should be about 0.
- **`copy()`** exists so that each thread gets its own board (§6).
- **`moveIdBound()`** lets engines use plain arrays indexed by move, for the history heuristic and for adding up MCTS visit counts across threads.

### 3.3 Suggested layout
```
src/Main.java                  environment check (already there)
src/engine/GameState.java      the contract above
src/engine/Engine.java, SearchResult.java
src/engine/RandomEngine.java   baseline: picks a random legal move
src/engine/AlphaBetaEngine.java
src/engine/MctsEngine.java
src/games/TicTacToe.java, ConnectFour.java, Othello.java, Hex.java, ...
src/games/GameTemplate.java    TODO-annotated skeleton to copy on tournament day
src/tools/Fuzzer.java, Match.java, Advisor.java
```
Run with the VS Code ▷ button (any file with a `main`), or `./run.sh tools.Advisor TicTacToe`.

### 3.4 The make/undo pattern (example for a placement game)
```java
private final int[] board = new int[CELLS];      // -1 empty, else player id
private final int[] moveStack = new int[CELLS];  // enough for the longest game
private final int[] winnerStack = new int[CELLS + 1];
private int ply = 0, toMove = 0, winner = NONE;
private long hash = 0;

public void makeMove(int m) {
    board[m] = toMove;
    hash ^= Z_PIECE[toMove][m] ^ Z_SIDE;          // incremental Zobrist (§4.5)
    moveStack[ply] = m;
    winnerStack[ply] = winner;                     // remember what to restore
    ply++;
    winner = checkWinThrough(m);                  // only lines through m; else full-board check → DRAW / NONE
    toMove = 1 - toMove;
}

public void undoMove() {
    ply--;
    int m = moveStack[ply];
    toMove = 1 - toMove;
    board[m] = -1;
    hash ^= Z_PIECE[toMove][m] ^ Z_SIDE;          // XOR is its own inverse
    winner = winnerStack[ply];
}
```
Board index layout used in all the tic-tac-toe checkpoints below:
```
0 1 2
3 4 5
6 7 8
```

---

## 4. Alpha-beta track

The constants used throughout:
```java
static final int INF = 1_000_000;      // bigger than any real score
static final int WIN = 100_000;        // score of a win found at the root; evaluate() stays far below
static final int MAX_PLY = 128;

int terminalScore(GameState s, int winner, int ply) {
    if (winner == GameState.DRAW) return 0;
    // WIN - ply: prefer FASTER wins and SLOWER losses (see §4.4)
    return winner == s.currentPlayer() ? WIN - ply : -(WIN - ply);
}
```
Give each ply its own move buffer so recursion doesn't overwrite a parent's list: `int[][] moveBuf = new int[MAX_PLY][state.maxMoves()]`.

### Stage 4.1: Negamax (plain minimax), about 15 min
Minimax says: I pick the move that maximizes my score, assuming you then pick the move that minimizes it. **Negamax** is the same thing written once, because `max(a, b) = -min(-a, -b)`: the child's score from your side is the negation of its score from mine.
```java
long nodes;

int negamax(GameState s, int depth, int ply) {
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
        if (score > best) best = score;                // at ply 0 also remember moves[i]
    }
    return best;
}
```
**Checkpoint (tic-tac-toe, depth 9, empty board).** Score = 0 (a draw) and `nodes` = **549,946** exactly, counting every call including the root and stopping at wins. Every one of the 9 first moves scores 0.

### Stage 4.2: Alpha-beta pruning, about 10 min
Carry a window `[alpha, beta]`:
- **alpha** is the score I am already guaranteed elsewhere.
- **beta** is the score my opponent will allow; above it, they would avoid this position earlier.

Once a move scores ≥ beta, the opponent will never let us reach this node, so the remaining moves are skipped (a **cutoff**).
```java
int alphaBeta(GameState s, int depth, int alpha, int beta, int ply) {
    nodes++;
    int w = s.winner();
    if (w != GameState.NONE) return terminalScore(s, w, ply);
    if (depth == 0) return s.evaluate();

    int[] moves = moveBuf[ply];
    int n = s.legalMoves(moves);
    int best = -INF;
    for (int i = 0; i < n; i++) {
        s.makeMove(moves[i]);
        int score = -alphaBeta(s, depth - 1, -beta, -alpha, ply + 1);
        s.undoMove();
        if (score > best) {
            best = score;
            if (ply == 0) rootBestMove = moves[i];
        }
        if (best > alpha) alpha = best;
        if (alpha >= beta) break;                      // cutoff
    }
    return best;
}
// root call: alphaBeta(state, depth, -INF, +INF, 0)
```
**Checkpoints (tic-tac-toe, full depth):**
- Empty board: the score is still 0, and `nodes` = **20,866** when moves are tried in order 0..8, down from 549,946. With a different move order you will see a different count, which is the point of §4.6.
- `XX..O....` with O to move: O must block at **2** (score 0). Every other move scores −99,998.
- `X...O...X` with O to move: O must play an **edge** (1, 3, 5 or 7, score 0). Corners 2 and 6 lose (−99,996). This is a classic trap.
- `XO.......` with X to move: moves **3, 4, 6** win (score 99,995 = WIN − 5). The others draw.
- `X.O.X.O..` with X to move: **8** wins immediately (99,999). Moves 1, 3, 5, 7 also win, but later (99,997). A correct engine must pick 8.
- Engine vs `RandomEngine`, 1000 games: **0 losses**.

### Stage 4.3: Iterative deepening and time control, about 20 min
You never know which depth fits in the time you have. So search depth 1, then 2, then 3, and so on until time runs out, and keep the best move from the last **completed** depth. This costs little, because each depth takes several times longer than the previous one. Combined with §4.5–4.6 it is often *faster* than one direct deep search, because the earlier depths teach the later ones which moves are good.

Abort cleanly. **Don't throw an exception out of the recursion**, because that skips `undoMove()` and corrupts the board. Use a flag instead:
```java
long deadline; boolean stopped;

// first lines of alphaBeta:
if ((++nodes & 1023) == 0 && System.nanoTime() > deadline) stopped = true;
if (stopped) return 0;
// ...and right after each s.undoMove():
if (stopped) return 0;                               // result is garbage; just unwind

SearchResult search(GameState root, long millis) {
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
```
**Checkpoint.** Print `depth, score, nodes, bestMove` after each iteration. The numbers should grow smoothly, and the search should stop within about 10 ms of the time limit.

### Stage 4.4: Win-distance scoring, about 5 min
This is already in `terminalScore`: a win found at ply *p* scores `WIN - p`. Without it, the engine "knows" it is winning but may drift forever without ever finishing the game, because every winning line looks equally good. The `X.O.X.O..` checkpoint tests this.

### Stage 4.5: Zobrist hashing and the transposition table, about 45 min
Different move orders often reach the same position (a **transposition**). A **transposition table (TT)** is a big hash map from position to "what I learned last time I searched it". It saves repeated work, and just as importantly it remembers the **best move** found there, which drives move ordering.

**Zobrist hashing** turns a position into a 64-bit number cheaply:
```java
static final long[][] Z_PIECE = new long[PIECE_KINDS][CELLS];
static final long Z_SIDE;
static {
    java.util.Random r = new java.util.Random(20260924);   // fixed seed = reproducible
    for (long[] row : Z_PIECE) for (int i = 0; i < row.length; i++) row[i] = r.nextLong();
    Z_SIDE = r.nextLong();
}
// hash = XOR of Z_PIECE[kind][cell] for every occupied cell, XOR Z_SIDE if player 1 to move.
// makeMove/undoMove update it by XOR-ing only what changed (see §3.4).
```
Include **everything that affects future play** in the hash: the side to move, and special states such as "must capture again" or pass counters. Otherwise the TT will return answers for the wrong situation.

**The TT**, packed so that the same code also works for multithreading later (§6.5). Each entry is two `long`s:
```java
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
```
Using it inside `alphaBeta`, after the terminal/depth checks:
```java
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
            if (f == EXACT) return sc;
            if (f == LOWER && sc >= beta) return sc;
            if (f == UPPER && sc <= alpha) return sc;
        }
    }
}
// ... search the moves, trying ttMove first (§4.6), tracking bestMoveHere ...
if (!stopped && h != 0) {
    int flag = best <= alphaOrig ? UPPER : best >= beta ? LOWER : EXACT;
    tt.store(h, TranspositionTable.pack(bestMoveHere, toTT(best, ply), depth, flag));
}
```
Win scores are relative to the root, so convert them to "distance from this node" when storing:
```java
static int toTT(int s, int ply)   { return s >  WIN - MAX_PLY ? s + ply : s < -(WIN - MAX_PLY) ? s - ply : s; }
static int fromTT(int s, int ply) { return s >  WIN - MAX_PLY ? s - ply : s < -(WIN - MAX_PLY) ? s + ply : s; }
```
Pitfalls:
- **Never store results from a `stopped` search.**
- The TT move might be illegal because of a hash collision. If you only use it to *reorder* the list from `legalMoves` (§4.6), that is harmless.
- In games with **repetition draws**, TT scores can be wrong, because the same position can have a different history. Usually you can accept this.

**Checkpoints.** Tic-tac-toe scores are unchanged, and node counts drop further. In Connect Four, the same depth runs several times faster than without the TT.

**Bonus: the principal variation (PV).** To print the engine's expected line, walk the TT from the root. Probe, check that the move is in `legalMoves`, make it, and repeat, then undo them all. Printing `best: e4  score: +35  depth: 9  pv: e4 e5 Nf3 ...` makes debugging much easier.

### Stage 4.6: Move ordering, about 30 min, and the biggest multiplier
Alpha-beta cuts off the most when the best move is searched **first**. Order moves as follows:
1. **TT move** (the best move from the previous iteration or visit)
2. Moves with a high `orderHint` (game-specific: captures, wins, blocks, centre)
3. **Killer moves**: 2 per ply, the quiet moves that recently caused a cutoff at the same ply in a sibling branch
4. Everything else, by the **history heuristic**: `history[player][move]`, increased by `depth*depth` each time the move causes a cutoff

Scoring each move once and then picking the best one lazily (a selection-sort step) is cheap, because after a cutoff you never sort the rest:
```java
int[] killers0 = new int[MAX_PLY], killers1 = new int[MAX_PLY];
int[][] history = new int[2][moveIdBound];            // halve all entries between searches,
                                                       // and whenever one passes 1 << 28 (avoids int overflow)

int moveScore(GameState s, int m, int ttMove, int ply) {
    if (m == ttMove) return Integer.MAX_VALUE;
    int sc = s.orderHint(m) * (1 << 20);               // keep orderHint within about ±1000 so this can't overflow
    if (m == killers0[ply]) sc += 1 << 19;
    else if (m == killers1[ply]) sc += 1 << 18;
    return sc + Math.min(history[s.currentPlayer()][m], (1 << 18) - 1);
}

// in alphaBeta, replacing the plain loop:
int[] sc = scoreBuf[ply];
for (int i = 0; i < n; i++) sc[i] = moveScore(s, moves[i], ttMove, ply);
for (int i = 0; i < n; i++) {
    int bi = i;
    for (int j = i + 1; j < n; j++) if (sc[j] > sc[bi]) bi = j;
    int tmpM = moves[i]; moves[i] = moves[bi]; moves[bi] = tmpM;
    int tmpS = sc[i];    sc[i] = sc[bi];       sc[bi] = tmpS;
    int m = moves[i];
    // ... make / recurse / undo as before ...
    if (alpha >= beta) {
        if (!s.isNoisy(m) && m != killers0[ply]) { killers1[ply] = killers0[ply]; killers0[ply] = m; }
        history[s.currentPlayer()][m] += depth * depth;
        break;
    }
}
```
**Checkpoints.** In tic-tac-toe with `orderHint` = centre 2, corners 1, edges 0, the full-depth node count falls well below 20,866. In Connect Four (centre columns first), the reached depth rises noticeably at equal time.

### Stage 4.7: Optional extras (only after 4.1–4.6 work)
| Technique | Idea | Value / risk |
|---|---|---|
| **Principal Variation Search (PVS)** | Search the first move with the full window. Search the others with a zero window `(-alpha-1, -alpha)` just to prove they are worse, and re-search only if one surprises. | +10–20% with good ordering; low risk |
| **Aspiration windows** | Start each iteration with a narrow window around the previous score, e.g. ±50, and widen it on failure | Small gain; low risk |
| **Late Move Reductions (LMR)** | Moves late in the ordered list (i ≥ 3, depth ≥ 3, not noisy or killer) are searched 1 ply shallower first, and re-searched at full depth only if they beat alpha | Big depth gain; needs good ordering |
| **Quiescence search** | At depth 0, don't evaluate mid-capture. Keep searching only `isNoisy` moves, with "stand pat" = `evaluate()`. This fixes the **horizon effect**. | Essential in capture games, useless otherwise |
| **Null-move pruning** | "Pass" and see if you're still winning | **Avoid** in unknown games: it is wrong when passing would be an advantage (zugzwang) |

Quiescence sketch:
```java
int quiesce(GameState s, int alpha, int beta, int ply) {
    int w = s.winner();
    if (w != GameState.NONE) return terminalScore(s, w, ply);
    int standPat = s.evaluate();
    if (standPat >= beta) return standPat;
    if (standPat > alpha) alpha = standPat;
    int n = s.legalMoves(moveBuf[ply]);
    for (int i = 0; i < n; i++) {
        int m = moveBuf[ply][i];
        if (!s.isNoisy(m)) continue;
        s.makeMove(m);
        int score = -quiesce(s, -beta, -alpha, ply + 1);
        s.undoMove();
        if (score >= beta) return score;
        if (score > alpha) alpha = score;
    }
    return alpha;
}
```

### 4.8 Gotcha: turns that don't alternate
Negamax's `-score` assumes the opponent moves next. If the new game has multi-action turns (move, then place, then remove…), the best trick is to model **each sub-action as its own move** where `currentPlayer()` stays the same. That also shrinks the branching factor enormously: 20×20×5 compound moves become 20 + 20 + 5 sub-moves. Then negate only when the player actually changes:
```java
int before = s.currentPlayer();
s.makeMove(m);
int score = (s.currentPlayer() == before)
        ?  alphaBeta(s, depth - 1,  alpha,  beta, ply + 1)
        : -alphaBeta(s, depth - 1, -beta, -alpha, ply + 1);
```
Build this in from the start; it costs nothing. Passes are just a normal move (e.g. `PASS = moveIdBound - 1`).

---

## 5. MCTS track

MCTS grows a tree one node per iteration. Each iteration has four steps:
1. **Selection.** From the root, repeatedly pick the child with the best **UCB1** score, as long as the node is fully expanded.
2. **Expansion.** Add one untried move as a new child.
3. **Simulation (playout).** Play random moves from there until the game ends.
4. **Backpropagation.** Walk back up and add the result to every node on the path.

UCB1 balances *exploitation* (moves that have won often) against *exploration* (moves that have been tried rarely):

  **UCB1(child) = wins/visits + C · sqrt( ln(parent.visits) / visits )**

The theoretical C is √2 ≈ 1.41 for rewards in [0,1]; 0.5–1.0 is often better in practice. At the end, play the **most-visited** root child. That is more robust than the one with the highest win rate.

### Stage 5.1: Plain UCT, about 40 min
```java
final class Node {
    final Node parent;
    final int move;                 // move from parent to here (-1 at root)
    final int mover;                // player who made `move`: results are stored from THEIR view
    final java.util.ArrayList<Node> children = new java.util.ArrayList<>();
    int[] untried; int untriedCount;
    int visits; double wins;        // win = 1, draw = 0.5, loss = 0   (for `mover`)
    int proven;                     // §5.2: 0 unknown, +1 mover wins, -1 mover loses

    Node(Node parent, int move, int mover, GameState s, int[] buf) {
        this.parent = parent; this.move = move; this.mover = mover;
        if (s.winner() == GameState.NONE) {
            untriedCount = s.legalMoves(buf);
            untried = java.util.Arrays.copyOf(buf, untriedCount);
        } else untried = new int[0];
    }
}

final java.util.SplittableRandom rng;   // one per engine instance (and per thread, §6)

SearchResult search(GameState root, long millis) {
    long deadline = System.nanoTime() + millis * 1_000_000L;
    int[] buf = new int[root.maxMoves()];
    Node rootNode = new Node(null, -1, 1 - root.currentPlayer(), root, buf);
    long iterations = 0;
    while ((iterations++ & 255) != 0 || System.nanoTime() < deadline) {
        Node node = rootNode;
        int made = 0;

        // 1. SELECTION
        while (node.untriedCount == 0 && !node.children.isEmpty()) {
            node = selectUCB(node);
            root.makeMove(node.move); made++;
        }
        // 2. EXPANSION
        if (node.untriedCount > 0) {
            int i = rng.nextInt(node.untriedCount);
            int m = node.untried[i];
            node.untried[i] = node.untried[--node.untriedCount];   // remove by swap-with-last
            int mover = root.currentPlayer();
            root.makeMove(m); made++;
            Node child = new Node(node, m, mover, root, buf);
            node.children.add(child);
            node = child;
        }
        // 3. SIMULATION
        int winner = root.winner();
        while (winner == GameState.NONE) {
            int n = root.legalMoves(buf);
            root.makeMove(buf[rng.nextInt(n)]); made++;
            winner = root.winner();
        }
        // 4. BACKPROPAGATION
        for (Node x = node; x != null; x = x.parent) {
            x.visits++;
            if (winner == x.mover) x.wins += 1;
            else if (winner == GameState.DRAW) x.wins += 0.5;
        }
        for (int k = 0; k < made; k++) root.undoMove();             // restore the root position
    }
    // pick the most-visited child; report its win rate as the "score"
}

Node selectUCB(Node p) {
    double logN = Math.log(p.visits);
    Node best = null; double bestVal = Double.NEGATIVE_INFINITY;
    for (Node c : p.children) {
        double v = c.wins / c.visits + C * Math.sqrt(logN / c.visits);
        if (v > bestVal) { bestVal = v; best = c; }
    }
    return best;
}
```
Because results are stored from the **mover's** point of view, a parent choosing among its children automatically maximizes for the player to move. This also handles non-alternating turns (§4.8) for free.

**Checkpoints (tic-tac-toe, 1 s per move):**
- It never loses to `RandomEngine`.
- It finds the forced block in `XX..O....` (O plays 2).
- It avoids the corner trap in `X...O...X` (O plays an edge).
- Print the root children's `visits` and `wins/visits`. The winning or drawing moves should dominate.

### Stage 5.2: MCTS-Solver, about 30 min
Plain MCTS only *estimates*. In sudden-death positions it can keep "sampling" a move that loses by force. **MCTS-Solver** (Winands et al. 2008) adds exact proofs:
- When a new node is terminal, set `proven = (winner == mover) ? +1 : -1` (leave draws as 0 in this simple version).
- **Propagate** after each expansion, walking up while it changes something. Let `p` be the parent of a proven node `x`, and let the player choosing at `p` be `x.mover`:
  - If `x.proven == +1` (the chooser can win by picking x), then `p.proven = (p.mover == x.mover) ? +1 : -1`.
  - If `x.proven == -1`, and `p.untriedCount == 0`, and **every** child of `p` has `proven == -1`, then the chooser loses whatever it does, so `p.proven = (p.mover == x.mover) ? -1 : +1`.
- **Selection:** stop descending at a proven node and backpropagate its proven result as the playout result. Skip children with `proven == -1` unless every child has it.
- **Root:** if a child has `proven == +1`, play it immediately. You can also stop searching as soon as the root is proven.

**Checkpoint.** In `X.O.X.O..` the solver proves move 8 as a win, and so the root as won, within milliseconds. Printing "proven win" in the advisor output is very reassuring during a game.

### Stage 5.3: Better playouts, about 20 min
- **Decisive moves.** During a playout, if some move wins immediately, play it (make, check `winner()`, undo, for each move). This costs extra per step but makes playouts far more realistic in games with threats. It is even better when the game class offers a cheap "winning move?" check.
- **Playout cutoff + evaluation (a hybrid).** Stop the playout after K random moves (e.g. 10–30) and convert `evaluate()` to a win probability for the side to move: `p = 1 / (1 + exp(-eval / SCALE))`. Credit each node with `p` or `1 - p` depending on whether `x.mover` is the side to move. This is how MCTS handles long games where random play never ends.
- **Heavier playouts**: choose random moves weighted by `orderHint`. This is a tradeoff: smarter playouts but fewer of them.

### Stage 5.4: Optional extras
| Technique | Idea | When |
|---|---|---|
| **RAVE / AMAF** (Gelly & Silver) | Also credit a move with the results of playouts where it was played *later*. Blend with the real statistics: β = sqrt(k / (3n + k)), k ≈ 1000. | Placement games where a move is good regardless of when it is played (Hex, Go-like) |
| **Tree reuse** | After your move and the opponent's reply, make the matching grandchild the new root (`parent = null`) | Free extra thinking time; the advisor must track moves |
| **Progressive widening** | Allow only the top `k = c · visits^0.5` children, ordered by `orderHint` | Huge branching factors (hundreds of moves or more) |
| **Implicit minimax backups** (Lanctot et al.) | Store an alpha-beta-style heuristic value in each node next to the win rate and blend the two in selection | When you have a decent `evaluate()` |

Memory: each node is roughly 60–100 bytes plus its `untried` array. With `-Xmx4g` you can hold millions of nodes. If needed, cap the node count and stop expanding past it.

---

## 6. Multithreading from zero

### 6.1 What a thread is
Your program normally runs on **one thread**, a single line of execution that does one thing at a time. This machine has **16 CPU cores** (see `Main`), so a single-threaded search uses about 1/16th of the available power. A **thread** is an additional line of execution that the operating system can run *at the same time* on another core. Think of it as hiring more cooks for the same kitchen.

### 6.2 The one danger: shared mutable data
If two threads modify the same object at the same time, the results get scrambled. A tiny example:
```java
int counter = 0;                 // shared by two threads
// both threads run:  for (int i = 0; i < 1_000_000; i++) counter++;
// Expected 2,000,000. Actual: often less, and different every run.
```
`counter++` is really three steps: read, add 1, write. Two threads can both read 41 and both write 42, losing one increment. This is a **race condition**. Races on a game board are worse: you get corrupted positions and crashes that happen only sometimes.

> **The golden rule for this project:** give every thread its **own copy of everything it changes**: its own `GameState` (from `copy()`), its own search tree or killers or history, and its own `Random`. Share only things that nobody changes (e.g. `static final` Zobrist tables), or things built for sharing (§6.5).

With that rule, threads never talk to each other while working. Each one works alone, and the main thread combines the answers at the end. There are no locks and no races.

### 6.3 The Java tools you need
| Tool | What it does |
|---|---|
| `Runtime.getRuntime().availableProcessors()` | How many cores you have (16 here) |
| `ExecutorService pool = Executors.newFixedThreadPool(n)` | Starts `n` worker threads that wait for tasks |
| `Callable<T>` | A task that returns a value; usually written as a lambda `() -> ...` |
| `pool.invokeAll(tasks)` | Runs all tasks in parallel and **waits until all finish**; returns a list of `Future<T>` |
| `future.get()` | Gets that task's result. If the task crashed, this throws an `ExecutionException`; call `e.getCause().printStackTrace()` to see the real error |
| `pool.shutdown()` | Lets the worker threads exit (do it at program end, or in `finally`) |
| `volatile boolean stop` | A flag that one thread sets and others read. Without `volatile`, other threads may *never see* the change. |
| `AtomicLong` | A counter that is safe to `incrementAndGet()` from many threads |

You don't even need a shared stop flag: every thread checks the same `deadline` with `System.nanoTime()` on its own.

**Warm-up exercise (about 15 min):** write `ThreadsDemo`. Start one task per core. Each task plays random tic-tac-toe games on its own `copy()` of the board for 1 second and returns how many it played. Print each thread's count and the total. You should see about 16 times the single-thread total. Then deliberately break the rule by sharing one board between all threads, and watch it fail.

### 6.4 Root-parallel MCTS (recommended), about 30 lines
Each thread runs a completely **independent** MCTS from the same position with a different random seed. At the end, add up each root move's visit counts across all threads and play the move with the most. Each tree is a separate opinion, and pooling them is like averaging polls. Chaslot, Winands and van den Herik (2008) found that this simple scheme scales almost perfectly and often beats fancier shared-tree schemes.
```java
// in MctsEngine: return visits per move id instead of a single move
int[] rootVisitCounts(GameState root, long millis) {
    // ... run the normal search loop ...
    int[] counts = new int[root.moveIdBound()];
    for (Node c : rootNode.children) counts[c.move] = c.visits;
    return counts;
}

SearchResult searchParallel(GameState root, long millis, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);   // or keep one pool for the whole program
    try {
        List<Callable<int[]>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            GameState myCopy = root.copy();          // copied HERE, before any thread starts
            long seed = System.nanoTime() + t;       // different randomness per thread
            tasks.add(() -> new MctsEngine(seed).rootVisitCounts(myCopy, millis));
        }
        int[] total = new int[root.moveIdBound()];
        for (Future<int[]> f : pool.invokeAll(tasks)) {             // blocks until every thread is done
            int[] counts = f.get();
            for (int m = 0; m < total.length; m++) total[m] += counts[m];
        }
        int best = -1;                               // only legal moves can have visits > 0
        for (int m = 0; m < total.length; m++) if (best < 0 || total[m] > total[best]) best = m;
        return new SearchResult(best, 0, 0, Arrays.stream(total).sum(), "root-parallel x" + threads);
    } finally {
        pool.shutdown();
    }
}
```
Why this is safe: each lambda captures only **its own** `myCopy` and creates its **own** `MctsEngine`, with its own tree and `Random`. The only shared thing, `total`, is touched by the main thread after `invokeAll` has returned.

**Checkpoint.** `Match` MCTS×8 threads vs MCTS×1 thread at equal time on Connect Four or Hex. The parallel version should win clearly.

**Pitfall.** If your game class has **`static` mutable fields** (e.g. a shared scratch buffer), threads will collide through them. Make such fields instance fields. `static final` random Zobrist tables are fine, because they are read-only.

### 6.5 Lazy SMP for alpha-beta (optional, after everything else)
Splitting the root moves between threads works poorly for alpha-beta, because each thread's pruning depends on the scores the others found. Modern engines such as Stockfish instead use **Lazy SMP**:
1. Every thread runs the **normal iterative-deepening search on the same root**, each with its own `GameState` copy, killers and history.
2. All threads share **one transposition table**. That is the only communication: thread A stores "this position is bad", and thread B finds it and skips work.
3. To stop threads doing identical work, odd-numbered helper threads start one depth deeper (`depth = 2, 3, 4…` instead of `1, 2, 3…`).
4. When time is up, use the **main thread's** result.

A shared TT needs protection from **torn reads**: one thread reads an entry while another is halfway through writing it, and gets the key from one entry and the data from another. The TT in §4.5 already contains the fix, **Hyatt's lockless XOR trick**. It stores `keys[i] = hash ^ data` next to `data[i]`. A reader recomputes `keys[i] ^ data[i]`. If the two halves came from different writes, the result will not equal `hash`, so the entry is treated as a miss. No locks are needed. So Lazy SMP is roughly: build one `TranspositionTable`, give it to N `AlphaBetaEngine`s, and run them with `invokeAll`.

---

## 7. Making it fast in Java

Measure before optimizing. Print **nodes per second** (or playouts per second for MCTS) and compare before and after each change.

- **No allocation in the hot path.** No `new` inside `alphaBeta`, `makeMove`, `legalMoves` or playouts. Use preallocated `int[]` buffers (one per ply), no `ArrayList<Integer>`, no boxing, no streams or lambdas, no `String` building. MCTS nodes are the exception: one allocation per iteration is fine.
- **Make/undo, not copy** (§3.2).
- **Incremental everything:** win detection through the last move only, incremental Zobrist, and incremental material or score counters that `evaluate()` just reads.
- **1-D board with a border (padding).** A 10×10 board stored as 12×12, with the border marked `OFF`, lets neighbour loops (`cell + dir`) run without bounds checks.
- **Bitboards** (one `long` per player when the board has ≤ 64 cells) turn whole-board checks into a few bitwise operations and are typically several times faster. For example, Connect Four on 7 columns × (6+1) bits (Pascal Pons' layout):
  ```java
  static boolean hasFour(long b) {
      long m = b & (b >> 7); if ((m & (m >> 14)) != 0) return true;  // horizontal
      m = b & (b >> 6);      if ((m & (m >> 12)) != 0) return true;  // diagonal \
      m = b & (b >> 8);      if ((m & (m >> 16)) != 0) return true;  // diagonal /
      m = b & (b >> 1);      if ((m & (m >> 2))  != 0) return true;  // vertical
      return false;
  }
  ```
  Practise bitboards on Connect Four. On tournament day, use plain arrays unless the board is tiny, because correct rules beat fast rules.
- **JIT warm-up.** Java compiles hot code to machine code only after it has run for a while. Run one short (~0.5 s) throwaway search when the advisor starts so the first real move isn't slow.
- **Memory:** `-Xmx4g` is already set in `run.sh` and `.vscode/settings.json`. A 2^22-entry TT takes 64 MB, and 2^24 takes 256 MB.
- **Perft** (a performance test): count the leaf positions at depth d using only `legalMoves`, `makeMove` and `undoMove`. It measures raw rules speed, and when the counts don't match known values or a second implementation, it catches rules bugs.

---

## 8. Tools to build

### 8.1 `Fuzzer`: automatic rules-bug finder (your best friend on tournament day)
For N random games (e.g. 10,000):
- Play uniformly random legal moves until `winner() != NONE`. Report an error if a game exceeds a large ply cap, because the game may never end.
- At every step, save `toString()` and `hash()`. After the game, `undoMove()` all the way back and check that every snapshot matches in reverse. This catches most make/undo bugs.
- Check that `parseMove(moveToString(m)) == m` for every legal move.
- Check `copy()` independence: make a move on the copy, and the original must be unchanged.
- If `hash() != 0`: check that it matches a hash recomputed from scratch.
- Print **average game length** and **average branching factor**, the numbers used to choose AB vs MCTS (§2), and games per second.

### 8.2 `Match`: engine vs engine
`Match <game> <engineA> <engineB> <games> <ms-per-move>`. Swap colours every game. Print W/D/L.

Deterministic engines (alpha-beta) replay the *same* game every time, so play the first 1–2 plies **randomly** to get variety. Twenty games is a rough signal; a few hundred is a real one.

### 8.3 `Advisor`: the tool you actually use during the tournament
A REPL that reads commands from the keyboard (`Scanner(System.in)`):

| Command | Effect |
|---|---|
| `show` | Print the board, whose turn, and the move history |
| `moves` | List the legal moves (in the format `parseMove` accepts) |
| `move <m>` | Apply a move (the opponent's, or yours). Reject illegal input with a clear message |
| `go [secs]` | Search and print best move, score (or win%), depth or playouts, nodes/s and PV |
| `play` | Apply the move `go` just suggested |
| `undo` | Take back one move (for typos) |
| `engine ab\|mcts`, `time <secs>`, `threads <n>` | Settings |
| `top` | MCTS: the 5 most-visited root moves with visits and win%, so you can pick using your own judgement too |
| `history` / `replay <m1> <m2> ...` | Print all moves / rebuild a position, which is **crash recovery**: if the program dies mid-game, restart and replay |

Choose the game by name on the command line (`switch (args[0]) { case "TicTacToe" -> new TicTacToe(); ... }`). Run a warm-up search at start-up (§7).

---

## 9. Practice games

Build them roughly in this order. Each one tests something different.

| Game | Why practise it | Notes |
|---|---|---|
| **Tic-tac-toe** | Correctness; exact checkpoints in §4 | Moves 0–8, `moveIdBound = 9` |
| **Connect Four** (7×6) | Deep search, TT, move ordering, bitboards | Move = column. Centre-first ordering. The first player wins with perfect play starting in the centre. Pascal Pons publishes **test files** of positions with exact scores (one position per line as a column sequence plus its score): a perfect checkpoint for alpha-beta + TT. |
| **Othello** (8×8) | Pass moves, evaluation design, flipping make/undo | Encode the pass as move 64 (`moveIdBound = 65`); the game ends when neither player can move. Eval: corners ≫ mobility ≫ disc count (only near the end). Alpha-beta is strong here. |
| **Hex** (e.g. 9×9 or 11×11) | Huge branching factor, no heuristics: MCTS shines | No draws. Win check: flood fill or union-find between the two edges. **Fast playout trick:** fill the remaining cells randomly (alternating colours) and check the winner **once** at the end. Filling after a win cannot change the winner. |
| **Breakthrough** (8×8) | Moving pieces, captures, tactical races | Pieces move 1 step forward or diagonally forward and capture diagonally. You win by reaching the last row or capturing everything. Branching ~25–30. A classic general-game-playing benchmark where both AB and MCTS-Solver do well. |

**Dress rehearsal.** A day before the tournament, pick a game you have **never** implemented (Amazons, Clobber, Gomoku, Pentago, Dots and Boxes, Quoridor, Nine Men's Morris…), set a 60-minute timer, and run the playbook below exactly. Amazons (6×6 or 10×10) is a good stand-in for "hard for engines": its branching factor is huge and moves are compound (queen move + arrow shot), which is a chance to practise §4.8.

---

## 10. Tournament day

### 10.1 Rules checklist (fill in during the first 10 minutes)
- [ ] Board shape and size, and how to index cells as ints
- [ ] Piece types, and what is in a position (anything besides the board: counters, reserves, "must continue" states?)
- [ ] **Move encoding** as an int, and the **text format** you will type for the opponent's moves
- [ ] Every move type: placement, movement, capture, pass, compound moves (→ split them, §4.8)
- [ ] Forced moves or restrictions
- [ ] How the game ends, who wins, and whether draws exist (repetition? move limit?)
- [ ] Special openings (swap/pie rule?)
- [ ] Estimated branching factor and game length (confirm with the Fuzzer)
- [ ] Time control of the tournament games (sets the `go` time)

### 10.2 The 60 minutes
| Minutes | Step |
|---|---|
| 0–10 | Read the rules and fill in the checklist. Decide the move encoding. |
| 10–35 | Copy `GameTemplate.java` into `MyGame.java` and implement the rules. **Correct beats fast.** |
| 35–42 | Run the `Fuzzer` until it is clean. Play a few moves by hand in the `Advisor`. |
| 42–52 | Write `evaluate()` (material, mobility, distance to goal, connectivity…) and `orderHint()` (wins, captures, centre). |
| 52–60 | Run a short `Match` of AB vs MCTS, pick the winner, set the time and threads. Do a warm-up. Ready. |

Fallbacks:
- If the rules aren't working by minute 40, drop everything else and fix them. **MCTS works with `evaluate() = 0`**, so an engine with correct rules and no heuristic is still useful.
- If the engine is weak, use it as a **blunder checker**: `go` for 2 seconds on your intended move's resulting position, and use `top` to see alternatives.

---

## 11. Suggested schedule

These estimates assume some familiarity with Java. The **minimum viable** set is starred.

| Block | Content | Est. |
|---|---|---|
| ★1 | `GameState`, `Engine`, `SearchResult`, `RandomEngine`, `TicTacToe`, `Fuzzer` | 1 h |
| ★2 | Alpha-beta stages 4.1–4.4, checkpoints | 1 h |
| ★3 | `Advisor` REPL, `Match` | 45 min |
| ★4 | TT + Zobrist + move ordering (4.5–4.6), Connect Four | 1.5 h |
| 5 | MCTS 5.1–5.2, checkpoints; Hex | 1.5 h |
| 6 | Threads: warm-up exercise, root-parallel MCTS | 1 h |
| 7 | `GameTemplate.java`, then the dress rehearsal with a new game | 1.5 h |
| 8 | Extras: 5.3 playout cutoff, 4.7 PVS/LMR/quiescence, Othello/Breakthrough, Lazy SMP | as time allows |

---

## 12. Sources and further reading

**Hands-on tutorials (start here)**
- Pascal Pons, *Solving Connect 4: how to build a perfect AI*. A step-by-step series covering negamax, alpha-beta, move ordering, bitboards, TT and iterative deepening, with test sets. <http://blog.gamesolver.org/> (e.g. [Part 3: MinMax](http://blog.gamesolver.org/solving-connect-four/03-minmax/), [Part 2: test protocol](http://blog.gamesolver.org/solving-connect-four/02-test-protocol/))
- Jeff Bradberry, *Introduction to Monte Carlo Tree Search*. A clear UCT explanation with a small implementation. <https://jeffbradberry.com/posts/2015/09/intro-to-monte-carlo-tree-search/>
- Sebastian Lague, *Coding Adventure: Chess* (video, plus a follow-up on making the engine stronger). Great visual intuition for search, move ordering and TT. <https://www.youtube.com/watch?v=U4ogK0MIzqk>
- CodinGame learning pages on MCTS and alpha-beta, aimed at bots under time limits. <https://www.codingame.com/learn/MCTS>

**Chess Programming Wiki** (the reference for alpha-beta techniques; nearly all of it applies to any two-player game)
- [Alpha-Beta](https://chessprogramming.org/Alpha-Beta), [Iterative Deepening](https://chessprogramming.org/Iterative_Deepening), [Move Ordering](https://chessprogramming.org/Move_Ordering)
- [Zobrist Hashing](https://chessprogramming.org/Zobrist_Hashing), [Transposition Table](https://en.wikipedia.org/wiki/Transposition_table) (Wikipedia)
- [Killer Heuristic](https://chessprogramming.org/Killer_Heuristic), [History Heuristic](https://chessprogramming.org/History_Heuristic), [Late Move Reductions](https://www.chessprogramming.org/Late_Move_Reductions)
- [Monte-Carlo Tree Search](https://www.chessprogramming.org/Monte-Carlo_Tree_Search)
- Parallel: [Lazy SMP](https://chessprogramming.org/Lazy_SMP), [Shared Hash Table](https://www.chessprogramming.org/Shared_Hash_Table)

**Papers**
- Browne et al. (2012), *A Survey of Monte Carlo Tree Search Methods*, IEEE TCIAIG 4(1). The map of all MCTS variants. [Semantic Scholar](https://www.semanticscholar.org/paper/A-Survey-of-Monte-Carlo-Tree-Search-Methods-Browne-Powley/c37f1baac3c8ba30250084f067167ac3837cf6fd)
- Winands, Björnsson, Saito (2008), *Monte-Carlo Tree Search Solver*, Computers and Games, LNCS 5131. [Springer](https://link.springer.com/chapter/10.1007/978-3-540-87608-3_3)
- Chaslot, Winands, van den Herik (2008), *Parallel Monte-Carlo Tree Search*. Compares leaf, root and tree parallelization. [PDF](https://dke.maastrichtuniversity.nl/m.winands/documents/multithreadedMCTS2.pdf)
- Gelly & Silver, *Monte-Carlo Tree Search and Rapid Action Value Estimation in Computer Go* (RAVE). [PDF](https://www.cs.utexas.edu/~pstone/Courses/394Rspring13/resources/mcrave.pdf)
- Baier & Winands, *MCTS-Minimax Hybrids*. When and how to mix MCTS with minimax. [PDF](https://dke.maastrichtuniversity.nl/m.winands/documents/mcts-minimax_hybrids_final.pdf)
- Lanctot et al. (2014), *Monte Carlo Tree Search with Heuristic Evaluations using Implicit Minimax Backups*. [arXiv:1406.0486](https://arxiv.org/abs/1406.0486)
- Hyatt & Mann (2002), *A Lockless Transposition-Table Implementation for Parallel Search*, ICGA Journal. [Link](https://journals.sagepub.com/doi/10.3233/ICG-2002-25104)

**General game playing** (engines that play games they have never seen, which is exactly your situation)
- Ludii general game system, whose built-in AIs include iterative-deepening alpha-beta and MCTS variants and pick an algorithm per game. [LudiiAI on GitHub](https://github.com/Ludeme/LudiiAI), [Overview paper](https://arxiv.org/abs/1907.00240)
- Soemers et al., *Optimised Playout Implementations for the Ludii General Game System*. Shows why the speed of rules code matters. [arXiv:2111.02839](https://arxiv.org/abs/2111.02839)

**Java concurrency**
- Baeldung, *A Guide to the Java ExecutorService*. <https://www.baeldung.com/java-executor-service-tutorial>
- `ExecutorService` Javadoc (JDK 25). <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html>
