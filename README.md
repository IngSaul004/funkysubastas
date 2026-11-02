# FunkySubastas

Casa de subastas de **FUNKY PUNKY MC** (Paper 26.3, Java 25). Precio fijo, buzón, comisiones que salen de la economía.

## Compilar
Se compila solo con GitHub Actions al subir cambios (`.github/workflows/compilar.yml`). El `.jar` sale en **Actions → la ejecución → Artifacts → FunkySubastas**.

## Instalar
1. Quitar zAuctionHouse (nunca dos plugins con `/ah`).
2. Copiar `FunkySubastas-x.y.z.jar` a `plugins/` y reiniciar.
3. La primera vez el servidor descarga el driver de SQLite (`plugin.yml` → `libraries`).
4. Permisos: `funkysubastas.usar` (todos, por defecto) y `funkysubastas.admin` (staff, por LuckPerms).

## Archivos en `plugins/FunkySubastas/`
- `config.yml`: límites, comisiones, mundos bloqueados, textos. Cambios con `/subasta recargar`.
- `subastas.db`: publicaciones, buzones y avisos. **Nunca borrarlo ni pisarlo con una copia vieja** (devolvería objetos ya vendidos).
- `registro.log`: cada movimiento (PUBLICO, COMPRO, CANCELO, VENCIO, RECOGIO, STAFF_QUITO, TEMPORADA, ERROR_PAGO).

## Comandos
| Comando | Qué hace |
| --- | --- |
| `/subasta` (`/ah`) | Abrir la subasta |
| `/subasta vender <precio>` | Publicar lo que tienes en la mano (`250`, `1,500`, `1.5k`) |
| `/subasta mias` | Tus publicaciones (clic = cancelar) |
| `/subasta buzon` | Recoger lo vencido, cancelado o que no te cupo |
| `/subasta buscar <texto>` | Filtrar por nombre del objeto (inglés), nombre personalizado o vendedor |
| `/subasta quitar <id>` | Staff: retirar una publicación (va al buzón del dueño) |
| `/subasta info` · `recargar` | Staff |
| `/subasta devolver-todo confirmar` | Staff: fin de temporada |

## Reglas de diseño
- Bloqueada en el lobby (inventario aparte) y no se publica en creativo.
- 3 publicaciones por jugador para todos (los rangos son solo cosméticos).
- Comisión de publicar y porcentaje de venta se destruyen (sumidero).
- Nada se tira al suelo ni se borra: lo que no cabe va al buzón.
- Toda compra revisa la base de datos justo antes de cobrar: un menú viejo no puede duplicar nada.
