# Rodet

App Android para gestionar bobinas de filamento 3D: inventario con lo que le queda
a cada bobina, coste de cada impresión (filamento y luz), estadísticas de qué gastas
más y qué volver a pedir, y tags NFC del ecosistema de tu impresora a partir de una
foto de la etiqueta. El plan original está en [PLAN.md](PLAN.md).

Se distribuye por [DracApps](https://marcmayol.com/DracApps/); página:
<https://marcmayol.com/rodet/>.

## Estado

| Fase | Estado |
|---|---|
| 1. Núcleo (`:core:model`, `:core:extract`, `:core:tags`) | ✅ tests en verde (los vectores reales de Creality, solo con claves) |
| Flujo principal: máquina → foto → revisión → grabar | ✅ hecho; Creality CFS verificado en una K2 |
| 2. Inventario, calculadora de coste y estadísticas (`:core:inventory`) | ✅ v0.2.0 |
| 5. Etiquetas imprimibles | pendiente |
| 6. Pulido | pendiente |

### Inventario, coste y estadísticas (`:core:inventory`, `app/.../inventory`)

- **Bobinas**: alta a mano (el mismo editor que el flujo del tag), con foto o
  duplicando; precio, tienda, fecha y tara. Lo que queda se sabe **pesándola**
  (bruto − tara), **descontando cada impresión** o **a mano**. Acercar el tag de
  una bobina al móvil abre su ficha.
- **Impresiones**: la calculadora abre un `.gcode` o `.gcode.3mf` (Creality Print,
  Orca, Bambu, PrusaSlicer, Cura, Simplify3D) y saca gramos por filamento, colores
  y tiempo; empareja cada filamento con la bobina más parecida (mismo material,
  color más cercano, la que está en uso). Coste = filamento + luz (W × h × €/kWh);
  desgaste, fallos, mano de obra y beneficio son opcionales. Guardar descuenta de
  las bobinas y borrar del historial lo devuelve.
- **Estadísticas**: gasto por tipo de filamento, por color y por mes, y «qué
  volver a pedir»: el ritmo de los últimos 90 días contra el stock que queda.
- Todo en `inventory.json` (privado, escritura atómica) y exportable/importable
  en JSON. Sin red, salvo la búsqueda de actualizaciones.

### Publicar

```
python scripts/publicar_release.py --notas "…"   # sube la versión de gradle.properties
```

Firma con `keystore.properties` (fuera de git). El módulo `:actualizador` es el de
Faltis; Google Play no admite `REQUEST_INSTALL_PACKAGES` en una app así, y la versión
de Play tendrá que ir sin él.

### El flujo del tag (`app/.../flow`)

1. **Máquina**: se elige una vez y se guarda (`Machine` en `core:tags`). Dice
   qué pegatina hace falta. Creality pide sus claves aquí si no las tiene.
2. **Foto**: CameraX con marco guía (se recorta a lo que hay dentro), linterna,
   galería o «a mano». OCR con ML Kit dentro del APK, sin conexión.
3. **Revisión**: `LabelExtractor` rellena; lo dudoso sale enmarcado, cada campo
   dice de dónde sale, cuentagotas sobre la foto, y antes de pedir la pegatina
   se codifica en seco (`preflight`) para no descubrir el error con el tag en la mano.
4. **Grabar**: comprueba el tipo de chip, no pisa un tag con datos sin
   preguntar, escribe, relee byte a byte y lo decodifica.

El borrador (paso, datos, foto) sobrevive a que Android mate la app. El lector
de tags de depuración sigue accesible desde «Leer un tag».

```
./gradlew test
```

Los módulos `core` son Kotlin/JVM puro. Un test recorre sus fuentes y falla si
alguno importa `android.*`.

## Diferencias con el plan

Salieron al contrastar los formatos con sus especificaciones y con el código
fuente de referencia (OpenSpool, OpenPrintTag, DnG-Crafts, OpenRFID):

1. **OpenPrintTag exige NFC-V (ICODE SLIX2), no NTAG.** Un lector de Prusa no
   lee un NTAG. El perfil genera la memoria completa de un SLIX2 de 304 B, con
   la misma estructura que `sample_tag.bin` de la especificación.
2. **CBOR: enteros siempre que la especificación lo permite** (diámetro en µm,
   secado en minutos). La densidad la define OpenPrintTag como número con
   decimales; va como float en su forma más corta, que también es determinista.
   La trampa 10 se cumple: dos escrituras iguales dan bytes idénticos.
3. **QIDI no es una variante de Anycubic:** usa MIFARE Classic con códigos del
   cfg oficial de QIDI (material 1-50, color 1-24). Elegoo tiene su propio
   layout. Son tres perfiles separados.
4. **Creality CFS necesita dos claves AES**, una para derivar la clave de sector
   desde el UID y otra para el payload. No están en el repositorio: las pone la
   persona, y la app comprueba su SHA-256 (los que publica OpenRFID) antes de
   escribir, para no gastar un tag con una clave mal copiada. Los códigos de
   material son los 73 `filament_id` de Creality Print 7.2; a un filamento que
   no es de Creality le toca su código genérico (`00001` PLA, `00003` PETG…).
5. `provenance` usa como clave el enum `Field` (con clave estable `key` para
   persistir) en lugar de `String`. Se añade `Source.TAG` para lo leído de un tag.
6. Versiones: las de LunAlign (Kotlin 2.1.21, JUnit 5.11.3), no las de 2024 del
   plan.

## Creality: contrastado con un tag de fábrica

El perfil se ha probado contra el volcado de un Hyper PLA de fábrica (carpeta
`test/tags/Creality` de OpenRFID): la clave de sector derivada coincide con la
del trailer real y el payload se descifra con los campos esperados.
Además se ha leído con el Pixel un tag propio (Hyper PLA negro de 1 kg, UID
`70B4276B`), y está como segundo vector en los tests. Ojo: su trailer lleva la
clave derivada **también como clave B** y bits de acceso `FF0780`, no la clave B
por defecto del volcado de OpenRFID. El test
necesita las claves reales, así que solo se ejecuta si se pasan por entorno:

```
RODET_CREALITY_KEYS=<derivación hex>:<payload hex> ./gradlew :core:tags:test
```

Verificado el 30-sep-2026: la K2 lee tanto un tag clonado como uno generado por
Rodet (PLA genérico #FF1E1E, ranura 1D).

## Lector de tags (depuración)

`:app` es por ahora una sola pantalla que lee cualquier tag (NDEF, MIFARE
Classic, NTAG, NFC-V), dice qué formato reconoce y muestra el volcado en hex.
No escribe nada. Las claves de Creality solo entran en el build `debug`, desde
`local.properties`:

```
creality.derivationKey=<hex>
creality.payloadKey=<hex>
```

```
./gradlew :app:installDebug
adb logcat -s RodetReader     # volcado completo de cada lectura
```

## Pendiente de verificar con hardware

- Todos los perfiles salvo OpenSpool y Creality CFS están marcados
  `verified = false` y avisan antes de escribir.
- Anycubic: el SKU (páginas 5-9) se escribe vacío; no se sabe si el ACE lo usa.
- Elegoo: las páginas 22 y 24 no están descifradas; se replican las del escritor
  de referencia.
- OpenPrintTag: falta la clave 28 (`tags`, aditivos como CF), porque su enum no
  se ha contrastado.
- Bambu: leer sus tags no está implementado; escribirlos es imposible por diseño.
- El Pixel 8 Pro del autor tiene `com.nxp.mifare`: puede probar QIDI y Creality.

## Atribuciones

- `core/tags/src/test/resources/openprinttag/sample_tag.bin`: especificación
  OpenPrintTag, licencia MIT.
- Layouts propietarios: ingeniería inversa de
  [DnG-Crafts](https://github.com/DnG-Crafts) y
  [OpenRFID](https://github.com/suchmememanyskill/OpenRFID).
