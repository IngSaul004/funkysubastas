package lat.funkypunky.subastas;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;
import java.util.logging.Level;

/** Al entrar: mensajes de lo que pasó mientras no estabas (ventas, vencidas) y aviso del buzón. */
final class Avisos implements Listener {

    private final FunkySubastas plugin;

    Avisos(FunkySubastas plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void alEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // 5 s después, para que no se pierda entre el MOTD y el inicio de sesión de AuthMe.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            try {
                for (String m : plugin.db().tomarAvisos(p.getUniqueId())) {
                    p.sendMessage(Texto.c(plugin.ajustes().txt("prefijo") + m));
                }
                int n = plugin.db().contarBuzon(p.getUniqueId());
                if (n > 0) p.sendMessage(plugin.ajustes().msg("aviso-buzon", "cantidad", String.valueOf(n)));
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "No se pudieron leer los avisos de " + p.getName(), ex);
            }
        }, 100L);
    }
}
