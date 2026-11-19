package dev.smsrelay;

import org.json.JSONObject;

/** 只替换已修改字段的文本区间，原文件里别处的注释和排版仍然有效。 */
final class JsonEdits {
  private final String text;
  private int i;
  private JsonEdits(String text) { this.text=text; }

  static String set(String text, String key, Object value) throws Exception {
    JsonEdits edit = new JsonEdits(text);
    edit.space();
    return edit.object(key.split("\\."),0,value);
  }

  private String object(String[] path, int depth, Object value) throws Exception {
    expect('{');
    space();
    int last = -1;
    while (at() != '}') {
      int start = i;
      quoted();
      String key = new org.json.JSONTokener(text.substring(start,i)).nextValue().toString();
      space();
      expect(':');
      space();
      start = i;
      skipValue();
      last = i;
      if (key.equals(path[depth])) {
        if (depth+1 == path.length)
          return text.substring(0,start)+literal(value)+text.substring(last);
        i=start;
        return object(path,depth+1,value);
      }
      space();
      if (at() == '}') break;
      expect(',');
      space();
    }
    Object child = value;
    for (int n=path.length-1;n>depth;n--) child=new JSONObject().put(path[n],child);
    int close=i;
    String indent = indent(depth+1);
    String before = last < 0 ? text.substring(0,close)
        : text.substring(0,last)+","+text.substring(last,close);
    return before+"\n"+indent+JSONObject.quote(path[depth])+": "+literal(child)
        +"\n"+indent(depth)+text.substring(close);
  }

  private static String literal(Object value) {
    return value instanceof String ? JSONObject.quote((String)value) : String.valueOf(value);
  }

  private static String indent(int depth) {
    StringBuilder b=new StringBuilder();
    for(int n=0;n<depth;n++) b.append("  ");
    return b.toString();
  }

  private char at() { return i<text.length() ? text.charAt(i) : '\0'; }
  private void expect(char c) {
    if (at()!=c) throw new IllegalArgumentException("config_syntax");
    i++;
  }

  private void quoted() {
    char quote=at();
    if (quote!='"' && quote!='\'') throw new IllegalArgumentException("config_syntax");
    i++;
    while (at()!=quote) {
      if (at()=='\0') throw new IllegalArgumentException("config_syntax");
      if (at()=='\\') i++;
      i++;
    }
    i++;
  }

  private void space() {
    while (i<text.length()) {
      if (Character.isWhitespace(at()) || at()=='\ufeff') { i++; continue; }
      if (text.startsWith("//",i) || at()=='#') {
        while (at()!='\0' && at()!='\n') i++;
      } else if (text.startsWith("/*",i)) {
        int next=text.indexOf("*/",i+2);
        if(next<0) throw new IllegalArgumentException("config_syntax");
        i=next+2;
      } else break;
    }
  }

  private void skipValue() {
    if (at()=='"' || at()=='\'') { quoted(); return; }
    if (at()=='{' || at()=='[') {
      char end = at()=='{' ? '}' : ']';
      i++;
      while(at()!=end) {
        if(at()=='\0') throw new IllegalArgumentException("config_syntax");
        space();
        if(at()==end) break;
        if(at()=='"' || at()=='\'' || at()=='{' || at()=='[') skipValue();
        else i++;
      }
      i++;
      return;
    }
    int start=i;
    while(at()!='\0' && ",]}#/, \r\n\t".indexOf(at())<0) i++;
    if(i==start) throw new IllegalArgumentException("config_syntax");
  }
}
