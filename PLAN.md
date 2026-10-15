# App Android de gestión de filamento 3D — plan de ruta

> Documento de encargo para pasar a Claude Code.
> Describe **qué** construir y **por qué** cada decisión, no solo la lista de tareas.
>
> Objetivo de la app: hacer una foto a la etiqueta de una bobina de filamento,
> extraer los datos, **poder revisarlos o escribirlos a mano**, grabar el tag
> NFC/RFID del ecosistema correspondiente y generar una etiqueta imprimible.

---

## 1. Decisión de partida: Android nativo, no PWA

|  | OCR | NDEF (OpenSpool / OpenPrintTag) | MIFARE Classic (Creality CFS) |
|---|---|---|---|
| **Android nativo** | ML Kit on-device | ✅ | ✅ (con matiz, §4.1) |
| iOS nativo | Vision | ✅ Core NFC | ❌ Apple no expone MIFARE Classic |
| Web / PWA | Tesseract.js | ⚠️ Web NFC solo en Chrome Android | ❌ Web NFC solo habla NDEF |

Como el alcance incluye **Creality CFS**, Android nativo es la única vía que lo
cubre todo. Si el alcance se recortara a los estándares abiertos (OpenSpool /
OpenPrintTag sobre pegatinas NTAG), una PWA costaría una fracción del esfuerzo.

**Stack:** Kotlin · Jetpack Compose + Material 3 · MVVM con StateFlow · Hilt ·
Room · CameraX · ML Kit Text Recognition v2 (bundled, on-device) · ZXing solo
para el codificador de QR. `minSdk 26`, `targetSdk 35`.

---

## 2. Alcance

### Dentro
- OCR on-device de la etiqueta, sin red.
- Extracción estructurada con **procedencia y confianza por campo**.
- **Entrada y edición manual completa** (requisito de primer nivel, §3).
- Escritura de tags: OpenSpool, OpenPrintTag, Anycubic ACE Pro, QIDI Box,
  Elegoo Canvas, Creality CFS.
- Lectura de tags existentes.
- Etiqueta imprimible (PDF / PNG / SVG) con QR.
- Inventario local con búsqueda y consumo estimado.

### Fuera, explícitamente
- **Escribir tags de Bambu Lab.** Llevan una firma criptográfica que el AMS
  valida contra una clave pública del firmware. No se pueden fabricar tags
  nuevos y válidos, y clonar uno existente solo duplica su identidad. No es un
  problema de esfuerzo: no se puede. La app debe *explicarlo* cuando alguien lo
  intente y ofrecer OpenSpool como alternativa.
- Cuentas de usuario, backend, sincronización en la nube. Todo local.
- Integración con Spoolman / Klipper / OctoPrint. Candidato a v2.

### Estado real de cada formato de tag

| Formato | Escribir | Qué falta |
|---|---|---|
| **OpenSpool** | ✅ | Nada. Spec pública, NDEF + JSON sobre NTAG215 |
| **OpenPrintTag** | ⚠️ | Forma clara (NDEF + CBOR); falta fijar los IDs de campo contra `openprinttag.org` |
| **Anycubic ACE / QIDI / Elegoo** | ⚠️ | Falta contrastar offsets contra un volcado real |
| **Creality CFS** | ⚠️ | Falta la clave AES-128 y los offsets (de K2-RFID / CFSWriter) |
| **Bambu Lab** | ❌ | Firma criptográfica no falsificable |

> **Regla innegociable:** cualquier perfil no verificado **avisa antes de
> escribir**, con la razón concreta. Escribir bytes con offsets equivocados
> gasta la pegatina y la persona no sabrá por qué no funciona.

---

## 3. Entrada manual — requisito de primer nivel

**La foto es un atajo, no el único camino.** Hay etiquetas borradas, bobinas
reetiquetadas, filamento a granel y marcas que no imprimen las temperaturas. La
entrada manual tiene que ser tan cómoda como la foto, no un formulario de
castigo escondido tras un menú.

