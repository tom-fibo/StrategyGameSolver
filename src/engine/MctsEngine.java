package engine;

import games.TicTacToe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Monte Carlo Tree Search (UCT), RESEARCH.md §5:
 *  - UCB1 selection, one-node expansion, random playouts, backpropagation (§5.1)
 *  - MCTS-Solver: proven wins/losses are propagated up the tree (§5.2)
 *  - playouts always take an immediately winning move ("decisive moves", §5.3)
 *  - optional playout cutoff: after N random moves, score the position with evaluate() (§5.3)
 *
 * Multithreading: TREE parallelization. All threads grow ONE shared tree; each thread has its own
 * GameState copy, random generator and move buffer. (Root parallelization from §6.4 was measured to be
 * weaker than a single thread in Connect Four: many small trees vote, but none of them sees deep.)
 * How the shared tree stays consistent:
 *  - visits/score are updated with atomic adds (VarHandle.getAndAdd), so no update is lost;
 *  - taking a move from `untried` and publishing a new child happen inside synchronized(node);
 *  - children live in a fixed-size array, and childCount is volatile, so a reader never sees a half-added child;
 *  - "virtual loss": a thread counts its visit on the way DOWN (before the result is known), so other threads
 *    see that path as temporarily worse and spread out over different lines.
 */
public class MctsEngine implements Engine {
    private static final int MAX_PLAYOUT_MOVES = 100_000;   // safety net for games that never end: scored as a draw

    private final int threads;
    private final double c;               // UCB1 exploration constant (higher = explore more)
    private final boolean decisiveMoves;  // playouts take an immediate win when one exists
    private final int playoutCutoff;      // 0 = play to the end; N = stop after N moves and use evaluate()
    private final double evalScale;       // evaluate() difference that counts as "clearly better" in the sigmoid

    private ExecutorService pool;         // worker threads, created on the first multi-threaded search

    /** Uses every CPU core, playouts to the end of the game. */
    public MctsEngine() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public MctsEngine(int threads) {
        this(threads, 1.0, true, 0, 100);
    }

    public MctsEngine(int threads, double c, boolean decisiveMoves, int playoutCutoff, double evalScale) {
        this.threads = Math.max(1, threads);
        this.c = c;
        this.decisiveMoves = decisiveMoves;
        this.playoutCutoff = playoutCutoff;
        this.evalScale = evalScale;
    }

    public String name() {
        return "MCTS Engine (" + threads + (threads == 1 ? " thread)" : " threads)");
    }

    // ------------------------------------------------------------------------------------------
    // Top level: build the root, run the workers on it, pick a move
    // ------------------------------------------------------------------------------------------

    public SearchResult search(GameState state, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        int[] moves = new int[state.maxMoves()];
        int n = state.legalMoves(moves);
        if (n == 0) throw new IllegalArgumentException("search() called on a finished game:\n" + state);
        if (n == 1) return new SearchResult(moves[0], 0, 0, 0, "only move");

        Node root = new Node(null, -1, 1 - state.currentPlayer(), state, new int[state.maxMoves()]);

        // Each worker gets its OWN copy of the state (the golden rule of §6.2); only the tree is shared.
        // Memory budget: the tree may use about half the heap (node size estimated in Worker.iterate).
        long nodeBudget = Runtime.getRuntime().maxMemory() / 2 / threads;
        List<Worker> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) workers.add(new Worker(root, state.copy(), deadline, nodeBudget));

