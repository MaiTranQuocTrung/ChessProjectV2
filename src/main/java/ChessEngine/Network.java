package ChessEngine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quantized (768 -> H)x2 -> 1 network with SCReLU activation, trained by the ChessNNUE project.
 *
 * <p>Input layer: 768 binary neurons, one per (piece colour, piece type, square). Each perspective sees
 * the board with its own pieces as "colour 0"; the black perspective additionally mirrors the board
 * vertically (a1 <-> a8). Both perspectives share the same weights.
 *
 * <p>Accumulator (first hidden layer): weights and biases scaled by {@code qa}, int16 in the file.
 *
 * <p>Output: {@code SCReLU(acc_stm) ++ SCReLU(acc_nstm)} (side to move first) dotted with int16 weights
 * scaled by {@code qb}, where {@code SCReLU(x) = clamp(x, 0, qa)^2}.
 *
 * <p>Weights and accumulators are held in int arrays although they fit in int16 (the exporter checks the
 * worst case): HotSpot only vectorizes the clamp-and-dot output layer on int lanes, which makes it
 * about six times faster than with short arrays.
 */
public final class Network {
    public static final int INPUTS = 768;
    /** Perspectives, equal to {@code Side.WHITE.ordinal()} and {@code Side.BLACK.ordinal()}. */
    public static final int WHITE = 0, BLACK = 1;
    private static final int MAGIC = 0x45554E4E; // "NNUE" read as little-endian int
    private static final int VERSION = 1;

    public final int hidden;
    public final int qa;
    public final int qb;
    public final int scale;
    final int[] ftWeights;  // [INPUTS * hidden], row f holds the weights of input neuron f
    final int[] ftBias;     // [hidden]
    final int[] outWeights; // [2 * hidden]: side-to-move half, then other half
    final int outBias;
    /** True if the output dot product provably fits in an int for every possible accumulator. */
    private final boolean intDotIsSafe;

    public Network(int hidden, int qa, int qb, int scale, short[] ftWeights, short[] ftBias, short[] outWeights,
                   int outBias) {
        if (ftWeights.length != INPUTS * hidden || ftBias.length != hidden || outWeights.length != 2 * hidden) {
            throw new IllegalArgumentException("weight shapes do not match hidden size " + hidden);
        }
        this.hidden = hidden;
        this.qa = qa;
        this.qb = qb;
        this.scale = scale;
        this.ftWeights = widen(ftWeights);
        this.ftBias = widen(ftBias);
        this.outWeights = widen(outWeights);
        this.outBias = outBias;
        this.intDotIsSafe = halfFitsInInt(0) && halfFitsInInt(hidden);
    }

