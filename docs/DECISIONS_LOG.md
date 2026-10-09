# DECISIONS LOG (ADR) - elimd downloader

Registro de decisiones técnicas, alternativas evaluadas y justificaciones.

---

## ADR-001: Arquitectura Base - Clean Architecture + MVI/MVVM
- **Fecha**: 2026-10-02
- **Decisión**: Adoptar Clean Architecture con separación estricta en capas (Presentation, Domain, Data, Core) y patrón MVI/MVVM para la UI.
- **Alternativas consideradas**:
  - MVP (Model-View-Presenter): Requiere más boilerplate y es menos reactivo.
  - MVVM puro sin capas de dominio: Menor escalabilidad y testing de lógica de negocio.
- **Justificación**: Permite testing independiente de cada capa, desacopla la UI de la lógica de negocio, y facilita la migración a múltiples plataformas.
- **Consecuencias**: Mayor inicial de estructura de archivos, pero mayor mantenibilidad a largo plazo.

---

## ADR-002: Gestión de Estado con StateFlow (Unidirectional Data Flow)
- **Fecha**: 2026-10-02
- **Decisión**: Usar `StateFlow`/`MutableStateFlow` en ViewModels en lugar de `LiveData` o mutación directa de estado.
- **Alternativas consideradas**:
  - `LiveData`: Menos integración con Coroutines y Flows.
  - Estado mutable directo en Composables: Dificulta el debugging y testing.
- **Justificación**: UDF evita bugs de concurrencia, facilita el replay de estado y se integra perfectamente con Coroutines.

---

## ADR-003: Inyección de Dependencias con Hilt
- **Fecha**: 2026-10-02
- **Decisión**: Usar Hilt para inyección de dependencias.
- **Alternativas consideradas**:
  - Koin: Más liviano, pero Hilt tiene mejor integración con el ecosistema Google.
  - Inyección manual: No escalable.
- **Justificación**: Hilt genera código de inyección de forma automática, reduce boilerplate y está optimizado para Android.

---

## ADR-004: Version Catalog (libs.versions.toml)
- **Fecha**: 2026-10-02
- **Decisión**: Usar `gradle/libs.versions.toml` para centralizar dependencias.
- **Justificación**: Centraliza las versiones y librerías, facilita la actualización y evita conflictos de versiones entre módulos.

---

## ADR-005: yt-dlp como motor de descarga (Subprocess) — **SUSTITUIDO, NO VIABLE**
- **Fecha**: 2026-10-02 (revisado y marcado inviable el mismo día)
- **Decisión original**: Usar yt-dlp ejecutado como subprocesso nativo.
- **Alternativas consideradas**:
  - API oficial de YouTube (requiere API key, límite de cuota, y no permite descargas directas).
  - Librerías de pago (prohibidas por las directrices).
  - youtube-dl (predecesor, menos activo).
- **Justificación original**: yt-dlp es 100% gratuito, open-source y actualizado a diario. *"Se invoca via `ProcessBuilder` desde Android."*

### Por qué se cayó

La premisa central —invocarlo con `ProcessBuilder` desde Android— es falsa:

1. **Android no tiene shell.** No existe `PATH`, ni permiso `exec` para
   binarios arbitrarios, ni un intérprete de Python. `ProcessBuilder("yt-dlp")`
   falla siempre, siempre.
2. **No hay build de yt-dlp para Android.** Los releases oficiales (verificados
   el 2026-08-19) publican `yt-dlp`, `yt-dlp.exe`, `yt-dlp_linux` y
   `yt-dlp.tar.gz`. Ninguno para Android.
3. **El empaquetado no es portable.** Los binarios de PyInstaller se enlazan
   contra **glibc**; Android usa **bionic**. Cruz-compilar no es viable.

La cláusula de "consecuencias" ("se puede embebir en `assets/`") era
especialmente engañosa: no es técnicamente posible.

**Descartado también**: Termux (obliga al usuario a instalar otra app, IPC
frágil, y Android 10+ estorba la ejecución en segundo plano).

**Sustituido por**: ADR-011 (motor sin servidor) y ADR-012 (plan B con
servidor). Se conserva este ADR deliberadamente, como registro del error, para
que ninguna sesión futura vuelva a proponerlo.

---

## ADR-011: NewPipeExtractor + OkHttp como motor de descarga, sin servidor
- **Fecha**: 2026-10-02
- **Estado**: **Aceptada e implementada. Verificada descargando en dispositivo real.**
- **Decisión**: Resolver y descargar directamente contra YouTube usando
  NewPipeExtractor (Java puro) y OkHttp, sin ningún componente externo.
