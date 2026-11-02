package lat.funkypunky.subastas;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Lee config.yml una vez (al arrancar y con /subasta recargar). */
final class Ajustes {

    final int limite;
    final int duracionHoras;
    final long duracionMs;
    final double comisionPublicar;
    final double porcentajeVenta;
    final double precioMin;
    final double precioMax;
    final Set<String> mundosBloqueados = new HashSet<>();
    final boolean bloquearCreativo;
    final Set<Material> prohibidos = EnumSet.noneOf(Material.class);
    final int revisarSegundos;
    final ZoneId zona;

    private final ConfigurationSection mensajes;
    private final String prefijo;

    Ajustes(FileConfiguration c, Logger log) {
        limite = Math.max(1, c.getInt("limite-publicaciones", 3));
        duracionHoras = Math.max(1, c.getInt("duracion-horas", 48));
        duracionMs = duracionHoras * 3_600_000L;
        comisionPublicar = Math.max(0, c.getDouble("comision-publicar", 10));
        porcentajeVenta = Math.min(100, Math.max(0, c.getDouble("porcentaje-venta", 5)));
        precioMin = Math.max(0.01, c.getDouble("precio-minimo", 1));
        precioMax = Math.max(precioMin, c.getDouble("precio-maximo", 1_000_000));
        for (String m : c.getStringList("mundos-bloqueados")) mundosBloqueados.add(m.toLowerCase(Locale.ROOT));
        bloquearCreativo = c.getBoolean("bloquear-creativo", true);
        for (String s : c.getStringList("objetos-prohibidos")) {
            Material m = Material.matchMaterial(s);
            if (m != null) prohibidos.add(m);
            else log.warning("objetos-prohibidos: no existe el material '" + s + "' (se ignora).");
        }
        revisarSegundos = Math.max(10, c.getInt("revisar-vencidas-segundos", 60));
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("zona-horaria", "America/Mexico_City"));
        } catch (Exception e) {
            log.warning("zona-horaria inválida, uso America/Mexico_City.");
            z = ZoneId.of("America/Mexico_City");
        }
        zona = z;
        mensajes = c.getConfigurationSection("mensajes");
        prefijo = mensajes == null ? "" : mensajes.getString("prefijo", "");
    }

    boolean mundoBloqueado(String mundo) {
        return mundosBloqueados.contains(mundo.toLowerCase(Locale.ROOT));
    }

    /** Texto crudo del config con {clave} reemplazado. pares = "clave", "valor", ... */
    String txt(String clave, String... pares) {
        String s = mensajes == null ? null : mensajes.getString(clave);
        if (s == null) s = clave;
        return reemplazar(s, pares);
    }

    /** Mensaje de chat con prefijo. */
    Component msg(String clave, String... pares) {
        return Texto.c(prefijo + txt(clave, pares));
    }

    /** Texto de menú (sin prefijo). */
    Component menu(String clave, String... pares) {
        return Texto.c(txt(clave, pares));
    }

    /** Lista de líneas (ayuda). */
    List<Component> lista(String clave, String... pares) {
        List<Component> out = new ArrayList<>();
        if (mensajes == null) return out;
        for (String s : mensajes.getStringList(clave)) out.add(Texto.c(reemplazar(s, pares)));
        return out;
    }

    private static String reemplazar(String s, String... pares) {
        for (int i = 0; i + 1 < pares.length; i += 2) {
            s = s.replace("{" + pares[i] + "}", pares[i + 1]);
        }
        return s;
    }
}
