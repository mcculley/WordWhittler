package org.enki;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import com.google.common.io.Resources;
import com.vdurmont.semver4j.Semver;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.PointerTarget;
import net.sf.extjwnl.data.PointerType;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.data.Word;
import net.sf.extjwnl.dictionary.Dictionary;
import org.enki.swing.ListListModel;
import org.enki.swing.TransformingListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.rules.RuleMatch;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class App {

    private final JLanguageTool languageTool = new JLanguageTool(new AmericanEnglish());
    private final Dictionary dictionary;

    private record TableRow(String name, Supplier<String> valueSupplier) {
    }

    public static @NotNull
    Semver getVersion() {
        try {
            final URL versionResource = App.class.getResource("/version.txt");
            final Semver devVersion = new Semver("0.0.0");
            if (versionResource == null) {
                return devVersion;
            }

            final String s = Resources.toString(versionResource, Charsets.US_ASCII).trim();
            return s.equals("PROJECT_VERSION") ? devVersion : new Semver(s);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Semver version = getVersion();

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

    private static @NotNull
    String getText(@NotNull final JTextComponent t) {
        final Document doc = t.getDocument();
        final int length = doc.getLength();
        try {
            return doc.getText(0, length);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isURL(@NotNull final String s) {
        // FIXME: Detect domain in accordance with Twitter rules.
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String message(@NotNull final RuleMatch match) {
        return match.getMessage().replace("<suggestion>", "'").replaceAll("</suggestion>", "'");
    }

    private class DocumentFrame extends JFrame {

        private final JList<RuleMatch> errorList = new JList<>();
        private final JTextComponent definitionArea = new JTextPane();
        private final JTree wordTree = new JTree();
        private final JTextPane contentArea = new ContentPane();
        private final JMenuItem saveMenuItem;

        private File file;
        private String savedHash;
        private final JSplitPane sideSplitPane;
        private final JSplitPane bottomSplitPane;
        private String selectedRegion;
        private List<IndexWord> selectedWords;

        private class ContentPane extends JTextPane {

            public ContentPane() {
                setToolTipText("");
            }

            private @Nullable
            RuleMatch findError(final int position) {
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
            public @Nullable
            String getToolTipText(@NotNull final MouseEvent event) {
                final int position = viewToModel2D(event.getPoint());
                final RuleMatch m = findError(position);
                return m == null ? null : message(m);
            }

        }

        private @NotNull
        String fullDefinition(@NotNull final List<IndexWord> l) {
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

            public WordTreeNode(@NotNull final Word word, @NotNull final TreeNode parent) {
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

            public SynonymsNode(@NotNull final IndexWordTreeNode word, @NotNull final List<Word> synonyms) {
                this.word = Objects.requireNonNull(word);
                this.synonyms = Objects.requireNonNull(synonyms);
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

            public PointerTypeNode(@NotNull final IndexWordTreeNode word, @NotNull final PointerType type,
                                   @NotNull final List<Word> targets) {
                this.word = Objects.requireNonNull(word);
                this.type = Objects.requireNonNull(type);
                this.targets = Objects.requireNonNull(targets);
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

            public IndexWordTreeNode(@NotNull final IndexWord word) {
                this.word = Objects.requireNonNull(word);
                this.children = new ArrayList<>();

                final List<Word> synonyms = synonymsAsList(word);
                if (!synonyms.isEmpty()) {
                    children.add(new SynonymsNode(this, synonyms));
                }

                final PointerType[] types = {
                        PointerType.ANTONYM,
                        PointerType.HYPERNYM,
                        PointerType.CATEGORY,
                        PointerType.CATEGORY_MEMBER
                };

                for (final PointerType type : types) {
                    final List<Word> targets = targetsAsList(this.word, type);
                    if (!targets.isEmpty()) {
                        children.add(new PointerTypeNode(this, type, targets));
                    }
                }
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

        private final TreeNode rootWordTreeNode = new TreeNode() {

            @Override
            public TreeNode getChildAt(final int childIndex) {
                return new IndexWordTreeNode(selectedWords.get(childIndex));
            }

            @Override
            public int getChildCount() {
                if (selectedWords == null) {
                    return 0;
                } else {
                    return selectedWords.size();
                }
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
        private static @NotNull
        TreePath path(@NotNull final TreeNode node) {
            final TreeNode parent = node.getParent();
            if (parent == null) {
                return new TreePath(node);
            } else {
                return new TreePath(nodePath(node));
            }
        }

        private void setWordOfInterest(@NotNull final String s) {
            selectedRegion = s;
            selectedWords = lookupAsList(dictionary, s);
            wordTree.setModel(makeWordTreeModel());
            definitionArea.setText("");

            final List<TreeNode> allWordTreeNodes = getNodes((TreeNode) wordTree.getModel().getRoot());
            for (final TreeNode n : allWordTreeNodes) {
                if (n instanceof SynonymsNode || n instanceof PointerTypeNode) {
                    wordTree.expandPath(path(n));
                }
            }

            final Optional<TreeNode> firstWord =
                    allWordTreeNodes.stream().filter(x -> x instanceof WordTreeNode).findFirst();
            firstWord.ifPresent(treeNode -> wordTree.setSelectionPath(path(treeNode)));
        }

        private static long wordCount(@NotNull final String s) {
            return Stream.of(s.split("\r?\n|\r| ")).filter(x -> x.trim().length() > 0).count();
        }

        private static @NotNull
        Set<Word> synonyms(@NotNull final IndexWord w) {
            return w.getSenses().stream()
                    .flatMap(x -> x.getWords().stream())
                    .collect(Collectors.toSet());
        }

        private static @NotNull
        List<Word> synonymsAsList(@NotNull final IndexWord w) {
            final Set<Word> words = synonyms(w);
            final List<Word> l = new ArrayList<>(words);
            l.sort(Comparator.comparing(Word::getLemma));
            return l;
        }

        private static @NotNull
        List<PointerTarget> getTargetsUnchecked(@NotNull final Synset s, @NotNull final PointerType t) {
            try {
                return s.getTargets(t);
            } catch (final JWNLException e) {
                throw new RuntimeException(e);
            }
        }

        private static @NotNull
        List<Word> targetsAsList(@NotNull final IndexWord m, @NotNull final PointerType type) {
            return m.getSenses().stream()
                    .flatMap(x -> getTargetsUnchecked(x, type).stream())
                    .map(PointerTarget::getSynset)
                    .flatMap(x -> x.getWords().stream())
                    .distinct()
                    .sorted(Comparator.comparing(Word::getLemma))
                    .collect(Collectors.toList());
        }

        private static @NotNull
        String rootWords(@NotNull final Collection<IndexWord> m) {
            return String.join(", ", m.stream().map(IndexWord::getLemma).collect(Collectors.toSet()));
        }

        private static @NotNull
        List<TreeNode> getNodes(@NotNull final TreeNode node) {
            final List<TreeNode> l = new ArrayList<>();
            l.add(node);
            for (final TreeNode child : Collections.list(node.children())) {
                l.addAll(getNodes(child));
            }

            return l;
        }

        private void updateTitle() {
            final StringBuilder b = new StringBuilder();
            if (file != null) {
                b.append(file.toPath());
            }

            // FIXME: Maybe just keep whole old text.
            final String currentHash = hash(getText(contentArea));
            final boolean clean = savedHash != null && savedHash.equals(currentHash);
            if (!clean) {
                b.append(" (unsaved)");
            }

            setTitle(b.toString());
        }

        public DocumentFrame() {
            super("WordWhittler");

            definitionArea.setEditable(false);
            wordTree.setRootVisible(false);
            wordTree.setModel(makeWordTreeModel());

            final JMenuBar menuBar = new JMenuBar();
            setJMenuBar(menuBar);

            final JMenu fileMenu = new JMenu("File");
            menuBar.add(fileMenu);

            // FIXME: Make accelerators compliant when running on Windows. I am using Mac standards here.

            final JMenuItem newMenuItem = new JMenuItem("New", KeyEvent.VK_N);
            fileMenu.add(newMenuItem);
            newMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.META_DOWN_MASK));
            newMenuItem.addActionListener(e -> createNewDocumentFrame());

            final JMenuItem openMenuItem = new JMenuItem("Open", KeyEvent.VK_O);
            fileMenu.add(openMenuItem);
            openMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK));
            openMenuItem.addActionListener(e -> {
                final JFileChooser fileChooser = new JFileChooser();
                int result = fileChooser.showOpenDialog(this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    final File selectedFile = fileChooser.getSelectedFile();
                    final DocumentFrame newFrame = createNewDocumentFrame();
                    try {
                        newFrame.loadFile(selectedFile);
                    } catch (final IOException x) {
                        // FIXME: Load the file before creating the new window.
                        JOptionPane.showMessageDialog(this, x, "error loading", JOptionPane.ERROR_MESSAGE);
                        newFrame.setVisible(false);
                        System.err.println(x);
                    }
                }
            });

            fileMenu.add(new JSeparator());

            final JMenuItem closeMenuItem = new JMenuItem("Close", KeyEvent.VK_C);
            fileMenu.add(closeMenuItem);
            closeMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.META_DOWN_MASK));
            closeMenuItem.addActionListener(e -> {
                // FIXME: Make sure there are no unsaved changes.
                setVisible(false);
            });

            saveMenuItem = new JMenuItem("Save", KeyEvent.VK_S);
            fileMenu.add(saveMenuItem);
            saveMenuItem.setEnabled(false);
            saveMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK));
            saveMenuItem.addActionListener(e -> {
                try {
                    final String content = getText(contentArea);
                    Files.write(file.toPath(), content.getBytes());
                    savedHash = hash(content);
                } catch (final IOException x) {
                    JOptionPane.showMessageDialog(this, x, "error saving", JOptionPane.ERROR_MESSAGE);
                }
            });

            final JMenuItem saveAsMenuItem = new JMenuItem("Save As...", KeyEvent.VK_A);
            fileMenu.add(saveAsMenuItem);
            saveAsMenuItem.setAccelerator(
                    KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            saveAsMenuItem.addActionListener(e -> {
                final JFileChooser fileChooser = new JFileChooser();
                int result = fileChooser.showSaveDialog(this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    final File selectedFile = fileChooser.getSelectedFile();
                    try {
                        file = selectedFile;
                        final String content = getText(contentArea);
                        Files.write(file.toPath(), content.getBytes());
                        savedHash = hash(content);
                        updateTitle();
                        saveMenuItem.setEnabled(true);
                    } catch (final IOException x) {
                        JOptionPane.showMessageDialog(this, x, "error saving", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            fileMenu.add(new JSeparator());

            final JMenuItem aboutMenuItem = new JMenuItem("About...");
            fileMenu.add(aboutMenuItem);
            aboutMenuItem.addActionListener(e -> {
                final URL aboutResource = getClass().getResource("/about.html");
                try {
                    final JEditorPane p = new JEditorPane();
                    p.setEditable(false);
                    p.setContentType("text/html");
                    p.setPage(aboutResource);
                    p.addHyperlinkListener(hyperlinkEvent -> {
                        if (hyperlinkEvent.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                            final URL url = hyperlinkEvent.getURL();
                            try {
                                Desktop.getDesktop().browse(url.toURI());
                            } catch (final IOException ex) {
                                JOptionPane.showMessageDialog(this, ex, "error opening", JOptionPane.ERROR_MESSAGE);
                            } catch (final URISyntaxException ex) {
                                throw new AssertionError(ex);
                            }
                        }
                    });
                    final JScrollPane s = new JScrollPane(p);
                    p.setPreferredSize(new Dimension(600, 200));
                    JOptionPane.showMessageDialog(this, s, "About WordWhittler", JOptionPane.INFORMATION_MESSAGE);
                } catch (final IOException x) {
                    throw new UncheckedIOException(x);
                }
            });

            fileMenu.add(new JSeparator());

            final JMenuItem quitMenuItem = new JMenuItem("Quit", KeyEvent.VK_Q);
            fileMenu.add(quitMenuItem);
            quitMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.META_DOWN_MASK));
            quitMenuItem.addActionListener(e -> {
                // FIXME: Check that the file has no unsaved changes.
                System.exit(0);
            });

            final DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) wordTree.getCellRenderer();
            renderer.setLeafIcon(null);
            renderer.setClosedIcon(null);
            renderer.setOpenIcon(null);

            wordTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            wordTree.addTreeSelectionListener(e -> {
                final TreeNode node = (TreeNode) e.getPath().getLastPathComponent();
                if (node instanceof WordTreeNode w) {
                    definitionArea.setText(w.word.getSynset().getGloss());
                } else {
                    definitionArea.setText("");
                }
            });

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

            errorList.setCellRenderer(new TransformingListCellRenderer<RuleMatch>(
                    ruleMatch -> {
                        final String region = getRegion(contentArea, ruleMatch);
                        return region.trim() + ": " + message(ruleMatch);
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
                    final int maxSelection = 20;
                    String selectedString = contentArea.getText()
                            .substring(contentArea.getSelectionStart(), contentArea.getSelectionEnd());
                    if (selectedString.length() > maxSelection) {
                        selectedString = selectedString.substring(0, maxSelection);
                    }

                    setWordOfInterest(selectedString);
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

                private boolean isDanger(@NotNull final Object value) {
                    if (value instanceof String s) {
                        return isNumeric(s) && s.startsWith("-");
                    }

                    return false;
                }

                @Override
                public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                               final boolean isSelected, final boolean hasFocus,
                                                               final int row, final int column) {
                    final Component c =
                            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    c.setForeground(isDanger(value) ? Color.RED :
                            isSelected ? table.getSelectionForeground() : table.getForeground());
                    return c;
                }

            });

            final TableRow[] infoRows = new TableRow[]{
                    new TableRow("Characters", () -> Integer.toString(getText(contentArea).length())),
                    new TableRow("Words", () -> Long.toString(wordCount(getText(contentArea)))),
                    new TableRow("Twitter Characters",
                            () -> Integer.toString(getTwitterCharacters(getText(contentArea)))),
                    new TableRow("Twitter Characters Remaining",
                            () -> Integer.toString(280 - getTwitterCharacters(getText(contentArea))))
            };

            infoTable.setModel(new TableRowModel(infoRows));
            infoTable.getColumnModel().getColumn(0).setMinWidth(180);
            infoTable.getColumnModel().getColumn(1).setMinWidth(50);

            final TableRow[] wordTableRows = new TableRow[]{
                    new TableRow("selection", () -> selectedRegion),
                    new TableRow("root(s)", () -> selectedWords == null ? "" : rootWords(selectedWords))
            };

            wordTable.setModel(new TableRowModel(wordTableRows));

            metaContainer.add(infoTable);

            metaContainer.add(Box.createVerticalStrut(0));

            metaContainer.add(wordTable);

            metaContainer.add(Box.createVerticalStrut(0));

            metaContainer.add(new JScrollPane(wordTree));

            sideSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, metaContainer,
                    new JScrollPane(definitionArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));

            final JSplitPane mainSplitPane =
                    new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sideSplitPane, new JScrollPane(contentArea));

            bottomSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplitPane, new JScrollPane(errorList));

            add(bottomSplitPane);

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
                    updateTitle();
                }

            };

            contentArea.getDocument().addDocumentListener(propagatingDocumentListener);

            final boolean debug = false;
            if (debug) {
                try {
                    final String testText = Files.readString(Path.of("./demo.txt"));
                    contentArea.setText(testText);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        private static @NotNull
        String hash(@NotNull final String s) {
            try {
                final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
                messageDigest.update(s.getBytes());
                return new String(messageDigest.digest());
            } catch (final NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }

        private void loadFile(@NotNull final File file) throws IOException {
            final String content = Files.readString(file.toPath());
            this.file = file;
            savedHash = hash(content);
            contentArea.setText(content);
            updateTitle();
            saveMenuItem.setEnabled(true);
        }

    }

    private static int getTwitterCharacters(@NotNull final String s) {
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

    private static @NotNull
    String getWordAtCaret(@NotNull final JTextComponent tc, final int caretPosition) {
        try {
            final int start = Utilities.getWordStart(tc, caretPosition);
            final int end = Utilities.getWordEnd(tc, caretPosition);
            return tc.getText(start, end - start);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static @NotNull String getRegion(@NotNull final JTextComponent tc, @NotNull final RuleMatch m) {
        return getText(tc).substring(m.getFromPos(), m.getToPos());
    }

    private @NotNull
    DocumentFrame createNewDocumentFrame() {
        final DocumentFrame mainFrame = new DocumentFrame();
        mainFrame.setSize(1200, 1000);
        mainFrame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            mainFrame.bottomSplitPane.setDividerLocation(0.80);
            mainFrame.sideSplitPane.setDividerLocation(0.50);
        });

        return mainFrame;
    }

    private final Pattern pattern = Pattern.compile("-?\\d+(\\.\\d+)?");

    public boolean isNumeric(@NotNull final String strNum) {
        Objects.requireNonNull(strNum);
        return pattern.matcher(strNum).matches();
    }

    public static List<IndexWord> lookupAsList(@NotNull final Dictionary dictionary, @NotNull final String s) {
        final Map<POS, IndexWord> m = lookup(dictionary, s);
        final Set<IndexWord> wordsAsSet = new HashSet<>(m.values());
        final List<IndexWord> wordsAsList = new ArrayList<>(wordsAsSet);
        wordsAsList.sort(Comparator.comparing(IndexWord::getLemma));
        return wordsAsList;
    }

    public static @NotNull
    Map<POS, IndexWord> lookup(@NotNull final Dictionary dictionary, @NotNull final String s) {
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

    public static void main(@NotNull final String[] args) {
        System.out.println("starting WordWhittler v" + version);
        SwingUtilities.invokeLater(() -> new App().createNewDocumentFrame());
    }

}
