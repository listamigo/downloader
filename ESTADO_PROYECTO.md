# ESTADO DEL PROYECTO - elimd downloader

> **Documento de traspaso.** Es la referencia para continuar el trabajo.
> Última actualización: **2026-10-03 (sesión 2)**
> Verificado en dispositivo real: **Xiaomi 220333QAG, Android 16 (API 36), arm64-v8a**

---

## 1. RESUMEN EJECUTIVO

| | |
|---|---|
| Build | **VERDE** — `./gradlew :app:assembleDebug` |
| Tests | **118 unitarios + 12 instrumentados, en verde** |
| APK | 23 MB |
| Descarga real | **VERIFICADA** — 1080p completo, 59,7 MB, MP4 con imagen y sonido |
| Bloqueo de YouTube | **RESUELTO con cookies de sesión** (§5) |
| Selector de calidad | **VERIFICADO en dispositivo**: 2160p, 1440p, 1080p, 720p, con el "une audio y video" bien etiquetado |
| Multiplexado >720p | **VERIFICADO de principio a fin**: descarga 1080p real, unida **sin recodificar** (§3.3) |
| Riesgo principal | ~~Bloqueo por IP~~ → resuelto. ~~Archivo 3x más grande~~ → resuelto. Queda la caducidad de las cookies |

La app **funciona end-to-end**: buscar → ver resultados con paginación → elegir
calidad → descargar → ver `COMPLETED` → abrir en un reproductor externo.

> **Aviso importante sobre este repositorio:** una versión anterior de este
> archivo declaraba casi todo como `COMPLETO` y a la vez listaba los mismos
> puntos como pendientes. Los componentes marcados como hechos **no existían**.
> Este documento solo contiene estado verificado con build y ejecución real.
>
> Y un aviso sobre cómo se verificó: este archivo daba "4 tests instrumentados en
> verde" cuando **no compilaban** (ver `docs/HANDOVER_2026-10-03.md` §7). Una
> cifra de tests solo vale si el sourceSet correspondiente compila.

> **Sobre la última sesión (interrumpida por un corte de luz):** el cambio al
> cliente iOS llegó a escribirse en `NewPipeMediaResolver.kt` pero se quedó sin
> compilar, sin probar y sin documentar. Los documentos de abajo seguían
> describiendo el cliente ANDROID. Esa laguna está cerrada en §3.

---

## 2. CÓMO COMPILAR, PROBAR E INSTALAR

```bash
cd "/home/elimdavid/a. donwloader/"

./gradlew :app:assembleDebug          # compilar
./gradlew :app:testDebugUnitTest      # tests
./gradlew :app:installDebug           # compilar + instalar por adb

# O manualmente:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.elimd.downloader/.feature.main.MainActivity
```

### Trazas útiles en el dispositivo

```bash
adb logcat -s DownloadEngine:* MainViewModel:* NewPipeMediaResolver:E MediaMuxer:*
```

Estas cuatro etiquetas son las que hacen falta para diagnosticar descargas.

### Requisitos

- JDK 17+ (probado con OpenJDK 21)
- Android SDK en `/home/elimdavid/Android/Sdk` (vía `local.properties`, **no versionado**)
- Dispositivo con API 21+ (probado en API 36)

### Dónde quedan los ficheros descargados

```
/sdcard/Android/data/com.elimd.downloader/files/Download/descargas/
```

Es el directorio externo propio de la app: **no requiere permisos de
almacenamiento** y se limpia al desinstalar.

Cuando hace falta unir pistas (§3.3) aparecen dos ficheros temporales
`<nombre>.video.tmp` y `<nombre>.audio.tmp` en el mismo directorio. Se borran
al terminar y al cancelar; **no se borran al pausar**, precisamente para que al
reanudar la descarga continúe por donde iba (`Range`) en vez de empezar de cero.

---

## 3. LA CAPA DE RESOLUCIÓN (lo implementado en la última sesión)

### El app NO necesita servidor

YouTube **es** el servidor. El flujo real es `YouTube → tu dispositivo`.
GitHub es solo un host de git y no participa del runtime.

### Por qué se abandonó yt-dlp

`ADR-005` del log de decisiones decía que yt-dlp se invocaba con
`ProcessBuilder` desde Android, y que podía embeberse en `assets/`.
**Eso no funciona en Android, y nunca funcionó:**

1. Android **no tiene shell, ni `PATH`, ni permiso de `exec`** para binarios
   arbitrarios. `ProcessBuilder("yt-dlp")` falla siempre.
2. **yt-dlp no publica build para Android.** Los releases oficiales
   (verificados en 2026-08-19) solo incluyen `yt-dlp`, `yt-dlp.exe`,
   `yt-dlp_linux`, `yt-dlp.tar.gz`. Nada para Android.
3. Los binarios de PyInstaller necesitan **glibc**, y Android usa **bionic**.
   Cruz-compilar es inviable en la práctica.

