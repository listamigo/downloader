# ESTADO DEL PROYECTO - elimd downloader

> **Documento de traspaso.** Es la referencia para continuar el trabajo.
> Última actualización: **2026-10-09 (sesión de C4)**
> Verificado en dispositivo real: **Xiaomi 220333QAG, Android 16 (API 36), arm64-v8a**

---

## 1. RESUMEN EJECUTIVO

| | |
|---|---|
| Build | **VERDE** — `./gradlew :app:assembleDebug` |
| Tests | **145 unitarios + 12 instrumentados, en verde** |
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

### P4 · ~~ALTA — Más tests~~ → Ampliado a 12 ficheros (2026-10-05)

Ya no son 26 tests en 3 ficheros. El recuento real por fichero:

| Fichero | Tests |
|---|---|
| `core/network/YouTubeSessionTest` | 19 |
| `core/extract/StreamSelectionTest` | 17 |
| `core/download/MuxerFormatTest` | 17 |
| `core/download/SegmentPlannerTest` | 16 |
| `core/extract/StreamResolutionPlanTest` | 12 |
| `core/download/DownloadEtaTest` | 9 |
| `feature/downloads/RemainingLabelTest` | 7 |
| `core/download/ByteFormatTest` | 6 |
| `data/repository/DownloadFileNameTest` | 6 |
| `domain/model/QualityFromLabelTest` | 5 |
| `core/extract/SearchResponseSanitizerTest` | 5 |
| `core/extract/ExtractionErrorsTest` | 4 |
| `core/download/DownloadServicePolicyTest` | 10 |
| `core/download/DownloadSlotsTest` | 4 |
| `core/common/VideoIdTest` | 8 |
| **Total unitarios** | **145** |

(`StreamSelectionTest` tenía 15 según la versión anterior de este documento;
ahora son 17.)

Faltan, por valor:

- `HttpFileDownloader`: reanudar con cabecera `Range`, fichero parcial,
  cancelación por corrutina. Es lo más delicado y usa `MockWebServer`.
- `MediaMuxer`: necesita un dispositivo o instrumentación; no se puede probar
  en la JVM porque depende de los codecs de Android.
- Tests de use cases con MockK. Ojo: **MockK ya está declarado en
  `build.gradle.kts` pero no se usa**, y `ADR-015` lo rechaza explícitamente.
  Hay que decidir una de las dos cosas: quitar la dependencia o revertir el ADR.

### P5 · ~~MEDIA — DownloadService desconectado~~ → **Hecho** (2026-10-07)

El motor arranca el servicio en cada `launchDownload` (punto común a
`startDownload`, `resume` y `retry`), y el propio servicio observa
`progressFlow` y decide cuándo apagarse: mientras haya descargas en cola o en
marcha muestra progreso; cuando el motor queda sin nada activo anuncia el
resultado (completada / fallo con motivo) y cierra. Ver **ADR-020**.

Detalles que hubo que resolver por el camino:

- La lógica de la notificación vive en `DownloadServicePolicy.kt`, sin un
  solo import de Android, para poder testearla en la JVM (10 tests).
- `DownloadEngineProgress` ahora lleva `fileName` y `error`: antes `publish`
  recibía el motivo del fallo y lo tiraba, así que ni la notificación ni la
  columna `error` de Room podían mostrarlo.
- El servicio se detiene solo si el motor no tiene nada trabajando; si el
  sistema lo reinicia tras matar el proceso (`START_STICKY` con `progressFlow`
  a `null`), se cierra en cuanto arranca en vez de colgarse con la
  notificación de "Preparando descarga...".
- `POST_NOTIFICATIONS` **sí se pide en runtime** (`MainActivity:41-50`); la
  línea de §8.1 que decía lo contrario era falsa.
- Sin FGS toda descarga muere con el proceso: era MEDIA, pero con
  multiplexado una descarga larga en background se quedaba a medias sin
  avisar.

Con el multiplexado esto pesa más: unir pistas tarda, así que una descarga
corta en background se quedaba a medias sin avisar. Ya no: el servicio vive
hasta que el motor termina.

### P6 · BAJA — Fondo de pantalla

`WallpaperManager` sin implementar (los ajustes ya persisten el estado).

