package org.enki;

import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.rules.RuleMatch;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.StringTokenizer;

public class App {

    private final JLanguageTool languageTool = new JLanguageTool(new AmericanEnglish());

    private static String getText(final JTextPane t) {
        final Document doc = t.getDocument();
        final int length = doc.getLength();
        try {
            return doc.getText(0, length);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isURL(final String s) {
        return s.startsWith("http://") ||
                s.startsWith("https://"); // FIXME: Detect domain in accordance with Twitter rules.
    }

    private static int getTwitterCharacters(final String s) {
        // See https://developer.twitter.com/en/docs/counting-characters

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

    private static String getWordAtCaret(JTextComponent tc) {
        try {
            int caretPosition = tc.getCaretPosition();
            int start = Utilities.getWordStart(tc, caretPosition);
            int end = Utilities.getWordEnd(tc, caretPosition);
            return tc.getText(start, end - start);
        } catch (final BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private void start() {
        final JTextPane contentArea = new JTextPane();
        final JLabel wordLabel = new JLabel();

        contentArea.addCaretListener(e -> {
            final String wordAtCaret = getWordAtCaret(contentArea);
            wordLabel.setText(wordAtCaret);

            try {
                final Highlighter h = contentArea.getHighlighter();
                h.removeAllHighlights();
                final List<RuleMatch> r = languageTool.check(getText(contentArea));
                for (final RuleMatch m : r) {
                    System.err.printf("m = '%s'\n", m);
                    try {
                        h.addHighlight(m.getFromPos(), m.getToPos(), DefaultHighlighter.DefaultPainter);
                    } catch (final BadLocationException ex) {
                        throw new AssertionError(ex);
                    }
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });

        final JFrame mainFrame = new JFrame("Parsimonious Publisher");

        final JComponent metaContainer = new Box(BoxLayout.Y_AXIS);

        final JTable infoTable = new JTable();

        infoTable.setModel(new DefaultTableModel() {

            @Override
            public int getRowCount() {
                return 3;
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
                switch (rowIndex) {
                    case 0:
                        switch (columnIndex) {
                            case 0:
                                return "Characters";
                            case 1:
                                return Integer.toString(getText(contentArea).length());
                            default:
                                throw new AssertionError();
                        }
                    case 1:
                        switch (columnIndex) {
                            case 0:
                                return "Twitter Characters";
                            case 1:
                                return Integer.toString(getTwitterCharacters(getText(contentArea)));
                            default:
                                throw new AssertionError();
                        }
                    case 2:
                        switch (columnIndex) {
                            case 0:
                                return "Twitter Characters Remaining";
                            case 1:
                                return Integer.toString(280 - getTwitterCharacters(getText(contentArea)));
                            default:
                                throw new AssertionError();
                        }
                    default:
                        throw new AssertionError();
                }
            }

        });

        metaContainer.add(infoTable);

        final JPanel caretWord = new JPanel();
        caretWord.add(new JLabel("Word at caret:"));
        caretWord.add(wordLabel);

        metaContainer.add(caretWord);

        final JSplitPane mainSplitPane =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, metaContainer, new JScrollPane(contentArea));

        mainFrame.add(mainSplitPane);

        contentArea.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(final DocumentEvent e) {
                ((DefaultTableModel) infoTable.getModel()).fireTableDataChanged();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                ((DefaultTableModel) infoTable.getModel()).fireTableDataChanged();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                ((DefaultTableModel) infoTable.getModel()).fireTableDataChanged();
            }

        });

        mainFrame.setSize(800, 600);
        mainFrame.setVisible(true);
    }

    public static void main(final String[] args) {
        SwingUtilities.invokeLater(() -> new App().start());
    }

}
