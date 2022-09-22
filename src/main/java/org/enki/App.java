package org.enki;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.util.StringTokenizer;

public class App {

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
                s.startsWith("https://"); // FIXME: detect domain in accordance with Twitter rules.
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

    private void start() {
        final JTextPane contentArea = new JTextPane();

        final JFrame mainFrame = new JFrame("Parsimonious Publisher");

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
        final JSplitPane mainSplitPane =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoTable, new JScrollPane(contentArea));

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
