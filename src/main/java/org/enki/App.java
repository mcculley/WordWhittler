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
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
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
        private final JTree wordTree = new JTree();
        private String selectedRegion;
        private List<IndexWord> selectedWords;

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

        private final String fullDefinition(final List<IndexWord> l) {
            final StringBuilder b = new StringBuilder();
            for (final IndexWord word : l) {
                b.append(word.getPOS());
                b.append('\n');
                final List<Synset> senses = word.getSenses();
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

        private class WordTreeNode implements TreeNode {

            private final Word word;
            private final TreeNode parent;

            public WordTreeNode(final Word word, final TreeNode parent) {
                this.word = Objects.requireNonNull(word);
                this.parent = Objects.requireNonNull(parent);
            }

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return null;
            }

            @Override
            public int getChildCount() {
                return 0;
            }

            @Override
            public TreeNode getParent() {
                return parent;
            }

            @Override
            public int getIndex(final TreeNode node) {
                return -1;
            }

            @Override
            public boolean getAllowsChildren() {
                return false;
            }

            @Override
            public boolean isLeaf() {
                return true;
            }

            @Override
            public Enumeration<? extends TreeNode> children() {
                return new Enumeration<>() {

                    @Override
                    public boolean hasMoreElements() {
                        return false;
                    }

                    @Override
                    public TreeNode nextElement() {
                        throw new UnsupportedOperationException();
                    }

                };
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final WordTreeNode that = (WordTreeNode) o;
                return word.equals(that.word);
            }

            @Override
            public int hashCode() {
                return Objects.hash(word);
            }

            @Override
            public String toString() {
                return word.getLemma() + " (" + word.getPOS().getLabel() + ")";
            }

        }

        private class SynonymsNode implements TreeNode {

            private final IndexWordTreeNode word;
            private final List<Word> synonyms;

            public SynonymsNode(final IndexWordTreeNode word) {
                this.word = Objects.requireNonNull(word);
                this.synonyms = synonymsAsList(word.word);
            }

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return new WordTreeNode(synonyms.get(childIndex), this);
            }

            @Override
            public int getChildCount() {
                return synonyms.size();
            }

            @Override
            public TreeNode getParent() {
                return word;
            }

            @Override
            public int getIndex(final TreeNode node) {
                return synonyms.indexOf(((WordTreeNode) node).word);
            }

            @Override
            public boolean getAllowsChildren() {
                return true;
            }

            @Override
            public boolean isLeaf() {
                return false;
            }

            @Override
            public Enumeration<? extends TreeNode> children() {
                return Collections.enumeration(
                        synonyms.stream().map(x -> new WordTreeNode(x, this)).collect(Collectors.toList()));
            }

            @Override
            public String toString() {
                return "synonyms";
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final SynonymsNode that = (SynonymsNode) o;
                return word.equals(that.word);
            }

            @Override
            public int hashCode() {
                return Objects.hash(word);
            }

        }

        private class PointerTypeNode implements TreeNode {

            private final IndexWordTreeNode word;
            private final PointerType type;
            private final List<Word> targets;

            public PointerTypeNode(final IndexWordTreeNode word, final PointerType type) {
                this.word = Objects.requireNonNull(word);
                this.type = Objects.requireNonNull(type);
                this.targets = targetsAsList(word.word, type);
            }

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return new WordTreeNode(targets.get(childIndex), this);
            }

            @Override
            public int getChildCount() {
                return targets.size();
            }

            @Override
            public TreeNode getParent() {
                return word;
            }

            @Override
            public int getIndex(final TreeNode node) {
                return targets.indexOf(((WordTreeNode) node).word);
            }

            @Override
            public boolean getAllowsChildren() {
                return true;
            }

            @Override
            public boolean isLeaf() {
                return false;
            }

            @Override
            public Enumeration<? extends TreeNode> children() {
                return Collections.enumeration(
                        targets.stream().map(x -> new WordTreeNode(x, this)).collect(Collectors.toList()));
            }

            @Override
            public String toString() {
                return type.getLabel() + "s";
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final PointerTypeNode that = (PointerTypeNode) o;
                return word.equals(that.word) && type == that.type;
            }

            @Override
            public int hashCode() {
                return Objects.hash(word, type);
            }

        }

        private class IndexWordTreeNode implements TreeNode {

            private final IndexWord word;
            private final List<TreeNode> children;

            public IndexWordTreeNode(final IndexWord word) {
                this.word = Objects.requireNonNull(word);
                this.children = new ArrayList<>();
                children.add(new SynonymsNode(this));
                children.add(new PointerTypeNode(this, PointerType.ANTONYM));
                children.add(new PointerTypeNode(this, PointerType.HYPERNYM));
            }

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return children.get(childIndex);
            }

            @Override
            public int getChildCount() {
                return children.size();
            }

            @Override
            public TreeNode getParent() {
                return rootWordTreeNode;
            }

            @Override
            public int getIndex(final TreeNode node) {
                return children.indexOf(node);
            }

            @Override
            public boolean getAllowsChildren() {
                return true;
            }

            @Override
            public boolean isLeaf() {
                return false;
            }

            @Override
            public Enumeration<? extends TreeNode> children() {
                return Collections.enumeration(children);
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final IndexWordTreeNode that = (IndexWordTreeNode) o;
                return word.equals(that.word);
            }

            @Override
            public int hashCode() {
                return Objects.hash(word);
            }

            @Override
            public String toString() {
                return word.getLemma() + " (" + word.getPOS().getLabel() + ")";
            }

        }

        private TreeNode rootWordTreeNode = new TreeNode() {

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return new IndexWordTreeNode(selectedWords.get(childIndex));
            }

            @Override
            public int getChildCount() {
                return selectedWords.size();
            }

            @Override
            public TreeNode getParent() {
                return null;
            }

            @Override
            public int getIndex(final TreeNode node) {
                return selectedWords.indexOf(((IndexWordTreeNode) node).word);
            }

            @Override
            public boolean getAllowsChildren() {
                return true;
            }

            @Override
            public boolean isLeaf() {
                return false;
            }

            @Override
            public Enumeration<? extends TreeNode> children() {
                return Collections.enumeration(
                        selectedWords.stream().map(IndexWordTreeNode::new).collect(Collectors.toList()));
            }

            @Override
            public String toString() {
                return "root";
            }

        };

        private TreeModel makeWordTreeModel() {
            return new DefaultTreeModel(rootWordTreeNode);
        }

        // FIXME: This seems dumb. There is not a better way to do this?
        private static TreeNode[] nodePath(final TreeNode node) {
            final TreeNode parent = node.getParent();
            if (parent == null) {
                return new TreeNode[]{node};
            } else {
                final TreeNode[] parentPath = nodePath(parent);
                final TreeNode[] result = new TreeNode[parentPath.length + 1];
                System.arraycopy(parentPath, 0, result, 0, parentPath.length);
                result[parentPath.length] = node;
                return result;
            }
        }

        // FIXME: This seems dumb. There is not a better way to do this?
        private static TreePath path(final TreeNode node) {
            final TreeNode parent = node.getParent();
            if (parent == null) {
                return new TreePath(node);
            } else {
                return new TreePath(nodePath(node));
            }
        }

        private void setWordOfInterest(final String s) {
            selectedRegion = s;
            selectedWords = lookupAsList(dictionary, s);
            wordTree.setModel(makeWordTreeModel());

            final List<TreeNode> allWordTreeNodes = getNodes((TreeNode) wordTree.getModel().getRoot());
            for (final TreeNode n : allWordTreeNodes) {
                if (n instanceof SynonymsNode) {
                    wordTree.expandPath(path(n));
                }
            }

            if (!selectedWords.isEmpty()) {
                final PointerType type = PointerType.CATEGORY;
                final List<Word> targets = targetsAsList(selectedWords.iterator().next(), type);
                System.err.println("targets=" + targets);
            }
        }

        private static Set<String> synonyms(final Collection<IndexWord> m) {
            return m.stream()
                    .flatMap(x -> x.getSenses().stream())
                    .flatMap(x -> x.getWords().stream())
                    .map(Word::getLemma)
                    .collect(Collectors.toSet());
        }

        private static Set<Word> synonyms(final IndexWord w) {
            return w.getSenses().stream()
                    .flatMap(x -> x.getWords().stream())
                    .collect(Collectors.toSet());
        }

        private static List<Word> synonymsAsList(final IndexWord w) {
            final Set<Word> words = synonyms(w);
            final List<Word> l = new ArrayList<>(words);
            l.sort(Comparator.comparing(Word::getLemma));
            return l;
        }

        private static List<PointerTarget> getTargetsUnchecked(final Synset s, final PointerType t) {
            try {
                return s.getTargets(t);
            } catch (final JWNLException e) {
                throw new RuntimeException(e);
            }
        }

        private static Set<String> targets(final Collection<IndexWord> m, final PointerType type) {
            return m.stream()
                    .flatMap(x -> x.getSenses().stream())
                    .flatMap(x -> getTargetsUnchecked(x, type).stream())
                    .map(PointerTarget::getSynset)
                    .flatMap(x -> x.getWords().stream())
                    .map(Word::getLemma)
                    .collect(Collectors.toSet());
        }

        private static List<Word> targetsAsList(final IndexWord m, final PointerType type) {
            final Set<Word> words = m.getSenses().stream()
                    .flatMap(x -> getTargetsUnchecked(x, type).stream())
                    .map(PointerTarget::getSynset)
                    .flatMap(x -> x.getWords().stream())
                    .collect(Collectors.toSet());
            final List<Word> l = new ArrayList<>(words);
            l.sort(Comparator.comparing(Word::getLemma));
            return l;
        }

        private static Set<String> hypernyms(final Collection<IndexWord> m) {
            return targets(m, PointerType.HYPERNYM);
        }

        private static Set<String> antonyms(final Collection<IndexWord> m) {
            return targets(m, PointerType.ANTONYM);
        }

        private static String rootWords(final Collection<IndexWord> m) {
            return String.join(", ", m.stream().map(IndexWord::getLemma).collect(Collectors.toSet()));
        }

        private static List<TreeNode> getNodes(final TreeNode node) {
            final List<TreeNode> l = new ArrayList<>();
            l.add(node);
            for (final TreeNode child : Collections.list(node.children())) {
                l.addAll(getNodes(child));
            }

            return l;
        }

        public DocumentFrame() throws HeadlessException {
            super("WordWhittler");

            definitionArea.setEditable(false);
            wordTree.setRootVisible(false);

            final DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) wordTree.getCellRenderer();
            renderer.setLeafIcon(null);
            renderer.setClosedIcon(null);
            renderer.setOpenIcon(null);

            wordTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            wordTree.addTreeSelectionListener(e -> {
                final TreeNode node = (TreeNode) e.getPath().getLastPathComponent();
                if (node instanceof WordTreeNode) {
                    final WordTreeNode w = (WordTreeNode) node;
                    definitionArea.setText(w.word.getSynset().getGloss());
                } else {
                    definitionArea.setText("");
                }
            });

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

            metaContainer.add(new JScrollPane(wordTree));

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

    public static List<IndexWord> lookupAsList(final Dictionary dictionary, final String s) {
        final Map<POS, IndexWord> m = lookup(dictionary, s);
        final Set<IndexWord> wordsAsSet = new HashSet<>(m.values());
        final List<IndexWord> wordsAsList = new ArrayList<>(wordsAsSet);
        wordsAsList.sort(Comparator.comparing(IndexWord::getLemma));
        return wordsAsList;
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
