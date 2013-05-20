package com.carrotsearch.labs.langdetect;


import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import com.carrotsearch.labs.langdetect.Implementation.ImplementationProxy;
import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class TestRegressionAgainstBaseline extends RandomizedTest {
  private static List<String> inputs = Lists.newArrayList();

  @BeforeClass
  static void setUp() throws Exception {
    Random r = RandomizedContext.current().getRandom();
    for (int i = 0; i < 10000; i++) {
      if (r.nextInt(10) < 3) {
        inputs.add(RandomStrings.randomRealisticUnicodeOfCodepointLength(r, 400));
      } else {
        inputs.add(RandomStrings.randomAsciiOfLength(r, 400));
      }
    }
  }

  @Test
  public void regressionDetectLanguage() {
    ImplementationProxy baseline = Implementation.BASELINE.getProxy();
    ImplementationProxy master = Implementation.MASTER.getProxy();

    Set<String> all = Sets.newTreeSet();
    for (String in : inputs) {
      String baselineLang = exceptionToNull(baseline, in);
      String masterLang   = exceptionToNull(master, in);
      assertEquals(baselineLang, masterLang);
      if (masterLang != null) all.add(masterLang);
    }
    System.out.println("Detected: " + all.size() + " langs: " + all);
  }

  static String exceptionToNull(ImplementationProxy baseline, String in) {
    try {
      return baseline.detectLanguage(in);
    } catch (Exception e) {
      if (e.getClass().getSimpleName().equals("LangDetectException")) {
        return null;
      }
      throw new RuntimeException(e);
    }
  }
}