### 3.1 Tres puntos de entrada, un solo editor

1. **Desde foto** → el OCR rellena, la persona revisa.
2. **Desde cero** → botón visible en la pantalla principal, formulario vacío
   con valores por defecto del material.
3. **Duplicar** una bobina del inventario → es el caso **más frecuente de
   todos** (la segunda bobina del mismo lote) y ahorra más tiempo que el OCR.

> **Nunca se escribe un tag sin pasar por el editor.** Un tag mal escrito gasta
> la pegatina; un segundo de revisión lo evita.

### 3.2 Procedencia visible por campo

| Procedencia | Indicador | Significado |
|---|---|---|
| `manual` | sin marca | lo ha puesto la persona, es la verdad |
| `ocr` conf ≥ 0.8 | punto verde | leído con claridad |
| `ocr` conf < 0.8 | punto ámbar + subrayado | leído con dudas, **revísalo** |
| `default` | gris, cursiva | deducido del material, no leído |
| `catalog` | punto azul | derivado del catálogo (p. ej. hex desde el nombre) |

Tocar el campo muestra la **evidencia**: el fragmento de texto del que salió.
Editarlo lo pasa a `manual` con confianza 1.0 y quita la marca.

### 3.3 Campos del editor

```
Material        desplegable del catálogo + "Otro…" (texto libre)
Subtipo         chips: Silk, Matte, Glow, Wood, CF, GF, HF, Transparent…
Marca           autocompletado sobre el catálogo, admite valores nuevos
Color           ver §3.4
Diámetro        segmentado: 1.75 / 2.85 / 3.00 / otro
Peso neto       segmentado: 250 / 500 / 750 / 1000 / 2000 / otro  (gramos)
Peso bobina     opcional, para calcular el restante al pesarla
Temp. boquilla  min/max con slider de rango
Temp. cama      min/max
Secado          temperatura + horas, opcional
Densidad        prerrellenada desde el material
Lote / serie    opcional
Notas           texto libre
```

> **La regla que separa un formulario útil de uno irritante:** al elegir
> material se rellenan los campos vacíos o marcados como `default` con los
> valores típicos de ese material, pero **nunca se pisa lo que la persona ya
> tocó a mano**.

### 3.4 Selector de color

El color es el campo que más se resiste al OCR y el que más importa en la
etiqueta. Cuatro vías, todas disponibles:

1. **Paleta del catálogo** con nombres en español e inglés.
2. **Cuentagotas sobre la foto** — tocar la bobina para muestrear el color
   real. Promediar un área de **9×9 px**, no un píxel suelto, para no coger un
   brillo especular.
3. **Código hex** con validación y vista previa.
4. **Rueda HSV** estándar.

Guardar siempre `colorHex`. `colorName` es texto libre y se conserva tal cual:
`"Fire Engine Red"` es mejor etiqueta que `"Red"`.

### 3.5 Validación

- **Bloqueante** (impide escribir el tag): falta material; falta color si el
  perfil lo exige; valores fuera de rango físico.
- **Advertencia** (deja continuar): temperatura incoherente con el material (un
  PLA a 260 °C), diámetro no nominal, peso inverosímil.

Las advertencias van en línea bajo el campo, **nunca en un diálogo modal**.

### 3.6 Presets
- Guardar la bobina actual como plantilla con nombre.
- Los 5 últimos materiales usados como accesos rápidos.
- Exportar / importar el inventario en JSON, para no dejar a nadie atrapado.

---

## 4. NFC

### 4.1 La restricción que hay que verificar antes de prometer nada

**MIFARE Classic no es NFC estándar.** Es un formato propietario de NXP y solo
lo soportan los teléfonos cuyo controlador NFC es de NXP. En móviles con
controlador de Broadcom o Qualcomm, `MifareClassic.get(tag)` devuelve `null`
aunque el NFC funcione perfectamente para todo lo demás.

Afecta a **Creality CFS** (y a leer Bambu). Detectarlo **en arranque**, no al
escribir:

