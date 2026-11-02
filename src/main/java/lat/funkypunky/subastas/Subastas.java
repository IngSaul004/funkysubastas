package lat.funkypunky.subastas;

import lat.funkypunky.subastas.BaseDatos.Guardado;
import lat.funkypunky.subastas.BaseDatos.Publicacion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * La lógica que mueve dinero y objetos. Regla de oro para que nada se duplique:
 * cada paso revisa el estado REAL en la base de datos justo antes de actuar,
 * y si algo falla a la mitad se deshace lo ya hecho (devolver objeto o dinero).
 */
final class Subastas {

    private final FunkySubastas plugin;

    Subastas(FunkySubastas plugin) {
        this.plugin = plugin;
    }

    private Ajustes a() {
        return plugin.ajustes();
    }

    private BaseDatos db() {
        return plugin.db();
    }

    private Economia eco() {
        return plugin.eco();
    }

    /** ¿Puede usar la subasta aquí? (bloqueada en el lobby, que tiene inventario aparte). */
    boolean puedeUsarAqui(Player p) {
        if (a().mundoBloqueado(p.getWorld().getName())) {
            p.sendMessage(a().msg("mundo-bloqueado"));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ Publicar

    void publicar(Player p, double precio) {
        if (!puedeUsarAqui(p)) return;
        if (a().bloquearCreativo && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            p.sendMessage(a().msg("creativo"));
            return;
        }
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (mano.getType() == Material.AIR || mano.getAmount() <= 0) {
            p.sendMessage(a().msg("mano-vacia"));
            return;
        }
        if (a().prohibidos.contains(mano.getType())) {
            p.sendMessage(a().msg("prohibido"));
            return;
        }
        if (precio < a().precioMin || precio > a().precioMax) {
            p.sendMessage(a().msg("precio-rango", "min", Texto.dinero(a().precioMin), "max", Texto.dinero(a().precioMax)));
            return;
        }
        try {
            if (db().contarDelVendedor(p.getUniqueId()) >= a().limite) {
                p.sendMessage(a().msg("limite", "limite", String.valueOf(a().limite)));
                return;
            }
        } catch (SQLException e) {
            fallo(p, "contar publicaciones", e);
            return;
        }
        double comision = a().comisionPublicar;
        if (!eco().tiene(p, comision)) {
            p.sendMessage(a().msg("sin-dinero-comision", "comision", Texto.dinero(comision)));
            return;
        }

        // 1) Quitar el objeto de la mano (primero el objeto: si algo falla después, se devuelve).
        ItemStack item = mano.clone();
        p.getInventory().setItemInMainHand(null);

        // 2) Cobrar la comisión (se destruye: nadie la recibe).
        if (!eco().retirar(p, comision)) {
            devolver(p, item);
            p.sendMessage(a().msg("error"));
            return;
        }

        // 3) Guardar la publicación.
        long ahora = System.currentTimeMillis();
        long id;
        try {
            id = db().publicar(p.getUniqueId(), p.getName(), item, precio, ahora, ahora + a().duracionMs);
        } catch (SQLException e) {
            eco().depositar(p, comision);
            devolver(p, item);
            fallo(p, "guardar publicación", e);
            return;
        }

        p.sendMessage(a().msg("publicado", "objeto", Texto.nombre(item), "precio", Texto.dinero(precio),
                "comision", Texto.dinero(comision), "horas", String.valueOf(a().duracionHoras), "id", String.valueOf(id)));
        plugin.registro().anotar("PUBLICO", p.getName() + " #" + id + " " + Texto.nombre(item) + " por " + Texto.dinero(precio)
                + " (comisión " + Texto.dinero(comision) + ")");
    }

    // ------------------------------------------------------------ Comprar

    void comprar(Player p, long id) {
        if (!puedeUsarAqui(p)) return;
        Publicacion pub;
        try {
            pub = db().buscar(id);
        } catch (SQLException e) {
            fallo(p, "buscar publicación", e);
            return;
        }
        if (pub == null) {
            p.sendMessage(a().msg("ya-vendida"));
            return;
        }
        if (pub.expira() <= System.currentTimeMillis()) {
            p.sendMessage(a().msg("vencida"));
            return;
        }
        if (pub.vendedor().equals(p.getUniqueId())) {
            p.sendMessage(a().msg("propia"));
            return;
        }
        double precio = pub.precio();
        if (!eco().tiene(p, precio)) {
            double falta = precio - eco().saldo(p);
            p.sendMessage(a().msg("sin-dinero", "falta", Texto.dinero(Math.max(0, falta))));
            return;
        }

        // 1) Cobrar al comprador.
        if (!eco().retirar(p, precio)) {
            p.sendMessage(a().msg("error"));
            return;
        }
        // 2) Quitar la publicación. Si ya no estaba (otro la compró), se devuelve el dinero.
        try {
            if (!db().borrarPublicacion(id)) {
                eco().depositar(p, precio);
                p.sendMessage(a().msg("ya-vendida"));
                return;
            }
        } catch (SQLException e) {
            eco().depositar(p, precio);
            fallo(p, "borrar publicación", e);
            return;
        }
        // 3) Pagar al vendedor (menos la comisión, que se destruye).
        double comisionVenta = Math.round(precio * a().porcentajeVenta) / 100.0;
        double pago = precio - comisionVenta;
        OfflinePlayer vendedor = Bukkit.getOfflinePlayer(pub.vendedor());
        if (!eco().depositar(vendedor, pago)) {
            plugin.getLogger().severe("No se pudo pagar " + Texto.dinero(pago) + " a " + pub.vendedorNombre()
                    + " por la venta #" + id + ". Pagar a mano con /eco give.");
            plugin.registro().anotar("ERROR_PAGO", pub.vendedorNombre() + " #" + id + " debía recibir " + Texto.dinero(pago));
        }
        // 4) Entregar el objeto (lo que no quepa va al buzón; nunca se tira al suelo).
        ItemStack item = pub.item();
        darOBuzon(p, item);

        String nombre = Texto.nombre(item);
        p.sendMessage(a().msg("comprado", "objeto", nombre, "precio", Texto.dinero(precio)));
        Player enLinea = vendedor.getPlayer();
        if (enLinea != null) {
            enLinea.sendMessage(a().msg("vendido", "objeto", nombre, "comprador", p.getName(),
                    "pago", Texto.dinero(pago), "comisionventa", Texto.dinero(comisionVenta)));
        } else {
            avisar(pub.vendedor(), a().txt("vendido-aviso", "objeto", nombre, "comprador", p.getName(), "pago", Texto.dinero(pago)));
        }
        plugin.registro().anotar("COMPRO", p.getName() + " compró #" + id + " " + nombre + " a " + pub.vendedorNombre()
                + " por " + Texto.dinero(precio) + " (vendedor recibe " + Texto.dinero(pago) + ")");
    }

    // ------------------------------------------------------------ Cancelar (dueño)

    void cancelar(Player p, long id) {
        if (!puedeUsarAqui(p)) return;
        try {
            Publicacion pub = db().buscar(id);
            if (pub == null || !pub.vendedor().equals(p.getUniqueId())) {
                p.sendMessage(a().msg("no-es-tuya"));
                return;
            }
            if (!db().borrarPublicacion(id)) {
                p.sendMessage(a().msg("no-es-tuya"));
                return;
            }
            darOBuzon(p, pub.item());
            p.sendMessage(a().msg("cancelada", "objeto", Texto.nombre(pub.item())));
            plugin.registro().anotar("CANCELO", p.getName() + " #" + id + " " + Texto.nombre(pub.item()));
        } catch (SQLException e) {
            fallo(p, "cancelar publicación", e);
        }
    }

    // ------------------------------------------------------------ Buzón

    void recoger(Player p, long id) {
        if (!puedeUsarAqui(p)) return;
        try {
            Guardado g = db().buscarBuzon(id);
            if (g == null || !g.dueno().equals(p.getUniqueId())) return;
            if (p.getInventory().firstEmpty() == -1) {
                p.sendMessage(a().msg("buzon-lleno-inventario"));
                return;
            }
            if (!db().sacarDelBuzon(id)) return;
            darOBuzon(p, g.item());
            p.sendMessage(a().msg("recogido", "objeto", Texto.nombre(g.item())));
            plugin.registro().anotar("RECOGIO", p.getName() + " buzón #" + id + " " + Texto.nombre(g.item()));
        } catch (SQLException e) {
            fallo(p, "recoger del buzón", e);
        }
    }

    // ------------------------------------------------------------ Staff

    void quitarStaff(CommandSender quien, long id) {
        try {
            Publicacion pub = db().buscar(id);
            if (pub == null) {
                quien.sendMessage(a().msg("ya-vendida"));
                return;
            }
            String motivo = a().txt("motivo-staff");
            boolean[] hecho = {false};
            db().transaccion(() -> {
                if (db().borrarPublicacion(id)) {
                    db().alBuzon(pub.vendedor(), pub.item(), motivo);
                    hecho[0] = true;
                }
            });
            if (!hecho[0]) {
                quien.sendMessage(a().msg("ya-vendida"));
                return;
            }
            notificar(pub.vendedor(), "quitada-staff", "objeto", Texto.nombre(pub.item()));
            quien.sendMessage(a().msg("staff-quitada", "id", String.valueOf(id), "vendedor", pub.vendedorNombre()));
            plugin.registro().anotar("STAFF_QUITO", quien.getName() + " retiró #" + id + " de " + pub.vendedorNombre()
                    + " (" + Texto.nombre(pub.item()) + ")");
        } catch (SQLException e) {
            fallo(quien, "retirar publicación", e);
        }
    }

    /** Fin de temporada: todas las publicaciones regresan al buzón de su dueño. */
    int devolverTodo(CommandSender quien) {
        int n = 0;
        try {
            String motivo = a().txt("motivo-temporada");
            for (Publicacion pub : db().todas()) {
                boolean[] hecho = {false};
                db().transaccion(() -> {
                    if (db().borrarPublicacion(pub.id())) {
                        db().alBuzon(pub.vendedor(), pub.item(), motivo);
                        hecho[0] = true;
                    }
                });
                if (hecho[0]) {
                    n++;
                    notificar(pub.vendedor(), "temporada", "objeto", Texto.nombre(pub.item()));
                }
            }
            plugin.registro().anotar("TEMPORADA", quien.getName() + " devolvió " + n + " publicaciones a los buzones");
        } catch (SQLException e) {
            fallo(quien, "devolver todo", e);
        }
        return n;
    }

    // ------------------------------------------------------------ Tarea: vencidas

    void revisarVencidas() {
        try {
            String motivo = a().txt("motivo-vencida");
            for (Publicacion pub : db().vencidas(System.currentTimeMillis())) {
                boolean[] hecho = {false};
                db().transaccion(() -> {
                    if (db().borrarPublicacion(pub.id())) {
                        db().alBuzon(pub.vendedor(), pub.item(), motivo);
                        hecho[0] = true;
                    }
                });
                if (hecho[0]) {
                    notificar(pub.vendedor(), "vencio", "objeto", Texto.nombre(pub.item()));
                    plugin.registro().anotar("VENCIO", pub.vendedorNombre() + " #" + pub.id() + " " + Texto.nombre(pub.item()));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error al revisar publicaciones vencidas", e);
        }
    }

    // ------------------------------------------------------------ Ayudantes

    /** Mete el objeto al inventario; lo que no quepa va al buzón (nunca al suelo: ahí se pierde). */
    void darOBuzon(Player p, ItemStack item) {
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(item.clone());
        for (ItemStack s : sobra.values()) {
            try {
                db().alBuzon(p.getUniqueId(), s, a().txt("motivo-inventario"));
                p.sendMessage(a().msg("al-buzon", "objeto", Texto.nombre(s)));
            } catch (SQLException e) {
                // Último recurso: si ni el buzón funciona, se tira a los pies para no perderlo.
                p.getWorld().dropItemNaturally(p.getLocation(), s);
                plugin.getLogger().log(Level.SEVERE, "No se pudo guardar en el buzón; se tiró al suelo junto a " + p.getName(), e);
            }
        }
    }

    /** Devolver un objeto que se acababa de quitar (si algo falló al publicar). */
    private void devolver(Player p, ItemStack item) {
        darOBuzon(p, item);
    }

    /** Mensaje al jugador si está conectado; si no, queda guardado para cuando entre. */
    private void notificar(UUID dueno, String clave, String... pares) {
        Player p = Bukkit.getPlayer(dueno);
        if (p != null) p.sendMessage(a().msg(clave, pares));
        else avisar(dueno, a().txt(clave, pares));
    }

    private void avisar(UUID dueno, String texto) {
        try {
            db().aviso(dueno, texto);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo guardar un aviso", e);
        }
    }

    private void fallo(CommandSender s, String que, SQLException e) {
        s.sendMessage(a().msg("error"));
        plugin.getLogger().log(Level.SEVERE, "Error de base de datos al " + que, e);
    }
}
