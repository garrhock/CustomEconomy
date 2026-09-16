# Message System — Design Specification

Status: implemented (phases 1-5) · Supersedes: `dev.smpeconomy.message` v1 (246 keys, single enum, global locale)

## 1. Why replace v1

v1 got the hard parts right and the soft centre wrong. This spec keeps what works and
rebuilds what rots.

**Keep:** compile-checked keys, load-time validation of file-vs-code drift, `copyDefaults`
merge on upgrade, lore italic handling, whole-sentence keys (no concatenation),
list-valued lore blocks.

**Replace:**

| Defect | Consequence |
|---|---|
| `get(key, Object... tokens)` — unchecked, unordered | A dropped or typo'd token ships silently. Players see `{price}`. |
| Single global message file | You can rebrand, not translate. Every player gets one language. |
| No pluralisation | `"1 items"`. Present in shipped v1 text today. |
| `CustomEconomy.getInstance().getMessages()` at ~40 call sites | Service location, not injection. Untestable without a running server. |
| One 246-constant enum | Fights the module boundaries (core / shards / spawners). |

## 2. Goals

1. **One authoring syntax.** MiniMessage everywhere, including vendored code.
2. **Fail at load, not in front of a player.** Key drift, token mismatch and malformed
   MiniMessage are console errors at startup.
3. **Real translation.** Per-player locale with a defined fallback chain.
4. **Correct plurals**, without a heavyweight dependency.
5. **Unit-testable** without Bukkit.
6. **Module-owned keys**, so vendored code can be updated in isolation.

### Non-goals

- Runtime language switching per player (locale is read per message resolution; no cache
  invalidation protocol).
- Gendered or case-inflected grammar. Slavic case agreement is out of scope; plural
  categories are supported, full CLDR grammar is not.
- Translating operator diagnostics (`/ecoadmin`). Documented boundary, see §9.

## 3. Architecture

```
dev.smpeconomy.message
├── MessageKey          interface — path() + declaredTokens() + plural()
├── keys/
│   ├── CoreKeys        enum implements MessageKey
│   ├── ShardKeys       enum
│   └── SpawnerKeys     enum
├── Messages            facade — resolve, format, send
├── MessageCatalog      one locale's loaded text; Bukkit-free
├── CatalogLoader       yml → MessageCatalog (+ defaults merge)
├── LocaleResolver      CommandSender → Locale, with fallback chain
├── MessageRegistry     every known key, for validation
├── TokenBag            checked token name/value set
├── PluralRules         locale → plural category selection
└── MessageValidator    load-time checks, returns a report
```

The dependency direction is one-way: `Messages → MessageCatalog → (plain data)`.
`MessageCatalog` and `PluralRules` have no Bukkit imports, so both are unit-testable.

## 4. Keys

Keys are enums implementing a shared interface, one enum per module.

```java
public interface MessageKey {
    String path();
    Set<String> declaredTokens();
    boolean plural();          // true → value is a map of plural categories
}

public enum CoreKeys implements MessageKey {
    PLAYERSHOP_SELL_CREATED("playershop.sell.created", "quantity", "item", "price"),
    SHOP_SECTION_ITEM_COUNT("shop.main.section-item-count", Plural.on("count")),
    ;
}
```

Declaring tokens on the key is the core change. It gives the validator something to check
the file against, and the sender something to check the caller against.

New module = new enum + one `MessageRegistry.register(...)` line. Nothing else changes.

## 5. Tokens

Replace `Object...` with a checked bag built at the call site:

```java
messages.send(player, CoreKeys.PLAYERSHOP_SELL_CREATED, TokenBag.of()
        .put("quantity", qty)
        .put("item", itemName)
        .put("price", formatted));
```

`TokenBag` validation on use:

- **Unknown token** — name not in `declaredTokens()` → reject.
- **Missing token** — declared but not supplied → reject.

Both are programmer errors, so they follow the strictness policy in §8 rather than being
silently rendered.

Rejected alternatives: varargs pairs (v1 — unchecked); a record per message (246 records
is not worth the ceremony); code generation from YAML (build complexity out of proportion
to a Bukkit plugin).

## 6. Locales

```
plugins/CustomEconomy/messages/
├── en.yml        shipped default, always present
├── de.yml        optional, server-supplied
└── ...
```

Resolution order for a recipient:

1. Exact locale (`de_AT`)
2. Language only (`de`)
3. Configured `default-locale`
4. Built-in `en.yml` from the jar

A `CommandSender` that is not a `Player` (console, command blocks) resolves straight to
`default-locale`.

**Inventory titles always use `default-locale`.** Minecraft fixes a window title when the
inventory is created, which is before the plugin knows who is opening it. `BaseGui` rebinds
its `messages` field to the viewer in `open()` before `populate()` runs, so *lore* is
per-player; the title is not. Making titles per-player would mean passing the viewer into
every menu constructor and every site that opens one — rejected as disproportionate.

```yaml
messages:
  default-locale: en
  per-player-locale: true    # false → everyone gets default-locale
  strict: false              # see §8
```

