package com.carrotsearch.labs.langdetect;

import java.io.IOException;
import java.io.Reader;
import java.lang.Character.UnicodeBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;


/**
 * {@link Detector} class is to detect language from specified text. 
 * Its instance is able to be constructed via the factory class {@link DetectorFactory}.
 * <p>
 * After appending a target text to the {@link Detector} instance with {@link #append(Reader)} or {@link #append(String)},
 * the detector provides the language detection results for target text via {@link #detect()} or {@link #getProbabilities()}.
 * {@link #detect()} method returns a single language name which has the highest probability.
 * {@link #getProbabilities()} methods returns a list of multiple languages and their probabilities.
 * <p>  
 * 
 * <pre>
 * import java.util.ArrayList;
 * import com.cybozu.labs.langdetect.Detector;
 * import com.cybozu.labs.langdetect.DetectorFactory;
 * import com.cybozu.labs.langdetect.Language;
 * 
 * class LangDetectSample {
 *     public void init(String profileDirectory) throws LangDetectException {
 *         DetectorFactory.loadProfile(profileDirectory);
 *     }
 *     public String detect(String text) throws LangDetectException {
 *         Detector detector = DetectorFactory.create();
 *         detector.append(text);
 *         return detector.detect();
 *     }
 *     public ArrayList<Language> detectLangs(String text) throws LangDetectException {
 *         Detector detector = DetectorFactory.create();
 *         detector.append(text);
 *         return detector.getProbabilities();
 *     }
 * }
 * </pre>
 * 
 * <ul>
 * <li>4x faster improvement based on Elmer Garduno's code. Thanks!</li>
 * </ul>
 * 
 * @author Nakatani Shuyo
 * @see DetectorFactory
 */
public class Detector {
    private static final double ALPHA_DEFAULT = 0.5;
    private static final double ALPHA_WIDTH = 0.05;

    private static final int ITERATION_LIMIT = 1000;
    private static final double PROB_THRESHOLD = 0.1;
    private static final double CONV_THRESHOLD = 0.99999;
    private static final int BASE_FREQ = 10000;

    private static final Pattern URL_REGEX = Pattern.compile("https?://[-_.?&~;+=/#0-9A-Za-z]{1,2076}");
    private static final Pattern MAIL_REGEX = Pattern.compile("[-_.0-9A-Za-z]{1,64}@[-_0-9A-Za-z]{1,255}[-_.0-9A-Za-z]{1,255}");
    
    private final HashMap<String, double[]> wordLangProbMap;
    private final ArrayList<String> langlist;

    private final StringBuilder text = new StringBuilder();
    private final double[] langProb;
    private final double[] trialProb;

    private double alpha = ALPHA_DEFAULT;
    private int n_trial = 7;
    private int max_text_length = 10000;
    private final Long seed;

    /**
     * Constructor.
     * Detector instance can be constructed via {@link DetectorFactory#create()}.
     * @param factory {@link DetectorFactory} instance (only DetectorFactory inside)
     */
    public Detector(DetectorFactory factory) {
        this.wordLangProbMap = factory.wordLangProbMap;
        this.langlist = factory.langlist;
        this.seed  = factory.seed;
        this.langProb = new double[langlist.size()];
        this.trialProb = new double[langlist.size()];
    }