- **Alternativas consideradas**:
  - yt-dlp por subprocess (ADR-005): imposible, ver arriba.
  - API oficial de YouTube: clave de developer, cuota limitada y sin descargas.
  - Librerías de pago: prohibidas por las directrices.
  - NewPipeExtractor: ya es el extractor real de la app NewPipe, en producción.
- **Justificación**: Es **Java puro**, sin binario nativo, sin servidor y sin
  coste. Descifra las URLs firmadas de YouTube usando Rhino como motor
  JavaScript, y resuelve el problema criptográfico que bloquea a las
  implementaciones naive. Al ser Java, se puede probar en la JVM del host
  contra YouTube real antes de confiar en el build de Android.
- **Consecuencias**:
  - Se resuelve por **JitPack**, que compila desde el código en cada
    resolución: build más lento y dependiente de la disponibilidad de JitPack.
  - Usa el cliente InnerTube `ANDROID`, que es el más bloqueado; produce
    `SignInConfirmNotBotException` en muchos vídeos (ver `ESTADO_PROYECTO.md` §5).
  - Solo hay streams *progresivos* hasta ~720p; para más hay que descargar
    vídeo y audio por separado y muxear (`media3-transformer`, pendiente).

---

## ADR-012: La costura `MediaResolver` para permitir un plan B con servidor
- **Fecha**: 2026-10-02
- **Decisión**: Aislar toda resolución de medios detrás de la interfaz
  `MediaResolver`, con `NewPipeMediaResolver` como implementación actual y
  `ServerMediaResolver` (servidor propio con yt-dlp) como alternativa.
- **Alternativas consideradas**:
  - Acoplar NewPipeExtractor directamente en la capa `data`: más rápido de
    escribir, pero hace que cambiar de motor signifique reescribir dominio y UI.
  - Plan B como tarea de reescritura:rowser alto para lo que probablemente es
    solo una implementación más.
- **Justificación**: Mantiene la puerta abierta al plan B a coste casi nulo, ya
  que el bloqueo de YouTube (§5 de `ESTADO_PROYECTO.md`) puede endurecerse. Con
  la interfaz, activar el plan B es **un único cambio de binding en
  `AppModule`**: dominio y presentación no se tocan.
- **Consecuencias**: Una abstracción adicional. Se acepta: el coste es bajo y la
  alternativa (re-escribir la capa de resolución) sería alto. El plan B exige
  una máquina siempre encendida, lo que puede no ser aceptable para el usuario.

---

## ADR-013: Progreso como `StateFlow`, con persistencia estrangulada
- **Fecha**: 2026-10-02
- **Decisión**: El progreso de descarga se modela como `StateFlow` (no
  `SharedFlow`) y se estrangula la escritura en Room a una cada 500 ms,
  escribiendo **siempre** los cambios de estado, incluidos los terminales.
- **Problema resuelto**: con `SharedFlow` (buffer 64) la descarga generaba
  cientos de ticks por segundo; el buffer se llenaba, `tryEmit` **descartaba
  silenciosamente** y el evento terminal `COMPLETED` **se perdía**. La barra
  llegaba al 100 % pero la descarga nunca se completaba (ver `ESTADO_PROYECTO.md` B2).
- **Justificación**: El progreso es *estado*, no un evento. `StateFlow` siempre
  converge al último valor y no pierde el final. Además `SharedFlow` habría
  hecho obligatorio un búfer en la UI, que no se quiere: la UI siempre quiere
  el valor actual.
- **Consecuencias**: Cualquier consumidor recibe el valor actual al suscribirse
  (cómodo en Compose). El estrangulamiento evita un número excesivo de
  escrituras en Room sin sacrificar los estados terminales.

---

## ADR-014: Cliente iOS de InnerTube, selector de calidad y multiplexado
- **Fecha**: 2026-10-03
- **Estado**: **Aceptada e implementada. Compila y con tests; pendiente de
  verificar en dispositivo real.**
- **Problema**: con el cliente ANDROID, `dQw4w9WgXcQ` exponía **1 solo
  formato** (360p). No había nada que elegir, y la calidad era un
  `DownloadQuality(id = "18")` hardcodeado en el ViewModel. Por encima de 720p
  además es imposible: YouTube solo da audio y video juntos hasta ahí.
- **Decisión**: tres cambios encadenados.
  1. Forzar el cliente **iOS** de InnerTube
     (`YoutubeStreamExtractor.setFetchIosClient(true)`), que expone 27 formatos
     frente a 1 del cliente ANDROID.
  2. Pedir las calidades **al resolver** y ofrecerlas en una hoja antes de
     descargar, en vez de asumirlas.
  3. Bajar y **unir** video y audio por separado cuando la calidad lo requiere,
     con `media3-transformer`.
