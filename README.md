# ChessEngine (King Bob IV)

A personal deep dive into chess programming. This is my second attempt at writing a chess engine. My first attempt was using a traditional minimax implementation which turned out to be super complicated down the line.
I have, therefore, decided to recompile the program to a negamax framework and rework all the evaluation along with the engine.


## Features

### Search
- Alpha-beta pruning
- Transposition table (reuse values from ID and move ordering)
- Quiescence search (both checks and captures)
- MVV-LVA sorted moves
- Iterative deepening
- Null move pruning (fixed R=2)

### Evaluation
- **Tampered evaluation**: Game phase determined by the number of pieces remaining on the board and their importance.
- **Total material**: Weights positions based on the remaining pieces.
- **Piece-square table**: Evaluates piece positions using weighted tables, influenced by the number of pieces.
- **Passed pawns**: Reward passed pawns
- **Simple mobility**: Considers the number of legal moves available for each player at their given turn.


