package dev.smpeconomy.message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageValidatorTest {

    /** A key defined by the test rather than the plugin, so cases stay readable. */
    private record Key(String path, Set<String> declaredTokens, boolean plural, String countToken)
            implements MessageKey {

        static Key of(String path, String... tokens) {
            return new Key(path, Set.of(tokens), false, null);
        }

        static Key plural(String path, String countToken, String... tokens) {
            return new Key(path, Set.of(tokens), true, countToken);
        }
    }

    private MessageValidator.Report check(MessageKey key, Map<String, Object> file) {
        MessageRegistry registry = new MessageRegistry();
        registry.register(key);
        return new MessageValidator().validate(registry, MessageCatalog.of(Locale.ENGLISH, file));
    }

    @Test
    void acceptsAMessageWhoseTokensMatchTheDeclaration() {
        var report = check(Key.of("sell.done", "price"), Map.of("sell", Map.of("done", "got {price}")));
        assertTrue(report.ok(), report.errors().toString());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void reportsAMissingKey() {
        var report = check(Key.of("sell.done"), Map.of());
        assertFalse(report.ok());
        assertTrue(report.errors().get(0).contains("missing"));
    }

    @Test
    void catchesATokenTheCodeNeverSupplies() {
        // The translator's typo case: {prise} reaches players verbatim without this.
        var report = check(Key.of("sell.done", "price"), Map.of("sell", Map.of("done", "got {prise}")));
        assertFalse(report.ok());
        assertTrue(report.errors().toString().contains("prise"));
    }

    @Test
    void catchesADeclaredTokenTheTextDropped() {
        var report = check(Key.of("sell.done", "price"), Map.of("sell", Map.of("done", "sold it")));
        assertFalse(report.ok());
        assertTrue(report.errors().toString().contains("never uses {price}"));
    }

    @Test
    void warnsAboutFileEntriesThisVersionNoLongerUses() {
        var report = check(Key.of("sell.done"), Map.of("sell", Map.of("done", "ok", "stale", "old")));
        assertTrue(report.ok(), "an unused entry is not fatal");
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).contains("sell.stale"));
    }

    @Test
    void requiresTheOtherFormSoEveryCountResolves() {
        var report = check(Key.plural("items", "count", "count"),
                Map.of("items", Map.of("one", "{count} item")));
        assertFalse(report.ok());
        assertTrue(report.errors().toString().contains("no 'other' form"));
    }

    @Test
    void allowsAPluralFormToOmitTheCount() {
        // "no items" is a legitimate zero form and must not be flagged.
        var report = check(Key.plural("items", "count", "count"),
                Map.of("items", Map.of("zero", "no items", "other", "{count} items")));
        assertTrue(report.ok(), report.errors().toString());
    }

    @Test
    void warnsRatherThanFailsWhenAPluralKeyStillHasASingleValue() {
        var report = check(Key.plural("items", "count", "count"), Map.of("items", "{count} items"));
        assertTrue(report.ok(), "an un-upgraded file must still start: " + report.errors());
        assertTrue(report.warnings().toString().contains("one"));
    }

    @Test
    void catchesPluralFormsOnAKeyThatIsNotDeclaredPlural() {
        var report = check(Key.of("items"), Map.of("items", Map.of("one", "a", "other", "b")));
        assertFalse(report.ok());
        assertTrue(report.errors().toString().contains("not declared plural"));
    }

    @Test
    void validatesEveryLineOfALoreBlock() {
        var report = check(Key.of("lore", "price"),
                Map.of("lore", List.of("costs {price}", "and {bogus}")));
        assertFalse(report.ok());
        assertTrue(report.errors().toString().contains("bogus"));
    }
}