- **Alternativas consideradas**:
  - **Dejar el cliente ANDROID y subir de versión del extractor**: no resuelve
    que el catálogo sea de 1 formato; el problema es del cliente elegido.
  - **Dejar el máximo en el motor sin preguntar al usuario**: más simple, pero
    el usuario pierde el control y el coste (unir pistas) es invisible.
  - **`ffmpeg` como binario nativo**: descartado por ADR-005 (no hay build ni
    `exec` para binarios arbitrarios en Android).
  - **MP4Box / concatenation binaria**: mismo motivo que `ffmpeg`.
  - **Dejar 720p como techo**: se descartó explícitamente porque con el cliente
    iOS las pistas de 1080p+ ya están disponibles; no unitedlas sería dejar la
    mitad del trabajo hecho.
- **Justificación**:
  - El cliente iOS es una constante de una línea, y su efecto se midió: 27
    formatos frente a 1. No tiene coste ni riesgo propio.
  - **Aviso explícito, con medición posterior**: esto **no** arregla el
    `SignInConfirmNotBotException` (§5.1 de `ESTADO_PROYECTO.md`). Se consultó
    `youtubei/v1/player` con 7 clientes de InnerTube y **ninguno** pasa desde
    esta IP: el bloqueo es de la red, no del cliente. Además, descompilando
    `onFetchPage` se vio que `fetchAndroidClient` se ejecuta **siempre primero y
    sin try/catch**, así que el flag solo *añade* una consulta iOS cuando Android
    ya ha funcionado: nunca evita el fallo de Android. Se mantiene porque en el
    caso bueno sí amplía el catálogo, y porque es inofensiva.
  - Unir pistas es el precio de las calidades altas. Se hace **en el
    dispositivo** y solo cuando hace falta: si hay un progresivo que coincide,
    se descarga en un solo fichero sin unión ni recodificación.
  - La salida se fuerza a **H.264 + AAC** en MP4 para que abra en cualquier
    reproductor, y a igualdad de resolución **se prefiere el stream H.264**,
    que media3 puede empaquetar sin recodificar (transmux) en vez de convertir.
- **Consecuencias**:
  - La calidad pasa a ser **el dato más caro del flujo**: resolver el detalle
    del vídeo es justo lo que YouTube bloquea por IP, así que ahora el fallo
    aparece al abrir la hoja de calidad, no al pulsar descargar. Por eso la
    hoja muestra el error en vez de cerrarse en silencio.
  - Una descarga >720p ocupa **el doble de disco** mientras dura (dos
    temporales más el resultado) y tarda más. El selector marca esas opciones
    con `requiresMuxing` para que el coste sea visible antes de confirmar.
  - Si la máxima calidad solo existe en VP9, media3 tiene que recodificar a
    H.264 en el dispositivo: lento, y dependiente de los codecs del teléfono.
  - "Solo video" por encima de 720p también pasa por el muxer aunque no haya
    audio: la pista suelta llega en WebM y guardarla con extensión `.mp4`
    produciría un fichero que muchos reproductores no abren.
  - `Transformer` exige un hilo con `Looper`, así que `MediaMuxer` levanta un
    `HandlerThread` propio y espera el resultado desde la corrutina.

---

## ADR-015: La política de calidad se separa del extractor para poder testearla
- **Fecha**: 2026-10-03
- **Decisión**: extraer la lógica de "qué stream sirve para esta petición" a
  `StreamSelection`, un objeto sin dependencias de NewPipeExtractor, que opera
  sobre un modelo propio (`StreamCandidate`). `NewPipeMediaResolver` solo
  traduce.
- **Problema que resolvió**: la lógica de selección estaba dentro del resolver,
  mezclada con llamadas de red. Para probarla hacía falta construir un
  `StreamInfo` real, contra YouTube, con un test que no se puede ejecutar sin
  red ni credenciales. Es exactamente el patrón que ya había destapado B1-B3:
  **lo que no se puede probar, no está verificado.**
- **Alternativas consideradas**:
  - **Probar el resolver contra YouTube real** (como se hizo en la fase 1):
    válido para descubrir bugs de la API, pero frágil, lento y dependiente de
    la red. Para lógica de decisión pura es la herramienta equivocada.
  - **Usar los builders de `VideoStream`/`AudioStream` en los tests**: posible
    (`VideoStream.Builder` es público), pero ata los tests al extractor: un
    cambio upstream los rompe y hay que conocer cada builder.
  - **Mockear el extractor con MockK**: añade una dependencia de mock para
    probar lógica que no depende de nadie.
