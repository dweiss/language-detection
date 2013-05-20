package com.carrotsearch.labs.langdetect;


import java.util.List;
import java.util.Random;

import org.junit.BeforeClass;
import org.junit.Test;

import com.carrotsearch.junitbenchmarks.AbstractBenchmark;
import com.carrotsearch.junitbenchmarks.BenchmarkOptions;
import com.carrotsearch.labs.langdetect.Implementation.ImplementationProxy;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import com.google.common.collect.Lists;

public class BenchmarkAgainstBaseline extends AbstractBenchmark {
  private static List<String> inputs = Lists.newArrayList();

  @BeforeClass
  public static void setUp() throws Exception {
    Random r = new Random(0xdeadbeef);
    for (int i = 0; i < 2000; i++) {
      if (r.nextInt(10) < 2) {
        inputs.add(RandomStrings.randomRealisticUnicodeOfCodepointLength(r, 400));
      } else {
        inputs.add(RandomStrings.randomAsciiOfLength(r, 400));
      }
    }
  }

  /* Try to prevent no-side-effects on JIT. */
  @SuppressWarnings("unused")
  private volatile Object guard;

  @Test
  @BenchmarkOptions(warmupRounds = 1, benchmarkRounds = 3, callgc = false)
  public void master() throws Exception {
    guard = doRun(Implementation.MASTER);
  }

  @Test
  @BenchmarkOptions(warmupRounds = 1, benchmarkRounds = 3, callgc = false)
  public void baseline() throws Exception {
    guard = doRun(Implementation.BASELINE);
  }

  public int doRun(Implementation impl) throws Exception {
    ImplementationProxy proxy = impl.getProxy();
    int v = 0;
    for (String in : inputs) {
      String lang = TestRegressionAgainstBaseline.exceptionToNull(proxy, in);
      if (lang != null) {
        v += lang.length();
      }
    }
    return v;
  }
}
