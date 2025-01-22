package org.example;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;



public class Helper {
    SimpleEval simpleEval = new SimpleEval();
    private Move idMove;
    public Move [] killerMoves = new Move[1000];

    //fetching iterative deepening move
    public void iterativeDeepeningMove(Move move){
        this.idMove = move;
    }

    //Null move pruning
    public boolean nullMovePruning(Board board){
        // King's bitboard
        long kingBitboard = board.getBitboard(Piece.make(board.getSideToMove(), PieceType.KING));
        // Pawn's bitboard
        long pawnBitboard = board.getBitboard(Piece.make(board.getSideToMove(), PieceType.PAWN));
        // All pieces bitboard
        long allPiecesBitboard = board.getBitboard(board.getSideToMove());
        // As long as we have other pieces on the board that are not king and pawns, allow null move pruning
        return (kingBitboard | pawnBitboard) != allPiecesBitboard;
    }

    public boolean isCapture(Board board, Move move){
        Square origin = move.getFrom();
        // What is the square the piece is moving to
        Square destination = move.getTo();
        // get piece at destination square
        Piece destinationPiece = board.getPiece(destination);
        // get piece at origin
        Piece originPiece = board.getPiece(origin);
        // If there is nothing at that destination square
        return destinationPiece != Piece.NONE && destinationPiece.getPieceSide() != originPiece.getPieceSide();
    }

    // check if a move is a check
    public boolean isCheck(Board board, Move move) {
        board.doMove(move);
        boolean isCheck = board.isKingAttacked();
        board.undoMove();
        return isCheck;
    }

    // Sorting by MVV-LVA, TT moves, checks, promotions and previous move from ID
    public List<Move> sortMoves(Board board, List<Move> legalMoves, TranspositionTable transpositionTable, int ply){
        List<MoveInfo> move_scores = new ArrayList<>();
        List<Move> sortedMoves = new ArrayList<>();

        for (Move move : legalMoves){
            if(!board.doMove(move)){continue;}
            board.undoMove();
            MoveInfo moveInfo = new MoveInfo(move,calculateMoveValue(board,move, transpositionTable, ply));
            move_scores.add(moveInfo);
        }

        // sort by biggest to smallest
        move_scores.sort((a,b) -> b.value - a.value);
        for (MoveInfo move_info : move_scores) {
            sortedMoves.add(move_info.move);
        }
        return sortedMoves;
    }

    // Calculating the value of each moves according to MVV-LVA but checking TT moves first + valuing promotions and checks
    private int calculateMoveValue(Board board, Move move, TranspositionTable transpositionTable, int ply){
        //ID value should always be looked at first
        if (move.equals(idMove)) {
            return 2000;
        }
        if (killerMoves[ply] != null && move.equals(killerMoves[ply])){
            return 1500;
        }
        //Transposition value
        if (transpositionTable.containsKey(board.getZobristKey())){
            TranspositionTable.Entry node = transpositionTable.getEntry(board.getZobristKey());
            Move TT_move = node.move;
           if(move.equals(TT_move)){
                return 1000 - node.depth;
            }
        }

        // Promotion Handling
        if (move.getPromotion() != null) {
            return 900; // Promotions are highly prioritized
        }

        // Check Handling
        if (isCheck(board, move)) {
            return 300; // Checks are prioritized after PV and promotions
        }

        //MVV-LVA here
        // Origin square and destination square
        Square origin = move.getFrom();
        Square destination = move.getTo();
        // Getting the pieces at origin and destination
        Piece originPiece = board.getPiece(origin);
        Piece destinationPiece = board.getPiece(destination);
        // If its not a capture then we dont have an opinion on it
        if (!isCapture(board,move)){
            return 0;
        }

        // Getting the piece type of victim and attacker
        PieceType origin_piece_type = originPiece.getPieceType();
        PieceType destination_piece_type = destinationPiece.getPieceType();

        // Getting the values of attacker and victim
        int origin_piece_value = simpleEval.pieceWorthMg(origin_piece_type);
        int destination_piece_value = simpleEval.pieceWorthMg(destination_piece_type);
        return destination_piece_value - origin_piece_value;
    }


    private static class MoveInfo{
        Move move;
        int value;

        public MoveInfo(Move move, int value){
            this.move = move;
            this.value = value;
        }
    }
}