### P7 · ~~BAJA — Migraciones de Room~~ → Hecho salvo `exportSchema` (2026-10-05)

La base de datos está en **`version = 2`**, con `MIGRATION_1_2` definida en
`AppDatabase.kt` y **registrada** en `AppModule` (`.addMigrations(...)`), sin
`fallbackToDestructiveMigration`: un esquema roto da crash en vez de borrar los
datos en silencio.

Lo único que queda es **`exportSchema = false`** y que no exista `app/schemas/`.
Mientras siga así, Room no puede validar migraciones, `MigrationTestHelper` es
inviable y la migración es SQL libre escrito a mano. Hay que activar
`exportSchema` y fijar `schemaLocation` **antes de la siguiente migración**, o la
validación automática no podrá rescatar nada.


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

### P9 · ~~BAJA — Velocidad real~~ → **Hecho** (2026-10-05)

`DownloadEngineProgress.speed` y `.eta` ya no se emiten a 0:

- `core/download/DownloadEta.kt` calcula velocidad media y tiempo restante real.
- Está cableado en **las tres** rutas de `DownloadEngine` (stream único, dos
  pistas y muxing).
- `HttpFileDownloader` estrangula los ticks a 150 ms antes de emitirlos.
- Cubierto por `DownloadEtaTest` (9 tests).

Único matiz de diseño: durante los primeros 2 s devuelve 0, porque con menos
muestras la velocidad media es ruido. No es un bug.

---

## 8.1 · HALLAZGOS DE LA AUDITORÍA DEL 2026-10-05

Auditoría de solo lectura, ordenada por gravedad. Estado de cada hallazgo tras
la sesión del **2026-10-09**: **C1, C2, C3, C4, C5 y C6 corregidos** (C3
parcial), P5 hecho. **Todos los hallazgos de la auditoría quedan cerrados.**

### C1 · `DownloadEngine` tiene carreras reales → **corregido (2026-10-07)**

| Dónde | Qué pasa | Estado |
|---|---|---|
| `launchDownload` | Si el job terminaba rápido, `invokeOnCompletion` se ejecutaba antes de guardar el job → `job = null` para siempre y la descarga quedaba indestructible. | **Corregido**: arranque `CoroutineStart.LAZY`; el job se guarda y se registra el handler **antes** de `job.start()`, y si la entrada fue cancelada entre medias el job ni arranca. |
| `publishStatus` | `activeDownloads.getValue(downloadId)` podía lanzar `NoSuchElementException` si `cancelDownload` borraba la clave entre las dos líneas. | **Corregido**: `computeIfPresent` atómico. |
| `resumeDownload:296` | `entry.job?.cancel()` es asíncrono: el corrutina viejo seguía escribiendo en `request.target` mientras el relanzado abría otro sobre el mismo fichero. Faltaba `join()`. | **Corregido**: `cancel()` + `join()` antes de relanzar. |
| `cleanup:325` | Cancelaba el `scope` del `@Singleton` (un `val`): después `startDownload` devolvía `success = true` sin lanzar nada jamás. | **Corregido**: `cleanup` solo cancela el trabajo activo; el scope del singleton vive. |
| `RepositoriesImpl.kt:73-102` | El estrangulamiento de 500 ms a Room estaba **anulado**: `lastStatus`/`lastWriteAt` eran un único par global, con 2+ descargas casi todo contaba como cambio. | **Corregido**: estado y reloj **por `downloadId`**. |
| `RepositoriesImpl.kt:286` | `isTerminal()` era código muerto. | **Eliminado** (la regla que prometía su KDoc ya la cumple `statusChanged`). |
| `DownloadEngine.kt` (publish) | `Log.d` en cada tick, con ticks a 150 ms × 4 segmentos. | **Corregido**: solo se logea cambio de estado. |
| `cancelDownload` | `publishStatus` llegaba tarde: la entrada ya se había borrado, así que el `CANCELLED` **nunca se emitía** a `progressFlow` y un foreground service no se habría enterado nunca. | **Corregido**: emisión directa tras borrar la entrada. |