- **Justificación**: la política es lo que decide si el usuario recibe el 1080p
  que pidió o un 360p en silencio. Es lógica de negocio puro y merece tests
  baratos y deterministas. Los 15 tests de `StreamSelectionTest` corren en la
  JVM en 5 s y **ya encontraron un bug real** (B9: el criterio de H.264 mandaba
  sobre la altura, así que "Mejor" devolvía 1080p en lugar de 2160p).
- **Consecuencias**: una capa más de tipos. Se acepta porque el alternativa
  es tener la decisión más importante de la app sin ninguna verificación
  automática. `MediaMuxer` queda fuera de este tratamiento: depende de los
  codecs de Android y no se puede probar en la JVM.

---

## ADR-016: Cookies de sesión en lugar de servidor (plan B descartado)
- **Fecha**: 2026-10-03
- **Estado**: **Aceptada, implementada y verificada en dispositivo.**
- **Problema**: desde la IP del usuario, YouTube devolvía
  `SignInConfirmNotBotException` en el 100 % de los vídeos, con cualquier
  cliente de InnerTube (7 probados) y con cualquier `player_client` de yt-dlp
  (5 probados). La app no podía descargar nada.
- **Decisión**: cargar un `cookies.txt` de la cuenta del usuario y enviarlo como
  cabecera `Cookie` en las peticiones del extractor. Sin servidor.
- **Alternativas consideradas, y por qué se descartaron**:
  - **Plan B: servidor con yt-dlp** (era lo que se iba a hacer). Descartado al
    medir que no hacía falta: con cookies, la app funciona sola. Añadía hosting,
    coste y un proceso más que mantener sin resolver nada pendiente.
    Se dejó escrito además que **Vercel no sirve** (serverless mata una descarga
    de 10 minutos y cobra la salida por GB) y que **un VPS cambia un bloqueo por
    otro**: las IP de centro de datos están más marcadas que una residencial.
  - **PO tokens** (`PoTokenProvider`). Descartado como solución *suficiente*:
    el PO token es un complemento de la sesión, no un sustituto. Se deja la
    puerta abierta si el acceso anónimo vuelve a cerrarse.
  - **Fork de NewPipeExtractor** para llamar a `fetchIosClient` sin pasar por
    Android. Innecesario una vez medido (§5.1): iOS también devuelve
    `LOGIN_REQUIRED` desde esta IP.
  - **Proxies rotatorios**. Coste recurrente sin garantía: el bloqueo, en última instancia,
    depende de la reputación de la IP de salida, no del cliente.
  - **WebView con el bot-guard dentro de la app** para sacar el PO token en
    local. Complejo (WebView + WebViewAssetLoader) para algo que la cookie ya
    resuelve.
- **Justificación**:
  - El mensaje de YouTube ("Sign in to confirm that you're **not a bot**") y el
    de yt-dlp ("Use --cookies-from-browser or --cookies") apuntan a lo mismo:
    faltaba una **sesión**, no potencia de cálculo. Se llevaba una sesión
    pidiendo más potencia.
  - Es la vía que la comunidad usa en la práctica, y es **gratis**: cero
    hosting, cero coste recurrente. Es justo lo que pide `r1.md`
    ("100% gratuitas... en lugar de APIs de pago").
  - NewPipeExtractor **no** tiene soporte de cookies, pero no lo necesita:
    todo lo que pide pasa por `OkHttpNewPipeDownloader`, que es nuestro.
- **Consecuencias**:
  - **La app tiene una dependencia externa del usuario**: hay que tener una
    cuenta y exportar el `cookies.txt`. No es automatizable, y las cookies
    caducan. Es el precio a pagar por no depender de un servidor.
  - **Las cookies son una credencial y se tratan como tal**: nunca se comitean
    (`.gitignore`), nunca se registran en el log, y hay tests que comprueban que
    **no se envían a ningún host que no sea YouTube** — en particular, no al CDN
    `googlevideo.com`, que es donde bajan los medios. Mandar la cookie de tu
    cuenta a un CDN de terceros sería una fuga sin motivo.
  - Si el `cookies.txt` no trae ninguna cookie de sesión real, la app **lo
    ignora** y sigue en modo anónimo. Fingir que hay sesión produciendo el mismo
    `LOGIN_REQUIRED` sin explicación sería peor que no tenerla.
  - Queda pendiente (P10) poder pegarlas desde Ajustes y traducir el
    `LOGIN_REQUIRED` a un mensaje que diga "renueva las cookies".

---

## ADR-017: La política de cookies se aísla en `YouTubeSession`
- **Fecha**: 2026-10-03
- **Decisión**: el parseo de `cookies.txt`, el filtro de dominios y caducidades,
  y la política de a qué host se envía la cabecera viven en
  `core/network/YouTubeSession`, no dispersos por el downloader.
