package com.omegarealm.core.player.paper;

import com.omegarealm.core.player.PublicPlayerProfile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Presents the current public profile fields with Paper's common dialog surface.
 */
public final class ProfileDialog {

    private static final DateTimeFormatter JOIN_DATE = DateTimeFormatter
            .ofPattern("d MMM uuuu", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private ProfileDialog() {
    }

    public static void show(Player viewer, PublicPlayerProfile profile) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Member since " + JOIN_DATE.format(profile.identity().firstSeenAt()) + " (UTC)")));
        profile.staffDesignation().ifPresent(designation -> body.add(
                DialogBody.plainMessage(Component.text("Staff: " + designation))));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(profile.identity().username()))
                        .body(body)
                        .build())
                .type(DialogType.notice()));
        viewer.showDialog(dialog);
    }
}
