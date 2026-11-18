import dev.smsrelay.Rules;
import java.util.*;

public final class RulesTest {
  static int count;

  static void check(boolean b) {
    count++;
    if (!b) throw new AssertionError("check " + count);
  }

  public static void main(String[] args) throws Exception {
    String[] examples = {"", "中文\n\"\\body= & 😀", "a😀b𐐷c", "\r\n"};
    Random random = new Random(1118);
    for (String sample : examples)
      for (int budget : new int[] {4, 7, 64, 1900}) {
        StringBuilder joined = new StringBuilder();
        for (String part : Rules.split(sample, budget)) {
          check(part.getBytes("UTF-8").length <= budget);
          joined.append(part);
        }
        check(joined.toString().equals(sample));
      }
    for (int i = 0; i < 200; i++) {
      StringBuilder s = new StringBuilder();
      for (int j = 0; j < 100; j++)
        s.appendCodePoint(new int[] {0x4e2d, 0x1f600, 0x0a, 0x5c, 0x22, 0x61}[random.nextInt(6)]);
      StringBuilder joined = new StringBuilder();
      for (String p : Rules.split(s.toString(), 4 + random.nextInt(1900))) {
        check(!Character.isHighSurrogate(p.charAt(p.length() - 1)));
        joined.append(p);
      }
      check(joined.toString().equals(s.toString()));
    }
    check(Rules.hash("same").equals(Rules.hash("same")));
    check(!Rules.hash("same").equals(Rules.hash("other")));
    check(Rules.delay(1, new int[] {30, 120, 600}) == 30000);
    check(Rules.delay(3, new int[] {30, 120, 600}) == 600000);
    check(Rules.delay(4, new int[] {30, 120, 600}) == -1);
    check(Rules.timeJump(600000, 1000));
    check(!Rules.timeJump(1000, 1000));
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
    c.clear();
    c.set(2026, 10, 18, 13, 2, 0);
    long cut = Rules.cutoff(c.getTimeInMillis(), "Asia/Shanghai", 7);
    c.setTimeInMillis(cut);
    check(c.get(Calendar.DAY_OF_MONTH) == 12 && c.get(Calendar.HOUR_OF_DAY) == 0);
    System.out.println("HOST_TEST_OK checks=" + count + " randomized_unicode_cases=200");
  }
}
