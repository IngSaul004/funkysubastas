package lat.funkypunky.subastas;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /subasta (alias /ah). */
final class Comando implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("vender", "buzon", "mias", "buscar", "ayuda");
    private static final List<String> SUBS_ADMIN = List.of("quitar", "info", "recargar", "devolver-todo");

    private final FunkySubastas plugin;

    Comando(FunkySubastas plugin) {
        this.plugin = plugin;
    }

    private Ajustes a() {
        return plugin.ajustes();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("funkysubastas.admin");

        // --- Comandos de staff (también desde la consola) ---
        switch (sub) {
            case "recargar" -> {
                if (!admin) return sinPermiso(s);
                plugin.recargar();
                s.sendMessage(a().msg("recargado"));
                return true;
            }
            case "info" -> {
                if (!admin) return sinPermiso(s);
                try {
                    s.sendMessage(a().msg("info",
                            "activas", String.valueOf(plugin.db().contarActivas(System.currentTimeMillis())),
                            "buzon", String.valueOf(plugin.db().contarBuzonTotal()),
                            "version", plugin.getPluginMeta().getVersion()));
                } catch (SQLException e) {
                    s.sendMessage(a().msg("error"));
                }
                return true;
            }
            case "quitar" -> {
                if (!admin) return sinPermiso(s);
                Long id = args.length >= 2 ? leerId(args[1]) : null;
                if (id == null) {
                    s.sendMessage(a().msg("no-es-tuya"));
                    return true;
                }
                plugin.subastas().quitarStaff(s, id);
                return true;
            }
            case "devolver-todo" -> {
                if (!admin) return sinPermiso(s);
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirmar")) {
                    s.sendMessage(a().msg("devolver-confirmar"));
                    return true;
                }
                int n = plugin.subastas().devolverTodo(s);
                s.sendMessage(a().msg("devuelto-todo", "cantidad", String.valueOf(n)));
                return true;
            }
            default -> {
            }
        }

        // --- Comandos de jugador ---
        if (!(s instanceof Player p)) {
            if (sub.isEmpty() || sub.equals("ayuda")) {
                plugin.enviarAyuda(s);
            } else {
                s.sendMessage(a().msg("solo-jugadores"));
            }
            return true;
        }
        if (!p.hasPermission("funkysubastas.usar")) return sinPermiso(p);

        switch (sub) {
            case "" -> plugin.menus().abrirPrincipal(p, 0, false, null);
            case "vender", "sell" -> {
                Double precio = args.length >= 2 ? leerPrecio(args[1]) : null;
                if (precio == null) {
                    p.sendMessage(a().msg("precio-invalido"));
                    return true;
                }
                plugin.subastas().publicar(p, precio);
            }
            case "buzon", "buzón" -> plugin.menus().abrirBuzon(p, 0);
            case "mias", "mías" -> plugin.menus().abrirMias(p);
            case "buscar" -> {
                if (args.length < 2) {
                    p.sendMessage(a().msg("buscar-uso"));
                    return true;
                }
                String texto = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                plugin.menus().abrirPrincipal(p, 0, false, texto);
            }
            default -> plugin.enviarAyuda(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String x : SUBS) if (x.startsWith(pre)) out.add(x);
            if (s.hasPermission("funkysubastas.admin")) {
                for (String x : SUBS_ADMIN) if (x.startsWith(pre)) out.add(x);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("vender")) {
            out.add("<precio>");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("devolver-todo") && s.hasPermission("funkysubastas.admin")) {
            out.add("confirmar");
        }
        return out;
    }

    /** "250", "1,500", "$2000", "1.5k", "2m". Redondea a centavos. null si no es válido. */
    static Double leerPrecio(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("$", "").replace(",", "").trim();
        double mult = 1;
        if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return null;
            return Math.round(v * 100) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long leerId(String s) {
        try {
            return Long.parseLong(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean sinPermiso(CommandSender s) {
        s.sendMessage(a().msg("sin-permiso"));
        return true;
    }
}
