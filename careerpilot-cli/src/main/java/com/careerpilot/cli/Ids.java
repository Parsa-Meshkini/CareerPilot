package com.careerpilot.cli;

import com.careerpilot.common.NotFoundException;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/** Lets people type the first 8 characters of an id (as printed by list commands) instead of the full UUID. */
final class Ids {
    private Ids() {}

    static <T> UUID resolve(String text, Collection<T> candidates, Function<T, UUID> idOf) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notFull) {
            var matches = candidates.stream().map(idOf).filter(id -> id.toString().startsWith(text.toLowerCase())).toList();
            if (matches.size() == 1) return matches.get(0);
            throw new NotFoundException(matches.isEmpty() ? "no object starts with '" + text + "'" : "'" + text + "' is ambiguous; type more characters");
        }
    }
}