- **Motivo**: hay dos riesgos opuestos y ambos rompen la app. Que se envíe una
  cookie inútil produce `LOGIN_REQUIRED` sin explicación; que se envíe a un host
  equivocado es una fuga de credencial. Son dos decisiones que merecen estar
  juntas y cubiertas por tests, no escondidas en la construcción de una cabecera.
- **Consecuencias**: 14 tests. Cubren el parseo del formato Netscape (líneas
  HttpOnly comentadas, caducidades, líneas mal formadas, dominios ajenos) y la
  política de envío (que no salga hacia `googlevideo.com` ni hacia un host que
  solo se le parezca a YouTube). Se descarta el fichero entero cuando no hay
  cookie de sesión, en vez de devolver una cabecera vacía.

---

## ADR-018: Remux propio con `MediaMuxer` del sistema, no con `media3-transformer`
- **Fecha**: 2026-10-03
- **Problema medido**: un 1080p de 4 minutos salía con **54 MB de un origen de
  17,9 MB**, y `ffprobe` mostraba que el origen era H.264 Constrained Baseline
  nivel 3.0 a 446 kbps mientras la salida era **High nivel 5.0 a 1,6 Mbps**.
  Constrained Baseline no se convierte en High: el vídeo estaba siendo recodificado
  aunque no hiciera falta, y por eso pesaba tres veces más y tardaba 46 s en
  terminar.
- **Causa raíz (verificada en el bytecode de media3 1.3.1, no de su docs)**: el
  camino de `Transformer` que no codifica solo cubre **un único fichero MP4 con
  audio y video dentro**. Se comprueba en `Transformer.remuxRemainingMedia()`,
  que solo mira `composition.sequences[0].editedMediaItems[0]` y contrasta sus
  dos formatos con los del muxer (`doesFormatsMatch`, que exige además
  `initializationDataEquals`). Nuestra composición tiene **dos secuencias** —
  el vídeo suelto de un fichero y el audio de otro— porque es justamente eso lo
  que exige YouTube por encima de 720p. Ese camino, por tanto, **no es aplicable
  nunca**, y la librería cae siempre en `processFullInput()`: el vídeo decodifica
  y el encoder elige sus parámetros.
- **Decisión**: **`core/download/Mp4Remuxer.kt`**, con `MediaExtractor` +
  `MediaMuxer` del propio Android, para el caso en que las pistas ya caben en un
  MP4 (H.264 + AAC), que es el que el selector elige siempre
  (`StreamSelection.pickFor` y `bestAudio` ya priorizan MP4). `media3-transformer`
  queda solo para cuando hay que convertir de verdad: un VP9 o AV1 a H.264, un
  Opus a AAC, o un contenedor que no sea MP4.
- **Alternativas descartadas**:
  - **`enableHighQualityTargeting(false)`**: es la palanca que parecía el arreglo
    (existe como `VideoEncoderSettings.experimentalSetEnableHighQualityTargeting`).
    Acota el bitrate pero no elimina la conversión: el resultado fue un efecto
    parcial (62 MB → 51 MB) y el perfil seguía siendo High. No era la causa.
  - **Bajar el itag por debajo de 720p**, donde YouTube entrega audio y video
    juntos: evitaría el problema haciendo el mux innecesario, a costa de quitarle
    al usuario todas las calidades por encima de 720p. El bug no puede resolverse
    quitando una función.
  - **Remux manual de dos MP4** intercalando muestras en un `moov` común, sin
    `MediaMuxer`: más control, pero es reimplementar el muxer de la plataforma
    para obtener el mismo resultado.
- **Consecuencias**: salida ≈ entrada (medido 59.649.224 B → 59.683.246 B, un
  0,06 % de cabecera) y 2,7 s de unión en lugar de 46 s. La ruta de conversión
  conserva el bitrate de origen y desactiva el *high quality targeting*, que por
  defecto sube la calidad por encima de lo pedido. Si el remux no puede hacerse,
  lanza `RemuxNotPossible` y se cae a la conversión: nunca se entrega un MP4 al
  que le falte una pista.

---

## ADR-019: El fin de una pista lo decide `readSampleData`, no `sampleTime`
- **Fecha**: 2026-10-03
- **Síntoma**: el remuxaba bien el vídeo y se comía el audio entero. El MP4
  resultante tenía imagen y ningún sonido.
- **Causa raíz**: el audio de los ficheros MP4 lleva `elst` con el retardo del
  encoder (`media_time=1024`), y Android lo descuenta: la primera muestra de un
  m4a a 44,1 kHz llega con `sampleTime = -23219` µs. El código tomaba tomaba
  cualquier tiempo negativo por "no hay más muestras", así que descartaba la
  pista entera. **No es un asset defectuoso**: el `ffprobe` lo da por bueno
  (AAC, 131 frames) y los ficheros de audio de YouTube llevan la misma edit list.
