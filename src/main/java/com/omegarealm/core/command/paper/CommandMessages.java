package com.omegarealm.core.command.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * Small, consistent sender-aware presentation for Omega commands.
 */
public final class CommandMessages {

    private CommandMessages() {
    }

    public static void send(CommandSender sender, Kind kind, String message) {
        if (sender instanceof Player) {
            sender.sendMessage(prefix().append(Component.text(message, kind.color)));
        } else {
            sender.sendPlainMessage("Ω | " + message);
        }
    }

    static void sendNotice(CommandSender sender, Kind kind, String title, String detail) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");

        if (sender instanceof Player) {
            Component notice = prefix()
                    .append(Component.text(title, kind.color).decorate(TextDecoration.BOLD))
                    .append(Component.text("\n  " + detail, NamedTextColor.WHITE));
            sender.sendMessage(notice);
            return;
        }

        sender.sendPlainMessage("Ω | " + title + "\n  " + detail);
    }

    static void sendPanel(CommandSender sender, String title, List<Field> fields) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(fields, "fields");

        if (sender instanceof Player) {
            Component panel = prefix().append(Component.text(title, NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD));
            for (Field field : fields) {
                panel = panel.append(Component.text("\n  "))
                        .append(Component.text(field.label() + ": ", NamedTextColor.GRAY))
                        .append(Component.text(field.value(), field.tone().color));
            }
            sender.sendMessage(panel);
            return;
        }

        StringBuilder panel = new StringBuilder("Ω | ").append(title);
        for (Field field : fields) {
            panel.append("\n  ").append(field.label()).append(": ").append(field.value());
        }
        sender.sendPlainMessage(panel.toString());
    }

    private static Component prefix() {
        return Component.text("Ω", NamedTextColor.AQUA)
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY));
    }

    enum Tone {
        NORMAL(NamedTextColor.WHITE),
        GOOD(NamedTextColor.GREEN),
        WARNING(NamedTextColor.YELLOW),
        ERROR(NamedTextColor.RED);

        private final NamedTextColor color;

        Tone(NamedTextColor color) {
            this.color = color;
        }
    }

    public enum Kind {
        INFO(NamedTextColor.GRAY),
        SUCCESS(NamedTextColor.GREEN),
        ERROR(NamedTextColor.RED);

        private final NamedTextColor color;

        Kind(NamedTextColor color) {
            this.color = color;
        }
    }

    record Field(String label, String value, Tone tone) {

        Field {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(tone, "tone");
        }

        static Field normal(String label, String value) {
            return new Field(label, value, Tone.NORMAL);
        }

        static Field good(String label, String value) {
            return new Field(label, value, Tone.GOOD);
        }

        static Field warning(String label, String value) {
            return new Field(label, value, Tone.WARNING);
        }

        static Field error(String label, String value) {
            return new Field(label, value, Tone.ERROR);
        }
    }
}
