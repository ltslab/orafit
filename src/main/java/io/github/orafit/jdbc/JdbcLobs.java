package io.github.orafit.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

/**
 * Oracle CLOB and BLOB columns arrive from Ora2Pg as text and bytea. The JDBC LOB methods an Oracle
 * application calls on them (getClob, getBlob, createClob, createBlob, setClob, setBlob) are
 * answered with in-memory LOBs over the value.
 */
final class JdbcLobs {
    private JdbcLobs() {}

    static boolean getter(String name) {
        return name.equals("getClob") || name.equals("getNClob") || name.equals("getBlob");
    }

    /** The LOB for a getClob/getBlob call by index or label, or null for SQL NULL. */
    static Object read(ResultSet result, String name, Object column) throws SQLException {
        if (name.equals("getBlob")) {
            byte[] bytes =
                    column instanceof Integer index
                            ? result.getBytes(index)
                            : result.getBytes((String) column);
            return bytes == null ? null : new MemoryBlob(bytes);
        }
        String text =
                column instanceof Integer index
                        ? result.getString(index)
                        : result.getString((String) column);
        return text == null ? null : new MemoryClob(text);
    }

    static Clob newClob() {
        return new MemoryClob("");
    }

    static Blob newBlob() {
        return new MemoryBlob(new byte[0]);
    }

    /** Binds setClob/setNClob/setBlob as the text or bytes they hold; false when not one. */
    static boolean bind(
            PreparedStatement delegate, List<Integer> outputs, Method method, Object[] args)
            throws SQLException {
        String name = method.getName();
        if (!(name.equals("setClob") || name.equals("setNClob") || name.equals("setBlob"))
                || args == null
                || args.length < 2) return false;
        boolean blob = name.equals("setBlob");
        Object value = args[1];
        for (int output : outputs) {
            if (value == null) {
                delegate.setNull(output, blob ? Types.BINARY : Types.VARCHAR);
            } else if (value instanceof Clob clob) {
                delegate.setString(output, clob.getSubString(1, Math.toIntExact(clob.length())));
            } else if (value instanceof Blob lob) {
                delegate.setBytes(output, lob.getBytes(1, Math.toIntExact(lob.length())));
            } else if (value instanceof Reader reader) {
                // pgJDBC implements only the int length of a character stream.
                if (args.length > 2)
                    delegate.setCharacterStream(output, reader, Math.toIntExact((Long) args[2]));
                else delegate.setCharacterStream(output, reader);
            } else if (value instanceof InputStream stream) {
                if (args.length > 2) delegate.setBinaryStream(output, stream, (Long) args[2]);
                else delegate.setBinaryStream(output, stream);
            } else {
                return false;
            }
        }
        return true;
    }

    /** A position or length outside the value; nothing is padded or cut to make it fit. */
    private static SQLException invalid() {
        return new SQLException("Invalid position or length", "22003");
    }

    private static final class MemoryClob implements Clob {
        private StringBuilder text;

        MemoryClob(String value) {
            text = new StringBuilder(value);
        }

        @Override
        public long length() {
            return text.length();
        }

        @Override
        public String getSubString(long pos, int length) throws SQLException {
            int from = Math.toIntExact(pos) - 1;
            if (from < 0 || from > text.length() || length < 0) throw invalid();
            return text.substring(from, Math.min(text.length(), from + length));
        }

        @Override
        public Reader getCharacterStream() {
            return new StringReader(text.toString());
        }

        @Override
        public Reader getCharacterStream(long pos, long length) throws SQLException {
            return new StringReader(getSubString(pos, Math.toIntExact(length)));
        }

        @Override
        public InputStream getAsciiStream() {
            return new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public long position(String search, long start) throws SQLException {
            if (start < 1) throw invalid();
            int found = text.indexOf(search, Math.toIntExact(start) - 1);
            return found < 0 ? -1 : found + 1;
        }

        @Override
        public long position(Clob search, long start) throws SQLException {
            return position(search.getSubString(1, Math.toIntExact(search.length())), start);
        }

        @Override
        public int setString(long pos, String value) throws SQLException {
            return setString(pos, value, 0, value.length());
        }

        @Override
        public int setString(long pos, String value, int offset, int length) throws SQLException {
            int from = Math.toIntExact(pos) - 1;
            if (from < 0 || from > text.length()) throw invalid();
            String part = value.substring(offset, offset + length);
            text.replace(from, Math.min(text.length(), from + part.length()), part);
            return part.length();
        }

        @Override
        public OutputStream setAsciiStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob.setAsciiStream", "0A000");
        }

        @Override
        public Writer setCharacterStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob.setCharacterStream", "0A000");
        }

        @Override
        public void truncate(long length) throws SQLException {
            if (length < 0 || length > text.length()) throw invalid();
            text.setLength(Math.toIntExact(length));
        }

        @Override
        public void free() {
            text = new StringBuilder();
        }
    }

    private static final class MemoryBlob implements Blob {
        private byte[] bytes;

        MemoryBlob(byte[] value) {
            bytes = value.clone();
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes(long pos, int length) throws SQLException {
            int from = Math.toIntExact(pos) - 1;
            if (from < 0 || from > bytes.length || length < 0) throw invalid();
            return Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + length));
        }

        @Override
        public InputStream getBinaryStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public InputStream getBinaryStream(long pos, long length) throws SQLException {
            return new ByteArrayInputStream(getBytes(pos, Math.toIntExact(length)));
        }

        @Override
        public long position(byte[] pattern, long start) throws SQLException {
            if (start < 1) throw invalid();
            outer:
            for (int i = Math.toIntExact(start) - 1; i + pattern.length <= bytes.length; i++) {
                for (int j = 0; j < pattern.length; j++)
                    if (bytes[i + j] != pattern[j]) continue outer;
                return i + 1;
            }
            return -1;
        }

        @Override
        public long position(Blob pattern, long start) throws SQLException {
            return position(pattern.getBytes(1, Math.toIntExact(pattern.length())), start);
        }

        @Override
        public int setBytes(long pos, byte[] value) throws SQLException {
            return setBytes(pos, value, 0, value.length);
        }

        @Override
        public int setBytes(long pos, byte[] value, int offset, int length) throws SQLException {
            int from = Math.toIntExact(pos) - 1;
            if (from < 0 || from > bytes.length) throw invalid();
            if (from + length > bytes.length) bytes = Arrays.copyOf(bytes, from + length);
            System.arraycopy(value, offset, bytes, from, length);
            return length;
        }

        @Override
        public OutputStream setBinaryStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Blob.setBinaryStream", "0A000");
        }

        @Override
        public void truncate(long length) throws SQLException {
            if (length < 0 || length > bytes.length) throw invalid();
            bytes = Arrays.copyOf(bytes, Math.toIntExact(length));
        }

        @Override
        public void free() {
            bytes = new byte[0];
        }
    }
}