    private static int[] widen(short[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }

    private boolean halfFitsInInt(int offset) {
        long pos = 0, neg = 0, maxAct = (long) qa * qa;
        for (int i = offset; i < offset + hidden; i++) {
            if (outWeights[i] > 0) pos += outWeights[i] * maxAct;
            else neg += outWeights[i] * maxAct;
        }
        // Both halves are summed in int before being widened, so each half must fit on its own.
        return pos <= Integer.MAX_VALUE && neg >= Integer.MIN_VALUE;
    }

    /**
     * Input neuron for {@code piece} on {@code square}, seen from {@code perspective}. Pieces and squares are
     * chesslib ordinals: {@code WHITE_PAWN} = 0 ... {@code BLACK_KING} = 11 (colour * 6 + type), {@code A1} = 0 ...
     * {@code H8} = 63. The black perspective swaps colours and mirrors the square vertically (sq ^ 56).
     */
    public static int featureIndex(int perspective, int piece, int square) {
        int relative = perspective == WHITE ? piece : (piece >= 6 ? piece - 6 : piece + 6);
        return relative * 64 + (square ^ (56 * perspective));
    }

    // ------------------------------------------------------------------ accumulator kernels
    // Offsets are feature row offsets (featureIndex * hidden). Simple counted loops over int[]
    // are auto-vectorised by HotSpot C2 into SIMD adds/subtracts.

    /** Full computation of one perspective's accumulator: bias + sum of active input rows. */
    public void refresh(Board board, int perspective, int[] out) {
        System.arraycopy(ftBias, 0, out, 0, hidden);
        for (int piece = 0; piece < 12; piece++) {
            for (long b = board.getBitboard(Piece.allPieces[piece]); b != 0; b &= b - 1) {
                addRow(out, featureIndex(perspective, piece, Long.numberOfTrailingZeros(b)) * hidden);
            }
        }
    }

    void addRow(int[] acc, int row) {
        final int[] w = ftWeights;
        for (int i = 0; i < hidden; i++) acc[i] += w[row + i];
    }

    void subRow(int[] acc, int row) {
        final int[] w = ftWeights;
        for (int i = 0; i < hidden; i++) acc[i] -= w[row + i];
    }

    /** Quiet move or promotion: one neuron turns on, one turns off. */
    void addSub(int[] src, int[] dst, int add, int sub) {
        final int[] w = ftWeights;
        for (int i = 0; i < hidden; i++) dst[i] = src[i] + w[add + i] - w[sub + i];
    }

    /** Capture (including en passant and capture-promotion): one neuron on, two off. */
    void addSubSub(int[] src, int[] dst, int add, int sub1, int sub2) {
        final int[] w = ftWeights;
        for (int i = 0; i < hidden; i++) dst[i] = src[i] + w[add + i] - w[sub1 + i] - w[sub2 + i];
    }

    /** Castling: king and rook both move, two neurons on and two off. */
    void addAddSubSub(int[] src, int[] dst, int add1, int add2, int sub1, int sub2) {
        final int[] w = ftWeights;
        for (int i = 0; i < hidden; i++) dst[i] = src[i] + w[add1 + i] + w[add2 + i] - w[sub1 + i] - w[sub2 + i];
    }

    // ------------------------------------------------------------------ output layer

    /** Evaluation in centipawns from the side to move's point of view. */
    public int forward(int[] stmAcc, int[] nstmAcc) {
        long sum = intDotIsSafe
                ? (long) screluDot(stmAcc, 0) + screluDot(nstmAcc, hidden)
                : screluDotWide(stmAcc, 0) + screluDotWide(nstmAcc, hidden);
        // sum is scaled by qa*qa*qb; dividing by qa brings it to the output bias' qa*qb scale.
        long out = sum / qa + outBias;
        return (int) (out * scale / ((long) qa * qb));
    }

    private int screluDot(int[] acc, int offset) {
        final int[] w = outWeights;
        final int max = qa;
        int sum = 0;
        for (int i = 0; i < hidden; i++) {
            int v = Math.min(Math.max(acc[i], 0), max);
            sum += v * v * w[offset + i];
        }
        return sum;
    }

    private long screluDotWide(int[] acc, int offset) {
        long sum = 0;
        for (int i = 0; i < hidden; i++) {
            int v = Math.min(Math.max(acc[i], 0), qa);
            sum += (long) (v * v) * outWeights[offset + i];
        }
        return sum;
    }

    /** Evaluates from scratch (both accumulators fully recomputed). Reference for the incremental path. */
    public int evaluateFull(Board board) {
        int[] white = new int[hidden], black = new int[hidden];
        refresh(board, WHITE, white);
        refresh(board, BLACK, black);
        return board.getSideToMove().ordinal() == WHITE ? forward(white, black) : forward(black, white);
    }

    // ------------------------------------------------------------------ file format

    /**
     * Little-endian layout: int32 magic "NNUE", version, inputs, hidden, qa, qb, scale;
     * int16 ftWeights[768 * hidden]; int16 ftBias[hidden]; int16 outWeights[2 * hidden]; int32 outBias.
     */
    public static Network load(Path path) throws IOException {
        return load(Files.readAllBytes(path), path.toString());
    }

    /** Loads from a stream, e.g. a classpath resource inside a jar. */
    public static Network load(InputStream in) throws IOException {
        return load(in.readAllBytes(), "stream");
    }

    private static Network load(byte[] bytes, String name) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != MAGIC) throw new IOException("not an NNUE file: " + name);
        if (buf.getInt() != VERSION) throw new IOException("unsupported NNUE version: " + name);
        if (buf.getInt() != INPUTS) throw new IOException("expected 768 inputs: " + name);
        int hidden = buf.getInt(), qa = buf.getInt(), qb = buf.getInt(), scale = buf.getInt();
        short[] ftW = readShorts(buf, INPUTS * hidden);
        short[] ftB = readShorts(buf, hidden);
        short[] outW = readShorts(buf, 2 * hidden);
        int outB = buf.getInt();
        if (buf.hasRemaining()) throw new IOException("trailing bytes in " + name);
        return new Network(hidden, qa, qb, scale, ftW, ftB, outW, outB);
    }

    private static short[] readShorts(ByteBuffer buf, int n) {
        short[] out = new short[n];
        buf.asShortBuffer().get(out);
        buf.position(buf.position() + 2 * n);
        return out;
    }
}
