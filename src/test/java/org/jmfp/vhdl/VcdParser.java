package org.jmfp.vhdl;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Streaming VCD (Value Change Dump) parser for MC68901 conformance testing.
 *
 * <p>Handles multi-bit vectors (b&lt;bin&gt; &lt;sym&gt;), single-bit changes
 * (&lt;0|1|x|z&gt;&lt;sym&gt;), scope/variable declarations, and timestamps.
 * x/z values are treated as 0.
 */
public class VcdParser {

    /** Represents a single VCD signal declaration. */
    public static class VcdSignal {
        public final String id;
        public final String name;
        public final int    width;
        public VcdSignal(String id, String name, int width) {
            this.id    = id;
            this.name  = name;
            this.width = width;
        }
        @Override public String toString() {
            return name + "[" + width + "]@" + id;
        }
    }

    /** Current integer value of each signal, keyed by VCD symbol. */
    private final Map<String, Long> values = new HashMap<>();

    /** All declared signals in declaration order. */
    private final List<VcdSignal> signals = new ArrayList<>();

    /** Map from VCD symbol to signal info. */
    private final Map<String, VcdSignal> symbolMap = new HashMap<>();

    // -------------------------------------------------------------------------

    public List<VcdSignal> getSignals() { return signals; }

    public long getValue(String symbol) {
        return values.getOrDefault(symbol, 0L);
    }

    // -------------------------------------------------------------------------

    /**
     * Callback invoked at every timestamp change.
     *
     * @param time      current simulation time (raw, in VCD timescale units)
     * @param changed   set of VCD symbols whose value changed at {@code time}
     * @param parser    the parser (for reading current values)
     */
    @FunctionalInterface
    public interface TimestampCallback {
        void onTimestamp(long time, Set<String> changed, VcdParser parser);
    }

    // -------------------------------------------------------------------------

    /**
     * Open the first entry in a ZIP file and parse the VCD inside it.
     *
     * @param zipFile absolute path to the .zip file
     * @param cb      called once per VCD timestamp (after all changes at that
     *                timestamp have been applied)
     */
    public void parseZip(String zipFile, TimestampCallback cb) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile), 1 << 20))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    parse(new BufferedReader(new InputStreamReader(zis), 1 << 20), cb);
                    return;
                }
            }
        }
        throw new IOException("No VCD entry found in " + zipFile);
    }

    /**
     * Parse a VCD stream.
     *
     * @param reader source
     * @param cb     called once per VCD timestamp
     */
    public void parse(BufferedReader reader, TimestampCallback cb) throws IOException {
        StreamTokenizer st = new StreamTokenizer(reader);
        st.resetSyntax();
        st.wordChars('!', '~');     // all printable ASCII
        st.whitespaceChars(0, ' ');
        st.eolIsSignificant(false);

        // Scope stack for resolving hierarchical signal names
        Deque<String> scopeStack = new ArrayDeque<>();

        long         currentTime    = 0L;
        Set<String>  changedThisTick = new LinkedHashSet<>();
        boolean      inDumpvars     = false;

        while (st.nextToken() != StreamTokenizer.TT_EOF) {
            String tok = st.sval;
            if (tok == null) continue;

            // ---- Keyword directives ----------------------------------------
            if (tok.startsWith("$")) {
                switch (tok) {
                    case "$scope": {
                        st.nextToken(); // type (module/begin/etc.)
                        st.nextToken(); // name
                        String scopeName = st.sval;
                        st.nextToken(); // $end
                        scopeStack.push(scopeStack.isEmpty()
                                ? scopeName
                                : scopeStack.peek() + "." + scopeName);
                        break;
                    }
                    case "$upscope": {
                        st.nextToken(); // $end
                        if (!scopeStack.isEmpty()) scopeStack.pop();
                        break;
                    }
                    case "$var": {
                        st.nextToken(); // type (wire/reg/integer/…)
                        st.nextToken(); // width
                        int width = 1;
                        try { width = Integer.parseInt(st.sval); } catch (NumberFormatException ignored) {}
                        st.nextToken(); // symbol
                        String sym  = st.sval;
                        st.nextToken(); // name
                        String name = st.sval;
                        // skip optional bit range "[n:m]"
                        st.nextToken();
                        if (st.sval != null && st.sval.startsWith("[")) {
                            st.nextToken(); // $end
                        }
                        // st.sval should now be "$end"
                        String fullName = scopeStack.isEmpty()
                                ? name
                                : scopeStack.peek() + "." + name;
                        VcdSignal sig = new VcdSignal(sym, fullName, width);
                        signals.add(sig);
                        symbolMap.put(sym, sig);
                        values.put(sym, 0L);
                        break;
                    }
                    case "$dumpvars":
                        inDumpvars = true;
                        break;
                    case "$end":
                        if (inDumpvars) {
                            inDumpvars = false;
                            // Treat $dumpvars block as timestamp 0
                            cb.onTimestamp(currentTime, changedThisTick, this);
                            changedThisTick = new LinkedHashSet<>();
                        }
                        break;
                    default:
                        // Skip unknown directives to $end
                        while (st.nextToken() != StreamTokenizer.TT_EOF) {
                            if ("$end".equals(st.sval)) break;
                        }
                        break;
                }
                continue;
            }

            // ---- Timestamp -------------------------------------------------
            if (tok.startsWith("#")) {
                long newTime = Long.parseLong(tok.substring(1));
                if (newTime != currentTime || currentTime == 0) {
                    if (!changedThisTick.isEmpty() || currentTime != 0) {
                        cb.onTimestamp(currentTime, changedThisTick, this);
                        changedThisTick = new LinkedHashSet<>();
                    }
                    currentTime = newTime;
                }
                continue;
            }

            // ---- Multi-bit vector: b<bin> <sym> ----------------------------
            if (tok.startsWith("b") || tok.startsWith("B")) {
                String bits = tok.substring(1);
                st.nextToken();
                String sym = st.sval;
                long val = 0L;
                for (char c : bits.toCharArray()) {
                    val <<= 1;
                    if (c == '1') val |= 1;
                    // 'x' and 'z' treated as 0
                }
                values.put(sym, val);
                changedThisTick.add(sym);
                continue;
            }

            // ---- Single-bit: <val><sym> ------------------------------------
            if (tok.length() >= 2 && "01xzXZ".indexOf(tok.charAt(0)) >= 0) {
                char  valChar = tok.charAt(0);
                String sym   = tok.substring(1);
                long   val   = (valChar == '1') ? 1L : 0L;
                values.put(sym, val);
                changedThisTick.add(sym);
                continue;
            }
        }

        // Flush last timestamp
        if (!changedThisTick.isEmpty()) {
            cb.onTimestamp(currentTime, changedThisTick, this);
        }
    }
}
