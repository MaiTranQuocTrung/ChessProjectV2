package ChessEngine;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/*
Search:
- Alpha beta pruning
- Transposition table (move ordering + reuse positions)
- Q search
- Check search extension
- MVV-LVA sorted moves
- 2 Killer moves
- Iterative deepening (time constraint + move ordering)
- Null move pruning
- Aspiration Window
- Reverse Futility Pruning
- PVS
Evaluation:
- NNUE (768 -> 1024)x2 -> 1 trained on Stockfish evaluations of Lichess positions (NNUEEval)
- Accumulators updated incrementally from the previous ply (2 to 4 weight rows per move)
 */

public class Engine{
    // Classes for eval, move ordering, TT
    private final Helper boardHelper = new Helper();
    private final NNUEEval nnueEval = new NNUEEval();
    // Approx 1GB TT
    private final TranspositionTable TT = new TranspositionTable(1024);
    private static final int MATE_SCORE = 1000000;
    // Scores this close to MATE_SCORE are mates, the difference being the number of plies
    private static final int MATE_BOUND = MATE_SCORE - 1000;
    // Search debug info
    int TOTAL_PRUNES;
    int TOTAL_NODES;
    // Null move pruning reduction rate
    int R = 2;

    public static class MinimaxInfo{
        public int state_value;
        public Move move;
        public List<Move> main_line;
        public int depth;

        public MinimaxInfo(int state_value, Move move) {
            this(state_value, move, null, 0);
        }

        // Used to store the line the engine found
        public MinimaxInfo(int state_value, Move move, List<Move>main_line, int depth) {
            this.state_value = state_value;
            this.move = move;
            this.main_line = main_line != null ? main_line : new ArrayList<>();
            this.depth = depth;
        }
    }

    public void newGame(){
        TT.clear();
    }

    public MinimaxInfo Think(Board board, long searchTime) {
        int depth = 1;
        MinimaxInfo bestChoice = null;
        SearchManager timeManager = new SearchManager(searchTime);
        // Root accumulators, every move searched from here updates them incrementally
        nnueEval.reset(board);

        if (TT.getCapacity() >= 100) {
            TT.clear();
        }

        while (depth <= 64) {
            TOTAL_NODES = 0;
            Instant starts = Instant.now();
            int aspirationWindow = 35;
            MinimaxInfo currChoice;

            while (true) {
                int alpha = bestChoice != null ? bestChoice.state_value - aspirationWindow : -MATE_SCORE;
                int beta = bestChoice != null ? bestChoice.state_value + aspirationWindow : MATE_SCORE;

                currChoice = Search(board, alpha, beta, depth, 0, timeManager, 0);

                // If score is inside the window (aka. alpha < score < beta) then we can proceed to next depth
                int score = currChoice.state_value;
                if(alpha < score && score < beta){
                    break;
                }
                //Widen the window
                aspirationWindow *= 2;

            }

            Instant end = Instant.now();
            long timeElapsed = Duration.between(starts, end).toMillis();
            long nps = (long)(TOTAL_NODES / Math.max(timeElapsed / 1000.0, 0.001));

            System.out.printf("Depth: %-2d | Time: %-5d | NPS: %-7d | Eval: %6.2f | Result Depth: %-2d | Line: %s%n",
                    depth,
                    timeElapsed,
                    nps,
                    (float)currChoice.state_value/100,
                    currChoice.depth,
                    currChoice.main_line
            );

            if (currChoice.move != null) {
                bestChoice = currChoice;
                boardHelper.iterativeDeepeningMove(bestChoice.move);
            }

            if (timeManager.shouldCancel()) {
                break;
            }

            depth++;
        }
        return bestChoice;
    }

    // Sorting by ID move > ttMove > promotions > MVV-LVA > Killer1 > Killer 2 > PST
    private List<Move> moveGenerator(Board board, boolean isCapture, int ply){
        if (isCapture){
            return boardHelper.sortMoves(board, board.pseudoLegalCaptures(), TT, ply,true);
        }
        return boardHelper.sortMoves(board,board.pseudoLegalMoves(), TT, ply,false);
    }


