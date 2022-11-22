package org.enki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.dictionary.Dictionary;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AppTest {

    @Test
    public void testWordySynonyms() throws JWNLException {
        final Dictionary dictionary = Dictionary.getDefaultResourceInstance();
//        final Map<String, Synset> phraseToWord = WordNetUtilities.phrasesToWords(dictionary);
//        System.out.println("phraseToWord=" + phraseToWord);
    }

    //@Test
    public void testDictionary() throws JWNLException {
        final Dictionary dictionary = Dictionary.getDefaultResourceInstance();
        final Set<IndexWord> s = POS.getAllPOS().stream()
                .flatMap(p -> WordNetUtilities.toStream(WordNetUtilities.getIndexWordIteratorUnchecked(dictionary, p)))
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
