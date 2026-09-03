package me.hcfcore.core.tag;

/**
 * Everything about how a player's tag menu is currently rendered. Every
 * button click derives a new state and re-opens the menu with it, rather
 * than mutating anything in place.
 */
public record TagMenuState(TagManager.Sort sort, boolean ascending, TagManager.Filter filter, int page,
                            String searchQuery) {

    public static TagMenuState initial() {
        return new TagMenuState(TagManager.Sort.ALPHABETICAL, true, TagManager.Filter.ALL, 0, null);
    }

    public TagMenuState withSort(TagManager.Sort newSort) {
        return new TagMenuState(newSort, ascending, filter, 0, searchQuery);
    }

    public TagMenuState withAscending(boolean newAscending) {
        return new TagMenuState(sort, newAscending, filter, page, searchQuery);
    }

    public TagMenuState withFilter(TagManager.Filter newFilter) {
        return new TagMenuState(sort, ascending, newFilter, 0, searchQuery);
    }

    public TagMenuState withPage(int newPage) {
        return new TagMenuState(sort, ascending, filter, newPage, searchQuery);
    }

    public TagMenuState withSearchQuery(String newQuery) {
        return new TagMenuState(sort, ascending, filter, 0, newQuery);
    }
}
