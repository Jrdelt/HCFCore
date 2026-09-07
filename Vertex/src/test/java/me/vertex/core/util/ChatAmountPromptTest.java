package me.vertex.core.util;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the mechanism that replaced the anvil-based amount entry: a
 * player's next chat line is captured, parsed, and consumed by a callback,
 * with none of the anvil's client-container state to fall out of sync
 * with. Chat events are constructed and delivered directly to
 * {@link ChatAmountPrompt#onChat} rather than through the plugin manager,
 * since {@code AsyncChatEvent} is a Paper-specific event MockBukkit has no
 * higher-level helper for simulating.
 */
class ChatAmountPromptTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private ChatAmountPrompt prompt;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        prompt = new ChatAmountPrompt(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void capturesAndParsesAValidAmount() {
        AtomicLong captured = new AtomicLong(-1);
        prompt.request(player, Component.text("How much?"), captured::set, () -> fail());

        AsyncChatEvent event = chat("100k");
        prompt.onChat(event);
        assertTrue(event.isCancelled(), "the typed amount must never reach public chat");

        tick();
        assertEquals(100_000L, captured.get());
        assertFalse(prompt.isPending(player.getUniqueId()), "answered prompts should no longer be pending");
    }

    @Test
    void pendingUntilAnsweredThenNotPending() {
        assertFalse(prompt.isPending(player.getUniqueId()));
        prompt.request(player, Component.text("prompt"), amount -> { }, () -> { });
        assertTrue(prompt.isPending(player.getUniqueId()));

        prompt.onChat(chat("5"));
        tick();
        assertFalse(prompt.isPending(player.getUniqueId()));
    }

    @Test
    void invalidInputRePromptsInsteadOfCancellingOrCompleting() {
        AtomicBoolean amountCalled = new AtomicBoolean();
        AtomicBoolean cancelCalled = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> amountCalled.set(true), () -> cancelCalled.set(true));

        AsyncChatEvent event = chat("not a number");
        prompt.onChat(event);
        assertTrue(event.isCancelled(), "garbage input must still be kept out of public chat");
        tick();

        assertFalse(amountCalled.get());
        assertFalse(cancelCalled.get());
        assertTrue(prompt.isPending(player.getUniqueId()), "an invalid answer should leave the prompt open to retry");

        // A valid follow-up answer completes the same prompt.
        prompt.onChat(chat("42"));
        tick();
        assertTrue(amountCalled.get());
    }

    @Test
    void typingCancelRunsTheCancelCallbackNotTheAmountCallback() {
        AtomicBoolean amountCalled = new AtomicBoolean();
        AtomicBoolean cancelCalled = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> amountCalled.set(true), () -> cancelCalled.set(true));

        prompt.onChat(chat("cancel"));
        tick();

        assertFalse(amountCalled.get());
        assertTrue(cancelCalled.get());
        assertFalse(prompt.isPending(player.getUniqueId()));
    }

    @Test
    void cancelIsCaseInsensitive() {
        AtomicBoolean cancelCalled = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> fail(), () -> cancelCalled.set(true));

        prompt.onChat(chat("CaNcEl"));
        tick();

        assertTrue(cancelCalled.get());
    }

    @Test
    void chatFromAPlayerWithNoPendingPromptIsIgnored() {
        AsyncChatEvent event = chat("100k");
        prompt.onChat(event);
        assertFalse(event.isCancelled(), "chat should pass through untouched with nothing pending");
    }

    @Test
    void aSecondRequestCancelsTheFirstOne() {
        AtomicReference<String> which = new AtomicReference<>();
        prompt.request(player, Component.text("first"), amount -> which.set("first-amount"), () -> which.set("first-cancel"));
        prompt.request(player, Component.text("second"), amount -> which.set("second-amount"), () -> which.set("second-cancel"));

        prompt.onChat(chat("10"));
        tick();

        assertEquals("second-amount", which.get(), "only the newest prompt should still be active");
    }

    @Test
    void runningACommandCancelsAPendingPrompt() {
        AtomicBoolean cancelCalled = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> fail(), () -> cancelCalled.set(true));

        prompt.onCommand(new PlayerCommandPreprocessEvent(player, "/spawn"));
        tick();

        assertTrue(cancelCalled.get());
        assertFalse(prompt.isPending(player.getUniqueId()));
    }

    @Test
    void quittingCleansUpAPendingPromptWithoutRunningCallbacks() {
        AtomicBoolean anyCallback = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> anyCallback.set(true), () -> anyCallback.set(true));

        prompt.onQuit(new PlayerQuitEvent(player, Component.text("bye"), PlayerQuitEvent.QuitReason.DISCONNECTED));
        tick();

        assertFalse(prompt.isPending(player.getUniqueId()));
        assertFalse(anyCallback.get(), "quitting should just clean up, not report a cancellation to a gone player");
    }

    @Test
    void timesOutAndRunsTheCancelCallback() {
        AtomicBoolean cancelCalled = new AtomicBoolean();
        prompt.request(player, Component.text("prompt"), amount -> fail(), () -> cancelCalled.set(true));

        ((BukkitSchedulerMock) server.getScheduler()).performTicks(20L * 60 + 1);

        assertTrue(cancelCalled.get());
        assertFalse(prompt.isPending(player.getUniqueId()));
    }

    private AsyncChatEvent chat(String text) {
        return new AsyncChatEvent(false, player, Set.of(),
                ChatRenderer.viewerUnaware((source, displayName, message) -> message),
                Component.text(text), Component.text(text), null);
    }

    private void tick() {
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();
    }

    private static void fail() {
        throw new AssertionError("this callback should not have run");
    }
}