Descartado: Termux (el usuario debe instalar una app aparte, IPC frágil y
Android 10+ estorba el ejecución en background).

### Lo que se implementó: NewPipeExtractor + OkHttp

NewPipeExtractor es **Java puro** (lo usa la propia app NewPipe en producción).
No necesita binario nativo, ni servidor, ni coste. Descifra las URLs firmadas
de YouTube usando Rhino como motor JavaScript.

```
core/extract/MediaResolver.kt            <- FRONTERA (interfaz, ver §6)
core/extract/NewPipeMediaResolver.kt     <- implementación actual
core/extract/StreamSelection.kt          <- política de calidad ( pura, testeada)
core/extract/OkHttpNewPipeDownloader.kt  <- puente HTTP que exige el extractor
core/extract/SearchResponseSanitizer.kt  <- workaround (ver §7)
core/download/HttpFileDownloader.kt      <- transferencia de bytes con Range y segmentos
core/download/Mp4Remuxer.kt              <- une las pistas SIN codificar (§3.3)
core/download/MediaMuxer.kt              <- decide: remux, o conversion con media3
```

Dependencias: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5` (vía **JitPack**,
no está en Maven Central) y `androidx.media3:media3-transformer:1.3.1`.

### 3.1 · Cliente iOS de InnerTube

`NewPipeMediaResolver` fuerza el cliente **iOS** en lugar del de Android:

```kotlin
YoutubeStreamExtractor.setFetchIosClient(true)
```

Motivo (medido el 2026-10-02 con `dQw4w9WgXcQ`): el cliente ANDROID expone
**1 formato**; el iOS, **27**; visionOS, **19**. Con ANDROC no había nada que
elegir, y por eso todo salía a 360p.

> **Lo que esto NO arregla:** el bloqueo `SignInConfirmNotBotException` (§5). Es
> un problema de IP, no de cliente. Con iOS se gana catálogo de formatos, no
> acceso.

### 3.2 · Selector de calidad en la app

Pulsar **Descargar** ya no baja directamente: abre una hoja con las calidades
**que ese vídeo expone de verdad**, porque se piden al resolver en ese momento.

- `MediaResolver.getAvailableQualities()` devuelve una opción por resolución y
  fps, más "Mejor" y las opciones de audio.
- Las calidades vienen del **detalle** del vídeo, que es justo lo que YouTube
  bloquea por IP. Si ese detalle falla, la hoja **muestra el error** en vez de
  dejar al usuario pulsando en silencio (el fallo silencioso era el bug B3).
- `DownloadQuality.requiresMuxing` avisa de qué opciones obligan a unir pistas.

### 3.3 · Multiplexado para >720p — **VERIFICADO DE PRINCIPIO A FIN**

YouTube solo entrega audio+video juntos (progresivo) hasta ~720p. Por encima hay
que bajar dos ficheros y unirlos:

```
video (video-only)  ─┐
                     ├─► Mp4Remuxer ─► un solo MP4   (si caben sin convertir)
