package VersionTesterTools;

import ChessEngine.Engine;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

/*
    Run:
    java -XX:StartFlightRecording=duration=60s,filename=C:\Users\ASUS\ChessProjectV2\search.jfr -jar out\artifacts\OptimTester_jar\KingBob.jar
    jfr view hot-methods search.jfr
 */
public class OptimizationCalibration {
    public static void main(String[] args){
        Engine engine = new Engine();
        Board board = new Board();
        //board.loadFromFen("r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2BPP3/2N2N2/PPP2PPP/R1BQK2R w KQ - 0 1");
        // number of plys
        int ply_num = 15;
        for (int i = 0; i < ply_num; i++){
            System.out.println("Move:" + i);
            System.out.println(board);
            Engine.MinimaxInfo engine_choice = engine.Think(board, 5000);
            Move engineMove = engine_choice.move;
            int engine_state_value = engine_choice.state_value;
            board.doMove(engineMove);
            System.out.println("Engine move:" + engineMove + " State value:" + (float) engine_state_value / 100 + " Depth:" + engine_choice.depth);
        }
    }
}
