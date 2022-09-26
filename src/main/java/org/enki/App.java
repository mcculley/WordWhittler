package org.enki;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.PointerTarget;
import net.sf.extjwnl.data.PointerType;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.data.Word;
import net.sf.extjwnl.dictionary.Dictionary;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.rules.RuleMatch;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class App {

    private final JLanguageTool languageTool = new JLanguageTool(new AmericanEnglish());
    private final Dictionary dictionary;

    private record TableRow(String name, Supplier<String> valueSupplier) {
    }

    private static class TableRowModel extends AbstractTableModel {

        private final TableRow[] rows;

        public TableRowModel(final TableRow[] rows) {
            this.rows = rows;
        }

        @Override
        public int getRowCount() {
            return rows.length;
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public boolean isCellEditable(final int rowIndex, final int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(final int rowIndex, final int columnIndex) {
            final TableRow infoRow = rows[rowIndex];
            return switch (columnIndex) {
                case 0 -> infoRow.name;
                case 1 -> infoRow.valueSupplier.get();
                default -> throw new AssertionError();
            };
        }

    }

    public App() {
        try {
            dictionary = Dictionary.getDefaultResourceInstance();
        } catch (final JWNLException e) {
            throw new AssertionError(e);
        }
    }

    private static String getText(final JTextComponent t) {
        final Document doc = t.getDocument();
        final int length = doc.getLength();
        try {
            return doc.getText(0, length);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isURL(final String s) {
        // FIXME: Detect domain in accordance with Twitter rules.
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String message(final RuleMatch match) {
        return match.getMessage().replace("<suggestion>", "'").replaceAll("</suggestion>", "'");
    }

    private class DocumentFrame extends JFrame {

        private final JList<RuleMatch> errorList = new JList<>();
        private final JTextComponent definitionArea = new JTextPane();
        private String selectedRegion;
        private Map<POS, IndexWord> selectedWords;

        private class ContentPane extends JTextPane {

            public ContentPane() {
                setToolTipText("");
            }

            private RuleMatch findError(final int position) {
                final int numErrors = errorList.getModel().getSize();
                for (int i = 0; i < numErrors; i++) {
                    final RuleMatch m = errorList.getModel().getElementAt(i);
                    if (position >= m.getFromPos() && position <= m.getToPos()) {
                        return m;
                    }
                }

                return null;
            }

            @Override
            public String getToolTipText(final MouseEvent event) {
                final int position = viewToModel2D(event.getPoint());
                final RuleMatch m = findError(position);
                return m == null ? null : message(m);
            }

        }

        private final String fullDefinition(final Map<POS, IndexWord> m) {
            final StringBuilder b = new StringBuilder();
            for (final POS p : m.keySet()) {
                b.append(p.getLabel());
                b.append('\n');
                final List<Synset> senses = m.get(p).getSenses();
                final int numSenses = senses.size();
                for (int i = 0; i < numSenses; i++) {
                    b.append(i + 1);
                    b.append(' ');
                    final Synset synSet = senses.get(i);
                    b.append(synSet.getGloss());
                    b.append('\n');
                }

                b.append('\n');
            }

            return b.toString().trim();
        }

        private void setWordOfInterest(final String s) {
            selectedRegion = s;
            selectedWords = lookup(dictionary, s);
            final String fullDefinition = fullDefinition(selectedWords);
            definitionArea.setText(fullDefinition);

            final Set<String> synonyms = synonyms(selectedWords);
            System.err.println("synonyms=" + synonyms);

            final Set<String> hypernyms = hypernyms(selectedWords);
            System.err.println("hypernyms=" + hypernyms);

            final Set<String> antonyms = antonyms(selectedWords);
            System.err.println("antonyms=" + antonyms);
        }

        private static Set<String> synonyms(final Map<POS, IndexWord> m) {
            return m.values().stream()
                    .flatMap(x -> x.getSenses().stream())
                    .flatMap(x -> x.getWords().stream())
                    .map(Word::getLemma)
                    .collect(Collectors.toSet());
        }

        private static List<PointerTarget> getTargetsUnchecked(final Synset s, final PointerType t) {
            try {
                return s.getTargets(t);
            } catch (final JWNLException e) {
                throw new RuntimeException(e);
            }
        }

        private static Set<String> targets(final Map<POS, IndexWord> m, final PointerType type) {
            return m.values().stream()
                    .flatMap(x -> x.getSenses().stream())
                    .flatMap(x -> getTargetsUnchecked(x, type).stream())
                    .map(PointerTarget::getSynset)
                    .flatMap(x -> x.getWords().stream())
                    .map(Word::getLemma)
                    .collect(Collectors.toSet());
        }

        private static Set<String> hypernyms(final Map<POS, IndexWord> m) {
            return targets(m, PointerType.HYPERNYM);
        }

        private static Set<String> antonyms(final Map<POS, IndexWord> m) {
            return targets(m, PointerType.ANTONYM);
        }

        private static String rootWords(final Map<POS, IndexWord> m) {
            return String.join(", ", m.values().stream().map(IndexWord::getLemma).collect(Collectors.toSet()));
        }

        public DocumentFrame() throws HeadlessException {
            super("WordWhittler");

            definitionArea.setEditable(false);

            final JTextPane contentArea = new ContentPane();

            final AtomicReference<CaretListener> contentCaretListener = new AtomicReference<>();

            final ListSelectionListener errorListListener = e -> {
                final List<RuleMatch> selectedMatches = errorList.getSelectedValuesList();
                if (!e.getValueIsAdjusting() && selectedMatches.size() == 1) {
                    final RuleMatch m = selectedMatches.get(0);
                    contentArea.removeCaretListener(contentCaretListener.get());
                    contentArea.setCaretPosition(m.getToPos());
                    contentArea.addCaretListener(contentCaretListener.get());
                    contentArea.requestFocus();
                }
            };

            errorList.setCellRenderer(new TransformingListCellRenderer<>(
                    (Function<RuleMatch, String>) ruleMatch -> {
                        final String region = getRegion(contentArea, ruleMatch);
                        return region + ": " + message(ruleMatch);
                    }));

            errorList.addListSelectionListener(errorListListener);

            final JTable wordTable = new JTable();

            contentCaretListener.set(e -> {
                final Range<Integer> selection =
                        Range.closed(contentArea.getSelectionStart(), contentArea.getSelectionEnd());
                final int dot = e.getDot();
                final int mark = e.getMark();

                if (mark == dot) {
                    final String wordAtCaret = getWordAtCaret(contentArea, dot);
                    setWordOfInterest(wordAtCaret);
                } else {
                    setWordOfInterest(contentArea.getText()
                            .substring(contentArea.getSelectionStart(), contentArea.getSelectionEnd()));
                }

                ((AbstractTableModel) wordTable.getModel()).fireTableDataChanged();

                try {
                    final Highlighter h = contentArea.getHighlighter();
                    h.removeAllHighlights();
                    final List<RuleMatch> r = languageTool.check(getText(contentArea));
                    errorList.setModel(new ListListModel<>(r));

                    int ruleRow = 0;
                    for (final RuleMatch m : r) {
                        final RuleMatch.Type type = m.getType();
                        final Highlighter.HighlightPainter painter = switch (type) {
                            case Hint -> new DefaultHighlighter.DefaultHighlightPainter(Color.LIGHT_GRAY);
                            case UnknownWord -> new DefaultHighlighter.DefaultHighlightPainter(Color.RED);
                            case Other -> new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
                        };

                        try {
                            h.addHighlight(m.getFromPos(), m.getToPos(), painter);
                        } catch (final BadLocationException ex) {
                            throw new AssertionError(ex);
                        }

                        if (dot >= m.getFromPos() && dot <= m.getToPos()) {
                            errorList.removeListSelectionListener(errorListListener);
                            errorList.setSelectedIndex(ruleRow);
                            errorList.addListSelectionListener(errorListListener);
                        }

                        ruleRow++;
                    }
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }

                if (dot != mark) {
                    contentArea.removeCaretListener(contentCaretListener.get());
                    contentArea.setSelectionStart(selection.lowerEndpoint());
                    contentArea.setSelectionEnd(selection.upperEndpoint());
                    contentArea.addCaretListener(contentCaretListener.get());
                }
            });

            contentArea.addCaretListener(contentCaretListener.get());

            final JComponent metaContainer = new Box(BoxLayout.Y_AXIS);

            final JTable infoTable = new JTable();

            infoTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {

                private boolean isDanger(final Object value) {
                    if (value instanceof String) {
                        final String s = (String) value;
                        if (isNumeric(s) && s.startsWith("-")) {
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                               final boolean isSelected, final boolean hasFocus,
                                                               final int row, final int column) {
                    final Component c =
                            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    c.setForeground(isDanger(value) ? Color.RED : table.getForeground());
                    return c;
                }

            });

            final TableRow[] infoRows = new TableRow[]{
                    new TableRow("Characters", () -> Integer.toString(getText(contentArea).length())),
                    new TableRow("Twitter Characters",
                            () -> Integer.toString(getTwitterCharacters(getText(contentArea)))),
                    new TableRow("Twitter Characters Remaining",
                            () -> Integer.toString(280 - getTwitterCharacters(getText(contentArea))))
            };

            infoTable.setModel(new TableRowModel(infoRows));

            final TableRow[] wordTableRows = new TableRow[]{
                    new TableRow("selection", () -> selectedRegion),
                    new TableRow("root(s)", () -> rootWords(selectedWords))
            };

            wordTable.setModel(new TableRowModel(wordTableRows));

            metaContainer.add(infoTable);

            metaContainer.add(new JSeparator());

            metaContainer.add(wordTable);

            metaContainer.add(new JScrollPane(definitionArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));

            metaContainer.add(new JScrollPane(errorList));

            final JSplitPane mainSplitPane =
                    new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, metaContainer, new JScrollPane(contentArea));

            add(mainSplitPane);

            final DocumentListener propagatingDocumentListener = new DocumentListener() {

                @Override
                public void insertUpdate(final DocumentEvent e) {
                    changedUpdate(e);
                }

                @Override
                public void removeUpdate(final DocumentEvent e) {
                    changedUpdate(e);
                }

                @Override
                public void changedUpdate(final DocumentEvent e) {
                    ((AbstractTableModel) infoTable.getModel()).fireTableDataChanged();
                    ((AbstractTableModel) wordTable.getModel()).fireTableDataChanged();
                }

            };

            contentArea.getDocument().addDocumentListener(propagatingDocumentListener);

            final boolean debug = true;
            if (debug) {
                contentArea.setText(
                        "WordWhittler is your intelligent writing assistant. It helps you to be more concise and precise. Write or paste your text here too have it checked continuously. Errors will be highlighted in different colours: we will mark seplling errors and we might someday have underilnes. Furthermore grammar error's are highlighted in yellow. It also marks style issues in a reliable manner (someday by underlining them in blue). did you know that you can sea synonyms by clicking (notd double clicking) a word? Its a impressively versatile tool especially if youd like to tell a colleague from over sea's about what happened at 5 PM in the afternoon on Monday, 27 May 2007.\nWordWhittler uses LanguageTool and extJWNL.");
            }
        }

    }

    private static int getTwitterCharacters(final String s) {
        // See https://developer.twitter.com/en/docs/counting-characters
        // FIXME: Need to handle counts for Unicode characters correctly?

        final StringTokenizer tokenizer = new StringTokenizer(s, " ", true);
        int total = 0;
        while (tokenizer.hasMoreElements()) {
            final String token = tokenizer.nextToken();
            if (isURL(token)) {
                total += 23;
            } else {
                total += token.length();
            }
        }

        return total;
    }

    private static String getWordAtCaret(final JTextComponent tc, final int caretPosition) {
        try {
            final int start = Utilities.getWordStart(tc, caretPosition);
            final int end = Utilities.getWordEnd(tc, caretPosition);
            return tc.getText(start, end - start);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static String getRegion(final JTextComponent tc, final RuleMatch m) {
        return getText(tc).substring(m.getFromPos(), m.getToPos());
    }

    private void start() {
        final JFrame mainFrame = new DocumentFrame();
        mainFrame.setSize(800, 600);
        mainFrame.setVisible(true);
    }

    private static class TransformingListCellRenderer<T> extends DefaultListCellRenderer {

        private final Function<T, String> transformer;

        public TransformingListCellRenderer(final Function<T, String> transformer) {
            this.transformer = transformer;
        }

        @Override
        public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
                                                      final boolean isSelected, final boolean cellHasFocus) {
            return super.getListCellRendererComponent(list, transformer.apply((T) value), index, isSelected,
                    cellHasFocus);
        }

    }

    private static class ListListModel<T> extends AbstractListModel<T> {

        private final List<T> list;

        public ListListModel(final List<T> list) {
            this.list = list;
        }

        @Override
        public int getSize() {
            return list.size();
        }

        @Override
        public T getElementAt(int i) {
            return list.get(i);
        }

    }

    private final Pattern pattern = Pattern.compile("-?\\d+(\\.\\d+)?");

    public boolean isNumeric(final String strNum) {
        Objects.requireNonNull(strNum);
        return pattern.matcher(strNum).matches();
    }

    public static Map<POS, IndexWord> lookup(final Dictionary dictionary, final String s) {
        final ImmutableMap.Builder<POS, IndexWord> m = new ImmutableMap.Builder<>();
        POS.getAllPOS().forEach(p -> {
            try {
                final IndexWord i = dictionary.lookupIndexWord(p, s);
                if (i != null) {
                    m.put(p, i);
                }
            } catch (final JWNLException e) {
                throw new AssertionError(e);
            }
        });

        return m.build();
    }

    public static void main(final String[] args) {
        SwingUtilities.invokeLater(() -> new App().start());
    }

}
