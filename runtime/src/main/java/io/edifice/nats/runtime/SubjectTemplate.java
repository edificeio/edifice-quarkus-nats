package io.edifice.nats.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The subject of a listener, where a token written {@code {name}} is a parameter.
 * <p>
 * The subscription is made on the wildcard subject, each parameter replaced by {@code *}, and the parameters are read
 * back from the subject of each message by their position.
 *
 * @param template   the subject as declared, for example {@code directory.users.{userId}.get}
 * @param wildcard   the subject subscribed to, for example {@code directory.users.*.get}
 * @param parameters the name of the parameter at each token position, {@code null} for a token that is not one
 */
public record SubjectTemplate(String template, String wildcard, List<String> parameters) {

  private static final Pattern PARAMETER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)}");
  private static final Pattern LITERAL = Pattern.compile("[^\\s{}*>]+");

  /**
   * Parses a subject.
   *
   * @param template the subject as declared in {@code @BrokerListener}
   * @return the parsed subject
   * @throws IllegalArgumentException when the subject is not valid, with the reason
   */
  public static SubjectTemplate parse(String template) {
    if (template == null || template.isBlank()) {
      throw new IllegalArgumentException("the subject is empty");
    }
    final String[] tokens = template.split("\\.", -1);
    final List<String> parameters = new ArrayList<>(tokens.length);
    final List<String> wildcardTokens = new ArrayList<>(tokens.length);
    for (int i = 0; i < tokens.length; i++) {
      final String token = tokens[i];
      final var parameter = PARAMETER.matcher(token);
      if (parameter.matches()) {
        final String name = parameter.group(1);
        if (parameters.contains(name)) {
          throw new IllegalArgumentException("the parameter {" + name + "} appears twice");
        }
        parameters.add(name);
        wildcardTokens.add("*");
      } else if (token.equals("*")) {
        parameters.add(null);
        wildcardTokens.add(token);
      } else if (token.equals(">")) {
        if (i != tokens.length - 1) {
          throw new IllegalArgumentException("'>' must be the last token");
        }
        parameters.add(null);
        wildcardTokens.add(token);
      } else if (LITERAL.matcher(token).matches()) {
        parameters.add(null);
        wildcardTokens.add(token);
      } else {
        throw new IllegalArgumentException("the token '" + token
          + "' is neither a literal, a wildcard nor a whole {parameter}");
      }
    }
    return new SubjectTemplate(template, String.join(".", wildcardTokens), Collections.unmodifiableList(parameters));
  }

  /**
   * Reads the parameters from the subject of a message received on {@link #wildcard()}.
   *
   * @param subject the subject of the message
   * @return the value of each parameter, by name, in the order of the subject
   */
  public Map<String, String> extract(String subject) {
    final Map<String, String> values = new LinkedHashMap<>();
    if (parameters.stream().allMatch(p -> p == null)) {
      return values;
    }
    final String[] tokens = subject.split("\\.", -1);
    for (int i = 0; i < parameters.size() && i < tokens.length; i++) {
      final String name = parameters.get(i);
      if (name != null) {
        values.put(name, tokens[i]);
      }
    }
    return values;
  }
}
