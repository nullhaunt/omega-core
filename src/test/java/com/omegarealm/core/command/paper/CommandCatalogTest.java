package com.omegarealm.core.command.paper;

import com.omegarealm.core.authorization.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandCatalogTest {

    private static CommandCatalog.Definition definition(String name, String syntax, String description) {
        return new CommandCatalog.Definition(name, syntax, description, Role.PLAYER,
                null, List.of(), false);
    }

    @Test
    void definitionsRequireMatchingSyntaxAndDescription() {
        assertThrows(IllegalArgumentException.class, () -> definition("", "", "Describe it"));
        assertThrows(IllegalArgumentException.class, () -> definition("example", "", "Describe it"));
        assertThrows(IllegalArgumentException.class, () -> definition("example", "other", "Describe it"));
        assertThrows(IllegalArgumentException.class, () -> definition("example", "example", " "));
    }
}