audio (best)        ─┘        └──────► media3         (si hay que convertir)
```

**El camino normal es el primero: copiar, no codificar.** Es lo que decide el
peso del archivo final, y por eso va primero.

#### Por qué `media3-transformer` no servía (causa raíz, verificada)

Media3 tiene un camino que no codifica, pero **solo le sirve a un único fichero
MP4 con audio y video dentro**: `Transformer.remuxRemainingMedia()` mira
únicamente `composition.sequences[0].editedMediaItems[0]`. Aquí las pistas vienen
en **dos ficheros distintos** —que es justo lo que YouTube exige por encima de
720p—, así que ese camino no es aplicable nunca y la librería recodifica
siempre. Se comprobó descompilando la librería, no leyendo su documentación.

El síntoma era un archivo **tres veces más grande** que el origen, con el perfil
cambiado (Constrained Baseline 3.0 → High 5.0) y el bitrate del encoder en lugar
del original. Ver **ADR-018**.

#### Qué hace ahora

- `Mp4Remuxer` copia las muestras con `MediaExtractor` + `MediaMuxer` del
  sistema: **salida ≈ entrada**, calidad exacta de origen y 2,7 s de unión.
- El selector ya elige siempre H.264 + AAC en MP4 (`StreamSelection.pickFor` y
  `bestAudio` priorizan MP4), así que el camino rápido es el habitual.
- Si las pistas **no** caben en un MP4 (un VP9, un Opus, un WebM), `MediaMuxer`
  cae a `media3-transformer` con salida forzada a **H.264 + AAC**, al bitrate
  del origen y con `enableHighQualityTargeting` desactivado. Ahí sí recodifica, y
  el archivo pesa más: es el precio de no ofrecer esa calidad en su formato
  original.
- Si el remux no puede hacerse por lo que sea, lanza `RemuxNotPossible` y se cae
  a la conversión. **Nunca se entrega un MP4 al que le falte una pista.**
- "Mejor" elige la máxima disponible aunque sea VP9, y ese caso va por
  conversión. "Solo video" también pasa por el muxer si la pista no viene en MP4.
- El progreso se reparte: 0-90 % descarga, 90-100 % unión.

**Verificación en el dispositivo, con contenido real** (`Save Your Tears`, 1080p,
itag 137 + 140, descargado desde el móvil con la app de este repositorio):

| | valor |
|---|---|
| Partes bajadas | 59.649.224 B (vídeo 55.542.138 + audio 3.983.429) |
| **Archivo final** | **59.683.246 B** (×1,0006) |
| Tiempo de unión | 2,7 s |
| `ffprobe` vídeo | `h264 High level 40 1920x1080 bit_rate=1785392` |
| `ffprobe` audio | `aac LC 44100 Hz stereo 128 kbps` |

El bitrate del vídeo coincide con el del origen (1.785.254 bps calculados sobre
los bytes y la duración) al 0,008 %, y el perfil no ha cambiado: la pista es la
misma. Antes, el mismo caso daba ×3,0 con el perfil cambiado. `ffmpeg -f null -`
decodifica el fichero entero sin un solo error.

**Verificación automatizada**: `Mp4RemuxerTest` y `MediaMuxerTest`,
instrumentados, **12 tests en verde en el Xiaomi real** (`app/src/androidTest/`):

| Test | Qué demuestra |
|---|---|
| `noSePierdeNingunaMuestra` | muestras y bytes de salida **idénticos** a los de entrada |
| `elFormatoDeCadaPistaNoCambia` | mismo MIME y mismo `csd-0`: no se recodificó |
| `losTiemposEmpiezanEnCeroYSonCrecientes` | ninguna pista arranca antes de 0 ni retrocede |
| `lasPistasSeIntercalan` | no sale todo el vídeo y luego todo el audio |
| `unVideoSinPistaDeAudioNoSeInventaSonido` | avisa y no deja un archivo mudo |
| `elArchivoPesaLoMismoQueSusPartes` | el resultado pesa menos de 2x las partes |
| `laDuracionSeConserva` | la duración del resultado es la del origen |

Es instrumentado porque depende de los codecs de Android: no se puede probar en
la JVM. La **decisión** de qué ruta se toma sí está aislada en funciones puras
(`canRemuxWithoutReencoding`) y se prueba en la JVM.

### Cómo se verifica (no solo compiló)

NewPipeExtractor es Java puro, así que la **política de selección de calidad se
extrajo a `StreamSelection`**, un objeto sin dependencias del extractor, y se
prueba en la JVM sin red ni dispositivos. Son los 15 tests de
`StreamSelectionTest`.

---

## 3.4 · El archivo final pesaba 3x y salía sin sonido — **ambos RESUELTOS**

Dos bugs encontrados al verificar el multiplexado. Los dos están explicados en
detalle en los ADR, así que aquí solo queda el resumen y dónde mirar.

**El archivo pesaba tres veces más que el origen**, con el perfil de vídeo
cambiado (Constrained Baseline 3.0 en la fuente → High 5.0 en la salida) y el
bitrate del encoder (1,6 Mbps) en lugar del original (446 kbps): es la firma
inequívoca de una recodificación. La causa no era el ajuste de bitrate que se
había tocado, sino que `media3-transformer` **solo puede no codificar un único
MP4 con audio y video dentro**, y aquí las pistas llegan en dos ficheros
(ADR-018). Solución: `Mp4Remuxer`, que copia con `MediaExtractor` +
`MediaMuxer`.

**El MP4 salía con imagen y sin sonido.** El audio de los MP4 lleva `elst` con
el retardo del encoder y Android lo descuenta: la primera muestra llega con
`sampleTime = -23219` µs. El remux tomaba ese tiempo negativo por "no hay más
muestras" y descartaba la pista entera (ADR-019).

---

## 4. BITÁCORA DE ERRORES ENCONTRADOS

Cada entrada: **síntoma → causa raíz → solución**. Todos verificados en ejecución.

### Fase 1 — El proyecto no compilaba (12 causas raíz)

Síntoma: `./gradlew build` fallaba. `./gradlew` ni siquiera existía.

| # | Causa raíz | Solución |
|---|---|---|
| 1 | No existían `gradlew` ni `gradle-wrapper.jar` | Generados con `gradle wrapper --gradle-version 8.9` |
| 2 | No existía `gradle.properties` (faltaba `android.useAndroidX`) | Creado |
| 3 | No existía `local.properties` | Creado con `sdk.dir` |
| 4 | `res/xml/` vacío, pero el Manifest referencia `backup_rules` y `data_extraction_rules` | Creados ambos XML |
| 5 | 8 drawables sin `</vector>` de cierre | Añadido el cierre a `ic_add`, `ic_arrow_down`, `ic_delete`, `ic_more`, `ic_previous`, `ic_refresh`, `ic_star`, `ic_tune` |
| 6 | Los 38 alias de versión del catalog no resolvían: `version = "coreKtx"` se tomaba como literal | Convertidos a la forma canónica `version.ref = "coreKtx"` |
| 7 | Hilt usaba group `androidx.hilt` en vez de `com.google.dagger` | Corregido en `libs.versions.toml` |
| 8 | ID de plugin Hilt inválido (`com.google.dagger.hilt.android.plugin`) | Corregido a `com.google.dagger.hilt.android` |
| 9 | `media3-player` **no existe** como artefacto (HTTP 404 en Google Maven) | El nombre real es **`media3-exoplayer`** |
| 10 | Compose compiler 1.5.8 incompatible con Kotlin 1.9.24 | Subido a 1.5.14 |
| 11 | `ApplicationComponent` **eliminado** en Hilt 2.51 → fallo de kapt | Sustituido por `SingletonComponent` |
| 12 | **`domain/repository/` no existía**; los `@Binds` de Hilt apuntaban a tipos inexistentes | Creadas `YouTubeSearchRepository`, `DownloadRepository`, `SettingsRepository` |

Encima de eso, **76 errores de Kotlin**. Los de mayor interés:

- `DownloadService`: `override fun IBinder? = null` era sintaxis inválida, y
  `NotificationManager::class.java` devuelve una `Class`, no una instancia.
  Reescrito con `getSystemService()`, canales y API 34.
- `MainViewModel` exponía un `State` de Compose, incompatible con
  `collectAsStateWithLifecycle`. Convertido a `StateFlow`.
- `SettingsScreen` usaba la `ListItem` de **Material 2** sobre Material3 1.1.2
  (`headlineText`, `supportingText`, `onClick` no existen ahí), y leía
  `settings.settings` (doble referencia al estado).
- `DownloadEngine`: `activeDownloads` **nunca se llenaba**, así que
  `pause`, `resume`, `cancel` y `getProgress` no hacían nada. Reescrito con
  `Job`s reales y progreso por Flow.
- `YoutubeDlExecutor` (ya eliminado): timeout de 60 s que mataba cualquier
  descarga larga, y el progreso nunca se parseaba (siempre 0).

### Fase 2 — Bugs encontrados probando en ejecución real

#### B1 · La paginación devolvía "no hay más resultados"

- **Síntoma**: tras unos resultados ya no cargaba más.
- **Causa raíz**: `SearchInfo.getNextPage().url` **no es un token de
  continuación**: devuelve la URL del endpoint, idéntica a la de la primera
  página. Reconstruir `Page(url)` desde ese string produce **0 resultados**
  (medido: página 1 = 11 ítems, reconstruida = 0; con el objeto `Page` real = 12).
- **Solución**: el resolver conserva el objeto `Page` real en una caché LRU
  (8 entradas) y expone un token opaco (`"np-1"`, `"np-2"`, …) al resto de la app.
  La caché se limpia en cada búsqueda nueva.

#### B2 · El 100 % nunca llegaba a `COMPLETED`

- **Síntoma**: la barra llegaba al 100 % pero la descarga seguía marcada como
  activa, mostrando "Cancelar todo" y nunca "Reproducir".
- **Causa raíz**: `progressFlow` era un `MutableSharedFlow` con buffer de 64.
  Al final de la descarga llegan **cientos de ticks por segundo**; la escritura
  en Room no daba abasto, el buffer se llenaba y **`tryEmit` descarta
  silenciosamente** cuando está lleno. El evento `COMPLETED` se perdía.
- **Solución**: dos cambios, porque ambos eran necesarios:
  1. `MutableSharedFlow` → **`MutableStateFlow`**. El progreso es *estado*, no un
     evento; `StateFlow` siempre converge al último valor y nunca pierde el final.
  2. La proyección a Room se **estrangula a 1 escritura cada 500 ms**, pero
     **un cambio de estado se escribe siempre**, incluidos los terminales
     (`COMPLETED`, `FAILED`, `CANCELLED`).

#### B3 · Los errores de descarga eran invisibles

- **Síntoma**: al pulsar "Descargar" no pasaba absolutamente nada visible.
- **Causa raíz** (dos capas):
  1. `getVideoInfo` se tragaba la excepción con `runCatching{}.getOrNull()`.
  2. El campo `message` del ViewModel se rellenaba pero **nunca se mostraba**
     en pantalla: no había ningún Snackbar.
- **Solución**: se registra en `logcat` y se propaga; `App.kt` muestra el
  mensaje en un Snackbar y lo consume con `consumeMessage()`.

#### B4 · Path traversal en el nombre de fichero

- **Síntoma**: detectado por un test, no por el usuario.
- **Causa raíz**: un vídeo titulado exactamente `..` (o `.`) producía un nombre
  de fichero que **resolvía fuera** del directorio de descargas
  (`File(dir, "..").canonicalFile.parentFile != dir`).
- **Solución**: `sanitizeFileName()` ahora hace `.trim('.')` tras sanear
  separadores. Cubierto por `DownloadFileNameTest`.

#### B5 · Permiso de notificaciones inútil

`POST_NOTIFICATIONS` con `android:maxSdkVersion="33"` lo **deshabilitaba
justamente en API 33+**. Corregido y se solicita en runtime desde
`MainActivity` (obligatorio desde Android 13).

#### B6 · `file://` no abre ficheros en API 24+