- **Decisión**: el final de pista se decide por el valor devuelto por
  `readSampleData` (negativo = no quedan muestras). `sampleTime` solo se usa
  para ordenar. Además cada pista se rebasa en su primer sample, con lo que el
  desfase de la edit list desaparece, y el tiempo que se escribe tiene suelo 0
  porque el muxer rechaza escribir antes del inicio.
- **Consecuencias**: el audio se copia entero y en orden. Cubierto por
  `Mp4RemuxerTest`, que compara número de muestras y bytes de entrada y salida.
  La misma clase comprueba además que el formato no cambia (mismo `csd-0`, que es
  la prueba de que no se recodificó) y que las pistas se intercalan.

---
- **Fecha**: 2026-10-02
- **Decisión**: Usar Room para descargas y DataStore para configuración.
- **Alternativas consideradas**:
  - SQLite crudo: Más boilerplate y sin Flow.
  - SharedPreferences: Menos type-safe.
- **Justificación**: Room proporciona DAOs, Flow, y migraciones. DataStore es type-safe y reactivo.

---

## ADR-007: Media3 (ExoPlayer) para reproductor
- **Fecha**: 2026-10-02
- **Decisión**: Usar Media3 para el reproductor de video/audio.
- **Alternativas consideradas**:
  - ExoPlayer directo: Media3 es la evolución oficial.
  - MediaPlayer nativo: Menos características.
- **Justificación**: Media3 es gratuito, soporta audio/video, background playback, y tiene integración con notificaciones.

---

## ADR-008: Configuración de Tema con Material 3
- **Fecha**: 2026-10-02
- **Decisión**: Usar Material 3 ColorScheme con soporte para LIGHT, DARK, SYSTEM, y AMOLED.
- **Justificación**: Material 3 es el estándar de Google, y el modo AMOLED es una diferenciadora clave para usuarios con pantallas OLED.

---

## ADR-009: Integración con reproductores externos
- **Fecha**: 2026-10-02
- **Decisión**: Usar `Intent.ACTION_VIEW` para permitir al usuario elegir el reproductor.
- **Justificación**: Es el estándar de Android para interoperabilidad con apps de terceros.

---

## ADR-010: Notificaciones con Foreground Service
- **Fecha**: 2026-10-02
- **Decisión**: Usar Foreground Service + Notificación para descargas en background.
- **Justificación**: Permite al usuario monitorear descargas y cancelarlas desde la barra de notificaciones.

---

## ADR-020: El foreground service lo arranca el motor y se apaga solo
- **Fecha**: 2026-10-07
- **Decisión**: `DownloadEngineImpl` inyecta `@ApplicationContext` y arranca
  `DownloadService` en `launchDownload` (punto común a `startDownload`,
  `resume` y `retry`). El servicio inyecta el motor, observa `progressFlow` y
  decide: progreso mientras haya descargas trabajando; anuncio de resultado y
  `stopSelf` cuando el motor queda vacío. La decisión pura vive en
  `DownloadServicePolicy.kt`, sin imports de Android.
- **Alternativas consideradas**:
  - Arrancarlo desde `DownloadRepositoryImpl` (ya tenía `Context`): duplica el
    arranque en `resume` y mete un servicio Android en la capa `data`.
  - Enlazar al servicio desde la UI (`bindService`): la app puede no estar en
    pantalla, que es justo el caso de uso.
  - WorkManager: reintenta y persiste, pero no puede mantener una notificación
    de progreso continua mientras transfiere bytes.
- **Justificación**: con el servicio desconectado (P5) cualquier descarga
  moría con el proceso y, con multiplexado, una descarga larga en background
  se quedaba a medias sin avisar. `launchDownload` es el único punto por el
  que pasa todo el trabajo nuevo, así que el servicio no puede quedar sin
  arrancar; el criterio de parada sale del propio motor (entradas activas), no
  de la UI. Mantener la política fuera del servicio permite probarla en la
  JVM: 10 tests en `DownloadServicePolicyTest`.
- **Nota**: `DownloadEngineProgress` gana `fileName` y `error` para que la
  notificación (que vive fuera de la app y no puede leer Room) sepa qué fichero
  falló y por qué; de paso, la columna `error` de Room por fin se rellena
  desde el motor.