Corregido de paso, porque el motor tiraba la información: `publish` recibía el
motivo del fallo (`error`) y no lo guardaba — `DownloadEngineProgress` ahora
lo lleva, la notificación lo muestra y `observeEngineProgress` lo escribe en la
columna `error` de Room (antes esa columna **nunca** se rellenaba desde el
motor, y el usuario veía "fallida" sin motivo).

Pendiente de esta misma fila: `_progressFlow` sigue siendo **un único
`StateFlow` para todas las descargas**: cualquier consumidor que no sea el
repositorio ve solo la última actualizada. El servicio lo tolera a propósito
(§P5), pero no es el diseño correcto.

### C2 · Violaciones de `r3.md` (prohíbe silenciar excepciones) → **corregido (2026-10-07)**

- `MainViewModel.observeDownloads` — `.catch { }` con lambda **vacía**. Si el
  Flow de Room petaba, la lista de descargas se quedaba congelada para
  siempre, sin log. **Corregido**: `Log.e` + snackbar (`message`); el catch de
  `observeSettings`, que solo quitaba el indicador de carga sin registrar
  nada, también.
- `NewPipeMediaResolver.getRelatedVideos` — tragaba la excepción con
  `runCatching {}.getOrDefault(emptyList())`: si falla la red, "0
  relacionados" se ve como si fuera verdad. **Corregido**: log + rethrow,
  el mismo criterio que ya usa `getVideoInfo` en el mismo fichero. Dato
  relevante: `GetRelatedVideosUseCase` hoy **no lo invoca nadie** (el selector
  de relacionados no existe en la UI), así que el rethrow no rompe nada — pero
  el día que se conecte, ya hereda el comportamiento correcto.
- `YouTubeSearchDataSourceImpl.downloadThumbnail` — el `runCatching` se
  tragaba **cualquier `Throwable`**, incluido un `OutOfMemoryError` de
  `body.bytes()`, sin log. **Corregido**: `catch (e: Exception)` con `Log.w` y
  `null`; los `Error` ya no se silencian. Una miniatura sigue siendo
  cosmética: sin red no hay imagen, pero queda registrado el porqué.

### C3 · Ajustes sin consumidor (la UI promete lo que no ocurre) → **parcial (2026-10-07)**

Hecho:

- **`maxConcurrentDownloads`**: ahora sí se lee. `DownloadEngineImpl` tiene
  un `DownloadSlots` (contador con `compareAndSet`): cada job espera su
  hueco antes de resolver la URL, y el límite se **relee en cada intento**
  para reaccionar si el usuario lo cambia con descargas en marcha (un
  `Semaphore` de kotlinx no admite redimensionarse). Mientras espera, el
  estado sigue siendo `QUEUED` — no se transfiere ningún byte. Cancelado
  durante la espera: no se cogió hueco y no se suelta. Test:
  `DownloadSlotsTest` (4, incluido uno con 16 hilos compitiendo para cazar
  la carrera del CAS).
- **Tres `onClick = { }` vacíos**: la fila "Ubicación de descarga" se
  **eliminó** (el ajuste `downloadLocation` no lo consume nadie y no existe
  selector: la fila prometía algo que no ocurre); "Versión de la aplicación"
  ahora usa `BuildConfig.VERSION_NAME` en vez del `"1.0.0"` hardcodeado
  (habilitado `buildFeatures.buildConfig` en `app/build.gradle.kts`) y
  "Versión" / "Acerca de" son filas informativas **no pulsables** —
  `SettingsItem` acepta `onClick` nullable y sin él la fila no lleva ripple.

Sigue pendiente:

- Interruptor de wallpaper: persiste el flag y no hace nada (**P6**).
- `enableBackgroundAudio`, `autoPlayThumbnails`, `showNotifications`: sin
  consumidor. El campo `downloadLocation` sigue en `AppSettings` (ya no se
  muestra en la UI) hasta que exista un selector que lo respete;
  `RepositoriesImpl:113-116` sigue escribiendo siempre en
  `getExternalFilesDir/descargas`.

### C4 · Seguridad y dependencias — **corregido (2026-10-09)**

- **`.gitignore` tenía un typo** (`tokengit`): no excluía nada, así que un
  `token.txt` o `token.json` se habría commiteado. **Corregido** el
  2026-10-05 → `token*` (verificado con `git check-ignore`).