```kotlin
val soportaMifareClassic = packageManager.hasSystemFeature("com.nxp.mifare")
```

Si es `false`, los perfiles que lo requieren aparecen **deshabilitados con una
explicación** ("tu teléfono no puede escribir este tipo de tag"), no fallando a
mitad de la escritura. Los estándares abiertos sobre NTAG siguen funcionando en
cualquier móvil con NFC.

### 4.2 Reader mode, no foreground dispatch

Más predecible, y evita el lío de `PendingIntent` y sus flags de mutabilidad en
Android 12+:

```kotlin
nfcAdapter.enableReaderMode(
    activity,
    { tag -> viewModel.onTagDiscovered(tag) },
    NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or   // lo necesita MIFARE Classic
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
    Bundle().apply {
        putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
    },
)
```

Activarlo solo mientras la pantalla de escritura está en primer plano
(`DisposableEffect` + `LifecycleEventObserver`) y desactivarlo al salir.

### 4.3 Escribir NDEF (OpenSpool, OpenPrintTag)

```kotlin
// Pegatina ya formateada
Ndef.get(tag)?.use { ndef ->
    require(ndef.isWritable) { "el tag es de solo lectura" }
    require(ndef.maxSize >= message.byteArrayLength) {
        "el tag tiene ${ndef.maxSize} B y el mensaje ocupa ${message.byteArrayLength} B"
    }
    ndef.connect()
    ndef.writeNdefMessage(message)
}

// Pegatina virgen de fábrica
NdefFormatable.get(tag)?.use { fmt ->
    fmt.connect()
    fmt.format(message)
}
```

Hay que manejar **los dos casos**: una pegatina NTAG recién comprada puede venir
formateada o no, y solo uno de los dos objetos será no nulo.

### 4.4 Escribir MIFARE Classic (Creality CFS)

Aquí el riesgo es real:

- **Nunca escribir el bloque 0** (datos de fabricante) ni los **trailers de
  sector** (bloques 3, 7, 11…), que contienen las claves de acceso. Un trailer
  con datos basura **inutiliza el sector para siempre**.
- Autenticar sector a sector con `authenticateSectorWithKeyA`.
- Verificar leyendo después de escribir.

```kotlin
MifareClassic.get(tag)?.use { mfc ->
    mfc.connect()
    for ((block, data) in write.sectors) {          // data: exactamente 16 bytes
        val sector = mfc.blockToSector(block)
        check(mfc.authenticateSectorWithKeyA(sector, key)) {
            "no se pudo autenticar el sector $sector"
        }
        mfc.writeBlock(block, data)
    }
}
```

La **clave AES del payload de Creality** no se inventa: va en un campo de
ajustes avanzados que la persona rellena, sacándola de los proyectos que ya
hicieron la ingeniería inversa (K2-RFID, CFSWriter). Sin clave **no se escribe**
— es preferible no escribir a escribir algo que el CFS rechace.

### 4.5 Manifest

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.CAMERA" />

<!-- a propósito NO obligatorios: sin NFC la app sirve igual
     para el inventario y las etiquetas impresas -->
<uses-feature android:name="android.hardware.nfc"    android:required="false" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

---

## 5. OCR: las trampas que de verdad cuestan

El reconocimiento de texto es la parte fácil. Lo que muerde es la
**normalización**. Cada punto de esta lista es un fallo real, comprobado sobre
etiquetas de eSUN, Smartfil, Bambu, Prusament y SUNLU. Cada uno merece su test
de regresión desde el primer día.

1. **Los símbolos de grado hay que resolverlos ANTES de normalizar a NFKC.**
   NFKC convierte `º` en `o`, y luego un reparador numérico lee esa `o` como
   `0`: **`75ºC` acaba valiendo 750**.
2. **Un `0` pegado a un número no es un símbolo de grado.**
   `Bed Temp: 60 C` valía **6 grados**. Hace falta un *lookbehind* que excluya
   dígitos.
