package lat.funkypunky.subastas;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

/** FunkySubastas: casa de subastas de FUNKY PUNKY MC. */
public final class FunkySubastas extends JavaPlugin {

    private Ajustes ajustes;
    private BaseDatos db;
    private Economia eco;
    private Registro registro;
    private Subastas subastas;
    private Menus menus;
    private BukkitTask tareaVencidas;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ajustes = new Ajustes(getConfig(), getLogger());

        db = new BaseDatos(new File(getDataFolder(), "subastas.db"), getLogger());
        try {
            db.abrir();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "No se pudo abrir subastas.db. FunkySubastas se apaga.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        eco = new Economia();
        registro = new Registro(new File(getDataFolder(), "registro.log"), () -> ajustes.zona, getLogger());
        subastas = new Subastas(this);
        menus = new Menus(this);

        PluginCommand cmd = getCommand("subasta");
        if (cmd != null) {
            Comando c = new Comando(this);
            cmd.setExecutor(c);
            cmd.setTabCompleter(c);
        }
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(new Avisos(this), this);

        programarVencidas();

        // La economía la registra Essentials al arrancar: se revisa cuando ya cargaron todos los plugins.
        getServer().getScheduler().runTask(this, () -> {
            if (!eco.disponible()) {
                getLogger().severe("No hay economía de Vault (VaultUnlocked + Essentials). Las compras van a fallar.");
            }
        });
        getLogger().info("FunkySubastas " + getPluginMeta().getVersion() + " listo.");
    }

    @Override
    public void onDisable() {
        if (tareaVencidas != null) tareaVencidas.cancel();
        if (db != null) db.cerrar();
    }

    void recargar() {
        reloadConfig();
        ajustes = new Ajustes(getConfig(), getLogger());
        programarVencidas();
    }

    private void programarVencidas() {
        if (tareaVencidas != null) tareaVencidas.cancel();
        long ticks = ajustes.revisarSegundos * 20L;
        tareaVencidas = getServer().getScheduler().runTaskTimer(this, () -> subastas.revisarVencidas(), 200L, ticks);
    }

    void enviarAyuda(CommandSender s) {
        for (Component linea : ajustes.lista("ayuda",
                "comision", Texto.dinero(ajustes.comisionPublicar),
                "porcentaje", Texto.dinero(ajustes.porcentajeVenta).substring(1),
                "limite", String.valueOf(ajustes.limite),
                "horas", String.valueOf(ajustes.duracionHoras))) {
            s.sendMessage(linea);
        }
        if (s.hasPermission("funkysubastas.admin")) {
            for (Component linea : ajustes.lista("ayuda-admin")) s.sendMessage(linea);
        }
    }

    Ajustes ajustes() {
        return ajustes;
    }

    BaseDatos db() {
        return db;
    }

    Economia eco() {
        return eco;
    }

    Registro registro() {
        return registro;
    }

    Subastas subastas() {
        return subastas;
    }

    Menus menus() {
        return menus;
    }
}
