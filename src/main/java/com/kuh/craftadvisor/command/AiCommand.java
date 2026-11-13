package com.kuh.craftadvisor.command;

import com.kuh.craftadvisor.CraftAdvisor;
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
                .then(Commands.literal("show")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    // Chat links run this while the chat screen is open; open JEI on the next task.
                                    Minecraft.getInstance().tell(() -> ShowRecipe.show(id));
                                    return 1;
                                })))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String text = StringArgumentType.getString(ctx, "text");
                            // Run after the chat screen has closed, so the JEI screen we open is not closed with it.
                            Minecraft.getInstance().tell(() -> AdvisorFlow.start(text));
                            return 1;
                        })));
    }
}