    /**
     * Set smoothing parameter.
     * The default value is 0.5(i.e. Expected Likelihood Estimate).
     * @param alpha the smoothing parameter
     */
    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }

    /**
     * Specify max size of target text to use for language detection.
     * The default value is 10000(10KB).
     * @param max_text_length the max_text_length to set
     */
    public void setMaxTextLength(int max_text_length) {
        this.max_text_length = max_text_length;
    }

    
    /**
     * Append the target text for language detection.
     * This method read the text from specified input reader.
     * If the total size of target text exceeds the limit size specified by {@link Detector#setMaxTextLength(int)},
     * the rest is cut down.
     * 
     * @param reader the input reader (BufferedReader as usual)
     * @throws IOException Can't read the reader.
     */
    public void append(Reader reader) throws IOException {
        char[] buf = new char[max_text_length/2];
        while (text.length() < max_text_length && reader.ready()) {
            int length = reader.read(buf);
            append(new String(buf, 0, length));
        }
    }

    /**
     * Append the target text for language detection.
     * If the total size of target text exceeds the limit size specified by {@link Detector#setMaxTextLength(int)},
     * the rest is cut down.
     * 
     * @param text the target text to append
     */
    public void append(String text) {
        // TODO: move these to pluggable cleanup filters (or remove entirely)?
        text = URL_REGEX.matcher(text).replaceAll(" ");
        text = MAIL_REGEX.matcher(text).replaceAll(" ");
        text = NGram.normalize_vi(text);
        char pre = 0;
        for (int i = 0; i < text.length() && i < max_text_length; ++i) {
            char c = text.charAt(i);
            if (c != ' ' || pre != ' ') this.text.append(c);
            pre = c;
        }
    }

    /**
     * Cleaning text to detect
     * (eliminate URL, e-mail address and Latin sentence if it is not written in Latin alphabet)
     */
    private void cleaningText() {
        int latinCount = 0, nonLatinCount = 0;
        for(int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c <= 'z' && c >= 'A') {
                ++latinCount;
            } else if (c >= '\u0300' && UnicodeBlock.of(c) != UnicodeBlock.LATIN_EXTENDED_ADDITIONAL) {
                ++nonLatinCount;
            }
        }
        if (latinCount * 2 < nonLatinCount) {
            int to = 0;
            for(int from = 0, max = text.length(); from < max; from++) {
                char c = text.charAt(from);
                if (c > 'z' || c < 'A') { 
                    text.setCharAt(to++, c);
                }
            }
            text.setLength(to);
        }
    }

    /**
     * Detect language of the target text and return the language name which has the highest probability.
     * @return detected language name which has most probability.
     * @throws LangDetectException 
     *  code = ErrorCode.CantDetectError : Can't detect because of no valid features in text
     */
    public String detect() throws LangDetectException {
        detectBlock();

        String language = /* Unrecognized. */ null;
        double maxP = 0;
        double [] langProb = this.langProb;
        for (int i = 0; i < langProb.length; i++) {
            final double p = langProb[i];
            if (p > PROB_THRESHOLD) {
                if (p > maxP) {
                    // TODO: in case of ties we could pick the language with a larger representation (more trigrams)?
                    maxP = p;
                    language = langlist.get(i);
                }
            }
        }

        return language;
    }

    /**
     * Get language candidates which have high probabilities
     * @return possible languages list (whose probabilities are over PROB_THRESHOLD, ordered by probabilities descendently
     * @throws LangDetectException 
     *  code = ErrorCode.CantDetectError : Can't detect because of no valid features in text
     */
    public List<Language> getProbabilities() throws LangDetectException {
        // TODO: return a reusable list (view).
        detectBlock();
        return sortProbability(langProb);
    }
    
    /**
     * @throws LangDetectException 
     * 
     */
    private void detectBlock() throws LangDetectException {
        cleaningText();
        ArrayList<String> ngrams = extractNGrams(); // TODO: iterate over n-grams, don't extract them
        if (ngrams.size()==0)
            throw new LangDetectException(ErrorCode.CantDetectError, "no features in text");
        
        double [] langProb = this.langProb;
        double [] trialProb = this.trialProb;

        Arrays.fill(langProb, 0d);

        Random rand = new TLRandom();
        if (seed != null) rand.setSeed(seed);
        for (int t = 0; t < n_trial; ++t) {
            Arrays.fill(trialProb, 0d);
            for(int i=0;i<trialProb.length;++i) 
                trialProb[i] = 1.0 / langlist.size();

            double alpha = this.alpha + rand.nextGaussian() * ALPHA_WIDTH;

            for (int i = 0;; ++i) {
                int r = rand.nextInt(ngrams.size());
                updateLangProb(trialProb, ngrams.get(r), alpha);
                if (i % 5 == 0) {
                    if (normalizeProb(trialProb) > CONV_THRESHOLD || i>=ITERATION_LIMIT) break;
                }
            }
            for(int j=0;j<langProb.length;++j) langProb[j] += trialProb[j] / n_trial;
        }
    }

    /**
     * Extract n-grams from target text
     * @return n-grams list
     */
    private ArrayList<String> extractNGrams() {
        ArrayList<String> list = new ArrayList<String>();
        NGram ngram = new NGram();
        for(int i=0;i<text.length();++i) {
            ngram.addChar(text.charAt(i));
            for(int n=1;n<=NGram.N_GRAM;++n){
                String w = ngram.get(n);
                if (w!=null && wordLangProbMap.containsKey(w)) list.add(w);
            }
        }
        return list;
    }

    /**
     * update language probabilities with N-gram string(N=1,2,3)
     * @param word N-gram string
     */
    private boolean updateLangProb(double[] prob, String word, double alpha) {
        if (word == null || !wordLangProbMap.containsKey(word)) return false;

        double[] langProbMap = wordLangProbMap.get(word);
        double weight = alpha / BASE_FREQ;
        for (int i=0;i<prob.length;++i) {
            prob[i] *= weight + langProbMap[i];
        }
        return true;
    }

    /**
     * normalize probabilities and check convergence by the maximun probability
     * @return maximum of probabilities
     */
    static private double normalizeProb(double[] prob) {
        double maxp = 0, sump = 0;
        for(int i=0;i<prob.length;++i) sump += prob[i];
        for(int i=0;i<prob.length;++i) {
            double p = prob[i] / sump;
            if (maxp < p) maxp = p;
            prob[i] = p;
        }
        return maxp;
    }

    /**
     * @param prob HashMap
     * @return language candidates ordered by decreasing probabilities
     */
    private List<Language> sortProbability(double[] prob) {
        // TODO: this sorting is not needed for the (common) case of requiring only max. element!
        ArrayList<Language> list = new ArrayList<Language>();
        for(int j=0;j<prob.length;++j) {
            double p = prob[j];
            if (p > PROB_THRESHOLD) {
                for (int i = 0; i <= list.size(); ++i) {
                    if (i == list.size() || list.get(i).prob < p) {
                        list.add(i, new Language(langlist.get(j), p));
                        break;
                    }
                }
            }
        }
        return list;
    }

    public String detect(CharSequence chs) throws LangDetectException
    {
        reset();
        append(chs.toString());
        return detect();
    }

    private void reset()
    {
        this.text.setLength(0);
    }
}