Al pulsar Reproducir, Android lanzaba `FileUriExposedException`.
**Solución**: añadido `FileProvider` (`androidx.core.content.FileProvider`)
con `res/xml/file_paths.xml`. Verificado: abre el `ResolverActivity` de Android.

#### B7 · Permisos de almacenamiento innecesarios

`WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` no hacen falta porque las
descargas van al directorio externo propio de la app. Eliminados: menos
prompts de privacidad y menos superficie de permisos.

### Fase 3 — Errores de la sesión del selector de calidad

#### B8 · Pausar borraba la descarga y hacía imposible reanudarla

- **Síntoma**: "Pausar" funcionaba, pero "Reanudar" no hacía nada.
- **Causa raíz**: el `finally` del job de descarga hacía
  `activeDownloads.remove(downloadId)` **también al cancelarse**. Como pausar es
  cancelar el job, la entrada desaparecía del mapa y `resumeDownload` salía
  por su `?: return` sin relanzar nada.
- **Solución**: la forgetting de la descarga solo ocurre en estados terminales.
  El job marca si llegó a `COMPLETED` o `FAILED`, y el borrado del mapa se hace
  en `invokeOnCompletion` comprobando esa marca. Al pausar, la entrada
  sobrevive y se puede relanzar.

#### B9 · "Mejor" no elegía la máxima calidad

