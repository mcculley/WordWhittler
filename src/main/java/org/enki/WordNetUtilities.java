package org.enki;

import com.google.common.collect.ImmutableMap;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.data.Word;
import net.sf.extjwnl.dictionary.Dictionary;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class WordNetUtilities {

    private WordNetUtilities() {
        throw new AssertionError("static utility class is not intended to be instantiated");
    }

    public static @NotNull <T> Stream<T> toStream(@NotNull final Iterator<T> i) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(i, Spliterator.ORDERED), false);
    }

    public static @NotNull Iterator<IndexWord> getIndexWordIteratorUnchecked(@NotNull final Dictionary dictionary, @NotNull final POS p) {
        try {
            return dictionary.getIndexWordIterator(p);
        } catch (final JWNLException e) {
            throw new RuntimeException(e);
        }
    }

    private static @NotNull String normalizeNonLetters(@NotNull final String s) {
        final int length = s.length();
        final StringBuilder b = new StringBuilder();

        for (int i = 0; i < length; i++) {
            final char c = s.charAt(i);
            if (Character.isLetter(c)) {
                b.append(Character.toLowerCase(c));
            }
        }

        return b.toString();
    }

    private static boolean contains(@NotNull final Word multiWord, @NotNull final Word singleWord) {
        assert !singleWord.getLemma().contains(" ");
        return Arrays.stream(multiWord.getLemma().split(" ")).map(WordNetUtilities::normalizeNonLetters)
                .collect(Collectors.toSet()).contains(singleWord.getLemma());
    }

    private static boolean hasCapitalizedLetter(@NotNull final Word word) {
        final String lemma = word.getLemma();
        final int length = lemma.length();
        for (int i = 0; i < length; i++) {
            final char c = lemma.charAt(i);
            if (Character.isUpperCase(c)) {
                return true;
            }
        }

        return false;
    }

    public static Map<String, Synset> phrasesToWords(@NotNull final Dictionary dictionary) {
        final ImmutableMap.Builder<String, Synset> phraseToWord = new ImmutableMap.Builder<>();
        final Set<IndexWord> s = POS.getAllPOS().stream()
                .flatMap(p -> toStream(getIndexWordIteratorUnchecked(dictionary, p)))
                .collect(Collectors.toSet());
        s.forEach(word -> {
            final List<Synset> senses = word.getSenses();
            for (final Synset synset : senses) {
                final List<Word> synonyms = synset.getWords();
                final Set<Word> multiwordSynonyms = new HashSet<>();
                final Set<Word> singleWordSynonyms = new HashSet<>();
                for (final Word synonym : synonyms) {
                    if (hasCapitalizedLetter(synonym)) {
                        continue;
                    }

                    final String lemma = synonym.getLemma();
                    if (lemma.contains(" ")) {
                        multiwordSynonyms.add(synonym);
                    } else {
                        singleWordSynonyms.add(synonym);
                    }
                }

                final Set<Word> singlesToRemove = new HashSet<>();
                for (final Word multiwordSynonym : multiwordSynonyms) {
                    for (final Word singleWordSynoym : singleWordSynonyms) {
                        if (contains(multiwordSynonym, singleWordSynoym)) {
                            singlesToRemove.add(singleWordSynoym);
                        }
                    }
                }

                singleWordSynonyms.removeAll(singlesToRemove);

                if (!multiwordSynonyms.isEmpty() && !singleWordSynonyms.isEmpty()) {
                    multiwordSynonyms.forEach(mw -> phraseToWord.put(mw.getLemma(), synset));
                }
            }
        });

        return phraseToWord.build();
    }

}
