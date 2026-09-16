package dev.smpeconomy.message;

import java.util.Set;

/** Messages owned by the shards module. */
public enum ShardKeys implements MessageKey {

    BALANCE("balance", "balance"),
    ACTIONBAR_KILL("actionbar-kill", "amount", "victim"),
    ACTIONBAR_EARNED("actionbar-earned", "amount"),
    ACTIONBAR_COOLDOWN("actionbar-cooldown", "victim"),
    ADMIN_GIVEN("admin-given", "amount", "balance", "player"),
    ADMIN_TAKEN("admin-taken", "amount", "balance", "player"),
    ADMIN_SET("admin-set", "balance", "player"),
    ADMIN_INSUFFICIENT("admin-insufficient", "balance", "player"),
    ADMIN_USAGE("admin-usage"),
    ADMIN_PLAYER_ONLINE("admin-player-online", "player"),
    RELOADED("reloaded"),
    PLAYER_ONLY("player-only"),
    SHOP_CONFIRM("shop-confirm", "cost", "item"),
    SHOP_BOUGHT("shop-bought", "balance", "cost", "item"),
    SHOP_INSUFFICIENT("shop-insufficient", "balance", "cost");

    private final String path;
    private final Set<String> tokens;

    ShardKeys(String path, String... tokens) {
        this.path = path;
        this.tokens = Set.of(tokens);
        this.plural = false;
        this.countToken = null;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Set<String> declaredTokens() {
        return tokens;
    }

    private final boolean plural;
    private final String countToken;

    ShardKeys(String path, Plural plural) {
        this.path = path;
        this.tokens = plural.tokens();
        this.plural = true;
        this.countToken = plural.countToken();
    }

    @Override
    public boolean plural() {
        return plural;
    }

    @Override
    public String countToken() {
        return countToken;
    }
}
