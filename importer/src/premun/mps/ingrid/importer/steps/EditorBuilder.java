package premun.mps.ingrid.importer.steps;

import org.jetbrains.mps.openapi.model.*;
import premun.mps.ingrid.importer.*;
import premun.mps.ingrid.parser.grammar.*;
import premun.mps.ingrid.plugin.library.*;

import java.util.*;

/**
 * Import step that creates projection editors for all concepts.
 */
public class EditorBuilder extends ImportStep {
    private Shortcuts shortcuts = new Shortcuts();
    private EditorHelper editorHelper;

    @Override
    public void Execute() {
        this.grammar.rules
            .values()
            .stream()
            .filter(r -> r instanceof ParserRule)
            .map(r -> (ParserRule) r)
            .forEach(this::findShortcuts);

        this.editorHelper = new EditorHelper(this.shortcuts);

        this.grammar.rules
            .values()
            .stream()
            .filter(r -> r instanceof ParserRule)
            .map(r -> (ParserRule) r)
            .forEach(this::buildEditor);
    }

    /**
     * Builds an editor for given rule.
     *
     * @param rule Rule for which the editor is built.
     */
    private void buildEditor(ParserRule rule) {
        // Interface - we need to find implementors
        for (Alternative alternative : rule.alternatives) {
            SNode editor = this.editorHelper.createEditor(alternative);
            this.editorModel.addRootNode(editor);
        }
    }

    /**
     * Finds a list of shortcuts for given rule and saves it into the shortcuts field.
     *
     * @param rule Rule
     */
    private void findShortcuts(ParserRule rule) {
        List<ShortcutItem> result = findShortcuts(rule, new ArrayList<>());

        // Rules without shortcuts, are rules that all have paths of length 1
        // If one of them had length > 1, we need to create the menu because of that
        if (result.stream().allMatch(s -> s.path.size() == 1)) return;

        // Debug
        {
            StringBuilder sb = new StringBuilder();
            sb
                .append(rule.name)
                .append(":\n");

            for (ShortcutItem item : result) {
                sb
                    .append("  To:  ")
                    .append(item.description)
                    .append("\n  ");

                item.path.stream().forEach(
                    n -> sb
                        .append(" -> ")
                        .append(n.getName())
                );

                sb.append("\n");
            }
            sb.append("\n#####################\n");

            GrammarImporter.LOGGER.info(sb.toString());
        }

        // TODO: find shortest common prefix and set it as matchingText
        result.stream().forEach(s -> s.matchingText = s.description);

        // Save all shortcuts for this rule
        this.shortcuts.put(rule, result);
    }

    /**
     * Finds a list of paths that lead from a rule to an end node
     * (a rule represented by a classic concept).
     *
     * Example:
     *           s :  a;
     *
     *           a :  c
     *             |  d
     *             |  'xxx'
     *             ;
     *
     *           c :  STRING;
     *           d :  DIGIT;
     *
     *           STRING : .+;
     *           DIGIT  : [0-9]+;
     *
     * Then findShortcuts(s) will find 3 different paths:
     *   1) s -> STRING (s->a_1->c)
     *   2) s -> DIGIT  (s->a_2->d)
     *   3) s -> 'xxx'  (s->a_3)
     *
     * @param rule Rule for which we want to find shortcuts.
     * @param path Alternatives that lead to that end node.
     * @return List of shortcuts.
     */
    private List<ShortcutItem> findShortcuts(ParserRule rule, List<SNode> path) {
        List<ShortcutItem> result = new ArrayList<>();

        // Interface - we need to find implementors
        for (Alternative alternative : rule.alternatives) {
            List<RuleReference> elements = alternative.elements;

            // Each alternative needs it's own path
            List<SNode> clonedPath = clonePath(path);

            if (elements.size() == 1 && elements.get(0).rule instanceof ParserRule && elements.get(0).quantity == Quantity.EXACTLY_ONE) {
                // A single parser rule reference (shortcut)
                ParserRule next = (ParserRule) elements.get(0).rule;

                // Add current rule to path
                clonedPath.add(alternative.node);

                // Recursively find all end nodes
                result.addAll(this.findShortcuts(next, clonedPath));
            } else {
                // More elements in an alternative -> not a shortcut but an end node
                if (clonedPath.isEmpty() || clonedPath.get(clonedPath.size() - 1) != alternative.node){
                    clonedPath.add(alternative.node);
                }

                ShortcutItem shortcut = new ShortcutItem(clonedPath);
                result.add(shortcut);
            }
        }

        return result;
    }

    private static List<SNode> clonePath(List<SNode> list) {
        List<SNode> clone = new ArrayList<>(list.size());
        clone.addAll(list);
        return clone;
    }
}