3. **Las temperaturas se asignan por la pista MÁS CERCANA al número**, no por
   la más frecuente en la ventana de contexto. En
   `Print Temp: 205-225 C  Bed Temp: 60 C` los 60 son de la cama.
4. **Temperaturas sin unidad.** `Nozzle 190-230` / `Bed 35-45` son habituales:
   hace falta una segunda pasada que busque números justo tras una pista.
5. **`\bno` encuentra el "No" de "Nozzle"** y captura `zzle` como número de
   lote. Exigir el punto (`no\.`). Y exigir que un lote contenga algún dígito.
6. **`1.75mm` no se detecta si exiges frontera de palabra al final** — entre el
   `5` y la `m` no hay ninguna. Es el caso más común que existe.
7. **Un entero suelto no es un diámetro.** `PLA 3 colores` no son 3 mm. Exigir
   unidad, símbolo `Ø` o forma decimal.
8. **`PIA` → `PLA`, `Nozz1e` → `nozzle`.** La distancia de edición no llega
   (`pia`/`pla` da 0.67). Lo que funciona es **colapsar las clases de
   caracteres que el OCR intercambia**: `i`/`l`/`1`/`|`, `0`/`o`, `5`/`S`,
   `8`/`B`.
9. **El plegado de acentos debe preservar la longitud del texto.** Con
   descomposición NFD los índices dejan de corresponder con el original y la
   evidencia citada apunta a otro sitio. Tabla de traducción carácter a
   carácter, no `Normalizer`.
10. **CBOR sin `float`.** Todo en enteros escalados (micras, densidad ×1000)
    para que dos escrituras del mismo filamento den bytes idénticos.

### Por qué reglas y no un modelo entrenado

La etiqueta de una bobina no es texto libre: es un formulario con **vocabulario
cerrado** (≈16 materiales, ≈50 marcas, ≈90 colores) y unidades muy marcadas.
Con esa estructura, reglas + *fuzzy matching* baten a cualquier modelo
entrenable con los pocos datos etiquetados que se reúnen en casa, y además son
**inspeccionables**: cada campo sale con su procedencia y su confianza, que es
justo lo que la UI necesita para pintar en verde lo leído y en ámbar lo
dudoso.

---

## 6. Arquitectura

### 6.1 Módulos Gradle

Los `core:*` son **Kotlin/JVM puros, sin dependencias de Android**. Es
deliberado: ahí vive el 80% del riesgo, y así se testea en JVM en milisegundos
sin emulador.

```
:app                  navegación, DI, tema, Application
:core:model           FilamentSpec, Provenance, catálogo   [JVM puro]
:core:extract         texto OCR -> FilamentSpec            [JVM puro]
:core:tags            NDEF, CBOR, layouts, perfiles        [JVM puro]
:core:label           render de etiqueta + QR              [JVM puro]
:data                 Room, repositorios, DataStore
:feature:capture      CameraX + ML Kit
:feature:editor       formulario manual y de revisión
:feature:write        escritura NFC
:feature:inventory    listado, detalle, búsqueda
```

Dependencias: `feature:*` → `core:*` y `:data`. Ningún `core` depende de un
`feature`. **Ningún `core` importa `android.*`.**

### 6.2 Modelo central

Un único formato canónico por el que pasa todo — OCR, catálogo, entrada
manual — y del que salen tanto los tags como las etiquetas:

```kotlin
data class FilamentSpec(
    val material: String = "",            // PLA, PETG, ABS, ASA, TPU, PA, PC…
    val subtype: String = "",             // Silk, Matte, CF, GF, Glow…
    val brand: String = "",
    val colorName: String = "",
    val colorHex: String = "",            // RRGGBB, sin '#'
    val diameterMm: Double? = null,
    val netWeightG: Int? = null,
    val spoolWeightG: Int? = null,
    val densityGCm3: Double? = null,
    val nozzleTempMin: Int? = null, val nozzleTempMax: Int? = null,
    val bedTempMin: Int? = null,    val bedTempMax: Int? = null,
    val dryTempC: Int? = null,      val dryTimeH: Int? = null,
    val serial: String = "", val lot: String = "", val notes: String = "",
    val provenance: Map<String, Provenance> = emptyMap(),
)

data class Provenance(
    val source: Source,        // OCR, DEFAULT, MANUAL, CATALOG, DERIVED
    val confidence: Double,    // 0.0 – 1.0
    val evidence: String = "", // fragmento de texto que lo justifica
)
```