Only `en.yml` ships. Any missing key in a translation falls back **per key**, not per
file, so a half-finished translation is usable rather than all-or-nothing.

## 7. Plurals

MiniMessage has no plural support and ICU4J is ~1 MB shaded — disproportionate here. Use
explicit plural categories in YAML, selected by locale rules:

```yaml
shop:
  main:
    section-item-count:
      one:   '<gray>  {count} item</gray>'
      other: '<gray>  {count} items</gray>'
```

`PluralRules` maps a count to a CLDR category (`zero`/`one`/`two`/`few`/`many`/`other`).
Shipped rules: English/Germanic (`n == 1 → one`, else `other`) and a default that always
returns `other`. Locales needing `few`/`many` supply those keys and a rule can be added
without touching call sites.

A plural key declares which token carries the count, so the validator can check that the
count token exists and that at minimum an `other` form is present.

**A plain string where plural forms are expected is read as the `other` form**, and warns
rather than erroring. This is the upgrade path: a server whose file predates a key becoming
plural keeps a single value, and `copyDefaults` only fills in *absent* keys — so without
this the message would resolve to nothing on the first restart after an update.

## 8. Validation & failure policy

`MessageValidator` runs on load and on reload, producing one report rather than a stream
of warnings.

Checks per key:

1. **Present** — resolvable in the default locale.
2. **Token match** — placeholders in the text are exactly `declaredTokens()`.
3. **Plural shape** — plural keys are maps containing at least `other`; non-plural keys
   are scalars or lists.
4. **Parses** — MiniMessage deserialises without error.

Checks per file:

5. **Unknown keys** — present in the file, unknown to the registry (typo or removed
   feature).

Strictness:

| Mode | On validation error |
|---|---|
| `strict: false` (default) | Log the report; fall back to the built-in default for that key. Server starts. |
| `strict: true` | Log the report and **disable the plugin**. For CI and staging. |

Runtime failure — a key that resolves nowhere — renders the key path (`playershop.sell.created`)
and logs once. Visible, diagnosable, not a crash.

## 9. Component-first, and the legacy boundary

`Messages` returns `Component`. `legacy()` exists **only** for APIs that cannot accept a
Component, and its use is a defect to be removed, not a supported pattern. After the
spawner GUI migration (§10 phase 4) there should be no remaining callers.

Operator diagnostics (`EcoAdminCommand`, ~15 strings) stay in code by deliberate decision:
`messages/*.yml` covers everything a *player* can see. This boundary is documented so it
reads as a choice rather than an oversight.

## 10. Migration plan

Each phase compiles and ships independently.

1. **Core types** — `MessageKey`, `TokenBag`, `MessageCatalog`, `CatalogLoader`,
   `PluralRules`, `MessageValidator`. Unit tests, no call-site changes.
2. **Split keys** — one enum per module implementing `MessageKey`, tokens declared,
   registry wired. v1 `MessageKey` deleted.
3. **Convert call sites** to `TokenBag`, and **inject `Messages`** via constructors,
   deleting the `getInstance()` service-location calls.
4. **Spawner GUI** — `setDisplayName(String)` → `displayName(Component)`,
   `setLore(List<String>)` → `lore(List<Component>)`, delete 34
   `translateAlternateColorCodes` calls, move its 27 messages into `messages/en.yml`.
5. **Locale + plurals** — split `messages.yml` → `messages/en.yml` on first run, add
   resolution and plural categories, fix the `"1 items"` cases.

## 11. Risks

- **Untested GUI.** Every menu was migrated and none has been opened in-game. Phase 4
  touches them again. Mitigation: phases 3 and 4 ship separately so a visual regression is
  attributable; a full click-through of `/shop`, `/ah`, `/multi`, `/worths` and
  `/shardshop` is required before either is called done.
- **Vendored drift.** `SpawnerKeys` and the spawner GUI refactor diverge from upstream
  DonutSpawner. Accepted: it is already forked and merged.
- **Translation half-life.** Per-key fallback means a stale translation degrades to English
  key by key rather than breaking. Accepted.

## 12. Definition of done

- No `Object...` token APIs remain.
- `CustomEconomy.getInstance().getMessages()` survives only where Java or Bukkit forces it:
  `BaseGui` (a subclass cannot reference an instance field inside its own `super(...)` call,
  which is where a title is needed), `SellGui` (no base class), `ShardsModule` and
  `DonutSpawners` (module entry points). Five sites, each with a stated reason.
- `translateAlternateColorCodes` remains only in `SpawnerItemUtil` and a shaded helper.
  `SpawnerItemUtil` encodes a spawner's mob type into item lore as `ChatColor.GRAY + "Type: "`
  and reads it back to identify items players already own — a data tag, not display text.
  Rewriting it would orphan every spawner currently in a chest.
- `strict: true` passes on a clean checkout.
- `MessageCatalog` and `PluralRules` have unit tests that run without a server.
- Every menu listed in §11 has been opened in-game after phase 4.
