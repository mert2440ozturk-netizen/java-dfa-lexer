import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DFA-Based Lexical Analyzer (Lexer) for Java source code
 *
 * Each DFA corresponds to the formal 5-tuple definition:  M = (Q, Σ, δ, q0, F)
 *   - States are numbered with integers: 0 = q0, 1 = q1, 2 = q2 ...
 *   - δ (transition function) -> transition(state, c) method
 *   - F (accepting states)    -> isAccepting(state) method
 *   - When no transition exists, transition() returns DEAD_STATE (-1)
 *
 * The classes correspond one-to-one to Figures 1-6 in
 * "DFA Designs for a Java Lexical Analyzer".
 *
 * Usage:
 *   javac LexicalAnalyzer.java
 *   java LexicalAnalyzer sample_input.txt
 */
class LexicalAnalyzer {

    // =====================================================================
    //  TOKEN TYPES
    // =====================================================================

    enum TokenType {
        KEYWORD, IDENTIFIER, INTEGER_LITERAL, FLOAT_LITERAL, BOOLEAN_LITERAL, NULL_LITERAL,
        OPERATOR, SEPARATOR, UNRECOGNIZED_CHAR, INVALID_LEXEME
    }

    static class Token {
        final String lexeme;
        final TokenType type;
        final int line;
        final int column;

        Token(String lexeme, TokenType type, int line, int column) {
            this.lexeme = lexeme;
            this.type = type;
            this.line = line;
            this.column = column;
        }
    }

    // =====================================================================
    //  DFA INFRASTRUCTURE
    // =====================================================================

    static final int DEAD_STATE = -1;   // means "no transition"

    /** Result of a DFA run: how many characters were read and the state it stopped in. */
    static class Result {
        final int length;
        final int finalState;

        Result(int length, int finalState) {
            this.length = length;
            this.finalState = finalState;
        }
    }

    static abstract class DFA {
        final String name;

        DFA(String name) {
            this.name = name;
        }

        /** δ function: (state, character) -> next state. Returns DEAD_STATE if undefined. */
        abstract int transition(int state, char c);

        /** Is the state in F? (the double circles in the figures) */
        abstract boolean isAccepting(int state);

        /** Is there an edge leaving q0 on this character? */
        boolean canStart(char c) {
            return transition(0, c) != DEAD_STATE;
        }

        /**
         * Runs the DFA character by character on the text, starting at 'start'.
         * Advances until no transition is left (longest match / maximal munch).
         */
        Result run(String text, int start) {
            int state = 0;                       // start in q0
            int i = start;
            while (i < text.length()) {
                int next = transition(state, text.charAt(i));
                if (next == DEAD_STATE) {        // no transition -> stop
                    break;
                }
                state = next;
                i++;
            }
            return new Result(i - start, state);
        }
    }

