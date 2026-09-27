package com.omegarealm.core.command.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Small, consistent sender-aware presentation for Omega commands.
 */
public final class CommandMessages {

    private CommandMessages() {
    }

    public static void send(CommandSender sender, Kind kind, String message) {
        if (sender instanceof Player) {
            NamedTextColor color = switch (kind) {
                case INFO -> NamedTextColor.GRAY;
                case SUCCESS -> NamedTextColor.GREEN;
                case ERROR -> NamedTextColor.RED;
            };
            sender.sendMessage(Component.text("Ω", NamedTextColor.AQUA)
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(message, color)));
        } else {
            sender.sendPlainMessage("Ω | " + message);
        }
    }

    public enum Kind {
        INFO, SUCCESS, ERROR
    }
}