- **Síntoma**: discovered por `StreamSelectionTest`, no por el usuario.
- **Causa raíz**: al elegir stream se ordenaba por "prefiere H.264" **antes**
  que por altura. Con un 2160p disponible solo en VP9 y un 1080p en H.264,
  "Mejor" devolvía el 1080p: exactamente lo contrario de lo pedido.
- **Solución**: el criterio de altura va primero; H.264 solo desempata entre
  streams de la misma resolución. Cubierto por
  `StreamSelectionTest.sin altura no se elige un progresivo...`.

#### B10 · `audioUrl` se ignoraba en el motor de descargas

- **Síntoma**: `resolveStream` siempre devolvía `audioUrl = null` y el motor
  nunca tenía con qué unir. Documentado como P2 desde el principio.
- **Causa raíz**: el motor hacía `media.videoUrl ?: media.audioUrl` y, como
  solo se pedían progresivos, nunca había segunda pista.
- **Solución**: implementado el ciclo completo: `resolveStream` devuelve video
  suelto + audio cuando hace falta, y `DownloadEngineImpl` baja las dos y las
  une con `MediaMuxer` (§3.3). Los temporales se limpian al terminar y al
  cancelar.

---

## 5. El bloqueo de YouTube — RESUELTO con cookies de sesión

### Qué era

Desde la IP `38.188.239.17`, YouTube devolvía `SignInConfirmNotBotException` en
**el 100 % de los vídeos probados**, con cualquier cliente, tanto desde el
host como desde el móvil (comparten IP).

La medición dio este cuadro, que fue lo que refutó el diagnóstico inicial:

| Cliente | Resultado |
|---|---|
| ANDROID | **HTTP 400** `Precondition check failed` |
| IOS | **HTTP 400** `Precondition check failed` |
| IOS (con la clave web) | **HTTP 400** `Precondition check failed` |
| WEB | `LOGIN_REQUIRED` — "Sign in to confirm you're not a bot" |
| TVHTML5 | `LOGIN_REQUIRED` |
| ANDROID_VR | `LOGIN_REQUIRED` |
| WEB_EMBEDDED | `ERROR` — "This video is unavailable" |

Y con `yt-dlp`, probando 5 `player_client` (default, tv, web_safari, mweb,
android_vr): **los cinco** igual. El propio yt-dlp lo decía en el error:
*"Use --cookies-from-browser or --cookies for the authentication."*

### Qué era realmente

**No era la IP, ni el cliente: era el acceso anónimo.** Lo dice el error de
YouTube ("Sign in to confirm that you're **not a bot**"): lo que se pedía era una
sesión, no potencia de cálculo. Por eso ningún cambio de cliente podía
arreglarlo: se estaba pidiendo una credencial a un sistema que no la pedía.

### La solución: cookies de la cuenta del usuario

`cookies.txt` exportado del navegador con la sesión de YouTube iniciada. Esa
cabecera viaja en las peticiones del extractor.