        long iterations = 0;
        int maxDepth = 0;
        List<WorkerStats> stats = new ArrayList<>();
        if (threads == 1) {
            stats.add(workers.get(0).call());
        } else {
            try {
                for (Future<WorkerStats> f : pool().invokeAll(workers)) stats.add(f.get());
            } catch (ExecutionException e) {
                // A worker crashed: rethrow its real exception so the stack trace points at the bug.
                if (e.getCause() instanceof RuntimeException re) throw re;
                if (e.getCause() instanceof Error err) throw err;
                throw new RuntimeException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        for (WorkerStats s : stats) {
            iterations += s.iterations();
            maxDepth = Math.max(maxDepth, s.maxDepth());
        }

        // Root statistics by move id (all threads have finished, so plain reads are fine now).
        int bound = state.moveIdBound();
        long[] visits = new long[bound];
        double[] wins = new double[bound];
        int[] proven = new int[bound];
        for (int k = 0; k < root.childCount; k++) {
            Node ch = root.children[k];
            visits[ch.move] = ch.visits;
            wins[ch.move] = ch.score / 1000.0;
            proven[ch.move] = ch.proven;
        }

        // Choice: a proven win if there is one; otherwise the most-visited move not proven lost;
        // if everything is lost, the most-visited move (hope the opponent errs).
        int best = -1;
        for (int pass = 0; pass < 3 && best < 0; pass++) {
            for (int i = 0; i < n; i++) {
                int m = moves[i];
                boolean eligible = pass == 0 ? proven[m] > 0 : pass == 1 ? proven[m] >= 0 : true;
                if (eligible && (best < 0 || visits[m] > visits[best])) best = m;
            }
        }

        int score;
        if (proven[best] > 0) score = AlphaBetaEngine.WIN;
        else if (proven[best] < 0) score = -AlphaBetaEngine.WIN;
        else score = visits[best] == 0 ? 0 : (int) Math.round((wins[best] / visits[best] - 0.5) * 2000);

        String info = describe(state, moves, n, visits, wins, proven, iterations);
        return new SearchResult(best, score, maxDepth, iterations, info);
    }

    /** e.g. "12,345 playouts, 16 threads |  3: 61.2% (40%)  2: 55.0% (21%)  4: WIN ..." (win rate, share of visits). */
    private String describe(GameState state, int[] moves, int n, long[] visits, double[] wins, int[] proven, long iterations) {
        long total = 0;
        for (int i = 0; i < n; i++) total += visits[moves[i]];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = moves[i];
        Arrays.sort(order, (a, b) -> Long.compare(visits[b], visits[a]));

        StringBuilder sb = new StringBuilder(String.format("%,d playouts, %d thread%s |", iterations, threads, threads == 1 ? "" : "s"));
        for (int i = 0; i < Math.min(5, n); i++) {
            int m = order[i];
            sb.append("  ").append(state.moveToString(m)).append(": ");
            if (proven[m] > 0) sb.append("WIN");
            else if (proven[m] < 0) sb.append("LOSS");
            else if (visits[m] == 0) sb.append("-");
            else sb.append(String.format("%.1f%%", 100 * wins[m] / visits[m]));
            if (total > 0) sb.append(String.format(" (%.0f%%)", 100.0 * visits[m] / total));
        }
        return sb.toString();
    }

    private ExecutorService pool() {
        if (pool == null) {
            // Daemon threads: the JVM can exit without an explicit shutdown().
            pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "mcts-worker");
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    // ------------------------------------------------------------------------------------------
    // Shared tree
    // ------------------------------------------------------------------------------------------

    private static final int[] NO_MOVES = new int[0];

    private static final class Node {
        final Node parent;
        final int move;           // move from parent to here (-1 at the root)
        final int mover;          // player who made `move`; score/proven are from THEIR point of view

        final int[] untried;      // legal moves not yet expanded: first untriedCount entries. Guarded by synchronized(this).
        int untriedCount;
        final Node[] children;    // one slot per legal move; the first childCount are filled
        volatile int childCount;

        volatile int visits;      // includes visits still in progress (virtual loss)
        volatile long score;      // sum of results for `mover` x1000 (win 1000, draw 500, loss 0)
        volatile int proven;      // 0 unknown, +1 mover wins with perfect play, -1 mover loses

        /** `s` must be the position AFTER `move`. `buf` is scratch space of size maxMoves(). */
        Node(Node parent, int move, int mover, GameState s, int[] buf) {
            this.parent = parent;
            this.move = move;
            this.mover = mover;
            int w = s.winner();
            if (w == GameState.NONE) {
                untriedCount = s.legalMoves(buf);
                if (untriedCount == 0) throw new IllegalStateException("No legal moves but game not over:\n" + s);
                untried = Arrays.copyOf(buf, untriedCount);
            } else {
                untried = NO_MOVES;
                if (w == mover) proven = 1;
                else if (w != GameState.DRAW) proven = -1;
            }
            children = new Node[untried.length];
        }

        boolean fullyExpanded() {
            return childCount == children.length;
        }
    }

    // Atomic "x += delta" on Node fields, safe when several threads update the same node.
    private static final VarHandle VISITS, SCORE;
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            VISITS = lookup.findVarHandle(Node.class, "visits", int.class);
            SCORE = lookup.findVarHandle(Node.class, "score", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record WorkerStats(long iterations, int maxDepth) {}

    // ------------------------------------------------------------------------------------------
    // One search thread: its own state copy, random generator and buffers; the tree is shared
    // ------------------------------------------------------------------------------------------

    private final class Worker implements Callable<WorkerStats> {
        private final Node root;
        private final GameState s;
        private final long deadline;
        private final long nodeBudget;
        private final SplittableRandom rng = new SplittableRandom();   // not shared: one per thread
        private final int[] buf;
        private long nodeCount;
        private int maxDepth;
        private int playoutMoves;   // moves made by the last playout (so they can be undone)

        Worker(Node root, GameState s, long deadline, long nodeBudget) {
            this.root = root;
            this.s = s;
            this.deadline = deadline;
            this.nodeBudget = nodeBudget;
            this.buf = new int[s.maxMoves()];
        }

        @Override
        public WorkerStats call() {
            long iterations = 0;
            // Stop at the deadline, or as soon as the root position is solved.
            while (root.proven == 0 && System.nanoTime() < deadline) {
                iterate();
                iterations++;
            }
            return new WorkerStats(iterations, maxDepth);
        }

        private void iterate() {
            Node node = root;
            VISITS.getAndAdd(root, 1);
            int made = 0;

            // 1+2. SELECTION down the tree, then EXPANSION of one new child
            while (node.proven == 0) {
                int m = takeUntriedMove(node);
                if (m >= 0) {
                    int mover = s.currentPlayer();
                    s.makeMove(m);
                    made++;
                    Node child = new Node(node, m, mover, s, buf);
                    nodeCount += 120 + 8L * child.untried.length;   // approx. bytes: object + untried[] + children[]
                    child.visits = 1;                       // this thread's visit, set before anyone can see the child
                    synchronized (node) {
                        node.children[node.childCount] = child;
                        node.childCount = node.childCount + 1;   // volatile write publishes the child
                    }
                    node = child;
                    if (child.proven != 0) propagateProof(child);
                    break;
                }
                if (node.childCount == 0) break;            // terminal, or out of node budget: simulate from here
                node = select(node);
                VISITS.getAndAdd(node, 1);                  // virtual loss until the result is added
                s.makeMove(node.move);
                made++;
            }
            if (made > maxDepth) maxDepth = made;

            // 3. SIMULATION: result from player 0's point of view (1 win, 0.5 draw, 0 loss)
            double r0;
            int proven = node.proven;
            if (proven != 0) {
                int winner = proven > 0 ? node.mover : 1 - node.mover;
                r0 = winner == 0 ? 1 : 0;
                playoutMoves = 0;
            } else {
                r0 = playout();
            }

            // 4. BACKPROPAGATION: visits were already counted on the way down; add the results
            for (Node x = node; x != null; x = x.parent) {
                double r = x.mover == 0 ? r0 : 1 - r0;
                SCORE.getAndAdd(x, Math.round(r * 1000));
            }

            // Restore this thread's copy of the root position
            for (int k = made + playoutMoves; k > 0; k--) s.undoMove();
        }

        /** Removes and returns a random untried move of `node`, or -1 (none left, or node budget used up). */
        private int takeUntriedMove(Node node) {
            if (nodeCount >= nodeBudget) return -1;
            synchronized (node) {
                if (node.untriedCount == 0) return -1;
                int i = rng.nextInt(node.untriedCount);
                int m = node.untried[i];
                node.untried[i] = node.untried[--node.untriedCount];   // remove by swap-with-last
                return m;
            }
        }

        /** UCB1, skipping children that are proven losses for the player choosing. */
        private Node select(Node p) {
            int n = p.childCount;
            double logN = Math.log(p.visits);
            Node best = null;
            double bestVal = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < n; k++) {
                Node ch = p.children[k];
                if (ch.proven < 0) continue;
                int v = ch.visits;
                double val = ch.score / (1000.0 * v) + c * Math.sqrt(logN / v);
                if (val > bestVal) {
                    bestVal = val;
                    best = ch;
                }
            }
            return best != null ? best : p.children[0];
        }

        /**
         * MCTS-Solver (§5.2). The player choosing at p is x.mover.
         * - x is a proven win for the chooser  -> p is decided: the chooser picks x.
         * - x is a proven loss for the chooser -> p is decided only if every move is expanded and lost.
         */
        private void propagateProof(Node x) {
            while (x.parent != null && x.proven != 0) {
                Node p = x.parent;
                if (p.proven != 0) return;
                boolean sameMover = p.mover == x.mover;   // false whenever turns alternate
                if (x.proven > 0) {
                    p.proven = sameMover ? 1 : -1;
                } else {
                    if (!p.fullyExpanded()) return;
                    for (int k = 0; k < p.children.length; k++) if (p.children[k].proven >= 0) return;
                    p.proven = sameMover ? -1 : 1;
                }
                x = p;
            }
        }

        /** Random moves until the game ends (or the cutoff). Leaves the moves made; count in playoutMoves. */
        private double playout() {
            playoutMoves = 0;
            int w = s.winner();
            while (w == GameState.NONE) {
                if (playoutCutoff > 0 && playoutMoves >= playoutCutoff) {
                    double p = 1 / (1 + Math.exp(-s.evaluate() / evalScale));   // chance the side to move wins
                    return s.currentPlayer() == 0 ? p : 1 - p;
                }
                if (playoutMoves >= MAX_PLAYOUT_MOVES) return 0.5;

                if (!decisiveMoves) {
                    s.makeMove(s.randomMove(rng, buf));   // lets the game use a cheap sampler
                    playoutMoves++;
                    w = s.winner();
                    continue;
                }
                int n = s.legalMoves(buf);
                if (n == 0) throw new IllegalStateException("No legal moves but game not over:\n" + s);
                int mover = s.currentPlayer();
                boolean played = false;
                {
                    for (int i = 0; i < n && !played; i++) {
                        s.makeMove(buf[i]);
                        if (s.winner() == mover) played = true;   // keep the winning move on the board
                        else s.undoMove();
                    }
                }
                if (!played) s.makeMove(buf[rng.nextInt(n)]);
                playoutMoves++;
                w = s.winner();
            }
            return w == 0 ? 1 : w == 1 ? 0 : 0.5;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Checkpoints from RESEARCH.md §5.1–5.2 on tic-tac-toe
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        MctsEngine engine = new MctsEngine();
        System.out.println("Testing " + engine.name());

        check(engine, new int[] {0, 4, 1}, "O must block at 2", 2);
        check(engine, new int[] {0, 4, 8}, "O must play an edge (1, 3, 5, 7)", 1, 3, 5, 7);
        check(engine, new int[] {0, 2, 4, 6}, "X wins at once with 8", 8);

        // Never lose to random play (both colours).
        SplittableRandom rng = new SplittableRandom(1);
        int games = 100, losses = 0;
        MctsEngine fast = new MctsEngine(1);
        int[] buf = new int[9];
        for (int g = 0; g < games; g++) {
            TicTacToe t = new TicTacToe();
            int mctsPlays = g % 2;
            while (!t.isTerminal()) {
                int m = t.currentPlayer() == mctsPlays ? fast.search(t, 20).bestMove() : buf[rng.nextInt(t.legalMoves(buf))];
                t.makeMove(m);
            }
            if (t.winner() == 1 - mctsPlays) losses++;
        }
        System.out.println("vs random: " + losses + " losses in " + games + " games " + (losses == 0 ? "(OK)" : "(FAIL)"));
    }

    private static void check(Engine engine, int[] setup, String expectation, int... goodMoves) {
        TicTacToe t = new TicTacToe();
        for (int m : setup) t.makeMove(m);
        SearchResult r = engine.search(t, 500);
        boolean ok = Arrays.stream(goodMoves).anyMatch(m -> m == r.bestMove());
        System.out.println(t);
        System.out.println(expectation + " -> played " + r.bestMove() + (ok ? " (OK)" : " (FAIL)") + "   score " + r.score());
        System.out.println("  " + r.info() + "\n");
    }
}
