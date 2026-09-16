package dev.smpeconomy.message;

import java.util.Set;

/** Messages owned by the spawner module. Separate enum so upstream changes stay contained. */
public enum SpawnerKeys implements MessageKey {

    NO_PERMISSION("spawners.no-permission"),
    BEING_VIEWED("spawners.being-viewed"),
    NOT_OWNER("spawners.not-owner"),
    SPAWNER_GIVEN("spawners.spawner-given", "player"),
    INVALID_TYPE("spawners.invalid-type"),
    MERGED("spawners.merged"),
    COLLECTED("spawners.collected", Plural.on("amount", "xp")),
    SOLD("spawners.sold", "money"),
    DESTROYED_WITHOUT_SILK("spawners.destroyed-without-silk", "amount"),
    SPAWNER_BROKEN("spawners.spawner-broken"),
    SPAWNER_STACKED("spawners.spawner-stacked", "amount"),
    SPAWNER_STACKED_MULTI("spawners.spawner-stacked-multi", "amount", "count"),
    GUI_TITLE("spawners.gui-title", "stack", "type"),
    GUI_TITLE_STORAGE("spawners.gui-title-storage", "page", "pages", "stack", "type"),
    GUI_SPAWNER_ITEM_NAME("spawners.gui-spawner-item-name", "stack", "type"),
    GUI_CLICK_TO_SELL("spawners.gui-click-to-sell"),
    GUI_STORAGE_FILLED("spawners.gui-storage-filled", "percent"),
    GUI_OPEN_STORAGE("spawners.gui-open-storage"),
    GUI_STORAGE_PREVIEW_ENTRY("spawners.gui-storage-preview-entry", "amount", "item"),
    GUI_STORAGE_PREVIEW_EMPTY("spawners.gui-storage-preview-empty"),
    GUI_STORAGE_ITEM_NAME("spawners.gui-storage-item-name", "amount", "item"),
    GUI_COLLECT_XP("spawners.gui-collect-xp"),
    GUI_COLLECT_XP_POINTS("spawners.gui-collect-xp-points", "xp"),
    GUI_CLOSE("spawners.gui-close"),
    GUI_PREVIOUS_PAGE("spawners.gui-previous-page"),
    GUI_PREVIOUS_PAGE_LORE("spawners.gui-previous-page-lore"),
    GUI_BACK("spawners.gui-back"),
    GUI_BACK_LORE("spawners.gui-back-lore"),
    GUI_SELL_ITEMS("spawners.gui-sell-items"),
    GUI_STORAGE_COLLECT("spawners.gui-storage-collect"),
    GUI_STORAGE_COLLECT_LORE("spawners.gui-storage-collect-lore"),
    GUI_DROP_ALL("spawners.gui-drop-all"),
    GUI_DROP_ALL_LORE("spawners.gui-drop-all-lore"),
    GUI_SELL_XP("spawners.gui-sell-xp"),
    GUI_SELL_ALL("spawners.gui-sell-all"),
    GUI_SELL_ALL_LORE("spawners.gui-sell-all-lore"),
    GUI_NEXT_PAGE("spawners.gui-next-page"),
    GUI_NEXT_PAGE_LORE("spawners.gui-next-page-lore");

    private final String path;
    private final Set<String> tokens;

    SpawnerKeys(String path, String... tokens) {
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

    SpawnerKeys(String path, Plural plural) {
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