- **`cookies.txt` real en la raíz del repo** (3278 B, sesión de la cuenta
  propia) → **Corregido** el 2026-10-09: el fichero se movió **fuera del árbol
  de trabajo** a `~/cookies-a-donwloader.txt` (no se pisó el `~/cookies.txt`
  preexistente, que era otro). Ya no hay ninguna credencial en el repo; el
  `.gitignore` sigue excluyendo `cookies.txt` por si reaparece. Para subirlo al
  móvil: `adb push ~/cookies-a-donwloader.txt /sdcard/Android/data/com.elimd.downloader/files/cookies.txt`.
- `OkHttpNewPipeDownloader.kt:111` — `DEBUG_PATHS = true` **sin**
  `BuildConfig.DEBUG` → **Corregido**: ahora es `BuildConfig.DEBUG`, así que el
  diagnóstico (`logPlayability`) solo escribe en logcat en depuración. No filtra
  cookies (solo un booleano), pero sí el motivo del fallo de YouTube.
- `DownloadsScreen.kt:93` — `startActivity(ACTION_VIEW)` sin `try/catch`:
  sin reproductor instalado, **crash** → **Corregido**: `catch
  (ActivityNotFoundException)` con `Log.w` + aviso al usuario.
- `YouTubeSearchDataSourceImpl.kt:57` — el `videoId` de la red se usa como
  nombre de fichero sin sanear. Hoy era seguro por accidente, porque
  `videoIdFromUrl` usa `[\w-]{6,}` → **Corregido**: `core/common/VideoId.kt`
  expone `sanitizeVideoId`, que solo admite `[A-Za-z0-9_-]{1,64}`; la miniatura
  descarta cualquier id con otra forma (antes de construir el nombre de fichero
  o la URL). 8 tests en `VideoIdTest`.
- ~~`POST_NOTIFICATIONS` se declara pero **nunca se pide en runtime**~~:
  **falso**. `MainActivity:41-50` lo pide en `onCreate` desde API 33
  (comprobado 2026-10-07, junto con el resto de esta auditoría).
- `FOREGROUND_SERVICE_DATA_SYNC` con `targetSdk 34`: cuando suba a 35, Android
  impondrá límite de 6 h/día y exigirá `Service.onTimeout()`, que no existe.
- **Dependencias declaradas y sin usar**: `mockk` (y `ADR-015` la rechaza),
  `retrofit`, `coil-compose` (no hay ni un `AsyncImage`: las miniaturas no se
  pintan), `navigation-compose`, `work-runtime-ktx`,
  `okhttp-logging-interceptor`, `media3-exoplayer`/`session`/`ui`.
- `HttpFileDownloader` **no envía cookies ni `Authorization`** a
  `googlevideo.com` (correcto por política), lo que significa que todo lo
  age-restricted o premium fallará aunque la sesión sea válida. No está
  documentado.

### C5 · `server/` es andamiaje abandonado → **corregido 2026-10-09**

El andamiaje muerto (`server/models/song.py`, `server/utils/helpers.py`) se
**borró**. En su lugar se construyó el plan B completo (ADR-022), sin copiar el
proyecto de referencia:

- **`server/`**: backend FastAPI + yt-dlp(librería) + ffmpeg. Núcleo puro
  (`app/quality.py`, `app/naming.py`) cubierto por 30 tests (`python3 -m
  unittest discover -s tests -t .`). Ficheros de despliegue en `server/`
  (`Dockerfile`, `railway.json`, `requirements.txt`, `README.md`). Contrato:
  `/api/health`, `/api/search`, `/api/video/{id}`, `/api/qualities/{id}`,
  `/api/media/{id}` (con `Range`), `/api/cookies`, `/api/related` → 501.
- **Cliente**: `SwitchingMediaResolver` enruta por el ajuste `useRemoteServer`
  (apagado por defecto) entre `NewPipeMediaResolver` y `ServerMediaResolver`;
  activado por qualifiers `@LocalResolver`/`@RemoteResolver` en `AppModule`.
  UI en `SettingsScreen` (switch + URL del servidor).

