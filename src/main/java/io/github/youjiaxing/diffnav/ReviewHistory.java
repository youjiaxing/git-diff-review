package io.github.youjiaxing.diffnav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Bounded review history. A failed navigation must not consume a history entry. */
final class ReviewHistory<T> {
    private final int limit;
    private final BiPredicate<T, T> samePlace;
    private final List<T> entries = new ArrayList<>();
    private int cursor = -1;

    ReviewHistory(int limit, BiPredicate<T, T> samePlace) {
        if (limit < 2) throw new IllegalArgumentException("History needs at least two entries");
        this.limit = limit;
        this.samePlace = samePlace;
    }

    void record(T from, T to) {
        if (cursor < 0 || !samePlace.test(entries.get(cursor), from)) {
            clear();
            entries.add(from);
            cursor = 0;
        } else entries.set(cursor, from);
        if (Objects.equals(from, to)) return;
        entries.subList(cursor + 1, entries.size()).clear();
        entries.add(to);
        cursor++;
        if (entries.size() > limit) {
            entries.removeFirst();
            cursor--;
        }
    }

    T current() { return cursor < 0 ? null : entries.get(cursor); }

    T peek(int direction) {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Invalid direction");
        int next = cursor + direction;
        return next < 0 || next >= entries.size() ? null : entries.get(next);
    }

    void commitTravel(int direction, T departed) {
        if (peek(direction) == null) throw new IllegalStateException("No history in that direction");
        entries.set(cursor, departed);
        cursor += direction;
    }

    void clear() { entries.clear(); cursor = -1; }
}