**Verificado el 2026-10-03:**

| Prueba | Antes | Con cookies |
|---|---|---|
| `yt-dlp -F 9bZkp7q19f0` | `Sign in to confirm you're not a bot` | **lista formatos** |
| App: hoja de calidad | "YouTube bloquea la descarga..." | **1080p, 720p, 480p, 360p** |

En la app, la hoja de calidad pasó a listar calidades reales y, lo importante,
**etiquetadas bien**: `360p` sin "une audio y video" (hay progresivo a esa
altura) y `1080p` con "une audio y video" (hay que unir pistas). Es decir, la
lógica de §3.3 funcionando sobre datos de verdad, no sobre fixtures de test.

**Consecuencia: el plan B (servidor) no hace falta.** No hace falta hosting, ni
Vercel, ni Railway, ni VPS. La app sigue siendo autónoma y sin coste, que es lo
que exige `r1.md`.

### 5.1 · Por qué `setFetchIosClient(true)` nunca pudo arreglar el bloqueo

Vale la pena dejarlo escrito, porque el comentario del código asumía lo
contrario. Descompilando `YoutubeStreamExtractor.onFetchPage` (v0.26.5):

```
59: invokevirtual fetchAndroidClient(...)   <- SIEMPRE primero, sin try/catch
66: getstatic  fetchIosClient               <- el flag se mira DESPUES
98: invokevirtual fetchIosClient(...)
```

El flag **añade** una consulta iOS *después* de que la de Android tenga éxito;
no la sustituye. Como `fetchAndroidClient` lanzaba y no está protegido, la
excepción salía del método y **la rama iOS no se ejecutaba nunca**. El stack
trace en el móvil lo confirmaba: `fetchAndroidClient` → `StreamInfo.getInfo`.

El flag se mantiene porque en el caso bueno sí amplía el catálogo (1 → 27
formatos), y porque no cuesta nada.

### 5.2 · Cómo se instalan las cookies

```bash
# 1. Exportar cookies.txt del navegador con la sesión de YouTube iniciada
#    (extensión "Get cookies.txt LOCALLY")
# 2. Copiarlas al móvil:
adb push cookies.txt /sdcard/Android/data/com.elimd.downloader/files/cookies.txt
# 3. Reiniciar la app
adb shell am force-stop com.elimd.downloader
```

Comprobar que se han cargado:

```bash
adb logcat -s YouTubeSession:*
# Sesion cargada de cookies.txt: 22 cookies de youtube.com
```

**Nunca se comitean**: `cookies.txt` está en `.gitignore`, y hay tests que
comprueban que las cookies **no** se envían a ningún host que no sea YouTube —
en particular, no al CDN `googlevideo.com`, que es donde bajan los medios.

Si el `cookies.txt` no trae ninguna cookie de sesión real, la app lo **ignora**
y sigue en modo anónimo, en vez de fingir que hay sesión. Se detecta al cargar:
el log avisa de cuántos nombres encontró.

---

## 6. PLAN B: la costura está lista, pero ya no hace falta

`MediaResolver` es una interfaz. `NewPipeMediaResolver` es la implementación
actual. El plan B consiste en escribir `ServerMediaResolver` implementando la
**misma interfaz** contra un servidor propio con yt-dlp:

```kotlin
interface MediaResolver {
    suspend fun search(query: String, pageToken: String?): SearchResult
    suspend fun getVideoInfo(videoId: String): YouTubeVideo?
    suspend fun getAvailableQualities(videoId: String): List<DownloadQuality>
    suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo>
    suspend fun resolveStream(videoId: String, quality: DownloadQuality, type: DownloadType): ResolvedMedia
}
```

Para activarlo basta **un cambio de binding en `AppModule.kt`**. Ni dominio ni
presentación se tocan.

Ventajas del plan B: yt-dlp se actualiza antes y usa clientes de InnerTube
distintos. Coste: una máquina siempre encendida, más el mantenimiento.

### Por qué ya no se va a hacer

Con las cookies resueltas (§5), un servidor añadiría coste y un proceso más que
mantener **sin resolver nada que quede pendiente**. Además, se descartó
explícitamente al analizar el hosting:

- **Vercel no sirve**: es serverless. Descargar un vídeo de 10 minutos mata el
  proceso por timeout, y la salida por ancho de banda se cobra por GB.
- **Railway o un VPS**: las IP de centro de datos (AWS/GCP/Azure) suelen estar
  **más** marcadas por YouTube que una residencial. Mudarse sin cookies sería
  cambiar un bloqueo por otro pagando por ello.
- **La alternativa gratuita** que sí habría servido: el móvil como hotspot
  (otra red) o una Raspberry en otra casa.

Se mantiene el diseño porque el coste de conservarlo es cero y el de revertirlo
no lo es: si algún día YouTube vuelve a tightened el acceso, la costura sigue
ahí.

---

