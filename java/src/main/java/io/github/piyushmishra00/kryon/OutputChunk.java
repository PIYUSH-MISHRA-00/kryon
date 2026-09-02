package io.github.piyushmishra00.kryon;

import java.util.Arrays;
import java.util.Objects;

/**
 * One chunk of output, tagged with the pipe it arrived on.
 *
 * <p>Chunk boundaries mean nothing. They reflect how the operating system delivered the data, not
 * lines or records.
 *
 * @param stream which pipe the bytes arrived on
 * @param data the bytes; never shared with Kryon's internals, so it is safe to keep
 */
public record OutputChunk(StreamKind stream, byte[] data) {

    /**
     * Creates a chunk, defensively copying the data.
     *
     * @param stream which pipe the bytes arrived on
     * @param data the bytes
     */
    public OutputChunk {
        Objects.requireNonNull(stream, "stream");
        data = data == null ? new byte[0] : data.clone();
    }

    /**
     * The bytes of this chunk.
     *
     * @return a copy, so a caller keeping the chunk cannot be surprised by later mutation
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutputChunk chunk
                && stream == chunk.stream
                && Arrays.equals(data, chunk.data);
    }

    @Override
    public int hashCode() {
        return 31 * stream.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "OutputChunk[" + stream + ", " + data.length + " bytes]";
    }
}