Normalización obligatoria: ajustar diámetro y peso a los **valores nominales**
del mercado (1.75 / 2.85 / 3.00 mm; 250 / 500 / 750 / 1000 / 2000 g) dentro de
una tolerancia, y corregir rangos invertidos (un guion mal leído).

Longitud estimada = `masa / (densidad · sección)`. Sirve de comprobación:
1 kg de PLA de 1.75 mm son ≈335 m. Si sale otra cosa, algún dato está mal.

### 6.3 Perfiles de tag

Un perfil traduce un `FilamentSpec` al binario de su ecosistema y **declara si
está verificado**:

```kotlin
interface TagProfile {
    val name: String
    val tagType: String            // NTAG215, MIFARE Classic 1K…
    val verified: Boolean          // false = layout sin contrastar, avisar
    val reference: String          // de dónde sale el layout
    fun encode(spec: FilamentSpec): TagWrite
}
```

Los layouts binarios de los formatos propietarios se describen como **tabla de
campos** (nombre, offset, longitud, codificación, escala), no como código. Así,
cuando alguien contraste los offsets contra un tag real, corregir el perfil es
**mover un número en una tabla**.

---

## 7. Etiquetas e impresión

No generar SVG para rasterizarlo después: hace falta una librería extra y se
pierde control tipográfico. En su lugar, `core:label` expone un **modelo de la
etiqueta** (cajas, textos, tamaños en milímetros) y Android lo dibuja:

- **Vista previa** → `Canvas` de Compose.
- **Impresión / PDF** → `PdfDocument` (1 mm = 2.8346 pt) + `PrintManager` con
  `PrintDocumentAdapter`, que ya habla con cualquier impresora que el sistema
  conozca.
- **Compartir** → PNG a alta resolución vía `FileProvider`.
- **SVG** → solo como exportación.

Formatos: Brother 62×29 y 62×100, Dymo 54×25, cuadrada 50 mm, y hoja A4 en
rejilla 3×8.

Detalles que se notan al imprimir:
- **Fusionar los módulos contiguos del QR** en un solo rectángulo por tirada:
  evita las líneas blancas del antialiasing y reduce mucho el número de nodos.
- **Ajustar el cuerpo de letra antes que truncar** el texto: bajar un 10% se
  nota mucho menos que perder el final de la línea.
- Muestra de color con **borde si es muy clara**, o no se ve sobre papel blanco.
- En la línea de temperaturas usar `Noz` / `Cama`, no `N` / `C`: una `C` suelta
  junto a un número se lee como Celsius.

SDK propietario de Brother/Dymo: **fuera de la v1**. El flujo de impresión del
sistema ya funciona con ellas.

---

## 8. Persistencia

```kotlin
@Entity(tableName = "spools")
data class SpoolEntity(
    @PrimaryKey val id: String,          // UUID
    val material: String, val subtype: String, val brand: String,
    val colorName: String, val colorHex: String,
    val diameterMm: Double?, val netWeightG: Int?, val spoolWeightG: Int?,
    val remainingG: Int?,                // se actualiza al pesar la bobina
    val densityGCm3: Double?,
    val nozzleTempMin: Int?, val nozzleTempMax: Int?,
    val bedTempMin: Int?,    val bedTempMax: Int?,
    val dryTempC: Int?,      val dryTimeH: Int?,
    val serial: String, val lot: String, val notes: String,
    val provenanceJson: String,
    val photoUri: String?,
    val tagUid: String?,                 // UID del tag escrito
    val createdAt: Long, val updatedAt: Long,
)
```

