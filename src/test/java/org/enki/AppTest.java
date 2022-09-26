package org.enki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.dictionary.Dictionary;
import org.junit.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class AppTest {

    private static <T> Stream<T> toStream(final Iterator<T> i) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(i, Spliterator.ORDERED), false);
    }

    private static Iterator<IndexWord> getIndexWordIteratorUnchecked(final Dictionary dictionary, final POS p) {
        try {
            return dictionary.getIndexWordIterator(p);
        } catch (final JWNLException e) {
            throw new RuntimeException(e);
        }
    }

    //@Test
    public void testDictionary() throws JWNLException {
        final Dictionary dictionary = Dictionary.getDefaultResourceInstance();
        final Set<IndexWord> s = POS.getAllPOS().stream()
                .flatMap(p -> toStream(getIndexWordIteratorUnchecked(dictionary, p)))
                .collect(Collectors.toSet());
        //  System.out.println("all index words = " + s);
        s.forEach(word -> {
            if (!word.getLemma().contains(" ")) {
                final Map<POS, IndexWord> m = App.lookup(dictionary, word.getLemma());
                final Set<IndexWord> roots = new HashSet<>(m.values());
//            final Set<String> roots = m.values().stream().map(IndexWord::getLemma).collect(Collectors.toSet());
                if (roots.size() > 1) {
                    System.out.printf("expected only one root for %s but got %s\n", word, roots);
                }
//            assertEquals(String.format("expected only one root for %s but got %s", word, roots), 1, roots.size());
            }
        });
    }

}
