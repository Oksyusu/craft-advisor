package com.kuh.craftadvisor.command;

import java.util.function.Consumer;

import com.kuh.craftadvisor.CraftAdvisor;
import com.kuh.craftadvisor.chat.ChatOut;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
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
                .executes(ctx -> help())
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
                .then(Commands.literal("show")
                        // Without this, an incomplete "/ai show" would be passed on to the server.
                        .executes(ctx -> {
                            ChatOut.info("craftadvisor.help.show");
                            return 1;
                        })
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    // Chat links run this while the chat screen is open; open JEI on the next task.
                                    Minecraft.getInstance().tell(() -> ShowRecipe.show(id));
                                    return 1;
                                })))
                // Goal list (spec 04).
                .then(withText("add", "craftadvisor.help.add", GoalFlow::add))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            Minecraft.getInstance().tell(GoalFlow::list);
                            return 1;
                        }))
                .then(withText("remove", "craftadvisor.help.remove", GoalFlow::remove))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            Minecraft.getInstance().tell(GoalFlow::clear);
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

    private static int help() {
        ChatOut.info("craftadvisor.help.usage");
        ChatOut.info("craftadvisor.help.preview");
        ChatOut.info("craftadvisor.help.consent");
        ChatOut.info("craftadvisor.help.show");
        ChatOut.info("craftadvisor.help.add");
        ChatOut.info("craftadvisor.help.list");
        ChatOut.info("craftadvisor.help.remove");
        ChatOut.info("craftadvisor.help.clear");
        return 1;
    }

    /** A literal taking free text; without text it shows its usage line instead of passing on to the server. */
    private static LiteralArgumentBuilder<CommandSourceStack> withText(String name, String usageKey,
                                                                      Consumer<String> action) {
        return Commands.literal(name)
                .executes(ctx -> {
                    ChatOut.info(usageKey);
                    return 1;
                })
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String text = StringArgumentType.getString(ctx, "text");
                            Minecraft.getInstance().tell(() -> action.accept(text));
                            return 1;
                        }));
    }
}