    static boolean isLetter(char c) {
        return Character.isLetter(c);            // A-Z, a-z and other Unicode letters
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // =====================================================================
    //  FIGURE 1 — IDENTIFIER DFA
    //    q0 --letter, _, $-->          q1 (accepting)
    //    q1 --letter, digit, _, $-->   q1 (accepting)   [self-loop]
    // =====================================================================

    static class IdentifierDFA extends DFA {
        IdentifierDFA() {
            super("Identifier");
        }

        @Override
        int transition(int state, char c) {
            switch (state) {
                case 0:
                    if (isLetter(c) || c == '_' || c == '$') return 1;
                    break;
                case 1:
                    if (isLetter(c) || isDigit(c) || c == '_' || c == '$') return 1;
                    break;
            }
            return DEAD_STATE;
        }

        @Override
        boolean isAccepting(int state) {
            return state == 1;
        }
    }

    // =====================================================================
    //  FIGURE 2 — RESERVED WORD (KEYWORD) DFA
    //    A prefix tree (trie) built automatically from the word list.
    //    Words sharing a prefix share the same path (q0 -i-> i -f-> if, i -n-> in -t-> int);
    //    the state reached by the last letter of each word is an accepting state.
    //    Each accepting state carries a token type:
    //      51 reserved words -> KEYWORD,  true/false -> BOOLEAN_LITERAL,  null -> NULL_LITERAL
    // =====================================================================

    static class ReservedWordDFA extends DFA {
        private final List<Map<Character, Integer>> transitions = new ArrayList<>();
        private final Map<Integer, TokenType> acceptingStates = new HashMap<>();

        ReservedWordDFA() {
            super("Reserved word");
            transitions.add(new HashMap<>());                  // q0
        }

        /** Adds words to the tree; the states reached by their last letters become accepting. */
        void add(TokenType type, String... words) {
            for (String word : words) {
                int state = 0;
                for (char c : word.toCharArray()) {
                    Integer next = transitions.get(state).get(c);
                    if (next == null) {                        // edge missing -> create a new state
                        next = transitions.size();
                        transitions.add(new HashMap<>());
                        transitions.get(state).put(c, next);
                    }
                    state = next;
                }
                acceptingStates.put(state, type);
            }
        }

        @Override
        int transition(int state, char c) {
            Integer next = transitions.get(state).get(c);
            return next == null ? DEAD_STATE : next;
        }

        @Override
        boolean isAccepting(int state) {
            return acceptingStates.containsKey(state);
        }

        /**
         * If the WHOLE lexeme is consumed and the DFA ends in an accepting state,
         * returns that state's token type; otherwise returns null.
         *   "int" -> KEYWORD,  "integer" / "in" -> null
         */
        TokenType classify(String lexeme) {
            Result r = run(lexeme, 0);
            if (r.length == lexeme.length() && isAccepting(r.finalState)) {
                return acceptingStates.get(r.finalState);
            }
            return null;
        }
    }

    // =====================================================================
    //  FIGURE 3 — NUMERIC LITERAL DFA
    //    q0 --digit--> q1 (accepting)    q1 --digit--> q1
    //    q1 --.-->     q2 (accepting)    q2 --digit--> q2
    //    q1 --e,E-->   q3                q2 --e,E-->   q3
    //    q3 --+,- -->  q4                q3 --digit--> q5 (accepting)
    //    q4 --digit--> q5 (accepting)    q5 --digit--> q5
    //  Accepting: q1 -> INTEGER_LITERAL,  q2 and q5 -> FLOAT_LITERAL
    // =====================================================================

    static class NumberDFA extends DFA {
        NumberDFA() {
            super("Numeric literal");
        }

        @Override
        int transition(int state, char c) {
            switch (state) {
                case 0:
                    if (isDigit(c)) return 1;
                    break;
                case 1:
                    if (isDigit(c)) return 1;
                    if (c == '.') return 2;
                    if (c == 'e' || c == 'E') return 3;
                    break;
                case 2:
                    if (isDigit(c)) return 2;
                    if (c == 'e' || c == 'E') return 3;
                    break;
                case 3:
                    if (c == '+' || c == '-') return 4;
                    if (isDigit(c)) return 5;
                    break;
                case 4:
                    if (isDigit(c)) return 5;
                    break;
                case 5:
                    if (isDigit(c)) return 5;
                    break;
            }
            return DEAD_STATE;
        }

        @Override
        boolean isAccepting(int state) {
            return state == 1 || state == 2 || state == 5;
        }
    }

    // =====================================================================
    //  FIGURE 4 — OPERATOR DFA (I):  =  !  <  >  *  /  %
    //    q0 --X--> [X] (accepting)      [X] --=--> [X=] (accepting)
    //    State numbering: if X is the k-th symbol, [X] = 2k+1 and [X=] = 2k+2
    // =====================================================================

    static class OperatorDFA1 extends DFA {
        private static final String FIRST = "=!<>*/%";

        OperatorDFA1() {
            super("Operator (I)");
        }

        @Override
        int transition(int state, char c) {
            if (state == 0) {
                int k = FIRST.indexOf(c);
                return k >= 0 ? 2 * k + 1 : DEAD_STATE;
            }
            if (state % 2 == 1 && c == '=') {            // from [X] on '=' to [X=]
                return state + 1;
            }
            return DEAD_STATE;
        }

        @Override
        boolean isAccepting(int state) {
            return state != 0;                           // every state except q0 is accepting
        }
    }

    // =====================================================================
    //  FIGURE 5 — OPERATOR DFA (II):  +  -  &  |
    //    q0 --X--> [X] (accepting)
    //    [X] --X--> [XX] (accepting)     ( ++  --  &&  || )
    //    [X] --=--> [X=] (accepting)     ( +=  -=  &=  |= )
    //    State numbering: if X is the k-th symbol, [X] = 3k+1, [XX] = 3k+2, [X=] = 3k+3
    // =====================================================================

    static class OperatorDFA2 extends DFA {
        private static final String FIRST = "+-&|";

        OperatorDFA2() {
            super("Operator (II)");
        }

        @Override
        int transition(int state, char c) {
            if (state == 0) {
                int k = FIRST.indexOf(c);
                return k >= 0 ? 3 * k + 1 : DEAD_STATE;
            }
            if (state % 3 == 1) {                        // state [X]
                char x = FIRST.charAt((state - 1) / 3);
                if (c == x) return state + 1;            // [XX]
                if (c == '=') return state + 2;          // [X=]
            }
            return DEAD_STATE;
        }

        @Override
        boolean isAccepting(int state) {
            return state != 0;
        }
    }

    // =====================================================================
    //  FIGURE 6 — SEPARATOR DFA
    //    q0 --( ) { } [ ] ; , .--> q1 (accepting)
    // =====================================================================

    static class SeparatorDFA extends DFA {
        private static final String SEPARATORS = "(){}[];,.";

        SeparatorDFA() {
            super("Separator");
        }

        @Override
        int transition(int state, char c) {
            if (state == 0 && SEPARATORS.indexOf(c) >= 0) return 1;
            return DEAD_STATE;
        }

        @Override
        boolean isAccepting(int state) {
            return state == 1;
        }
    }

    // =====================================================================
    //  LEXICAL ANALYZER
    // =====================================================================

    private final IdentifierDFA identifierDFA = new IdentifierDFA();
    private final NumberDFA numberDFA = new NumberDFA();
    private final SeparatorDFA separatorDFA = new SeparatorDFA();
    private final ReservedWordDFA reservedWordDFA = new ReservedWordDFA();

    // DFAs that can be run at the start of a lexeme.
    // Their start-character sets are disjoint, so at most one of them matches any character.
    private final List<DFA> dfas = List.of(
            identifierDFA,
            numberDFA,
            new OperatorDFA1(),
            new OperatorDFA2(),
            separatorDFA);

    private final String source;
    private int position = 0;
    private int line = 1;
    private int column = 1;

    private final List<Token> tokens = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public LexicalAnalyzer(String source) {
        this.source = source;

        // Java's 51 reserved words (_ has been a reserved word since Java 9)
        reservedWordDFA.add(TokenType.KEYWORD,
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                "import", "instanceof", "int", "interface", "long", "native", "new",
                "package", "private", "protected", "public", "return", "short", "static",
                "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "_");
        // true, false and null are literals, not keywords
        reservedWordDFA.add(TokenType.BOOLEAN_LITERAL, "true", "false");
        reservedWordDFA.add(TokenType.NULL_LITERAL, "null");
    }

    /** Reads the source code character by character from start to end and splits it into tokens. */
    public void analyze() {
        while (position < source.length()) {
            char c = source.charAt(position);

            // 1) Spaces, tabs and line breaks are not tokens: skip them
            if (Character.isWhitespace(c)) {
                advance(1);
                continue;
            }

            int startLine = line;
            int startColumn = column;

            // 2) Find the DFA that can start with this character
            DFA dfa = findStartingDFA(c);

            // 3) No DFA can start with this character: unrecognized character
            if (dfa == null) {
                String lexeme = String.valueOf(c);
                tokens.add(new Token(lexeme, TokenType.UNRECOGNIZED_CHAR, startLine, startColumn));
                errors.add(location(startLine, startColumn)
                        + "Unrecognized character '" + lexeme + "' (no DFA can start with this character)");
                advance(1);
                continue;
            }

            // 4) Run the DFA until no transition is left
            Result r = dfa.run(source, position);
            int end = position + r.length;
            String lexeme = source.substring(position, end);

            if (dfa == numberDFA && letterFollowsNumber(end)) {
                // 5) A letter immediately follows the number (e.g. "3abc").
                //    Absorb the adjacent letters and digits and report the whole lexeme as invalid.
                while (end < source.length() && isIdentifierChar(source.charAt(end))) {
                    end++;
                }
                lexeme = source.substring(position, end);
                addInvalidLexeme(lexeme, startLine, startColumn,
                        "a letter immediately follows a number; identifiers cannot start with a digit");

            } else if (!dfa.isAccepting(r.finalState)) {
                // 6) The DFA did not stop in an accepting state -> invalid lexeme (e.g. "2e+" stops in q4)
                addInvalidLexeme(lexeme, startLine, startColumn, nonAcceptingReason(dfa, r.finalState));

            } else {
                // 7) Valid token
                tokens.add(new Token(lexeme, determineType(dfa, r.finalState, lexeme), startLine, startColumn));
            }

            advance(lexeme.length());
        }
    }

    private DFA findStartingDFA(char c) {
        for (DFA dfa : dfas) {
            if (dfa.canStart(c)) {
                return dfa;
            }
        }
        return null;
    }

    private TokenType determineType(DFA dfa, int finalState, String lexeme) {
        if (dfa == identifierDFA) {
            TokenType type = reservedWordDFA.classify(lexeme);       // Figure 2
            return type != null ? type : TokenType.IDENTIFIER;
        }
        if (dfa == numberDFA) {
            return finalState == 1 ? TokenType.INTEGER_LITERAL : TokenType.FLOAT_LITERAL;
        }
        if (dfa == separatorDFA) {
            return TokenType.SEPARATOR;
        }
        return TokenType.OPERATOR;
    }

    private String nonAcceptingReason(DFA dfa, int finalState) {
        if (dfa == numberDFA && finalState == 3) return "expected a digit or sign after e/E";
        if (dfa == numberDFA && finalState == 4) return "expected a digit after the exponent sign";
        return dfa.name + " DFA stopped in q" + finalState + ", which is not an accepting state";
    }

    private void addInvalidLexeme(String lexeme, int line, int column, String reason) {
        tokens.add(new Token(lexeme, TokenType.INVALID_LEXEME, line, column));
        errors.add(location(line, column) + "Invalid lexeme '" + lexeme + "' (" + reason + ")");
    }

    private boolean letterFollowsNumber(int i) {
        if (i >= source.length()) return false;
        char c = source.charAt(i);
        return isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierChar(char c) {
        return isLetter(c) || isDigit(c) || c == '_' || c == '$';
    }

    /** Advances n characters and updates the line and column numbers. */
    private void advance(int n) {
        for (int k = 0; k < n; k++) {
            if (source.charAt(position) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            position++;
        }
    }

    private static String location(int line, int column) {
        return "Line " + line + ", Column " + column + ": ";
    }

    // =====================================================================
    //  OUTPUT
    // =====================================================================

    public void printTable() {
        int lexemeWidth = "Lexeme".length();
        int typeWidth = "Token".length();
        for (Token t : tokens) {
            lexemeWidth = Math.max(lexemeWidth, t.lexeme.length());
            typeWidth = Math.max(typeWidth, t.type.name().length());
        }

        String rule = "+" + "-".repeat(6) + "+" + "-".repeat(6) + "+" + "-".repeat(8)
                + "+" + "-".repeat(lexemeWidth + 2) + "+" + "-".repeat(typeWidth + 2) + "+";
        String headerFormat = "| %-4s | %-4s | %-6s | %-" + lexemeWidth + "s | %-" + typeWidth + "s |%n";
        String rowFormat = "| %-4d | %-4d | %-6d | %-" + lexemeWidth + "s | %-" + typeWidth + "s |%n";

        System.out.println();
        System.out.println("LEXEME / TOKEN TABLE");
        System.out.println(rule);
        System.out.printf(headerFormat, "No", "Line", "Column", "Lexeme", "Token");
        System.out.println(rule);
        int no = 1;
        for (Token t : tokens) {
            System.out.printf(rowFormat, no++, t.line, t.column, t.lexeme, t.type.name());
        }
        System.out.println(rule);
    }

    public void printErrors() {
        System.out.println();
        if (errors.isEmpty()) {
            System.out.println("ERROR REPORT: No errors found.");
            return;
        }
        System.out.println("ERROR REPORT (" + errors.size() + (errors.size() == 1 ? " error)" : " errors)"));
        for (String e : errors) {
            System.out.println("  " + e);
        }
    }

    public void printSummary() {
        Map<TokenType, Integer> counts = new EnumMap<>(TokenType.class);
        for (Token t : tokens) {
            counts.merge(t.type, 1, Integer::sum);
        }
        System.out.println();
        System.out.println("SUMMARY (" + tokens.size() + " lexemes in total)");
        for (Map.Entry<TokenType, Integer> e : counts.entrySet()) {
            System.out.printf("  %-20s %d%n", e.getKey().name(), e.getValue());
        }
    }

    // =====================================================================
    //  MAIN
    // =====================================================================

    public static void main(String[] args) throws IOException {
        String fileName = args.length > 0 ? args[0] : "sample_input.txt";
        Path path = Paths.get(fileName);

        if (!Files.exists(path)) {
            System.out.println("File not found: " + path.toAbsolutePath());
            System.out.println("Usage: java LexicalAnalyzer <source_file>");
            return;
        }

        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        System.out.println("Analyzed file: " + path.getFileName());

        LexicalAnalyzer analyzer = new LexicalAnalyzer(source);
        analyzer.analyze();
        analyzer.printTable();
        analyzer.printErrors();
        analyzer.printSummary();
    }
}