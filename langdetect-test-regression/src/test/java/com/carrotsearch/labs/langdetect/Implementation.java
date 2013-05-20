package com.carrotsearch.labs.langdetect;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

public enum Implementation {
  BASELINE,
  MASTER;

  static interface ImplementationProxy {
    String detectLanguage(CharSequence chs) throws Exception;
  }

  static {
    /*
     * Initialize baseline.
     */
    final String languages[] =
    {
        "af", "ar", "bg", "bn", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi",
        "fr", "gu", "he", "hi", "hr", "hu", "id", "it", "ja", "kn", "ko", "lt", "lv",
        "mk", "ml", "mr", "ne", "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl",
        "so", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh-cn",
        "zh-tw"
    };

    try {
      List<String> profileData = new ArrayList<String>();
      for (String language : languages) {
        URL langData = Resources.getResource(
            com.cybozu.labs.langdetect.DetectorFactory.class, "/profiles/" + language);
        profileData.add(Resources.toString(langData, Charsets.UTF_8));
      }
      com.cybozu.labs.langdetect.DetectorFactory.loadProfile(profileData);
      com.cybozu.labs.langdetect.DetectorFactory.setSeed(0);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    
    try {
      List<String> profileData = new ArrayList<String>();
      for (String language : languages) {
        URL langData = Resources.getResource(DetectorFactory.class, "/profiles/" + language);
        profileData.add(Resources.toString(langData, Charsets.UTF_8));
      }
      DetectorFactory.loadProfile(profileData);
      DetectorFactory.setSeed(0);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  public Implementation.ImplementationProxy getProxy() {
    switch (this) {
      case BASELINE:
        return new ImplementationProxy() {
          public String detectLanguage(CharSequence chs) throws Exception {
            com.cybozu.labs.langdetect.Detector detector = 
                com.cybozu.labs.langdetect.DetectorFactory.create();
            detector.append(chs.toString());
            return detector.detect();
          }
        };

      case MASTER:
        return new ImplementationProxy() {
          public String detectLanguage(CharSequence chs)  throws Exception {
            Detector detector = DetectorFactory.create();
            detector.append(chs.toString());
            return detector.detect();
          }
        };
    }
    throw new RuntimeException();
  }
}