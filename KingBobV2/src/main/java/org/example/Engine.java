package org.example;

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
- Q search (check + captures)
- MVV-LVA sorted moves
- Iterative deepening (time constraint + reusing TT positions, alpha and beta bounds)
Evaluation:
- Tampered eval (Game phase decided by number of pieces on the board)
- Total material (weighted by number of pieces)
- Piece square table (weighted by number of pieces)
- Simple mobility
- Doubled pawns punishment
- Reward passed pawns
 */

public class Engine{
    // Classes for eval, move ordering, TT
    private final Evaluation evaluation = new Evaluation();
    private final Helper boardHelper = new Helper();
    private final TranspositionTable TT = new TranspositionTable(1024);
    private static final int MATE_SCORE = 1000000;
    // Search debug info
    int TOTAL_PRUNES;
    int TOTAL_NODES;

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

    public MinimaxInfo Think(Board board, int alpha, int beta, long searchTime){
        // Parameters
        int depth = 1;
        MinimaxInfo bestChoice = null;
        int bestChoiceDepth = 1;
        // Time manager class
        SearchManager timeManager = new SearchManager(searchTime);
        // We have to reset TT if it overflows
        if (TT.getCapacity() >= 100){
            TT.clear();
        }
        while (depth <= 64) {
            if (timeManager.shouldCancel()) {
                break;
            }
            TOTAL_NODES = 0;
            //Starting clock
            Instant starts = Instant.now();
            //Running search
            MinimaxInfo currChoice = Search(board, alpha, beta, depth, 0, timeManager);
            //Stop clock
            Instant end = Instant.now();
            //print duration of search
            long timeElapsed = Duration.between(starts,end).toMillis();
            long nps = (long)(TOTAL_NODES / Math.max(timeElapsed / 1000.0, 0.001));
            System.out.printf("VERSION 1.6 | Depth: %-2d | Time: %-5d | NPS: %-7d | Eval: %6.2f | Result Depth: %-2d | Line: %s%n",
                    depth,
                    timeElapsed,
                    nps,
                    (float)currChoice.state_value/100,
                    currChoice.depth,
                    currChoice.main_line
            );
            //Avoid taking none completed search
            //If at depth 1 we have a cached depth of 7 and then at depth 2 we re-search. If we run out of time use the cached result at depth 1
            if (currChoice.move != null && bestChoiceDepth <= currChoice.depth ){
                bestChoice = currChoice;
                bestChoiceDepth = bestChoice.depth;
            }
            depth++;

        }
        return bestChoice;
    }

    // Sorting by MVV-LVA and TT + checks and promotions
    private List<Move> actions(Board board){
        return boardHelper.sortMoves(board,board.pseudoLegalMoves(), TT);
    }


    // Search through capture and check moves to give an accurate eval of quiet positions
    private int QSearch(Board board, int alpha, int beta) {
        TOTAL_NODES++;

        if (board.isRepetition()) {
            return 0;
        }

        int stand_pat = evaluation.eval(board);
        int bestValue = stand_pat;

        if (stand_pat >= beta) {
            TOTAL_PRUNES++;
            return stand_pat;
        }

        if (alpha < stand_pat) {
            alpha = stand_pat;
        }

        for (Move action : actions(board)) {
            if (boardHelper.isCapture(board, action) || boardHelper.isCheck(board, action)) {
                if (!board.doMove(action)){
                    continue;
                }
                int score = -QSearch(board, -beta, -alpha);
                board.undoMove();

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
        }
        return bestValue;
    }

    private MinimaxInfo Search(Board board, int alpha, int beta, int depth, int ply, SearchManager timeManager) {
        TOTAL_NODES++;

        // Time management check
        if (timeManager.shouldCancel()) {
            return new MinimaxInfo(0, null);
        }

        // Checkmate detection
        if (board.isMated()) {
            return new MinimaxInfo(-MATE_SCORE - depth, null);
        }

        // Draw detection
        if (board.isRepetition()) {
            return new MinimaxInfo(0, null);
        }

        // Transposition table lookup
        TranspositionTable.Entry entry = TT.getEntry(board.getZobristKey());
        if (entry != null && entry.depth >= depth && ply > 0) {
            int entryValue = entry.value;
            Move entryMove = entry.move;
            List<Move> entryLine = entry.mainLine;
            int entryDepth = entry.depth;

            // Adjust the bestValue based on the flag
            if (entry.flag == FLAG.EXACT) {
                return new MinimaxInfo(entryValue, entryMove, entryLine, entryDepth);
            } else if (entry.flag == FLAG.LOWER && entryValue > beta) {
                return new MinimaxInfo(beta, entryMove, entryLine, entryDepth);
            } else if (entry.flag == FLAG.UPPER && entryValue <= alpha) {
                return new MinimaxInfo(alpha, entryMove, entryLine, entryDepth);
            }
        }

        // Quiescence search at leaf nodes
        if (depth == 0) {
            int score = QSearch(board, alpha, beta);
            return new MinimaxInfo(score, null);
        }

        int bestValue = -Integer.MAX_VALUE;
        Move bestMove = null;
        List<Move> bestLine = new ArrayList<>();

        // Main search loop
        for (Move action : actions(board)) {
            if (!board.doMove(action)){
                continue;
            }
            MinimaxInfo childInfo = Search(board, -beta, -alpha, depth - 1, ply + 1, timeManager);
            int score = -childInfo.state_value;
            board.undoMove();

            // Time management check
            if (timeManager.shouldCancel()) {
                return new MinimaxInfo(0, null);
            }

            if (score > bestValue) {
                bestValue = score;
                bestMove = action;

                bestLine.clear();
                bestLine.add(action);
                bestLine.addAll(childInfo.main_line);

                if (score > alpha) {
                    alpha = score;
                }
            }

            if (score >= beta) {
                TOTAL_PRUNES++;
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
        TT.store(board.getZobristKey(),  depth, bestValue, flag, bestMove, bestLine);
        return new MinimaxInfo(bestValue, bestMove, bestLine, depth);
    }

    public static void main(String[] args){
        Board board = new Board();
        //board.loadFromFen("8/8/8/2P3R1/5B2/2rP1p2/p1P1PP2/RnQ1K2k w Q - 5 3");
        Engine myEngine = new Engine();

        //Parameters
        int alpha = -MATE_SCORE;
        int beta = MATE_SCORE;

        MinimaxInfo engine_choice;
        int move_counter = 0;
        while (!board.isMated() && !board.isDraw() && !board.isStaleMate()){
            System.out.println(board);
            engine_choice = myEngine.Think(board,alpha,beta,10000);
            Move engineMove = engine_choice.move;
            int engine_state_value = engine_choice.state_value;
            board.doMove(engineMove);
            System.out.println("Engine move:" + engineMove+ " State value:" + (float)engine_state_value/100 + " Total prunes:" + myEngine.TOTAL_PRUNES + " Depth:" + engine_choice.depth);
        }
        System.out.println("Draw?:" + board.isDraw());
        System.out.println("Number of moves:"+move_counter);
        System.out.println(board);
        System.out.println(board.getFen());
    }
}