package org.enki;

import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.rules.RuleMatch;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class App {

    private final JLanguageTool languageTool = new JLanguageTool(new AmericanEnglish());

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
        return s.startsWith("http://") ||
                s.startsWith("https://"); // FIXME: Detect domain in accordance with Twitter rules.
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
        final JTextPane contentArea = new JTextPane();
        final JLabel wordLabel = new JLabel();
        final JList<RuleMatch> errorList = new JList<>();

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
                    return region + ": " +
                            ruleMatch.getMessage().replace("<suggestion>", "'").replaceAll("</suggestion>", "'");
                }));

        errorList.addListSelectionListener(errorListListener);

        contentCaretListener.set(e -> {
            final int selectionStart = contentArea.getSelectionStart();
            final int selectionEnd = contentArea.getSelectionEnd();
            final int dot = e.getDot();
            final int mark = e.getMark();

            if (mark == dot) {
                final String wordAtCaret = getWordAtCaret(contentArea, dot);
                wordLabel.setText(wordAtCaret);
            } else {
                wordLabel.setText(contentArea.getText()
                        .substring(contentArea.getSelectionStart(), contentArea.getSelectionEnd()));
            }

            try {
                final Highlighter h = contentArea.getHighlighter();
                h.removeAllHighlights();
                final List<RuleMatch> r = languageTool.check(getText(contentArea));
                errorList.setModel(new ListModel<>() {

                    @Override
                    public int getSize() {
                        return r.size();
                    }

                    @Override
                    public RuleMatch getElementAt(final int index) {
                        return r.get(index);
                    }

                    @Override
                    public void addListDataListener(ListDataListener l) {
                    }

                    @Override
                    public void removeListDataListener(ListDataListener l) {
                    }

                });

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
                contentArea.setSelectionStart(selectionStart);
                contentArea.setSelectionEnd(selectionEnd);
                contentArea.addCaretListener(contentCaretListener.get());
            }
        });

        contentArea.addCaretListener(contentCaretListener.get());

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
                return switch (rowIndex) {
                    case 0 -> switch (columnIndex) {
                        case 0 -> "Characters";
                        case 1 -> Integer.toString(getText(contentArea).length());
                        default -> throw new AssertionError();
                    };
                    case 1 -> switch (columnIndex) {
                        case 0 -> "Twitter Characters";
                        case 1 -> Integer.toString(getTwitterCharacters(getText(contentArea)));
                        default -> throw new AssertionError();
                    };
                    case 2 -> switch (columnIndex) {
                        case 0 -> "Twitter Characters Remaining";
                        case 1 -> Integer.toString(280 - getTwitterCharacters(getText(contentArea)));
                        default -> throw new AssertionError();
                    };
                    default -> throw new AssertionError();
                };
            }

        });

        metaContainer.add(infoTable);

        final JPanel caretWord = new JPanel();
        caretWord.add(wordLabel);

        metaContainer.add(caretWord);

        metaContainer.add(new JScrollPane(errorList));

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

    public static void main(final String[] args) {
        SwingUtilities.invokeLater(() -> new App().start());
    }

}
