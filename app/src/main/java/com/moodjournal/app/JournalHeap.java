package com.moodjournal.app;

/** Stable IDs keep folders distinct even when their names match. Null color uses the default. */
record JournalHeap(String id, String name, String category, Integer color, String description) {
    static final int MAX_DESCRIPTION = 2000;
    static final String[] COLOR_NAMES = {"Default", "Lavender", "Sage", "Peach", "Sky", "Rose"};
    static final int[] COLORS = {0xFFFFFFFF, 0xFFEDE6F1, 0xFFE8EDE1, 0xFFFAE6D5, 0xFFE1EDF5, 0xFFF5E2E8};

    JournalHeap(String id, String name, String category, Integer color) { this(id, name, category, color, ""); }

    JournalHeap {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid Heap ID");
        if (name == null || name.trim().isEmpty() || name.length() > 100)
            throw new IllegalArgumentException("Give your Heap a name (up to 100 characters).");
        if (category == null || category.length() > 60)
            throw new IllegalArgumentException("Use a category of up to 60 characters.");
        if (color != null && (color >>> 24) != 255) throw new IllegalArgumentException("Choose an opaque RGB color");
        if (description == null || description.length() > MAX_DESCRIPTION)
            throw new IllegalArgumentException("Use a description of up to 2,000 characters.");
        name = name.trim();
        category = category.trim();
        description = description.trim();
    }

    int background() { return color == null ? COLORS[0] : color; }
}
