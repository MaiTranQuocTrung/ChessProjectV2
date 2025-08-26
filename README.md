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