`tagUid` permite el mejor detalle de UX de toda la app: **acercar el móvil a una
bobina ya etiquetada y que aparezca su ficha al instante**.

Guardar la foto original en el almacenamiento de la app: si mañana mejora el
extractor, se puede reprocesar sin volver a fotografiar.

---

## 9. Navegación

```
Inicio ──┬─► Cámara ──► Recorte/ROI ──► OCR ──┐
         │                                     ├─► EDITOR ──► Perfil ──► Escribir NFC ──► Hecho
         ├─► "Añadir a mano" ──────────────────┤              │
         │                                     │              └──► Etiqueta ──► Imprimir/Compartir
         ├─► Inventario ──► Detalle ──► Duplicar
         └─► Leer tag (NFC) ──► Detalle
```

- **Recorte/ROI:** ajustar el recuadro antes del OCR mejora muchísimo el
  resultado, sobre todo en cajas con texto de marketing alrededor.
- **Editor:** es la pantalla central de la app. Merece el mayor esfuerzo de
  diseño de todas.
- **Escribir NFC:** estados explícitos (buscando → detectado → autenticando →
  escribiendo → verificando → hecho). Los errores dicen *qué* falló y *qué hacer*.
- Tema claro/oscuro y textos en español e inglés desde el día uno.

---

## 10. Fases

Cada fase termina con algo verificable. No pasar a la siguiente sin los tests de
la anterior en verde.

| Fase | Qué | Criterio de aceptación |
|---|---|---|
| **1. Núcleo** | `core:model`, `core:extract`, `core:tags` en Kotlin/JVM puro. Modelo, catálogo, extractor con las 10 trampas de §5, NDEF, CBOR canónico, layouts, perfiles | Batería de tests del extractor y los perfiles en verde. **Sin UI** |
| **2. Entrada manual** | `:data` con Room, `:feature:editor`, `:feature:inventory`. Crear a mano, guardar, listar, duplicar. Selector de color sin cuentagotas | **La app ya es útil sin cámara ni NFC** |
| **3. NFC** | `:feature:write`. Detección de capacidades (§4.1). OpenSpool primero (el único verificado), luego el resto con su aviso. Leer tags y abrir ficha por `tagUid` | Escribir un NTAG215 con OpenSpool y que lo lea otra app |
| **4. Cámara + OCR** | `:feature:capture` con CameraX + ML Kit, recorte de ROI, volcado al editor con procedencia pintada, cuentagotas de color | Etiquetas reales fotografiadas se extraen con la precisión esperada |
| **5. Etiquetas** | `core:label`, vista previa, PDF, impresión del sistema, compartir PNG, hoja A4 | Impresa al 100% mide **exactamente 62×29 mm** |
| **6. Pulido** | Export/import JSON, presets, accesibilidad (TalkBack, tamaños de fuente), animaciones, onboarding | — |

> El orden no es casual: **la fase 2 entrega valor antes que el OCR**. Si el
> proyecto se parara ahí, ya tendrías una app usable.

---

## 11. Testing

| Nivel | Qué | Dónde |
|---|---|---|
| Unidad JVM | Extractor, perfiles, CBOR, NDEF, layouts | `core:*/src/test` |
| Room | Migraciones y DAOs | `:data/src/androidTest` |
| Compose UI | Editor: autorrelleno, validación, procedencia | `:feature:editor/src/androidTest` |
| NFC | Con un *fake*, ver nota | `:feature:write/src/test` |

> **`android.nfc.Tag` es `final` y no se puede construir en un test.** Hay que
> envolver el acceso a NFC tras una interfaz propia (`TagWriter`) **desde el
> principio**; si no, esa capa acaba siendo imposible de probar.

