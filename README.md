# CustomEconomy

A production-grade two-currency economy for Paper Minecraft servers — selling, admin and player-run shops, a dynamic market, progression multipliers, and an inflation-resistant second currency — built on a layered service/repository architecture with pooled SQL persistence.

![Java](https://img.shields.io/badge/Java-21-orange)
![Paper](https://img.shields.io/badge/Paper%20API-1.21.2-blue)
![Build](https://img.shields.io/badge/build-Maven-red)

<!-- SCREENSHOT: the shop GUI, ideally with a category open. Save to docs/images/shop.png and uncomment.
![Shop GUI](docs/images/shop.png)
-->

## Overview

CustomEconomy runs the whole economy of a live SMP server. Players sell gathered items for money, buy from a paginated admin shop, trade with each other through an auction house and buy-order board, and level per-category multipliers that raise their sell rates over time. Prices move on their own: a dynamic market recalculates item values from real transaction volume on a rolling window.

Alongside money it runs **shards** — a deliberately scarce second currency, earned only through PvP, that prices the progression items which shouldn't be purchasable with inflating money.

The two currencies were separate plugins until the merge; they now share one jar, one connection pool, and one config file, because they were never independent in practice — the shop audit has to reason about both, and the earn feedback was written to make them feel like one system.

It integrates with the standard server-plugin ecosystem — Vault for balances, PlaceholderAPI for scoreboards, LuckPerms for permission tiers — rather than reimplementing them.

## Features

- **Selling** — sell from hand, inventory, or a dedicated sell GUI, with an itemised earnings breakdown
- **Admin shop** — categorised, paginated buy menus with quantity selection and large-purchase confirmation
- **Player shops** — an auction house of player sell offers and a board of player buy orders, with claim storage, offer history, and filters
- **Dynamic market** — prices respond to real sales volume over a rolling window, with population-adaptive depth and a circuit breaker that turns dump crashes into gradual declines
- **Category multipliers** — per-category XP and levels that scale sell rates, on a configurable curve (`level² × scale`, +5% per level, capped at 3.0×)
- **Shards** — a second currency with a bounded supply, its own shop, and a PvP-only earn path
- **Transaction log** — every sale recorded with quantity, unit price, multiplier applied, and source
- **Dual persistence** — SQLite out of the box, MySQL for multi-server setups, switched by one config key
- **Integrations** — Vault, PlaceholderAPI, LuckPerms, EssentialsX
- **Extensible** — public APIs for both currencies and a custom `PlayerSellEvent` for other plugins to hook

## Why two currencies

On a long-running server, the primary currency inflates. Players accumulate faster than they spend, prices lose meaning, and anything priced in money eventually becomes free in real terms. That's fine for consumables and fatal for progression items — a spawner meant to take weeks to earn becomes trivial by month two.

Shards are insulated from that. They enter the economy through exactly two channels and no others:

1. **PvP kills** — the only gameplay source
2. **Admin grants** — `shardsadmin give` from console, the integration point for votes, crates, and events

Nothing else mints shards. Because supply is bounded by player activity rather than by time spent grinding, shard prices hold their meaning across a season, and progression items priced in shards stay gated.

## Architecture

The plugin is layered so that game logic, persistence, and presentation stay independent:

```
dev.smpeconomy
├── api/          Public API surface + custom Bukkit events
├── command/      Command handlers (/sell, /shop, /worth, /worths, /ah, /offers, /multi, /ecoadmin)
├── config/       Config loading and typed accessors
├── database/     HikariCP pool, versioned schema migrations
│   └── repository/   All SQL — player, progression, transaction, market, player shop
├── gui/          Inventory menus, market stats, and the shop price audit
├── hook/         Optional third-party integrations (Vault, PlaceholderAPI)
├── model/        Domain types (ItemWorth, Transaction, SellResult, PlayerListing, MarketPrice)
├── service/      Business logic (sell, shop, player shop, worth, market, multipliers, chat input)
├── shards/       The second currency, wired in as a subsystem
│   ├── api/          Public API for other plugins
│   ├── command/      /shards, /shardshop, /shardsadmin
│   ├── gui/          Shard shop inventory menu
│   ├── listener/     PvP kill detection and earn hooks
│   ├── papi/         PlaceholderAPI expansion
│   ├── service/      Balance logic and the in-memory cache
│   ├── storage/      Shard persistence + legacy import
│   └── util/         Message formatting and earn feedback
└── util/         Formatting and item helpers
```

Commands parse input and delegate; services hold the rules; repositories own every SQL statement. `DatabaseManager` is responsible only for pool lifecycle and DDL, so swapping the storage engine never touches game logic.

`ShardsModule` is the seam the merge left behind: it owns the shard subsystem's wiring and exposes `enable`/`reload`/`disable`, so shards stay a self-contained unit inside a single plugin lifecycle rather than being dissolved into it.

### Points of interest

**Shop exploit auditing.** In an economy with both a shop and a sell multiplier, any item whose shop price falls below its maximum achievable sell value becomes an infinite money loop. `ShopExploitValidator` checks every shop section against the live price table on startup and after every reload, classifying each entry as immediately exploitable, exploitable at max multiplier, or within a thin margin of breakeven. Pricing mistakes surface in the console before players find them. The `shop.buy-price-markup` default of 3.3 is derived from this: max multiplier (3.0) × a 1.1 safety margin.

**Market depth that scales with population.** A fixed volume baseline makes a small server's prices thrash and a large server's prices barely move. Baseline volume is instead `max(min-participants, unique sellers in window) × baseline-per-seller`, so a quiet server gets a stable market and growth makes pricing progressively more player-driven.

**Circuit breaker.** No item may fall more than `max-daily-drop` below its UTC-day-start price, so a coordinated dump degrades a price gradually instead of collapsing it in one tick.

**Collusion protection.** Any currency minted by player-versus-player interaction is vulnerable to kill-trading: two accounts, often one player with an alt, repeatedly killing each other to print currency from nothing. A configurable per-victim cooldown means killing the same player again pays nothing until it expires, so farming a cooperative partner yields no more than genuine combat.

**Buying is saving, not skipping.** Spawner *placement* remains gated behind a rank permission. Purchasing early banks the item; it doesn't bypass the progression ladder.

**Currency correctness.** Money balances and prices are stored as `DECIMAL(20,4)`, never floating point, so repeated transactions can't accumulate rounding error. Shards are integral by design — there is no such thing as a fractional shard.

**Main-thread discipline.** Minecraft's game loop is single-threaded and blocking it stalls the server for every connected player. Transaction writes are batched and flushed asynchronously on a configurable interval; market ticks and player-shop expiry sweeps run async, hopping back to the main thread only for Vault refunds. Shard balances are cached in memory while a player is online and mirrored to SQL on a single-threaded executor, which keeps the game loop unblocked and guarantees writes for a given player stay ordered.

**Schema migrations.** A `schema_version` table gates incremental migrations, so an existing database upgrades in place instead of requiring a wipe. Player listings and claim storage arrive in v2–v3; the `shards` table is v4.

**Engine-aware tuning.** SQLite runs single-writer with WAL journaling so readers aren't blocked; MySQL gets a tuned pool with server-side prepared-statement caching. DDL and upserts are emitted per dialect.

## Requirements

- Java 21+
- Paper 1.21.2+
- Optional: Vault, PlaceholderAPI, LuckPerms, EssentialsX

## Building

```bash
mvn clean package
```

The shaded jar lands at `target/CustomEconomy.jar`. HikariCP and the MySQL driver are relocated under `dev.smpeconomy.libs` to avoid colliding with other plugins that bundle the same libraries.

## Installation

1. Drop `CustomEconomy.jar` into `plugins/`
2. Start the server once to generate `config.yml`, `items.yml`, `shop.yml`, and `messages.yml`
3. Configure the database, item prices, shop stock, and shard shop stock
4. `/ecoadmin reload`

### Upgrading from separate CustomEconomy + Shards jars

1. **Remove `Shards.jar` from `plugins/`.** Leaving it in place would run two plugins registering the same commands.
2. Leave `plugins/Shards/` on disk for the first start.
3. Start the server. Schema migration v4 creates the `shards` table, and existing balances are imported once from `plugins/Shards/shards.db` into the shared database. The import writes a `shards-imported.flag` marker so it never runs twice, never overwrites a balance already present, and leaves the old file untouched.
4. Move the `earn`, `display`, `feedback`, and `shop` sections of your old `plugins/Shards/config.yml` under the `shards:` key in `plugins/CustomEconomy/config.yml`, and copy over your customised `messages.yml`. Note the nesting: the old top-level `shop:` is now `shards.shop`, which is what keeps it clear of the admin shop's own `shop:` block.
5. Verify balances with `/shards`, then delete `plugins/Shards/`.

## Configuration

`config.yml` covers the database connection, currency display, sell behaviour, the multiplier curve, market tuning, player-shop limits, and the entire shard subsystem. `items.yml` defines sellable items, base prices, and categories. `shop.yml` defines admin shop sections. `messages.yml` holds the MiniMessage strings for shard feedback.

```yaml
database:
  type: SQLITE          # or MYSQL — covers money and shards alike
  transaction-batch-interval-ms: 2000

shop:
  # Must stay above max-multiplier (3.0) × safety-margin (1.1) = 3.3
  buy-price-markup: 3.3
  confirm-above: 10000.0

multipliers:
  enabled: true
  xp-scale: 100           # XP for level n = n² × scale
  step-per-level: 0.05    # +5% per level
  max-multiplier: 3.0

market:
  enabled: true
  tick-interval-seconds: 300
  adaptive-depth:
    enabled: true
    baseline-per-seller: 50
    min-participants: 10
  circuit-breaker:
    enabled: true
    max-daily-drop: 0.15

shards:
  earn:
    kill:
      amount: 10
      # Minutes before killing the SAME victim pays again.
      # Collusion-minting protection, not an activity throttle.
      same-victim-cooldown-minutes: 30
  display:
    symbol: "⧫"
  shop:
    title: "⧫ Shard Shop"
    rows: 3
    entries:
      zombie:
        slot: 13
        icon: SPAWNER
        name: "<green>Zombie Spawner</green>"
        cost: 400
        commands:
          - "silkspawners give %player% ZOMBIE 1"
```

Shard shop entries run configured console commands with `%player%` substituted, so the shop can sell anything another plugin can grant — no code changes needed to add stock.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/sell [hand\|inventory\|all]` | Sell items for currency | `customeco.sell` |
| `/worth [item]` | Check an item's sell value | `customeco.worth` |
| `/worths` | Browse the full price table | `customeco.worth` |
| `/shop` | Open the admin shop GUI | `customeco.shop` |
| `/auctionhouse`, `/ah` | Browse player sell offers | `customeco.shop` |
| `/offers`, `/o` | Browse player buy orders | `customeco.shop` |
| `/multi` | View category multiplier progress | `customeco.sell` |
| `/autosell` | Toggle periodic automatic selling | `customeco.autosell` |
| `/ecoadmin` | Administration and reload (money *and* shards) | `customeco.admin` |
| `/shards` | View your shard balance | `shards.use` |
| `/shardshop` | Open the shard shop | `shards.use` |
| `/shardsadmin <give\|take\|set\|reload> [player] [amount]` | Shard administration | `shards.admin` |

Player-shop listing limits use `customeco.shop.listings.<number>` (highest numeric node wins), or `customeco.shop.listings.unlimited`.

## Placeholders

With PlaceholderAPI installed:

| Placeholder | Returns |
| --- | --- |
| `%shards_balance%` | The player's current shard balance |

## API

`CustomEconomyAPI` is a stable facade over the services, so internal refactors don't break dependents:

```java
CustomEconomyAPI api = CustomEconomy.getInstance().getAPI();

// What is this item worth to this player, multipliers included?
double price = api.getSellPrice(itemStack, player);   // -1 if unpriced

Optional<ItemWorth> worth = api.getItemWorth(itemStack);
double multiplier = api.getMultiplier(player, ItemCategory.ORES);

// Sell on a player's behalf — fires PlayerSellEvent, main thread only
SellResult result = api.sellItems(player, items);
```

`ShardsAPI` is a static facade for the second currency, reachable without a compile-time dependency:

```java
long balance = ShardsAPI.getBalance(playerUuid);
ShardsAPI.deposit(playerUuid, 50);
boolean paid = ShardsAPI.withdraw(playerUuid, 400);   // false if short
```

`PlayerSellEvent` fires after items leave the inventory but **before** funds are deposited, and is cancellable — cancelling returns the items and deposits nothing:

```java
@EventHandler
public void onSell(PlayerSellEvent event) {
    Player player = event.getPlayer();
    SellResult result = event.getResult();
    List<Transaction> transactions = event.getTransactions();

    if (shouldBlock(player)) {
        event.setCancelled(true);
    }
}
```

## Roadmap

- **Autosell tiers** — permission-gated intervals for donor ranks (config in place, disabled)
- **Shard sinks beyond spawners** — vault rows and KOTH stakes

## Author

Garrett Hockersmith - [LinkedIn](https://www.linkedin.com/in/garrett-hockersmith/) - [texgeh@gmail.com](mailto:texgeh@gmail.com)

## License

Released under the [MIT License](LICENSE). Copyright 2026 Garrett Hockersmith.
