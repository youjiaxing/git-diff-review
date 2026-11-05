package io.github.youjiaxing.diffnav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Bounded review history. A failed navigation must not consume a history entry. */
final class ReviewHistory<T> {
    private final int limit;
    private final BiPredicate<T, T> samePlace;
    private final Consumer<T> release;
    private final List<T> entries = new ArrayList<>();
    private int cursor = -1;

    ReviewHistory(int limit, BiPredicate<T, T> samePlace) {
        this(limit, samePlace, ignored -> {});
    }

    ReviewHistory(int limit, BiPredicate<T, T> samePlace, Consumer<T> release) {
        if (limit < 2) throw new IllegalArgumentException("History needs at least two entries");
        this.limit = limit;
        this.samePlace = samePlace;
        this.release = release;
    }

    void record(T from, T to) {
        if (cursor < 0 || !samePlace.test(entries.get(cursor), from)) {
            clear();
            entries.add(from);
            cursor = 0;
        } else replace(cursor, from);
        if (Objects.equals(from, to)) return;
        while (entries.size() > cursor + 1) release.accept(entries.removeLast());
        entries.add(to);
        cursor++;
        if (entries.size() > limit) {
            release.accept(entries.removeFirst());
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
        replace(cursor, departed);
        cursor += direction;
    }

    void commitTravel(int direction, T departed, T arrived) {
        commitTravel(direction, departed);
        replace(cursor, arrived);
    }

    void discardNext(int direction) {
        if (peek(direction) == null) throw new IllegalStateException("No history in that direction");
        release.accept(entries.remove(cursor + direction));
        if (direction < 0) cursor--;
    }

    private void replace(int index, T value) {
        T old = entries.set(index, value);
        if (old != value) release.accept(old);
    }

    void clear() { entries.forEach(release); entries.clear(); cursor = -1; }
}
