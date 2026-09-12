package me.vertex.core.command;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandVisibilityListenerTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void foreignNamespaceDoesNotInheritVertexStaffPermission(){
        var server=MockBukkit.mock();var player=server.addPlayer();
        var commands=new ArrayList<>(List.of("other:backpack","essentials:vanish","vertex:backpack","VERTEX:WAND"));
        var event=new PlayerCommandSendEvent(player,commands);new CommandVisibilityListener().onCommandTreeSend(event);
        assertEquals(List.of("other:backpack","essentials:vanish"),List.copyOf(event.getCommands()));
    }
}
