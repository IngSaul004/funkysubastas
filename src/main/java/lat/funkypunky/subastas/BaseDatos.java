package lat.funkypunky.subastas;

import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Todo lo guardado vive en plugins/FunkySubastas/subastas.db (SQLite).
 * Por qué SQLite y no YAML: cada cambio se guarda completo o no se guarda (un reinicio a la mitad
 * no deja datos a medias), y con transacciones "quitar de la subasta + poner en el buzón" es un solo paso.
 * Se usa siempre desde el hilo principal del servidor: así dos compras nunca ocurren al mismo tiempo.
 * NUNCA borrar ni reemplazar este archivo con una copia vieja (devolvería objetos ya vendidos).
 */
final class BaseDatos {

    record Publicacion(long id, UUID vendedor, String vendedorNombre, ItemStack item,
                       double precio, long creada, long expira) {
    }

    record Guardado(long id, UUID dueno, ItemStack item, String motivo, long fecha) {
    }

    @FunctionalInterface
    interface Accion {
        void run() throws SQLException;
    }

    private final File archivo;
    private final Logger log;
    private Connection con;

    BaseDatos(File archivo, Logger log) {
        this.archivo = archivo;
        this.log = log;
    }

    void abrir() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No se encontró el driver de SQLite (org.sqlite.JDBC).", e);
        }
        File carpeta = archivo.getParentFile();
        if (carpeta != null && !carpeta.exists() && !carpeta.mkdirs()) {
            throw new SQLException("No se pudo crear la carpeta " + carpeta);
        }
        con = DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
        try (Statement st = con.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS publicaciones ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, vendedor TEXT NOT NULL, vendedor_nombre TEXT NOT NULL, "
                    + "item BLOB NOT NULL, precio REAL NOT NULL, creada INTEGER NOT NULL, expira INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS buzon ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, dueno TEXT NOT NULL, item BLOB NOT NULL, "
                    + "motivo TEXT NOT NULL, fecha INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS avisos ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, dueno TEXT NOT NULL, mensaje TEXT NOT NULL, fecha INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pub_vendedor ON publicaciones(vendedor)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pub_expira ON publicaciones(expira)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_buzon_dueno ON buzon(dueno)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_avisos_dueno ON avisos(dueno)");
        }
    }

    void cerrar() {
        if (con == null) return;
        try {
            con.close();
        } catch (SQLException e) {
            log.warning("Error al cerrar la base de datos: " + e.getMessage());
        }
    }

    /** Ejecuta varias operaciones como una sola: o se guardan todas o ninguna. */
    void transaccion(Accion a) throws SQLException {
        boolean auto = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            a.run();
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(auto);
        }
    }

    // ---------------- Publicaciones ----------------

    long publicar(UUID vendedor, String nombre, ItemStack item, double precio, long creada, long expira) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO publicaciones (vendedor, vendedor_nombre, item, precio, creada, expira) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, vendedor.toString());
            ps.setString(2, nombre);
            ps.setBytes(3, item.serializeAsBytes());
            ps.setDouble(4, precio);
            ps.setLong(5, creada);
            ps.setLong(6, expira);
            ps.executeUpdate();
        }
        return ultimoId();
    }

    Publicacion buscar(long id) throws SQLException {
        List<Publicacion> l = publicaciones("SELECT * FROM publicaciones WHERE id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    List<Publicacion> activas(long ahora) throws SQLException {
        return publicaciones("SELECT * FROM publicaciones WHERE expira > ? ORDER BY creada DESC", ahora);
    }

    List<Publicacion> delVendedor(UUID vendedor) throws SQLException {
        return publicaciones("SELECT * FROM publicaciones WHERE vendedor = ? ORDER BY creada DESC", vendedor.toString());
    }

    int contarDelVendedor(UUID vendedor) throws SQLException {
        return contar("SELECT COUNT(*) FROM publicaciones WHERE vendedor = ?", vendedor.toString());
    }

    List<Publicacion> vencidas(long ahora) throws SQLException {
        return publicaciones("SELECT * FROM publicaciones WHERE expira <= ?", ahora);
    }

    List<Publicacion> todas() throws SQLException {
        return publicaciones("SELECT * FROM publicaciones");
    }

    int contarActivas(long ahora) throws SQLException {
        return contar("SELECT COUNT(*) FROM publicaciones WHERE expira > ?", ahora);
    }

    /** true solo si ESTA llamada la borró (si otra ya la había borrado, devuelve false). */
    boolean borrarPublicacion(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM publicaciones WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    // ---------------- Buzón ----------------

    void alBuzon(UUID dueno, ItemStack item, String motivo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO buzon (dueno, item, motivo, fecha) VALUES (?,?,?,?)")) {
            ps.setString(1, dueno.toString());
            ps.setBytes(2, item.serializeAsBytes());
            ps.setString(3, motivo);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    List<Guardado> buzon(UUID dueno) throws SQLException {
        List<Guardado> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM buzon WHERE dueno = ? ORDER BY fecha ASC")) {
            ps.setString(1, dueno.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Guardado g = leerGuardado(rs);
                    if (g != null) out.add(g);
                }
            }
        }
        return out;
    }

    Guardado buscarBuzon(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM buzon WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leerGuardado(rs) : null;
            }
        }
    }

    int contarBuzon(UUID dueno) throws SQLException {
        return contar("SELECT COUNT(*) FROM buzon WHERE dueno = ?", dueno.toString());
    }

    int contarBuzonTotal() throws SQLException {
        return contar("SELECT COUNT(*) FROM buzon");
    }

    boolean sacarDelBuzon(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM buzon WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    // ---------------- Avisos (para quien estaba desconectado) ----------------

    void aviso(UUID dueno, String mensaje) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO avisos (dueno, mensaje, fecha) VALUES (?,?,?)")) {
            ps.setString(1, dueno.toString());
            ps.setString(2, mensaje);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    List<String> tomarAvisos(UUID dueno) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT mensaje FROM avisos WHERE dueno = ? ORDER BY fecha ASC")) {
            ps.setString(1, dueno.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        if (!out.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM avisos WHERE dueno = ?")) {
                ps.setString(1, dueno.toString());
                ps.executeUpdate();
            }
        }
        return out;
    }

    // ---------------- Ayudantes ----------------

    private long ultimoId() throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private int contar(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            poner(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private List<Publicacion> publicaciones(String sql, Object... params) throws SQLException {
        List<Publicacion> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            poner(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    ItemStack it = leerItem(rs.getBytes("item"), "publicación #" + id);
                    if (it == null) continue;
                    out.add(new Publicacion(id, UUID.fromString(rs.getString("vendedor")), rs.getString("vendedor_nombre"),
                            it, rs.getDouble("precio"), rs.getLong("creada"), rs.getLong("expira")));
                }
            }
        }
        return out;
    }

    private Guardado leerGuardado(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        ItemStack it = leerItem(rs.getBytes("item"), "buzón #" + id);
        if (it == null) return null;
        return new Guardado(id, UUID.fromString(rs.getString("dueno")), it, rs.getString("motivo"), rs.getLong("fecha"));
    }

    private static void poner(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    /** Si un objeto no se puede leer (archivo dañado), se avisa en consola y se salta: nunca se borra. */
    private ItemStack leerItem(byte[] datos, String donde) {
        try {
            return ItemStack.deserializeBytes(datos);
        } catch (Exception e) {
            log.warning("No se pudo leer el objeto de " + donde + ": " + e.getMessage());
            return null;
        }
    }
}