    /*
        Quiescence Search: At cutoff depth go through every non-quiet moves from the cutoff node until
                           there is no more captures, in terms of checks we will handle this under check extensions.
     */
    private int QSearch(Board board, int alpha, int beta, int ply) {
        TOTAL_NODES++;

        if (board.isDraw()) {
            return 0;
        }

        int stand_pat = nnueEval.positionalEvaluation(board);
        int bestValue = stand_pat;

        if (stand_pat >= beta) {
            TOTAL_PRUNES++;
            return stand_pat;
        }

        if (alpha < stand_pat) {
            alpha = stand_pat;
        }

        for (Move move : moveGenerator(board, true, ply)) {
            nnueEval.doMove(board, move);
            int score = -QSearch(board, -beta, -alpha, ply + 1);
            nnueEval.undoMove(board);

            if (score >= beta) {
                TOTAL_PRUNES++;
                return score;
            }

            if (score > bestValue){
                bestValue = score;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return bestValue;
    }

    private MinimaxInfo Search(Board board, int alpha, int beta, int depth, int ply, SearchManager timeManager, int numExtension) {
        TOTAL_NODES++;

        if (depth <= 0) {
            int score = QSearch(board, alpha, beta, ply);
            return new MinimaxInfo(score, null);
        }

        // Time management check
        if (timeManager.shouldCancel()) {
            return new MinimaxInfo(0, null);
        }

        // Draw detection, not at the root: it has to return a move even if the position occurred before
        if (ply > 0 && (board.isDraw() || board.isStaleMate() || board.isRepetition(1))) {
            return new MinimaxInfo(0, null);
        }
        // Checkmate detection
        if (board.isMated()) {
            return new MinimaxInfo(-MATE_SCORE + ply, null);
        }

        // Transposition table lookup
        TranspositionTable.Entry entry = TT.getEntry(board.getZobristKey());
        if (entry != null && entry.depth >= depth && ply > 0) {
            int entryValue = scoreFromTT(entry.value, ply);
            Move entryMove = entry.move;
            List<Move> entryLine = entry.mainLine;
            int entryDepth = entry.depth;
            // Adjust the bestValue based on the flag
            if (entry.flag == FLAG.EXACT) {
                return new MinimaxInfo(entryValue, entryMove, entryLine, entryDepth);
            } else if (entry.flag == FLAG.LOWER && entryValue > beta) {
                return new MinimaxInfo(entryValue, entryMove, entryLine, entryDepth);
            } else if (entry.flag == FLAG.UPPER && entryValue <= alpha) {
                return new MinimaxInfo(entryValue, entryMove, entryLine, entryDepth);
            }
        }

        //Pruning params
        boolean isKingAttacked = board.isKingAttacked();
        // Evaluated once, shared by reverse futility pruning and null move pruning
        int staticEval = nnueEval.positionalEvaluation(board);

        //Reverse Futility Pruning
        int futilityMargin = 150 * depth;
        //If your score is so good you can take a big hit and still get the beta cutoff (exclusive), no need to search deeper
        //Only apply when we are at a relatively lower depths, at deeper depths there are more tactical nuances
        if (depth < 6 && staticEval - futilityMargin > beta
                && !isKingAttacked && numExtension == 0){
            return new MinimaxInfo(staticEval,null);
        }

        //Null move pruning
        // If you skip a turn and still manage to obtain a beta cutoff then we don't need to search further
        if (depth > 2  && beta <= staticEval
                && !isKingAttacked && boardHelper.nullMovePruning(board)
                && numExtension == 0){
            nnueEval.doNullMove(board);
            MinimaxInfo childInfo = Search(board, -beta, beta + 1, depth - R, ply + 1, timeManager, numExtension);
            int score = -childInfo.state_value;
            Move move = childInfo.move;
            nnueEval.undoMove(board);
            // Beta cutoff, return the score immediately
            if (score >= beta){
                TT.store(board.getZobristKey(), depth, scoreToTT(score, ply), FLAG.LOWER, move, null);
                return new MinimaxInfo(score,null);
            }
        }

        // PVS search params
        int moveCounter = 0;
        int bestValue = -Integer.MAX_VALUE;
        Move bestMove = null;

        //Calculated line
        List<Move> bestLine = new ArrayList<>();

        // Main search loop
        for (Move move : moveGenerator(board, false, ply)) {
            nnueEval.doMove(board, move);
            /*
             Move extension:
             If our king is under attack extend the search by +1 depth but we shouldn't extend pass 16 ply because of time
             */
            int extension = numExtension < 16 && isKingAttacked ? 1 : 0;
            MinimaxInfo childInfo;
            int score;
            moveCounter++;
            /*
            Principle variation search:
            The idea is that our move ordering is so good is that most likely the best move will always be first.
            So search the first move at full window but narrow the window down for the other moves,
            but if we do find a better move (a move that raises the current alpha value) then we must research it at full window.
             */
            if(moveCounter <= 1 || numExtension > 0){
                childInfo = Search(board, -beta, -alpha, depth - 1 + extension, ply + 1, timeManager, numExtension + extension);
                score = -childInfo.state_value;
            }
            //Else do a narrow search
            else{
                childInfo = Search(board, - alpha - 1, -alpha, depth - 1 + extension, ply + 1, timeManager, numExtension + extension);
                score = -childInfo.state_value;
                //If it turns out we found a better move then do a full search
                if (score > alpha && score < beta){
                    childInfo = Search(board, -beta, -alpha, depth - 1 + extension, ply + 1, timeManager, numExtension + extension);
                    score = -childInfo.state_value;
                }
            }
            nnueEval.undoMove(board);

            // If time is up return the best move found so far.
            if (timeManager.shouldCancel()) {
                return new MinimaxInfo(0,null);
            }

            if (score > bestValue) {
                bestValue = score;
                bestMove = move;

                bestLine.clear();
                bestLine.add(move);
                bestLine.addAll(childInfo.main_line);
                // We found a new highest valued move in the position. Record it for future pruning
                if (score > alpha) {
                    alpha = score;
                }
            }
            // Beta cutoff, the tree is pruned, no further nodes will be explored
            if (score >= beta) {
                TOTAL_PRUNES++;
                // Killer Move is a quiet move which caused a beta-cutoff
                if (bestMove != null && !boardHelper.isCapture(board, bestMove)){
                    /*
                    Storing killer moves based on ply, replacing killer moves by latest beta cutoff
                     */
                    boardHelper.killerMoves[1][ply] = boardHelper.killerMoves[0][ply];
                    boardHelper.killerMoves[0][ply] = bestMove;
                }
                break;
            }
        }
        // Store position in transposition table
        FLAG flag;
        if (bestValue >= beta) {
            flag = FLAG.LOWER;
        } else if (bestValue > alpha) {
            flag = FLAG.EXACT;
        } else {
            flag = FLAG.UPPER;
        }
        TT.store(board.getZobristKey(),  depth, scoreToTT(bestValue, ply), flag, bestMove, bestLine);
        return new MinimaxInfo(bestValue, bestMove, bestLine, depth);
    }

    // Mate scores count plies from the root of the search, but a stored position can be reached again at another ply
    // or in a later search, so the table holds them counted from the position itself
    private static int scoreToTT(int score, int ply){
        if (score >= MATE_BOUND){
            return score + ply;
        }
        if (score <= -MATE_BOUND){
            return score - ply;
        }
        return score;
    }

    private static int scoreFromTT(int score, int ply){
        if (score >= MATE_BOUND){
            return score - ply;
        }
        if (score <= -MATE_BOUND){
            return score + ply;
        }
        return score;
    }

    public static void main(String[] args){
        Board board = new Board();
        //board.loadFromFen("7k/5b2/3p3K/8/5N2/8/3p3p/6qQ w - - 0 1");
        Engine myEngine = new Engine();

        MinimaxInfo engine_choice;
        while (!board.isMated() && !board.isDraw() && !board.isStaleMate()){
            System.out.println(board);
            engine_choice = myEngine.Think(board, 10000);
            Move engineMove = engine_choice.move;
            int engine_state_value = engine_choice.state_value;
            board.doMove(engineMove);
            System.out.println("Engine move:" + engineMove + " State value:" + (float) engine_state_value / 100 + " Total prunes:" + myEngine.TOTAL_PRUNES + " Depth:" + engine_choice.depth);
        }
        System.out.println("Draw?:" + board.isDraw());
        System.out.println(board);
        System.out.println(board.getFen());
    }
}
