package dev.smsrelay;

import android.database.Cursor;
import android.util.Base64;

public final class TestBody {
  public static void main(String[] args) throws Exception {
    String expected = new String(Base64.decode(args[0], Base64.DEFAULT), "UTF-8");
    if (!expected.contains("SMSRELAY_TEST"))
      throw new SecurityException("synthetic marker required");
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("SMSRELAY_TEST[A-Za-z0-9_]*").matcher(expected);
    if (!m.find()) throw new IllegalArgumentException("marker");
    String marker = m.group();
    PlatformBridge bridge = new PlatformBridge();
    int matches = 0;
    try (Cursor c =
        bridge.query(
            new String[] {"body"}, "body LIKE ?", new String[] {"%" + marker + "%"}, "_id DESC")) {
      while (c.moveToNext()) {
        String actual = c.getString(0);
        matches++;
        boolean exact = expected.equals(actual);
        boolean crlf = expected.replace("\r\n", "\n").equals(actual.replace("\r\n", "\n"));
        System.out.println(
            "TEST_BODY exact="
                + exact
                + " equal_after_CRLF_normalization="
                + crlf
                + " chars="
                + actual.length()
                + " utf8="
                + actual.getBytes("UTF-8").length
                + " sha256="
                + Rules.hash(actual));
        if (!crlf) {
          int first = 0;
          while (first < Math.min(expected.length(), actual.length())
              && expected.charAt(first) == actual.charAt(first)) first++;
          System.out.println(
              "DIFF first="
                  + first
                  + " expected_char="
                  + (first < expected.length() ? (int) expected.charAt(first) : -1)
                  + " actual_char="
                  + (first < actual.length() ? (int) actual.charAt(first) : -1)
                  + " trailing_actual="
                  + (int) actual.charAt(actual.length() - 1));
        }
      }
    } finally {
      bridge.close();
    }
    if (matches != 1) throw new AssertionError("expected one synthetic row, got " + matches);
  }
}
