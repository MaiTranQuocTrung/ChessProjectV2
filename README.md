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
- **Tampered evaluation**: Game phase determined by the number of pieces remaining on the board and their importance.
- **Total material**: Weights positions based on the remaining pieces.
- **Piece-square table**: Evaluates piece positions using weighted tables, influenced by the number of pieces.
- **Passed pawns**: Reward passed pawns
- **Mobility**: Weighted by pieces

## Download and build (KingBobV2)

The current engine lives in the `KingBobV2` Maven module.

### Prerequisites

- [Git](https://git-scm.com/)
- [JDK 22](https://jdk.java.net/22/) or newer
- [Apache Maven](https://maven.apache.org/download.cgi) 3.8+

Confirm both tools are on your `PATH`:

```bash
java -version
mvn -version
```

### Clone the repo

```bash
git clone https://github.com/MaiTranQuocTrung/ChessProjectV2.git
cd ChessProjectV2/KingBobV2
```

### Build

```bash
mvn package
```

This compiles the engine and writes a jar to `KingBobV2/target/`.

### Run locally

From `KingBobV2`, start a self-play game (10 seconds per move):

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java -Dexec.mainClass="org.example.Engine"
```

You can also open the repo in IntelliJ IDEA and run `org.example.Engine`.

## Challenge it on Lichess

KingBobV2 is hosted as the Lichess bot **[KingBobIV](https://lichess.org/@/KingBobIV)**. It accepts **Blitz**, **Rapid**, and **Classical** games.

1. Sign in at [lichess.org](https://lichess.org).
2. Open the bot profile: [https://lichess.org/@/KingBobIV](https://lichess.org/@/KingBobIV).
3. Click **Challenge** (the crossed swords).
4. Pick a Blitz, Rapid, or Classical time control, then send the challenge.
5. Wait for the bot to accept. It only accepts while it is online — check the profile for a green **Active** status.

You can also challenge from any Lichess game screen: click **Play with a friend**, paste `KingBobIV` as the opponent, and send the invite.

