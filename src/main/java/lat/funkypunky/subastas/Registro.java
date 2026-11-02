package lat.funkypunky.subastas;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.time.ZoneId;
import java.util.logging.Logger;

/** registro.log: una línea por cada movimiento (publicó, compró, canceló, venció, staff...). */
final class Registro {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File archivo;
    private final Supplier<ZoneId> zona;
    private final Logger log;

    Registro(File archivo, Supplier<ZoneId> zona, Logger log) {
        this.archivo = archivo;
        this.zona = zona;
        this.log = log;
    }

    synchronized void anotar(String accion, String detalle) {
        String linea = FORMATO.format(ZonedDateTime.now(zona.get())) + " | " + accion + " | " + detalle;
        try (PrintWriter w = new PrintWriter(new FileWriter(archivo, StandardCharsets.UTF_8, true))) {
            w.println(linea);
        } catch (IOException e) {
            log.warning("No se pudo escribir registro.log: " + e.getMessage());
        }
    }
}