## 7. WORKAROUND ACTIVO (borrar cuando upstream lo corrija)

`NewPipeExtractor v0.26.5` lanza `NullPointerException` al parsear búsquedas
porque **YouTube omite el campo `badges`** en algunos `videoRenderer` y la
librería lo da por hecho:

```
Cannot invoke "com.grack.nanojson.JsonObject.isEmpty()" because "badges" is null
```

Afecta a **4-8 elementos por consulta**, y el síntoma era una búsqueda que
devolvía 0 resultados **de forma intermitente**.

`SearchResponseSanitizer` intercepta la respuesta y inyecta `"badges": []`
donde falta. Es semánticamente neutro (solo alimenta el contador de insignias
del canal).

- **Cómo quitarlo**: cuando TeamNewPipe lo corrija, borrar
  `SearchResponseSanitizer.kt` y las 3 llamadas en `OkHttpNewPipeDownloader`.
- **Riesgo aceptado**: depende de la forma del JSON; si YouTube renombra el
  renderer, deja de aplicarse (y vuelve el fallo intermitente).
- Cubierto por `SearchResponseSanitizerTest`.

---

## 8. PENDIENTES

### P0 · ~~ALTA — Verificar en dispositivo~~ → Hecho salvo el final (§5.1)

**Verificado en el Xiaomi real** (2026-10-03):

| Qué | Estado |
|---|---|
| Búsqueda y paginación | ✅ funciona |
| La hoja de calidad abre | ✅ |
| Si YouTube bloquea, lo dice y no falla en silencio | ✅ (era el bug B3) |
| `MediaMuxer` une las dos pistas en un MP4 con imagen y sonido | ✅ 12 tests instrumentados |

**Descarga >720p de principio a fin: HECHA** (2026-10-03, Xiaomi real). Con las
cookies en el móvil, buscar → elegir **1080p** → descargar:

```
NewPipeMediaResolver: Plan para 1080p (BOTH): unir 137 (mpeg-4, 1080p) + 140
Mp4Remuxer: Remux sin recodificar: -> ...mp4, 59683246 B (origen 59649224 B)
DownloadEngine: download=1 COMPLETED 100.0% error=null
```

Archivo final 59.683.246 B para 59.649.224 B de partes (×1,0006), unión en
2,7 s, `ffprobe` con perfil y bitrate intactos y decodificación completa sin
errores. La cadena `resolver → descargar → unir` está verificada junta. Detalle
y mediciones en §3.3.

Las calidades >1080p de este vídeo salen en **WEBM**, así que sí pasan por la
ruta de conversión de media3; esa ruta queda sin medir de punta a punta.

### P1 · ~~ALTA — Desbloquear el acceso a YouTube~~ → **Hecho** (§5)

Resuelto con cookies de sesión: `YouTubeSession` las carga y
`OkHttpNewPipeDownloader` las inyecta. Verificado en dispositivo. **El plan B
queda descartado** (§6) y el fork del extractor también.

Lo que queda de este tema es solo mantenimiento:

- **Las cookies caducan.** Cuando el usuario las renueve, el error volverá a
  ser `LOGIN_REQUIRED` y habrá que volver a hacer `adb push`. Conviene
  detectarlo y decirlo claro en la UI en vez de mostrar un error genérico: si
  el `playabilityStatus` es `LOGIN_REQUIRED`, el mensaje debería ser
  "renueva las cookies", no "este vídeo no está disponible".
- **Falta una forma cómoda de renovar las cookies** sin `adb`: hoy es copiar el
  fichero a mano. Es lo siguiente que se debería construir (§8-P10).

El fork de NewPipeExtractor (para llamar a `fetchIosClient` sin pasar por
Android) ya no tiene sentido: aunque se lograra, iOS también saldría con
`LOGIN_REQUIRED`.

### P2 · ~~ALTA — Multiplexado para >720p~~ → Hecho (§3.3)

Implementado, verificado en dispositivo y con la política de selección cubierta
por tests. El tamaño del archivo final ya no es un problema: se copia la pista
en vez de codificarla (ADR-018). Lo único que queda por medir es la ruta de
conversión (VP9 → H.264), que por definición pesa más.

### P3 · MEDIA — Reproductor propio

`feature/player/PlayerScreen.kt` y `PlayerViewModel.kt` **no existen** (un
estado anterior los declaraba COMPLETOS). Mientras tanto, Reproducir delega en
el reproductor externo vía `ACTION_VIEW` + FileProvider, **que ya funciona**.
Por tanto P3 es una mejora de experiencia, no un bloqueante.

### P4 · MEDIA — Más tests

Hoy hay 26: `SearchResponseSanitizerTest` (5), `DownloadFileNameTest` (6) y
`StreamSelectionTest` (15). Faltan, por valor:

- `HttpFileDownloader`: reanudar con cabecera `Range`, fichero parcial,
  cancelación por corrutina. Es lo más delicado y usa `MockWebServer`.