**Contra el reto bot en Railway (2026-10-09):** YouTube lanza "Sign in to
confirm you're not a bot" a las IPs de datacenter. El servidor ahora ejecuta
todo con **impersonación de navegador** curl-cffi (plan `YTDLP_IMPERSONATE`,
def. `chrome`, con reintento sin impersonar como respaldo); `/api/health` la
reporta. Si aun así apareciese, la palanca queda en `POST /api/cookies`.

**URL embebida (2026-10-09):** el backend por defecto viaja en el APK
(`BuildConfig.DEFAULT_SERVER_URL`, sobreescribible con `-PserverUrl=...`),
provisto por DI (`@DefaultServerUrl` en `AppModule`). `ServerMediaResolver`
cae a esa URL cuando el ajuste `serverUrl` está vacío: **activar el switch de
Ajustes basta**, sin escribir la URL a mano. Escribir una URL siempre gana.

**Hallazgo de calidad (medido, supera la conclusión previa):** sin PO token
YouTube solo entrega **itag 18 (360p)**, ni con cookies ni cambiando
`player_client`. Con PO token (bgutil) + `player_client=default` aparece la
escalera completa hasta **4K**. El techo no es la IP: es el PO token. El
servidor lo soporta por `YT_PO_TOKEN`, `YTDLP_POT_SCRIPT` o `YTDLP_POT_BASEURL`
(sidecar `brainicism/bgutil-ytdlp-pot-provider`).

### C6 · Un test codificaba el diagnóstico equivocado — **corregido 2026-10-05**

`ExtractionErrorsTest` fallaba en `testDebugUnitTest`, y la culpa era del test, no
del código. El caso `el bloqueo de YouTube dice que va por IP` afirmaba que el
mensaje debe mencionar "IP" y "red", pero el diagnóstico correcto (§5: no era la
IP, era la falta de sesión) ya se había aplicado al código y **no al test**.

Test corregido: ahora comprueba que el mensaje habla de `cookies` y de `sesion`, y
además afirma que **no** contiene "IP". Así, si alguien reintroduce el
diagnóstico equivocado, el test lo detecta en vez de@darse por bueno.

Verificado tras el cambio: `testDebugUnitTest` → **123 unitarios, 0 fallos, 0
skipped**, y `assembleDebugAndroidTest` compila.

> Moral: un test que fija el diagnóstico equivocado no es una red de seguridad,
> es una trampa. Este habría "revertido" el arreglo correcto.

---

## 9. ESTRUCTURA DEL CÓDIGO

```
app/src/main/java/com/elimd/downloader/
├── core/
│   ├── extract/        # MediaResolver, SwitchingMediaResolver, NewPipe/ServerMediaResolver, StreamSelection
│   ├── download/       # HttpFileDownloader, MediaMuxer, DownloadEngineImpl
│   ├── database/       # Room: AppDatabase, DownloadEntity, DownloadDao
│   ├── datastore/      # DataStoreSettingsDataSource
│   ├── network/        # YouTubeSearchDataSource (adaptador delgado)
│   ├── common/         # VideoId: forma segura de un id de YouTube
│   └── di/             # AppModule (Database/DataStore/Network/Repository) + qualifiers
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

Tests: `app/src/test/` → **155 tests** (el desglose por fichero está
en §8-P4). Instrumentados: `app/src/androidTest/` → `Mp4RemuxerTest` (6) y
`MediaMuxerTest` (6), 12 tests que necesitan dispositivo.

`core/youtubedl/` fue **eliminado** por completo. `feature/player/` existe pero
está **vacío** (sin `PlayerScreen` ni `PlayerViewModel`).

Backend del plan B (ADR-022): `server/` (FastAPI + yt-dlp + ffmpeg), con 30
tests propios y ficheros de despliegue para Railway. El cliente lo activa con
el ajuste `useRemoteServer`.

Base de datos: `version = 2`, `exportSchema = false`, con `MIGRATION_1_2`
registrada. Ver §8-P7.

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


---

## PRINCIPIO DE BÚSQUEDA DE SOLUCIONES

Cuando no se encuentra una solución, no concluir "imposible". Concluir "no encontrada aún". Siempre existe una vía funcional en sistemas informáticos; el deber es hallarla mediante hipótesis, experimentación controlada y verificación con datos.
