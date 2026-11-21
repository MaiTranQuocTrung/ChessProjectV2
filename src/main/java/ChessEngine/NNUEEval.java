package ChessEngine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.CastleRight;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.game.GameContext;
import com.github.bhlangonijr.chesslib.move.Move;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/*
NNUE evaluation, a (768 -> 1024)x2 -> 1 network trained on Lichess positions evaluated by Stockfish:
- Input: 768 neurons, one per (piece colour, piece type, square)
- Accumulator: 1024 neurons seen from white and 1024 seen from black (board flipped, colours swapped),
  both perspectives use the same weights
- Output: side to move's accumulator + other side's accumulator -> SCReLU -> centipawns for the side to move
Efficient updates:
- The search makes and takes back moves through doMove/doNullMove/undoMove, which keep accumulators per ply
- A ply's accumulators are its parent's plus/minus the weight rows of the neurons the move changed:
  2 for quiet moves and promotions, 3 for captures, 4 for castling
- Accumulators are only brought up to date when a position is evaluated
 */
public class NNUEEval {
    private static final int MATE_SCORE = 5000;
    private static final String NETWORK_FILE = "nnue-768x1024.nnue";
    private static final int MAX_PLY = 1024;
    // Castling switches 2 neurons on and 2 off, the most of any move
    private static final int MAX_CHANGES = 4;
    // Run with -Dnnue.verify=true to compare every evaluation with a full recomputation (slow, for debugging)
    private static final boolean VERIFY = Boolean.getBoolean("nnue.verify");
    // Loaded once and shared by every NNUEEval
    private static final Network network = loadNetwork();

    private final int hidden = network.hidden;
    // Accumulators per ply: [ply][perspective (0 white, 1 black)][neuron]
    private final int[][][] accumulators = new int[MAX_PLY][2][hidden];
    private final boolean[] computed = new boolean[MAX_PLY];
    // Neurons each ply's move switched on and off, as piece * 64 + square
    private final int[][] added = new int[MAX_PLY][MAX_CHANGES];
    private final int[][] removed = new int[MAX_PLY][MAX_CHANGES];
    private final int[] numAdded = new int[MAX_PLY];
    private final int[] numRemoved = new int[MAX_PLY];
    private int ply;

    // Computes the root position's accumulators from scratch, call before searching it
    public void reset(Board board){
        ply = 0;
        network.refresh(board, Network.WHITE, accumulators[0][Network.WHITE]);
        network.refresh(board, Network.BLACK, accumulators[0][Network.BLACK]);
        computed[0] = true;
    }

    // Use instead of board.doMove(move). Returns false and changes nothing if the move is illegal
    public void doMove(Board board, Move move){
        recordMove(board, move);
        // no need for full validation this is done by moveGenerator already
        board.doMove(move, false);
        /*boolean played = board.doMove(move, false);
        if (VERIFY && !played){
            throw new IllegalStateException("Illegal move reached the search: " + move + " in " + board.getFen());
        }*/
    }

    // Use instead of board.doNullMove()
    public void doNullMove(Board board){
        board.doNullMove();
        nextPly();
    }

    // Use instead of board.undoMove(), after a move or a null move
    public void undoMove(Board board){
        board.undoMove();
        ply--;
    }

    // Evaluation in centipawns from the side to move's point of view
    public int positionalEvaluation(Board board){
        /*if (board.isMated()){
            return -MATE_SCORE;
        }*/
        updateAccumulators();
        int us = board.getSideToMove().ordinal();
        int eval = network.forward(accumulators[ply][us], accumulators[ply][us ^ 1]);
        if (VERIFY && eval != network.evaluateFull(board)){
            throw new IllegalStateException("NNUE accumulators out of sync with the board: " + board.getFen());
        }
        return eval;
    }