## ADR-021: Endurecer la superficie de seguridad (C4 de la auditoría)
- **Fecha**: 2026-10-09
- **Decisión**: cuatro cambios independientes sobre la misma idea ("lo que
  viene de fuera no se usa a ciegas"):
  1. **Credencial fuera del árbol**: `cookies.txt` se mueve de la raíz del repo
     a `~/cookies-a-donwloader.txt`. Sigue en `.gitignore`, pero además deja de
     estar físicamente en el directorio de trabajo.
  2. **`DEBUG_PATHS = BuildConfig.DEBUG`**: el diagnóstico de
     `playabilityStatus` solo se registra en depuración.
  3. **`ACTION_VIEW` defendido** en `DownloadsScreen`: `catch
     (ActivityNotFoundException)` con log y aviso al usuario (complementa
     ADR-009).
  4. **`sanitizeVideoId`** (`core/common/VideoId.kt`): valida la forma del id de
     YouTube (`[A-Za-z0-9_-]{1,64}`) antes de usarlo como nombre de fichero o
     trozo de URL; si no encaja, la miniatura se omite.
- **Alternativas consideradas**:
  - Dejar `cookies.txt` en el repo confiando en `.gitignore`: protege el commit,
    pero no un `cp -r`, un backup ni una captura. Insuficiente.
  - Mover el fichero a `files/` dentro del repo: no resuelve nada, sigue
    estando en el árbol.
  - `DEBUG = true` con un flag manual: una rama que alguien olvidará cambiar en
    release; `BuildConfig.DEBUG` es el propio compilador.
  - `runCatching` genérico alrededor del `startActivity`: prohibido por `r3.md`
    (oculta también fallos reales); se captura la excepción concreta.
  - "Arreglar" el `videoId` en vez de rechazarlo: un id reparado apunta a otro
    vídeo; devolver `null` es la respuesta honesta.
- **Justificación**: son los hallazgos C4 de la auditoría del 2026-10-05. El
  repo no tenía fugas activas, pero tenía una credencial en el árbol y tres
  usos de datos de red sin validar. Ninguno cuesta rendimiento; todos eliminan
  formas de fallo que no dependen de que el código de arriba siga portándose
  bien.
- **Verificado**: `./gradlew :app:testDebugUnitTest :app:assembleDebug` →
  **BUILD SUCCESSFUL, 145 tests, 0 fallos** (8 nuevos de `VideoIdTest`).

---

## ADR-022: Backend remoto opcional (plan B) activable a demanda — C5
- **Fecha**: 2026-10-09
- **Contexto**: C5 de la auditoría. `server/` era andamiaje muerto. El
  `NewPipeMediaResolver` local es la ruta por defecto y gratis, pero cuando
  YouTube reta la IP (bloqueo "Sign in to confirm you're not a bot") deja de
  funcionar. El plan B es un servidor propio con `yt-dlp` detrás de la **misma**
  interfaz `MediaResolver` (costura ya prevista en ADR-012), activable con un
  interruptor para no imponer coste ni proceso a quien no lo use.
- **Decisión**:
  1. **Contrato API REST** entre app y servidor (ver §Contrato). El servidor
     entrega metadatos y **sirve los bytes del medio ya muxeado**; no devuelve
     URLs firmadas de `googlevideo`, que están atadas a la IP que las pidió y
     fallarían (403) al bajarlas desde el móvil.
  2. **El servidor muxea**. `resolveStream` devuelve un único MP4 (video+audio
     unidos con ffmpeg) o un M4A. Así `DownloadEngineImpl.needsJoining()`
     (línea 327) toma la rama `downloadSingle` y el móvil solo baja un archivo
     con `HttpFileDownloader` (paralelo por segmentos + reanudable por Range).
  3. **Selección de backend**: nuevo `SwitchingMediaResolver` (implementa
     `MediaResolver`) que lee en cada llamada si el interruptor está encendido
     y delega en `NewPipeMediaResolver` (local) o `ServerMediaResolver`
     (remoto). Es el binding sin cualificar de `MediaResolver`; los dos backends
     se inyectan con los qualifiers `@LocalResolver`/`@RemoteResolver`.
  4. **Calidades honestas**: la escalera de calidades sale de los formatos
     reales que `yt-dlp` obtiene en ese servidor; no se recorta ni se asume
     ningún techo. Si una calidad pedida no está disponible, el servidor
     responde error explícito (no entrega otra calidad en su lugar), coherente
     con `StreamSelection.Plan.None`.
  5. **Sin sustitución silenciosa**: en modo servidor, un fallo del servidor se
     propaga (r3). No hay fallback mágico a local que oculte la causa.
- **Alternativas consideradas (todas OSS/gratuitas)**:
  - **`http.server` de la stdlib** (lo que usa el proyecto de referencia):
    suficiente, pero sin validación de esquema, sin OpenAPI y con manejo de
    Range/streaming manual. Descartado como base; se reutiliza su *conocimiento*
    (orden de `player_client`, proxy, cookies), no su código.
  - **FastAPI + uvicorn**: tipado con Pydantic, validación de entrada y
    OpenAPI autogenerado (`/docs`) para poder probar el contrato desde el
    navegador. Elegido.
  - **Flask**: síncrono y sin validación nativa; menos adecuado para el
    patrón `ensure-then-serve` (descarga bloqueante en un hilo).
  - **Node/Express**: obligaría a un segundo ecosistema; el de referencia ya
    es Python y `yt-dlp` es Python.
  - **Devolver URLs directas de `googlevideo`** (y bajar desde el móvil): la
    URL va firmada con la IP del que la pidió; desde otra IP da 403. Descartado
    tras el análisis (endurece la app a un fallo garantizado).
  - **Servir por streaming chunked sin Range** (como la referencia): obliga a
    descarga secuencial, sin reanudar y sin progreso con tamaño conocido. Se
    materializa y se sirve con Range: progreso real y paralelismo.
- **Superar el techo de 360p (medido, no asumido)**: la referencia
  (`continuarv4.md`) atribuía el techo de 360p a la IP de datacenter y proponía
  `mweb` para abrir la escalera. La medición del 2026-10-09 en este entorno
  **corrige ese diagnóstico**: la palanca es el **PO token**, no la IP ni el
  cliente. Sin PO token, *ningún* cliente pasa de **itag 18 (360p)**, ni con
  cookies. Con PO token (bgutil) + `player_client=default` aparece la escalera
  completa hasta **4K**; `mweb` de hecho **falla** con PO token
  ("enumerate_adapters"). El servidor por tanto:
  - usa `default` como primer cliente (`DEFAULT_VIDEO_CLIENTS`), no `mweb`,
  - soporta el PO token por `YT_PO_TOKEN`, `YTDLP_POT_SCRIPT` (modo script con
    Node) o `YTDLP_POT_BASEURL` (sidecar `brainicism/bgutil-ytdlp-pot-provider`),
  - acepta **proxy residencial por entorno** (`YTDLP_PROXY`) y cookies
    (`POST /api/cookies` o `YTDLP_COOKIES`) como refuerzo,
  - y reporta en `/api/health` qué palancas hay activas, para diagnosticar en
    vez de adivinar. El techo se **mide** en cada despliegue, no se fija.
- **Contrato API** (JSON salvo el medio):
  - `GET /api/health` → estado, versión, `yt_dlp`, `cookies`, `proxy`.
  - `POST /api/cookies` (cuerpo con el `cookies.txt` Netscape) / `DELETE`.
  - `GET /api/search?q=&page=` → `{query, videos[], nextPage, hasNextPage}`.
  - `GET /api/video/{id}` → `YouTubeVideoDTO`.
  - `GET /api/qualities/{id}` → `[QualityDTO]` (escalera real).
  - `GET /api/related/{id}` → **501** (yt-dlp no los ofrece; el cliente los
    delega a local, que es barato y no necesita red del servidor).
  - `GET /api/media/{id}?quality=&type=video|audio` → bytes, con
    `Accept-Ranges: bytes` y soporte `Range`/206. Materialización con caché en
    disco y lock por clave para que la sonda `Range: bytes=0-0` de
    `HttpFileDownloader` y los 4 segmentos no repitan la descarga.
- **Formato de id de calidad** (estable y legible, no itag): `best`,
  `v:<height>:<fps>` (fps 0 si no aplica), `a:<kbps>`.
- **Consecuencias**:
  - Añade dependencias de runtime al servidor (`fastapi`, `uvicorn[standard]`,
    `yt-dlp`), todas MIT/Unlicense. La app NO añade dependencias: usa
    `kotlinx.serialization` y `OkHttp` ya presentes.
  - El interruptor está **apagado por defecto**: sin servidor configurado la
    app se comporta exactamente como hoy.
  - `server/` deja de ser andamiaje: se versiona con `requirements.txt`,
    `Dockerfile` y `tests/`.
- **Verificado (2026-10-09)**:
  - Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → **BUILD
    SUCCESSFUL, 155 tests, 0 fallos** (incluye `ServerMediaResolverTest`,
    `SwitchingMediaResolverTest`).
  - Servidor: `python3 -m unittest discover -s tests -t .` → **30 tests OK**;
    verificado E2E en local (health, search, escalera completa con PO token,
    `Range` 206 + caché, ffprobe H.264+AAC).
- **Pendiente**: despliegue real (requiere `gh auth login` y `railway login`
  del usuario; `docker` no está instalado localmente).
