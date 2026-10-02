package dev.heimdall.assertions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public final class Templates {
  private Templates() {}

  private static final Pattern VARIABLE =
      Pattern.compile("\\$\\{(env|vars|run|step|secrets)\\.([^}]+)}");

  public static Object value(String key, Map<String, Object> context) {
    Object value = context;
    for (String part : key.split("\\.")) {
      if (!(value instanceof Map<?, ?> map) || !map.containsKey(part))
        throw new IllegalArgumentException("Missing variable: %s".formatted(key));
      value = map.get(part);
    }
    return value;
  }

  public static String render(String text, Map<String, Object> context, boolean url) {
    if (text == null) return null;
    var m = VARIABLE.matcher(text);
    var out = new StringBuffer();
    while (m.find()) {
      Object v = value("%s.%s".formatted(m.group(1), m.group(2)), context);
      if (v == null) throw new IllegalArgumentException("Null template variable");
      String s = String.valueOf(v);
      if (url && !(m.group(1).equals("env") && m.group(2).equals("baseUrl")))
        s = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
      m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(s));
    }
    m.appendTail(out);
    return out.toString();
  }

  public static Object structured(Object input, Map<String, Object> context) {
    if (input instanceof String s) {
      var m = VARIABLE.matcher(s);
      if (m.matches()) return value("%s.%s".formatted(m.group(1), m.group(2)), context);
      return render(s, context, false);
    }
    if (input instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> out.put(String.valueOf(k), structured(v, context)));
      return out;
    }
    if (input instanceof List<?> list)
      return list.stream().map(v -> structured(v, context)).toList();
    return input;
  }
}