- `MediaMuxer`: necesita un dispositivo o instrumentación; no se puede probar
  en la JVM porque depende de los codecs de Android.
- Tests de use cases con MockK (MockK ya está declarado pero **no se usa**).

### P5 · MEDIA — DownloadService desconectado

`DownloadService` funciona y gestiona canales, progreso y `FOREGROUND_SERVICE_TYPE`,
pero **nadie lo arranca**: el motor no lo invoca. La descarga vive mientras el
proceso esté vivo, sin notificación de fondo real. Si se cierra la app, se
pierde. Conectar el servicio con `DownloadEngineImpl` y lanzar desde
`startDownload`.

Con el multiplexado esto pesa más: unir pistas tarda, así que una descarga
corta en background se queda a medias sin avisar.

### P6 · BAJA — Fondo de pantalla

`WallpaperManager` sin implementar (los ajustes ya persisten el estado).

### P7 · BAJA — Migraciones de Room

`version = 1` y `exportSchema = false`. **Antes de tocar el esquema** hay que
activar `exportSchema` y escribir la migración; si no, se pierde la BD del
usuario. Relevante si se añaden columnas (p. ej. velocidad real, que hoy es 0).


### P10 · MEDIA — Gestión de las cookies desde la app

Hoy las cookies entran por `adb push` a mano (§5.2). Funciona, pero es un
fricción cada vez que caducan. Lo que falta:

1. **Detectar `LOGIN_REQUIRED` y decirlo**: hoy el error que ve el usuario es el
   texto del extractor, que no dice "renueva las cookies". Debería traducirse.
2. **Pegar las cookies desde Ajustes** en vez de copiar un fichero: un campo de
   texto donde se pegue el `cookies.txt`, validado al guardar con el mismo
   criterio que `YouTubeSession.buildHeader`.
3. **Indicador de estado**: si hay sesión o no, y cuándo se cargó.

Es el P1 que queda de este tema, y el único que toca código de sesión.

### P8 · BAJA — Riesgo de suministro

NewPipeExtractor se resuelve por **JitPack**, que compila desde el código en
cada resolución: más lento y dependiente de la disponibilidad de JitPack.
Alternativa: clonar y publicar como artefacto propio, o bajar de versión.

### P9 · BAJA — Velocidad real

`DownloadEngineProgress.speed` y `eta` se emiten siempre a 0. `HttpFileDownloader`
podría calcular la velocidad por diferencia de bytes entre muestras.

---

## 9. ESTRUCTURA DEL CÓDIGO

```
app/src/main/java/com/elimd/downloader/
├── core/
│   ├── extract/        # MediaResolver, NewPipeMediaResolver, StreamSelection
│   ├── download/       # HttpFileDownloader, MediaMuxer, DownloadEngineImpl
│   ├── database/       # Room: AppDatabase, DownloadEntity, DownloadDao
│   ├── datastore/      # DataStoreSettingsDataSource
│   ├── network/        # YouTubeSearchDataSource (adaptador delgado)
│   └── di/             # AppModule (Database/DataStore/Network/Repository)
├── domain/
│   ├── model/          # Models.kt
│   ├── repository/     # CREADO en fase 1 — sin él, kapt fallaba
│   └── usecase/        # 32 use cases
├── data/
│   ├── repository/     # RepositoriesImpl (startDownload ya implementado)
│   └── source/         # interfaces
└── feature/
    ├── main/           # MainActivity, App (tema+nav+Snackbar), MainViewModel
    ├── home/           # búsqueda con paginación + hoja de calidad
    ├── downloads/      # lista real desde Room con acciones
    └── settings/       # acciones conectadas al ViewModel
```

Tests: `app/src/test/` → `SearchResponseSanitizerTest` (5),
`StreamSelectionTest` (15), `DownloadFileNameTest` (6).

`core/youtubedl/` fue **eliminado** por completo. `feature/player/` **no existe**.

Config: `compileSdk 34`, `minSdk 21`, `targetSdk 34`, AGP 8.2.2, Kotlin 1.9.24,
Compose compiler 1.5.14, Hilt 2.51, media3 1.3.1.

---

## 10. OTROS DOCUMENTOS

- `docs/ARCHITECTURE.md` — arquitectura por capas (actualizado 2026-10-03)
- `docs/DECISIONS_LOG.md` — ADR-005 **sustituido** por ADR-011/012, y
  ADR-014 para el cliente iOS + selector + multiplexado (actualizado 2026-10-03)
- `docs/MANUAL_DE_DESARROLLO.md` — guía de desarrollo (actualizado 2026-10-03)

Los tres se actualizaron el 2026-10-02 porque describían un `yt-dlp` por
subprocess que **nunca pudo funcionar** en Android, y el 2026-10-03 para
recuperar el cambio al cliente iOS, que se había quedado sin documentar.
