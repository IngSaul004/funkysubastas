package lat.funkypunky.subastas;

import lat.funkypunky.subastas.BaseDatos.Guardado;
import lat.funkypunky.subastas.BaseDatos.Publicacion;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Menús de cofre (Java; en Bedrock Geyser los muestra igual. Los formularios de Bedrock llegan en la 1.1).
 * Los menús solo MUESTRAN copias: todo clic se cancela y la acción real la hace Subastas,
 * que vuelve a revisar la base de datos. Así un menú viejo abierto nunca puede duplicar nada.
 */
final class Menus implements Listener {

    private static final int POR_PAGINA = 45;

    /** Marca qué menú es y qué publicación hay en cada casilla. */
    static final class Holder implements InventoryHolder {
        enum Tipo { PRINCIPAL, MIAS, BUZON, CONFIRMAR }

        final Tipo tipo;
        int pagina;
        boolean barato;
        String filtro;
        long confirmar;
        final long[] ids = new long[54];
        Inventory inv;

        Holder(Tipo tipo) {
            this.tipo = tipo;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final FunkySubastas plugin;

    Menus(FunkySubastas plugin) {
        this.plugin = plugin;
    }

    private Ajustes a() {
        return plugin.ajustes();
    }

    // ------------------------------------------------------------ Principal

    void abrirPrincipal(Player p, int pagina, boolean barato, String filtro) {
        if (!plugin.subastas().puedeUsarAqui(p)) return;
        List<Publicacion> lista;
        int mias;
        int buzon;
        try {
            lista = plugin.db().activas(System.currentTimeMillis());
            mias = plugin.db().contarDelVendedor(p.getUniqueId());
            buzon = plugin.db().contarBuzon(p.getUniqueId());
        } catch (SQLException e) {
            error(p, e);
            return;
        }
        if (filtro != null && !filtro.isBlank()) {
            String f = filtro.toLowerCase(Locale.ROOT);
            lista.removeIf(pub -> !Texto.nombreSimple(pub.item()).toLowerCase(Locale.ROOT).contains(f)
                    && !pub.item().getType().name().toLowerCase(Locale.ROOT).contains(f.replace(' ', '_'))
                    && !pub.vendedorNombre().toLowerCase(Locale.ROOT).contains(f));
        }
        if (barato) lista.sort(Comparator.comparingDouble(Publicacion::precio));

        int paginas = Math.max(1, (lista.size() + POR_PAGINA - 1) / POR_PAGINA);
        pagina = Math.max(0, Math.min(pagina, paginas - 1));

        Holder h = new Holder(Holder.Tipo.PRINCIPAL);
        h.pagina = pagina;
        h.barato = barato;
        h.filtro = filtro;
        Inventory inv = Bukkit.createInventory(h, 54, a().menu("titulo-principal",
                "pagina", String.valueOf(pagina + 1), "paginas", String.valueOf(paginas)));
        h.inv = inv;

        long ahora = System.currentTimeMillis();
        boolean staff = p.hasPermission("funkysubastas.admin");
        for (int i = 0; i < POR_PAGINA; i++) {
            int idx = pagina * POR_PAGINA + i;
            if (idx >= lista.size()) break;
            Publicacion pub = lista.get(idx);
            List<Component> extra = new ArrayList<>();
            extra.add(a().menu("lore-separador"));
            extra.add(a().menu("lore-precio", "precio", Texto.dinero(pub.precio())));
            extra.add(a().menu("lore-vendedor", "vendedor", pub.vendedorNombre()));
            extra.add(a().menu("lore-expira", "tiempo", Texto.tiempo(pub.expira() - ahora)));
            extra.add(a().menu("lore-id", "id", String.valueOf(pub.id())));
            extra.add(a().menu("lore-comprar"));
            if (staff) extra.add(a().menu("lore-staff"));
            inv.setItem(i, conLore(pub.item(), extra));
            h.ids[i] = pub.id();
        }
        if (lista.isEmpty()) inv.setItem(22, boton(Material.GRAY_DYE, a().menu("vacio")));

        barraInferior(inv);
        if (pagina > 0) inv.setItem(45, boton(Material.ARROW, a().menu("boton-anterior")));
        inv.setItem(47, boton(Material.HOPPER, a().menu(barato ? "boton-orden-barato" : "boton-orden-nuevo"),
                a().menu("boton-orden-lore")));
        inv.setItem(48, boton(Material.CHEST, a().menu("boton-buzon", "cantidad", String.valueOf(buzon))));
        inv.setItem(49, boton(Material.NETHER_STAR, a().menu("boton-ayuda"), a().menu("boton-ayuda-lore")));
        inv.setItem(50, boton(Material.WRITABLE_BOOK, a().menu("boton-mias",
                "cantidad", String.valueOf(mias), "limite", String.valueOf(a().limite))));
        if (filtro != null && !filtro.isBlank()) {
            inv.setItem(51, boton(Material.NAME_TAG, a().menu("boton-filtro", "filtro", filtro)));
        }
        if (pagina < paginas - 1) inv.setItem(53, boton(Material.ARROW, a().menu("boton-siguiente")));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ Confirmar compra

    private void abrirConfirmar(Player p, Publicacion pub, Holder anterior) {
        Holder h = new Holder(Holder.Tipo.CONFIRMAR);
        h.confirmar = pub.id();
        h.pagina = anterior.pagina;
        h.barato = anterior.barato;
        h.filtro = anterior.filtro;
        Inventory inv = Bukkit.createInventory(h, 27, a().menu("titulo-confirmar"));
        h.inv = inv;
        ItemStack relleno = boton(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 27; i++) inv.setItem(i, relleno);
        List<Component> extra = List.of(
                a().menu("lore-separador"),
                a().menu("lore-precio", "precio", Texto.dinero(pub.precio())),
                a().menu("lore-vendedor", "vendedor", pub.vendedorNombre()));
        inv.setItem(13, conLore(pub.item(), extra));
        inv.setItem(11, boton(Material.LIME_STAINED_GLASS_PANE, a().menu("boton-confirmar", "precio", Texto.dinero(pub.precio()))));
        inv.setItem(15, boton(Material.RED_STAINED_GLASS_PANE, a().menu("boton-cancelar")));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ Mis publicaciones

    void abrirMias(Player p) {
        if (!plugin.subastas().puedeUsarAqui(p)) return;
        List<Publicacion> lista;
        try {
            lista = plugin.db().delVendedor(p.getUniqueId());
        } catch (SQLException e) {
            error(p, e);
            return;
        }
        Holder h = new Holder(Holder.Tipo.MIAS);
        Inventory inv = Bukkit.createInventory(h, 54, a().menu("titulo-mias"));
        h.inv = inv;
        long ahora = System.currentTimeMillis();
        for (int i = 0; i < Math.min(POR_PAGINA, lista.size()); i++) {
            Publicacion pub = lista.get(i);
            List<Component> extra = new ArrayList<>();
            extra.add(a().menu("lore-separador"));
            extra.add(a().menu("lore-precio", "precio", Texto.dinero(pub.precio())));
            extra.add(a().menu("lore-expira", "tiempo", Texto.tiempo(pub.expira() - ahora)));
            extra.add(a().menu("lore-id", "id", String.valueOf(pub.id())));
            extra.add(a().menu("lore-cancelar"));
            inv.setItem(i, conLore(pub.item(), extra));
            h.ids[i] = pub.id();
        }
        if (lista.isEmpty()) inv.setItem(22, boton(Material.GRAY_DYE, a().menu("vacio")));
        barraInferior(inv);
        inv.setItem(49, boton(Material.ARROW, a().menu("boton-volver")));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ Buzón

    void abrirBuzon(Player p, int pagina) {
        if (!plugin.subastas().puedeUsarAqui(p)) return;
        List<Guardado> lista;
        try {
            lista = plugin.db().buzon(p.getUniqueId());
        } catch (SQLException e) {
            error(p, e);
            return;
        }
        int paginas = Math.max(1, (lista.size() + POR_PAGINA - 1) / POR_PAGINA);
        pagina = Math.max(0, Math.min(pagina, paginas - 1));
        Holder h = new Holder(Holder.Tipo.BUZON);
        h.pagina = pagina;
        Inventory inv = Bukkit.createInventory(h, 54, a().menu("titulo-buzon",
                "pagina", String.valueOf(pagina + 1), "paginas", String.valueOf(paginas)));
        h.inv = inv;
        for (int i = 0; i < POR_PAGINA; i++) {
            int idx = pagina * POR_PAGINA + i;
            if (idx >= lista.size()) break;
            Guardado g = lista.get(idx);
            List<Component> extra = List.of(
                    a().menu("lore-separador"),
                    a().menu("lore-motivo", "motivo", g.motivo()),
                    a().menu("lore-recoger"));
            inv.setItem(i, conLore(g.item(), extra));
            h.ids[i] = g.id();
        }
        if (lista.isEmpty()) inv.setItem(22, boton(Material.GRAY_DYE, a().menu("vacio")));
        barraInferior(inv);
        if (pagina > 0) inv.setItem(45, boton(Material.ARROW, a().menu("boton-anterior")));
        inv.setItem(49, boton(Material.ARROW, a().menu("boton-volver")));
        if (pagina < paginas - 1) inv.setItem(53, boton(Material.ARROW, a().menu("boton-siguiente")));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ Clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        // Se cancela TODO (también shift-clic desde tu inventario): nadie mete ni saca objetos de estos menús.
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        long id = slot >= 0 && slot < h.ids.length ? h.ids[slot] : 0;

        switch (h.tipo) {
            case PRINCIPAL -> {
                if (slot < POR_PAGINA && id != 0) {
                    if (e.isShiftClick() && e.isRightClick() && p.hasPermission("funkysubastas.admin")) {
                        luego(() -> {
                            plugin.subastas().quitarStaff(p, id);
                            abrirPrincipal(p, h.pagina, h.barato, h.filtro);
                        });
                        return;
                    }
                    luego(() -> {
                        try {
                            Publicacion pub = plugin.db().buscar(id);
                            if (pub == null || pub.expira() <= System.currentTimeMillis()) {
                                p.sendMessage(a().msg("ya-vendida"));
                                abrirPrincipal(p, h.pagina, h.barato, h.filtro);
                            } else if (pub.vendedor().equals(p.getUniqueId())) {
                                p.sendMessage(a().msg("propia"));
                            } else {
                                abrirConfirmar(p, pub, h);
                            }
                        } catch (SQLException ex) {
                            error(p, ex);
                        }
                    });
                    return;
                }
                switch (slot) {
                    case 45 -> luego(() -> abrirPrincipal(p, h.pagina - 1, h.barato, h.filtro));
                    case 53 -> luego(() -> abrirPrincipal(p, h.pagina + 1, h.barato, h.filtro));
                    case 47 -> luego(() -> abrirPrincipal(p, 0, !h.barato, h.filtro));
                    case 48 -> luego(() -> abrirBuzon(p, 0));
                    case 50 -> luego(() -> abrirMias(p));
                    case 51 -> luego(() -> abrirPrincipal(p, 0, h.barato, null));
                    case 49 -> luego(() -> {
                        p.closeInventory();
                        plugin.enviarAyuda(p);
                    });
                    default -> {
                    }
                }
            }
            case CONFIRMAR -> {
                if (slot == 11) {
                    luego(() -> {
                        p.closeInventory();
                        plugin.subastas().comprar(p, h.confirmar);
                    });
                } else if (slot == 15) {
                    luego(() -> abrirPrincipal(p, h.pagina, h.barato, h.filtro));
                }
            }
            case MIAS -> {
                if (slot < POR_PAGINA && id != 0) {
                    luego(() -> {
                        plugin.subastas().cancelar(p, id);
                        abrirMias(p);
                    });
                } else if (slot == 49) {
                    luego(() -> abrirPrincipal(p, 0, false, null));
                }
            }
            case BUZON -> {
                if (slot < POR_PAGINA && id != 0) {
                    luego(() -> {
                        plugin.subastas().recoger(p, id);
                        abrirBuzon(p, h.pagina);
                    });
                } else if (slot == 45) {
                    luego(() -> abrirBuzon(p, h.pagina - 1));
                } else if (slot == 53) {
                    luego(() -> abrirBuzon(p, h.pagina + 1));
                } else if (slot == 49) {
                    luego(() -> abrirPrincipal(p, 0, false, null));
                }
            }
        }
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ Ayudantes

    /** Abrir/cerrar menús dentro del clic da problemas: se hace en el siguiente tick. */
    private void luego(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void barraInferior(Inventory inv) {
        ItemStack relleno = boton(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 45; i < 54; i++) inv.setItem(i, relleno);
    }

    private static ItemStack boton(Material m, Component nombre, Component... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(nombre);
            if (lore.length > 0) meta.lore(List.of(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Copia del objeto con líneas extra al final de su descripción (el original no se toca). */
    private static ItemStack conLore(ItemStack original, List<Component> extra) {
        ItemStack it = original.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            List<Component> actual = meta.lore();
            if (actual != null) lore.addAll(actual);
            lore.addAll(extra);
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void error(Player p, SQLException e) {
        p.sendMessage(a().msg("error"));
        plugin.getLogger().log(Level.SEVERE, "Error de base de datos en un menú", e);
    }
}
