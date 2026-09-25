# StrategyGameSolver

A game-agnostic search engine (alpha-beta and MCTS) for a tournament whose game is revealed on the day.
See **[RESEARCH.md](RESEARCH.md)** for the algorithms, the staged build plan with checkpoints, and the sources.

## Running

Requires JDK 25 (`/usr/lib/jvm/java-25-openjdk-amd64`). There is no build tool; it uses plain `javac`.

**VS Code:** open this folder with the *Extension Pack for Java* installed, open any file with a `main` method, and press ▷ (or the *Run* code lens above `main`).
`.vscode/settings.json` points the Java extension at `src/` and adds `-ea -Xmx4g` to every run.
Programs run in the integrated terminal, so keyboard input works (needed for the advisor REPL).

**Command line:**
```bash
./run.sh                          # compiles src/ into out/ and runs Main (environment check)
./run.sh tools.Advisor TicTacToe  # runs any main class, passing the remaining arguments
```

## Layout
```
src/Main.java      environment check: Java version, cores, heap, assertions
src/engine/        GameState contract, engines (RESEARCH.md §3–6)
src/games/         game implementations (RESEARCH.md §9)
src/tools/         Fuzzer, Match, Advisor (RESEARCH.md §8)
```