    // Records the neurons the move switches on and off, must be called before the move is played
    private void recordMove(Board board, Move move){
        Square from = move.getFrom();
        Square to = move.getTo();
        Piece piece = board.getPiece(from);
        Piece captured = board.getPiece(to);
        Piece promotion = move.getPromotion();
        nextPly();
        if (captured != Piece.NONE){
            removePiece(captured, to);
        }
        // En passant: the captured pawn is not on the destination square
        else if (piece.getPieceType() == PieceType.PAWN && from.getFile() != to.getFile()
                && board.getEnPassantTarget() != Square.NONE){
            Square target = board.getEnPassantTarget();
            Piece victim = board.getPiece(target);
            if (victim != Piece.NONE){
                removePiece(victim, target);
            }
        }
        removePiece(piece, from);
        addPiece(promotion == Piece.NONE ? piece : promotion, to);
        // Castling also moves the rook
        if (piece.getPieceType() == PieceType.KING){
            GameContext context = board.getContext();
            if (context.isCastleMove(move)){
                CastleRight castleSide = context.isKingSideCastle(move) ? CastleRight.KING_SIDE : CastleRight.QUEEN_SIDE;
                Move rookMove = context.getRookCastleMove(piece.getPieceSide(), castleSide);
                Piece rook = board.getPiece(rookMove.getFrom());
                removePiece(rook, rookMove.getFrom());
                addPiece(rook, rookMove.getTo());
            }
        }
    }

    private void nextPly(){
        ply++;
        computed[ply] = false;
        numAdded[ply] = 0;
        numRemoved[ply] = 0;
    }

    private void addPiece(Piece piece, Square square){
        added[ply][numAdded[ply]++] = piece.ordinal() * 64 + square.ordinal();
    }

    private void removePiece(Piece piece, Square square){
        removed[ply][numRemoved[ply]++] = piece.ordinal() * 64 + square.ordinal();
    }

    // Plies that were never evaluated are computed now, starting from the nearest computed ply
    private void updateAccumulators(){
        if (computed[ply]){
            return;
        }
        int base = ply - 1;
        while (!computed[base]){
            base--;
        }
        for (int p = base + 1; p <= ply; p++){
            updatePly(p);
        }
    }

    // Parent's accumulators + rows of the neurons switched on - rows of the neurons switched off, in one pass
    private void updatePly(int p){
        int na = numAdded[p];
        int nr = numRemoved[p];
        int[] add = added[p];
        int[] rem = removed[p];
        for (int perspective = 0; perspective < 2; perspective++){
            int[] src = accumulators[p - 1][perspective];
            int[] dst = accumulators[p][perspective];
            if (na == 1 && nr == 1){
                // Quiet move or promotion
                network.addSub(src, dst, row(add[0], perspective), row(rem[0], perspective));
            }
            else if (na == 1 && nr == 2){
                // Capture, en passant or capture-promotion
                network.addSubSub(src, dst, row(add[0], perspective), row(rem[0], perspective), row(rem[1], perspective));
            }
            else if (na == 2 && nr == 2){
                // Castling
                network.addAddSubSub(src, dst, row(add[0], perspective), row(add[1], perspective),
                        row(rem[0], perspective), row(rem[1], perspective));
            }
            else {
                // Null move (nothing changes)
                System.arraycopy(src, 0, dst, 0, hidden);
                for (int i = 0; i < na; i++) network.addRow(dst, row(add[i], perspective));
                for (int i = 0; i < nr; i++) network.subRow(dst, row(rem[i], perspective));
            }
        }
        computed[p] = true;
    }

    // Offset of the weight row of a piece on a square, seen from one perspective
    private int row(int pieceSquare, int perspective){
        return Network.featureIndex(perspective, pieceSquare >>> 6, pieceSquare & 63) * hidden;
    }

    // The network is a resource, so it is packed into the jar. When the resources folder is not on the
    // classpath (compiled without Maven), it is read from the file in the project folder
    private static Network loadNetwork(){
        try (InputStream in = NNUEEval.class.getResourceAsStream("/" + NETWORK_FILE)){
            if (in != null){
                return Network.load(in);
            }
            Path file = Path.of("src", "main", "resources", NETWORK_FILE);
            if (Files.exists(file)){
                return Network.load(file);
            }
        } catch (IOException e){
            throw new UncheckedIOException("Could not read the NNUE network " + NETWORK_FILE, e);
        }
        throw new IllegalStateException("NNUE network " + NETWORK_FILE + " not found on the classpath or in src/main/resources");
    }
}
