package org.example;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

public class VersionTester {
    public static void main(String [] args){
        Board board = new Board();
        OldEngine oldEngine = new OldEngine();
        Engine engine = new Engine();
        Engine.MinimaxInfo engine_choice;
        OldEngine.MinimaxInfo oldEngineChoice;
            while (!board.isMated() && !board.isDraw() && !board.isStaleMate()) {
                if (board.getSideToMove() == Side.BLACK) {
                    System.out.println(board);
                    engine_choice = engine.Think(board, 500);
                    Move engineMove = engine_choice.move;
                    int engine_state_value = engine_choice.state_value;
                    board.doMove(engineMove);
                    System.out.println("Engine move:" + engineMove + " State value:" + (float) engine_state_value / 100 + " Total prunes:" + engine.TOTAL_PRUNES + " Depth:" + engine_choice.depth);
                } else {
                    System.out.println(board);
                    oldEngineChoice = oldEngine.Think(board, 500);
                    Move engineMove = oldEngineChoice.move;
                    int oldEngineValue = oldEngineChoice.state_value;
                    board.doMove(engineMove);
                    System.out.println("Engine move:" + engineMove + " State value:" + (float) oldEngineValue / 100 + " Total prunes:" + oldEngine.TOTAL_PRUNES + " Depth:" + oldEngineChoice.depth);
                }
            }
            System.out.println("DRAW:" + board.isDraw());
            boolean whiteWin = board.getSideToMove() == Side.BLACK;
            System.out.println("WHITE WIN?:" + whiteWin);
    }
}
