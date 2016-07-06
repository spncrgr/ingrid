package premun.mps.ingrid.parser;

import premun.mps.ingrid.parser.grammar.*;
import premun.mps.ingrid.parser.grammar.exception.*;

import java.util.*;
import java.util.regex.*;

/**
 * Class operates on a rule set generated by ANTLR parser.
 */
class GrammarResolver {
    /**
     * Resolves rules that weren't resolved during first parsing. That means
     * that everything that was discovered during first walk and is saved as
     * a string name will be replaced by reference to the this.rules element.
     * Flattens lexer rules so that they become either strings or regexes.
     *
     * @param parserResult Result of ANTLR parsing.
     * @return Resolved grammar ready to be imported to MPS.
     */
    public static GrammarInfo generateGrammar(ParserResult parserResult) throws IngridParserException {
        GrammarInfo grammar = new GrammarInfo(parserResult.grammarName);

        Map<String, Rule> rules = parserResult.rules;

        // Lexer rules must be resolved first so that parser rules can reference them
        // Lexer rules do not contain references to parser rules
        // We need to copy the array because we are changing it inside forEach
        new ArrayList<>(rules.keySet())
            .stream()
            .filter(name -> rules.get(name) instanceof LexerRule || rules.get(name) instanceof RegexRule)
            .forEach(name -> {
                FlatLexerRule flatRule = null;
                // We always get updated Rule from rule set again, because we might have updated it
                flatRule = flattenLexerRule(rules.get(name), rules);
                grammar.rules.put(name, flatRule);

                // We need to put it to the old set too, so resolving in the second step works too,
                // because we create new flatRule objects inside flattenLexerRule.
                rules.put(name, flatRule);
            });

        // We need to copy the array because we are changing it inside forEach
        new ArrayList<>(rules.values())
            .stream()
            .filter(r -> r instanceof ParserRule)
            .forEach(rule -> {
                resolveParserRule((ParserRule) rule, rules);

                // We do not call "new Rule" inside here, so we don't need to update old rule set.
                // Unlike in the first step, ParserRule objects stay the same, just their content is changed.
                grammar.rules.put(rule.name, rule);
            });

        grammar.rootRule = grammar.rules.get(parserResult.rootRule);

        return grammar;
    }

    /**
     *
     * Matches (string) references inside rule alternatives with actual
     * pointers to rule definitions.
     *
     * @param rule Rule to be resolved
     * @param rules Set of rules where we look up references.
     * @throws IngridParserException
     */
    private static void resolveParserRule(ParserRule rule, Map<String, Rule> rules) throws IngridParserException {
        // For each alternative line..
        for (Alternative alternative : rule.alternatives) {
            // For each element on the line..
            for (int i = 0; i < alternative.elements.size(); ++i) {
                RuleReference ref = alternative.elements.get(i);

                // Rule referenced in this alternative element
                Rule element = ref.rule;

                if (element instanceof UnresolvedRule) {
                    // Lexer rules were resolved first, so they are ready to be referenced
                    if (rules.containsKey(element.name)) {
                        Rule lookedUpRule = rules.get(element.name);
                        alternative.elements.set(i, new RuleReference(lookedUpRule, ref.quantity));
                    } else {
                        // Special EOF token might not be defined, but is a valid reference
                        if (element.name.equals("EOF")) {
                            // We can ignore this rule
                            alternative.elements.remove(i);
                            --i;
                            continue;
                        }

                        throw new IngridParserException(
                            "Couldn't resolve rule '" + element.name + "' (inside " + rule.name + ")");
                    }
                } else if (element instanceof QuantifierRule) {
                    if (i == 0) {
                        throw new IngridParserException(
                            "Quantifier suffix found with no previous reference");
                    }

                    // Apply quantifier to previous element of alternative
                    alternative.elements.get(i - 1).quantity = ((QuantifierRule) element).quantity;
                    // Remove quantifier itself
                    alternative.elements.remove(i);
                    --i;
                }
            }
        }
    }

