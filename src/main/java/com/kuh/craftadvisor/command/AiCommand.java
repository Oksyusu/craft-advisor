package com.kuh.craftadvisor.command;

import com.kuh.craftadvisor.CraftAdvisor;
import com.kuh.craftadvisor.chat.ChatOut;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Registers /ai as a client command: NeoForge runs it locally and never sends it to the server. */
@EventBusSubscriber(modid = CraftAdvisor.MODID, value = Dist.CLIENT)
public final class AiCommand {
    private AiCommand() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ai")
                .executes(ctx -> {
                    ChatOut.info("craftadvisor.help.usage");
                    ChatOut.info("craftadvisor.help.preview");
                    ChatOut.info("craftadvisor.help.consent");
                    return 1;
                })
                // Literals win over the free-text argument, so "/ai preview ..." is never sent as a question.
                .then(Commands.literal("preview")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String text = StringArgumentType.getString(ctx, "text");
                                    Minecraft.getInstance().tell(() -> AdvisorFlow.preview(text));
                                    return 1;
                                })))
                .then(Commands.literal("consent")
                        .executes(ctx -> {
                            Minecraft.getInstance().tell(AdvisorFlow::consent);
                            return 1;
                        }))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String text = StringArgumentType.getString(ctx, "text");
                            // Run after the chat screen has closed, so the JEI screen we open is not closed with it.
                            Minecraft.getInstance().tell(() -> AdvisorFlow.start(text));
                            return 1;
                        })));
    }
}
