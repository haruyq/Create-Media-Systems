package org.haruyq.createmediasystems.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TabSectionsTest {
    @Test
    void eachSectionStartsOnItsOwnRowUnderABannerRow() {
        // Ten of section a, two of b; "x" is of no section and stays with b.
        List<String> items = new ArrayList<>(Collections.nCopies(10, "a"));
        items.addAll(List.of("b", "x", "b"));

        Map<Integer, String> banners = TabSections.layOut(items, item -> item.equals("x") ? null : item, "");

        assertEquals(Map.of(0, "a", 3, "b"), banners);
        List<String> expected = new ArrayList<>(Collections.nCopies(9, ""));
        expected.addAll(Collections.nCopies(10, "a"));
        expected.addAll(Collections.nCopies(8 + 9, ""));
        expected.addAll(List.of("b", "x", "b"));
        assertEquals(expected, items);
    }
}