    /**
     * Flattens rule into a regex or string literal.
     * TODO: Cyclic (faulty) ANTLR definition will cause endless loop and stack overflow.
     *
     * @param rule Rule to be resolved.
     * @param rules Set of rules where we look up references.
     */
    private static FlatLexerRule flattenLexerRule(Rule rule, Map<String, Rule> rules) {
        // Because some rules were resolved as a dependency of another rule,
        // it might happen that it is already flattened.
        if (rule instanceof FlatLexerRule) {
            return (FlatLexerRule) rule;
        }

        if (rule instanceof UnresolvedLexerRule) {
            throw new IngridParserException(
                "Rule '" + rule.name + "' must be resolved before flattening");
        }

        LexerRule lexerRule = (LexerRule) rule;

        // If we have only one element, we might be looking at a literal rule..
        // We count all elements of all alternatives:
        if (lexerRule.alternatives.size() == 1 && lexerRule.alternatives.get(0).size() == 1) {
            Rule only = lexerRule.alternatives.get(0).get(0);
            if (only instanceof LiteralRule) {
                return new LiteralRule(rule.name, ((LiteralRule) only).value);
            }
        }

        // We can construct one big regex out of sub rules
        List<List<String>> regexs = new ArrayList<>();

        // Gather all sub rule contents (or resolve them, if wasn't resolved before)
        for (List<Rule> alternative : lexerRule.alternatives) {
            List<String> subRegex = new ArrayList<>();

            for (Rule element : alternative) {
                // Is each sub element already resolved?
                if (!(element instanceof FlatLexerRule)) {
                    if (element instanceof QuantifierRule) {
                        int lastIndex = subRegex.size() - 1;

                        if (lastIndex < 0) {
                            throw new IngridParserException("Quantifier suffix found with no prefix regex");
                        }

                        // If more characters, we might need braces
                        // There are cases where we might not, such as [A-Z] or (foo), so might be improved a little
                        // It's tricky though, because of cases such as [A-Z][a-z], so simple heuristics is not enough
                        String prevRegex = subRegex.get(lastIndex);
                        if (prevRegex.length() > 1) {
                            prevRegex = '(' + prevRegex + ')';
                        }

                        // We append it to the previous rule
                        String quantifier = ((QuantifierRule) element).quantity.toString();

                        subRegex.set(lastIndex, prevRegex + quantifier);

                    } else if(element instanceof BlockStartRule) {
                        subRegex.add("(");
                    } else if(element instanceof BlockEndRule) {
                        subRegex.add(")");
                    } else if(element instanceof BlockAltRule) {
                        subRegex.add("|");
                    } else if (element instanceof UnresolvedLexerRule) {
                        if (!rules.containsKey(element.name)) {
                            throw new UnresolvableRuleException("Failed to resolve lexer rule '" + element.name + "'");
                        }

                        FlatLexerRule flatRule = flattenLexerRule(rules.get(element.name), rules);
                        rules.put(element.name, flatRule);
                        subRegex.add(flatRule.getContent());
                    } else {
                        throw new IngridParserException(
                            "Rule '" + element.name + "' (" + element.getClass().getSimpleName() + ") failed to be flattened");
                    }
                } else {
                    // We need to escape literal rule
                    // e.g. <?xml   to   <\?xml
                    String regex;
                    if (element instanceof LiteralRule) {
                        regex = escapeLiteral((LiteralRule) element);
                    } else {
                        regex = ((FlatLexerRule) element).getContent();
                    }

                    subRegex.add(regex);
                }
            }

            regexs.add(subRegex);
        }

        // Build regex from gathered strings
        return new RegexRule(rule.name, buildLexerRegex(regexs));
    }

    /**
     * Helper method that turns {{a,b,c}, {d,(e|f)}} into ((a|b|c)|(d|(e|f))).
     *
     * @param alternatives Array of arrays of strings.
     * @return Flattened string ready for regex.
     */
    private static String buildLexerRegex(List<List<String>> alternatives) {
        StringBuilder expression = new StringBuilder();

        if (alternatives.size() == 1) {
            expression.append(String.join("", alternatives.get(0)));
        } else {
            expression.append('(');
            for (List<String> alt : alternatives) {
                // Here we are appending "|X" to "(Y|Z"
                if (expression.length() > 1) {
                    expression.append('|');
                }

                // If X does not contain a "|",
                // we don't need to put it in brackets..
                // This could be improved for cases such as "(a|b)",
                // which contains "|" but does not need brackets.
                String altRegex = String.join("", alt);
                if (altRegex.contains("|")) {
                    expression
                        .append('(')
                        .append(altRegex)
                        .append(')');
                } else {
                    expression
                        .append(altRegex);
                }
            }

            expression.append(')');
        }

        return expression.toString();
    }

    /**
     * Escapes special regex characters inside of a literal, so it can be used as regex.
     *
     * @param rule Literal rule
     */
    private static String escapeLiteral(LiteralRule rule) {
        String specialChars = "\\.[]{}()*+-?^$|";
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < rule.value.length(); ++i) {
            char c = rule.value.charAt(i);
            if (specialChars.indexOf(c) != -1) {
                sb.append("\\");
            }

            sb.append(c);
        }

        return sb.toString();
    }
}