Casos que no se pueden olvidar en los tests del editor:
- Elegir material rellena los campos vacíos pero **no pisa** los editados a mano.
- Editar un campo cambia su procedencia a `manual` y quita el indicador.
- Un campo bloqueante vacío desactiva el botón de escribir tag.
- Duplicar una bobina **no arrastra** el `tagUid` ni el `id`.

---

## 12. Dependencias

```kotlin
// :core — JVM puro, sin Android
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
implementation("com.google.zxing:core:3.5.3")            // solo en :core:label

// Android
implementation("androidx.core:core-ktx:1.13.1")
implementation(platform("androidx.compose:compose-bom:2024.09.03"))
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.9.2")
implementation("androidx.navigation:navigation-compose:2.8.1")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

implementation("com.google.dagger:hilt-android:2.52")
ksp("com.google.dagger:hilt-compiler:2.52")

implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")
implementation("androidx.datastore:datastore-preferences:1.1.1")

implementation("androidx.camera:camera-camera2:1.3.4")
implementation("androidx.camera:camera-lifecycle:1.3.4")
implementation("androidx.camera:camera-view:1.3.4")

// OCR on-device. La variante "bundled" mete el modelo en el APK:
// +4 MB, pero funciona sin red y sin esperar a Google Play Services.
implementation("com.google.mlkit:text-recognition:16.0.1")

testImplementation("app.cash.turbine:turbine:1.1.0")
testImplementation("org.robolectric:robolectric:4.13")
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
```

Comprobar versiones al empezar: algunas habrán avanzado.

---

## 13. Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El móvil no soporta MIFARE Classic | Creality CFS no funciona | Detectar con `com.nxp.mifare` y deshabilitar con explicación (§4.1) |
| Layouts propietarios sin verificar | El tag no funciona y se gasta la pegatina | Avisar antes de escribir; contrastar contra un volcado real en cuanto haya hardware |
| Clave AES de Creality no disponible | Perfil inservible | Campo en ajustes avanzados; sin clave, **no escribir** |
| OCR flojo con poca luz o etiquetas curvas | Frustración | Recorte de ROI, linterna y, sobre todo, **entrada manual de primera clase** |
| Fragmentación de NFC entre fabricantes | Fallos difíciles de reproducir | Registrar UID, tecnologías y errores; pantalla de diagnóstico |

---

## 14. Prompt inicial para Claude Code

> Lee `PLAN_APP_FILAMENTO_ANDROID.md`.
>
> Empieza por la **Fase 1**: crea el proyecto Gradle con los módulos
> `:core:model`, `:core:extract` y `:core:tags` como módulos Kotlin/JVM puros,
> sin ninguna dependencia de Android.
>
> - `:core:model` — `FilamentSpec` y `Provenance` según §6.2, más el catálogo
>   de materiales (con sus rangos típicos de temperatura y densidad), marcas
>   (incluidas las españolas: Smartfil, Winkle, Sakata 3D, Recreus, BCN3D) y
>   colores con nombre en español e inglés. Normalización a valores nominales y
>   validación con dos niveles (bloqueante y advertencia).
> - `:core:extract` — extractor de texto OCR a `FilamentSpec` con procedencia y
>   confianza por campo. **Lee la §5 entera antes de escribir una sola regex:**
>   son diez trampas de normalización concretas, y cada una necesita su test de
>   regresión. Escribe los tests a la vez que el código, no después.
> - `:core:tags` — codificación NDEF (registros MIME y externos, empaquetado
>   TLV para NTAG, comprobación de capacidad), CBOR canónico y determinista
>   (sin `float`), el DSL de layouts binarios como tabla, y los perfiles
>   OpenSpool, OpenPrintTag, Anycubic ACE (con sus variantes QIDI y Elegoo),
>   Creality CFS y Bambu. Cada perfil declara `verified` y `reference`. El de
>   Bambu **no codifica**: lanza un error explicando por qué y proponiendo
>   OpenSpool.
>
> No escribas nada de UI en esta fase. Termina cuando todos los tests pasen, y
> dime qué cobertura tienen los casos de la §5.
