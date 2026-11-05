package io.github.youjiaxing.diffnav;

/** Dependency-free regression suite, also runnable without Gradle or an IDE process. */
public final class ReviewHistoryTest {
    private record Place(String file, int line) {}
    public static void main(String[] args) {
        var history = new ReviewHistory<Place>(4, (a, b) -> a.file().equals(b.file()));
        Place a = new Place("a.go", 10), b = new Place("b.go", 20), c = new Place("c.go", 30);
        check(history.peek(-1) == null && history.peek(1) == null, "empty history");
        history.record(a, b);
        history.record(new Place("b.go", 25), c);
        check(history.peek(-1).equals(new Place("b.go", 25)), "save scrolled departure position");
        // Merely considering an unavailable/deleted target must not advance the cursor.
        check(history.peek(-1).equals(new Place("b.go", 25)), "failed navigation leaves history intact");
        history.commitTravel(-1, new Place("c.go", 35));
        check(history.peek(1).equals(new Place("c.go", 35)), "forward restores latest C position");
        history.commitTravel(-1, new Place("b.go", 27));
        check(history.current().equals(a) && history.peek(-1) == null, "A/B/C back chain");
        history.commitTravel(1, new Place("a.go", 12));
        history.record(new Place("b.go", 28), new Place("d.go", 40));
        check(history.peek(1) == null, "branch after back removes stale forward chain");
        check(history.peek(-1).equals(new Place("b.go", 28)), "branch preserves B position");
        history.record(new Place("external.go", 1), a);
        history.commitTravel(-1, a);
        check(history.peek(-1) == null, "manual switch starts a separate chain");
        for (int i = 0; i < 10; i++) history.record(new Place("f" + i, 1), new Place("f" + (i + 1), 2));
        int count = 1;
        while (history.peek(-1) != null) { history.commitTravel(-1, history.current()); count++; }
        check(count == 4, "history memory bound");
        history.clear();
        check(history.current() == null && history.peek(1) == null, "dispose/reset");
        System.out.println("ReviewHistoryTest: 10 regression checks passed");
    }
    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
