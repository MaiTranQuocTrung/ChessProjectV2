# ChessEngine (King Bob IV)

A personal deep dive into chess programming. This is my second attempt at writing a chess engine. My first attempt was using a traditional minimax implementation which turned out to be super complicated down the line.
I have decided to recompile the program to a negamax framework and rework all of the evaluation along with the engine.


## Features

### Search
- Alpha-beta pruning
- Transposition table (reuse values from ID and move ordering)
- Quiescence search
- Iterative deepening
- Null move pruning (R=2)
- Reverse futility pruning (150 margin)
- Aspiration window (+/- 35 window size)
- Check extension
- Principle Variation Search
- Late Move Pruning

### Move Ordering:
- MVV-LVA sorted moves
- Iterative deepening first move
- TT moves
- 2 Killer moves
- PST

### Evaluation
- **NNUE**: a (768 -> 1024)x2 -> 1 neural network trained on Stockfish evaluations of Lichess positions
  (`NNUEEval`, network file in `src/main/resources`)
- **Efficient updates**: each position's accumulators are computed from the previous ply's, changing 2 input
  neurons for a quiet move, 3 for a capture and 4 for castling

The previous hand-crafted evaluation (`SimpleEval`: tapered material, piece-square tables, passed pawns, mobility)
is still `OldEngine`'s evaluation, and move ordering uses its piece values and tables.

## Project layout

One Maven project with one source folder:

```
pom.xml                            build: chesslib dependency and the bot jar
src/main/java/ChessEngine/         the engine
    uciLichess.java                  UCI protocol, what the Lichess bot runs
    Engine.java                      search
    NNUEEval.java, Network.java      NNUE evaluation
    Helper.java                      move ordering
    SimpleEval.java                  hand-crafted evaluation
    OldEngine.java                   previous version of the engine, the opponent in version tests
    TranspositionTable.java, SearchManager.java, SmallOpeningBook.java, FLAG.java
src/main/java/VersionTesterTools/  matches between Engine and OldEngine
src/main/resources/                the trained network, packed into the jar
data/                              test openings and the opening book they are taken from
notes/                             code snippets kept for later
```

## Open and run in IntelliJ IDEA

Requires JDK 22 or newer. Use File > Open and select this folder (the one containing `pom.xml`): IntelliJ imports
it as a Maven project and downloads chesslib. These run configurations then appear in the run menu of the toolbar.
They run from the project folder, which the version tests need to find `data/`.

| Run configuration | Main class | What it does |
|---|---|---|
| UCI engine (Lichess bot) | `ChessEngine.uciLichess` | The engine as a UCI program: type `position startpos` and `go` in the console |
| Self-play | `ChessEngine.Engine` | The engine plays a game against itself, 10 seconds per move |
| Version test: all openings | `VersionTesterTools.FullVersionTester` | `Engine` (white) against `OldEngine` (black) from every opening in `data/positions.txt`, 0.5 s per move |
| Version test: one game | `VersionTesterTools.VersionTester` | One game from the start position, `OldEngine` white, 1 s per move |
| Regenerate test openings | `VersionTesterTools.PositionLoader` | Rewrites `data/positions.txt` with the first 1501 lines of `data/whiteProcessedBook.txt` |

## Build the bot jar

```bash
mvn package
```

In IntelliJ: Maven tool window > Lifecycle > package. This writes `target/KingBob.jar` with chesslib and the
network inside; `java -jar target/KingBob.jar` starts the UCI engine, which is what the Lichess bot runs.

## Challenge it on Lichess

The engine is hosted as the Lichess bot **[KingBobIV](https://lichess.org/@/KingBobIV)**. It accepts **Blitz**, **Rapid**, and **Classical** games.

1. Sign in at [lichess.org](https://lichess.org).
2. Open the bot profile: [https://lichess.org/@/KingBobIV](https://lichess.org/@/KingBobIV).
3. Click **Challenge** (the crossed swords).
4. Pick a Blitz, Rapid, or Classical time control, then send the challenge.
5. Wait for the bot to accept. It only accepts while it is online — check the profile for a green **Active** status.

You can also challenge from any Lichess game screen: click **Play with a friend**, paste `KingBobIV` as the opponent, and send the invite.

