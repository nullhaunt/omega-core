package com.omegarealm.core.player.geyser;

import com.omegarealm.core.player.skin.BedrockSkinFingerprint;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.auth.BedrockClientData;
import org.geysermc.geyser.util.PluginMessageUtils;

import java.util.HexFormat;

/**
 * The Geyser entry point in the same Omega JAR used by Paper. Geyser exposes
 * the Bedrock client's current skin here, after the Java connection is ready.
 */
public final class OmegaGeyserSkinExtension implements Extension {

    @Subscribe
    public void onJoin(SessionJoinEvent event) {
        if (!(event.connection() instanceof GeyserSession session)) {
            logger().warning("Omega skin bridge cannot read this Geyser connection.");
            return;
        }

        BedrockClientData client = session.getClientData();
        if (client == null || client.isPersonaSkin()) {
            return;
        }

        BedrockSkinFingerprint.forClassicSkin(client.getSkinData(),
                        client.getSkinImageWidth(), client.getSkinImageHeight())
                .ifPresent(message -> {
                    try {
                        logger().debug("Forwarding classic skin fingerprints for "
                                + session.bedrockUsername() + ": "
                                + HexFormat.of().formatHex(message, 1, 7) + ", "
                                + HexFormat.of().formatHex(message, 33, 39) + ", "
                                + HexFormat.of().formatHex(message, 65, 71) + ".");
                        PluginMessageUtils.sendMessage(session, BedrockSkinFingerprint.CHANNEL, message);
                    } catch (RuntimeException | LinkageError failure) {
                        logger().warning("Omega could not forward this Bedrock skin fingerprint to Paper.");
                    }
                });
    }
}
