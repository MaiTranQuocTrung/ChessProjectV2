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
- Reverse futility pruning (150 × depth margin)
- Aspiration window (+/- 35 window size)
- Check extension
- Principal Variation Search

### Move Ordering
- MVV-LVA sorted moves
- Iterative deepening first move
- TT moves
- 2 Killer moves
- PST

### Evaluation
- **NNUE**: a (768 -> 1024)x2 -> 1 neural network trained from scratch on 97 million Lichess positions evaluated by
  Stockfish (`NNUEEval`, network file in `src/main/resources`). How it works and how it was trained:
  [NNUE: architecture and training](#nnue-architecture-and-training)
- **Efficient updates**: each position's accumulators are computed from the previous ply's, changing 2 input
  neurons for a quiet move, 3 for a capture and 4 for castling

The previous hand-crafted evaluation (`SimpleEval`: tapered material, piece-square tables, passed pawns, mobility)
is still `OldEngine`'s evaluation, and move ordering uses its piece values and tables.

## NNUE: architecture and training

The network was designed and trained from scratch for this engine. Stockfish only supplied the training labels, its
evaluations of the positions; no Stockfish network or weights are used. The training code is a separate Python
project, ChessNNUE, which is not part of this repository: this repository holds the inference code (`NNUEEval.java`,
`Network.java`) and the trained network.

### Network architecture

```text
side to move's view -> 768 inputs -> 1024 accumulator --+
                                                        +--> 2048 -> SCReLU -> 1 output -> x 400 = centipawns
other side's view   -> 768 inputs -> 1024 accumulator --+
                       (both views use the same weights)
```

- **Inputs**: 768 binary neurons, one per (colour, piece type, square), 2 x 6 x 64. A neuron is 1 when that piece
  stands on that square, so at most 32 are on.
- **Two perspectives**: the position is encoded twice, as White sees it (the board as is) and as Black sees it (the
  board flipped vertically so a1 becomes a8, with the colours swapped). Both use the same weights, so the network
  learns one notion of "my pieces" and "the opponent's pieces" that serves either side.
- **Accumulator** (hidden layer): 1024 neurons per perspective, the sum of a bias and the weight rows of the active
  inputs.
- **Output**: the side to move's accumulator followed by the other side's, 2048 values, goes through SCReLU
  (`clamp(x, 0, 1)^2`) into a single output neuron. Its value times 400 is the evaluation in centipawns from the side
  to move's point of view. Since the side to move's half always comes first, the network knows whose turn it is.
- **Size**: 789,505 parameters: 768 x 1024 accumulator weights, 1024 accumulator biases, 2048 output weights and one
  output bias.
- **Quantization**: the network is trained with floating point numbers and stored as integers, so the engine
  evaluates with integer arithmetic only. Accumulator weights and biases are multiplied by 255 and stored as int16,
  output weights by 64 (int16) and the output bias by 255 x 64 (int32). This moves evaluations by 6 cp on average.
- **File format** of `src/main/resources/nnue-768x1024.nnue` (1.58 MB, little-endian): seven int32 (`"NNUE"`,
  version 1, 768 inputs, hidden size 1024, 255, 64, output scale 400), then the int16 accumulator weights
  `[768][1024]`, the int16 accumulator biases `[1024]`, the int16 output weights `[2048]` (side-to-move half first)
  and the int32 output bias. Weight row `colour * 384 + piece type * 64 + square` belongs to that input neuron, with
  a1 = 0 and pawn = 0 ... king = 5.

### Training data

The positions come from the
[Lichess evaluation database](https://huggingface.co/datasets/Lichess/chess-position-evaluations): about 416 million
positions evaluated by Stockfish on Lichess, published as 20 parquet files of 2.2 GB. 8 of the files were used:
427 million rows, as each position appears once per search depth and principal variation.

Each position keeps the evaluation of its deepest search, and is dropped if:

- that search is shallower than depth 16 (2-3% of the positions)
- the position cannot arise in a game, for example a missing king or more promoted pieces than missing pawns (1%)
- the side to move is in check (8%)
- Stockfish's best move is a capture or a promotion (22%). The engine only uses the static evaluation in quiet
  positions, after the quiescence search has resolved the captures, so these positions would teach the network noise.

About two thirds of the positions pass (the shares above come from the logs of 3 of the 8 files). That leaves
97,293,210 unique positions: 500,000 random ones are held out for validation and 96,793,210 are used for training.
Each position's label is Stockfish's evaluation in centipawns from the side to move's point of view, clamped to
±3000, with forced mates counted as ±3000 (12.3% of the positions are at ±3000).

### Training setup

| Setting | Value |
|---|---|
| Target | Stockfish's evaluation as a win probability, `sigmoid(cp / 400)` |
| Loss | `(sigmoid(output) - target)^2`, averaged over the batch |
| Optimizer | Adam, learning rate 1e-3 decaying to 1e-5 on a cosine schedule |
| Batch size | 16,384 positions |
| Length | 10 epochs (59,070 steps), reshuffled every epoch |
| Weight clipping | ±1.98 after every step, so the quantized accumulator cannot overflow int16 |
| Hardware | laptop CPU (Intel Core Ultra 9 185H), 16 threads, no GPU |
| Software | Python 3.14 with NumPy, Numba, PyTorch, PyArrow and python-chess |
| Time | 63.7 minutes, about 250,000 positions per second |

Comparing win probabilities instead of centipawns makes the loss care about the evaluations that decide games: going
from +10 to +15 pawns barely changes the win probability, going from 0 to +1 pawn changes it a lot. PyTorch holds the
parameters and runs Adam, while the forward and backward passes are hand-written, multithreaded Numba kernels that go
through the batch position by position (gather the active weight rows, SCReLU, output, gradients). On a CPU that is
about 10 times faster than the same network as a PyTorch graph.

Validation loss after each epoch, against 0.05131 for always predicting 0 and 0.02864 for a material count:

| Epoch | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Validation loss | 0.01103 | 0.01026 | 0.00990 | 0.00966 | 0.00951 | 0.00939 | 0.00931 | 0.00925 | 0.00923 | 0.00922 |

### Results

Agreement with Stockfish on held-out positions, for the quantized network the engine uses:

| Evaluation | Mean error | Correlation | Right side better |
|---|---|---|---|
| material count | 152 cp | 0.52 | 61.8% |
| NNUE | 99 cp | 0.82 | 95.3% |

Mean error and correlation are over positions where Stockfish's evaluation is within ±1000 cp. "Right side better"
counts, among the positions where Stockfish sees at least a pawn of advantage, how often the evaluation favours the
same side.

In play, the NNUE was tested against the hand-crafted `SimpleEval` with this engine's search on both sides, so only
the evaluation differed. Over 79 games at 100 ms per move, from 40 book openings played with both colours, it scored
59 wins, 10 draws and 10 losses: 81%, roughly +250 Elo. The engine searches about as many nodes per second with the
NNUE as it did with `SimpleEval`.

### Inside the engine

`Engine` holds one `NNUEEval` and calls `positionalEvaluation(board)` for the quiescence search's stand-pat score,
reverse futility pruning and null move pruning. The search makes and takes back moves through `nnueEval.doMove`,
`doNullMove` and `undoMove` instead of the board's own methods, so the evaluator sees every move:

- `NNUEEval` keeps a stack of accumulator pairs (White's and Black's perspective), one per ply.
- Making a move only records which input neurons it switches on and off. The accumulators are brought up to date when
  a position is evaluated, starting from the nearest ply that is already computed, so moves that are cut off before
  an evaluation, or rejected as illegal, cost nothing.
- A ply's accumulators are its parent's plus the weight rows of the neurons switched on, minus those switched off,
  computed in one pass with the kernel that fits the move:

| Move | Input neurons | Kernel in `Network` |
|---|---|---|
| quiet move, promotion | 1 on, 1 off | `addSub` |
| capture, en passant, capture-promotion | 1 on, 2 off | `addSubSub` |
| castling | 2 on, 2 off | `addAddSubSub` |

The output layer is recomputed at every evaluation. Weights and accumulators are kept in `int` arrays even though
they fit in 16 bits, because HotSpot only vectorizes the output layer's clamp-and-multiply loop on int lanes.

Timings on one thread of the Core Ultra 9 185H: updating one perspective after a quiet move takes 119 ns, against
2,885 ns to recompute it from scratch (24 times faster); a capture takes 157 ns, castling 196 ns and the output layer
266 ns. In a search-like walk through a move tree that evaluates every node, an evaluation costs 762 ns with
incremental updates and 5,825 ns when both accumulators are recomputed.

The network is loaded once, from the classpath (it is packed into the jar) or, when `src/main/resources` is not on the
classpath, from `src/main/resources/nnue-768x1024.nnue` in the working directory. The trainer's self-test checks that
incremental updates are bit-identical to recomputing from scratch, over perft move trees and random games with
take-backs and null moves, and that the Java evaluation matches the Python trainer's exactly. Running the engine with
`-Dnnue.verify=true` compares every evaluation of a real search with a full recomputation: slow, but it catches
accumulators that go out of sync with the board.

### Retraining

In the ChessNNUE project:

```powershell
py -3.14 -m venv .venv; .venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python train\download_data.py --shards 0 1 2 3 4 5 6 7
.venv\Scripts\python train\prepare_data.py (Get-ChildItem data\raw\*.parquet).FullName
.venv\Scripts\python train\train.py --hidden 1024 --epochs 10
.venv\Scripts\python train\export.py checkpoints\run\last.pt nets\nnue-768x1024.nnue
```

These download the 8 parquet files (about 18 GB), filter and encode them (about 5 minutes per file), train, and
quantize the result into a network file, checking that the accumulator cannot overflow int16. To use a new network,
copy it over `src/main/resources/nnue-768x1024.nnue` and rebuild.

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

