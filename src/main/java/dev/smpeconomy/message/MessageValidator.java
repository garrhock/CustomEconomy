package dev.smpeconomy.message;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks a catalog against the keys the code uses, so mistakes show up at startup and not in chat. */
public final class MessageValidator {

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");

    /** Errors block startup in strict mode; warnings never do. */
    public record Report(List<String> errors, List<String> warnings) {

        public boolean ok() {
            return errors.isEmpty();
        }

        public int total() {
            return errors.size() + warnings.size();
        }
    }

    public Report validate(MessageRegistry registry, MessageCatalog catalog) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();

        for (MessageKey key : registry.all()) {
            known.add(key.path());
            validateKey(key, catalog, errors, warnings);
        }

        for (String path : catalog.paths()) {
            if (!known.contains(path)) {
                warnings.add(path + " — in the file but unused by this version"
                             + " (typo, or left over from an older release)");
            }
        }
        return new Report(List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateKey(MessageKey key, MessageCatalog catalog,
                             List<String> errors, List<String> warnings) {
        String path = key.path();

        if (!catalog.has(path)) {
            errors.add(path + " — missing");
            return;
        }

        if (key.plural()) {
            Map<String, String> forms = catalog.plurals(path).orElse(null);
            if (forms == null) {
                errors.add(path + " — declared plural, but the file has no usable value");
                return;
            }
            if (catalog.string(path).isPresent()) {
                warnings.add(path + " — is plural but the file has one value; it will be used"
                             + " for every count. Add 'one'/'other' forms to fix the grammar.");
            }
            if (!forms.containsKey(PluralRules.FALLBACK)) {
                errors.add(path + " — plural block has no '" + PluralRules.FALLBACK
                           + "' form, so some counts resolve to nothing");
            }
            forms.forEach((category, text) -> checkTokens(path + "." + category, text, key, errors));
            return;
        }

        if (catalog.plurals(path).isPresent() && catalog.string(path).isEmpty()) {
            errors.add(path + " — the file gives plural forms but the key is not declared plural");
            return;
        }

        for (String line : catalog.list(path).orElse(List.of())) {
            checkTokens(path, line, key, errors);
        }
    }

    private void checkTokens(String where, String text, MessageKey key, List<String> errors) {
        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }

        Set<String> declared = key.declaredTokens();
        for (String token : used) {
            if (!declared.contains(token)) {
                errors.add(where + " — uses {" + token + "}, which the code does not supply");
            }
        }
        // plural forms can legitimately drop the count, e.g. a "no items" zero form
        if (!key.plural()) {
            for (String token : declared) {
                if (!used.contains(token)) {
                    errors.add(where + " — never uses {" + token + "}, which the code supplies");
                }
            }
        }
    }
}
