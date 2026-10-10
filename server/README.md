# adonwloader backend (plan B)

Servidor opcional que resuelve YouTube con `yt-dlp` cuando el dispositivo no
puede (bloqueo por IP). Es el "plan B" de ADR-022: la app funciona sin él, y se
activa con un interruptor en Ajustes.

- **Stack**: FastAPI + uvicorn + yt-dlp (todas OSS). Muxea con ffmpeg.
- **No capa la calidad**: expone la escalera real de formatos del servidor.
- **Sirve los bytes** (no URLs firmadas) con soporte `Range`, para que el móvil
  descargue un único MP4/M4A de forma paralela y reanudable.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/health` | Estado + palancas activas (proxy, cookies, PO token) |
| GET | `/api/search?q=&page=&page_size=` | Búsqueda |
| GET | `/api/video/{id}` | Metadatos |
| GET | `/api/qualities/{id}` | Escalera de calidades real |
| GET | `/api/related/{id}` | 501 (el cliente los pide en local) |
| GET | `/api/media/{id}?quality=&type=video\|audio` | Bytes del medio (Range) |
| GET | `/api/debug/{id}?client=` | Telemetría de PO token (requiere `DEBUG_TOKEN`) |
| POST | `/api/cookies` | Sube `cookies.txt` (Netscape), cuerpo `{"content": "..."}` |
| DELETE | `/api/cookies` | Borra las cookies |

Documentación interactiva en `/docs`.

### Telemetría de debug (`/api/debug/{id}`)

Corre `yt-dlp` en modo verbose **sin descargar** y devuelve: settings efectivas,
ping al proveedor PO (`reachable`/`pingMs`), resultado/errores, formatos y
alturas halladas, señales calculadas (`pluginMentioned`, `potProviderArgUsed`,
`potTokenMentioned`, `getPotRequested`) y las últimas 80 líneas de log
**redactadas** (cookies/tokens enmascarados). `?client=web_safari` fuerza un
único `player_client`.

Requiere la env `DEBUG_TOKEN` (si falta, responde `503`):

```bash
curl -H "X-Debug-Token: $DEBUG_TOKEN" \
  "https://<host>/api/debug/9bZkp7q19f0"
```

## Variables de entorno

| Variable | Efecto |
|---|---|
| `PORT` | Puerto (Railway lo inyecta). |
| `MEDIA_CACHE_DIR` | Directorio de caché (def. `/tmp/adonwloader-media`). |
| `MEDIA_CACHE_TTL` | Segundos de vida del caché (def. 21600 = 6 h). |
| `YTDLP_PROXY` / `RESIDENTIAL_PROXY` | **La palanca contra el throttle de IP de datacenter.** |
| `YTDLP_COOKIES` / `COOKIES_FILE` | Ruta a un `cookies.txt` montado. |
| `YT_PO_TOKEN` | PO token manual (`cliente+token`). Caduca en horas; poco práctico. |
| `YTDLP_POT_SCRIPT` | Ruta a `generate_once.js` del proveedor bgutil (modo script; requiere Node). |
| `YTDLP_POT_BASEURL` | URL del proveedor bgutil por HTTP (modo sidecar). |
| `VIDEO_CLIENTS` / `SEARCH_CLIENTS` | Orden de `player_client` (def. `default` primero). |
| `DEBUG_TOKEN` | Habilita `/api/debug` (telemetría). **Fail-closed**: sin esta variable el endpoint responde `503`. Se envía en el header `X-Debug-Token` (o `?token=`). |

> **Calidad medida (2026-10-09):** el techo **depende del entorno**, no de una
> regla fija. El mismo código, sin PO token, en una red local daba solo **itag 18
> (360p)**; en el datacenter de Railway, **sin PO token, proxy ni cookies**,
> devuelve la **escalera completa hasta 2160p** (verificado: 720p y 144p se
> descargan y muxean). Por eso el servidor **no asume techo**: publica la
> escalera real de su entorno vía `/api/qualities`. El PO token (bgutil) y el
> proxy (`YTDLP_PROXY`) son palancas **opcionales** para cuando el entorno SÍ
> esté capado. Comprueba `/api/health` y `/api/qualities`.
>
> **PO token (opcional, solo si tu entorno está capado)** (elige una):
> - **Sidecar (recomendado):** despliega la imagen
>   `brainicism/bgutil-ytdlp-pot-provider` como segundo servicio y pon
>   `YTDLP_POT_BASEURL=http://<servicio>:4416`.
> - **Modo script:** monta el repo `bgutil-ytdlp-pot-provider` (con `node_modules`)
>   y su runtime Node, y apunta `YTDLP_POT_SCRIPT` a `server/build/generate_once.js`.
>   El script y el plugin deben compartir versión mayor (aquí fijada a `2.0.2`).
>
> **Versión del proveedor (verificado 2026-10-10):** con **1.3.2** la extracción
> desde la IP de Railway devolvía `502 "Sign in to confirm you're not a bot"`;
> con **2.0.2** (mismo mayor que el plugin) los 10 videos de prueba pasaron a
> `200` con escalera completa. Usa **2.0.2 o superior** y mantén plugin y
> proveedor alineados. Al reconstruir, recuerda `npm ci --include=dev && npx tsc`
> para generar `build/main.js`.

## Despliegue en Railway

1. Crea un proyecto y conecta el repo `listamigo/downloader`.
2. **Build → Builder = `Dockerfile`** y **Dockerfile Path = `Dockerfile`** (raíz).
   Alternativa en monorepo: **Settings → Source → Root Directory = `server`**.
   Si no, Railway usa Railpack y detecta el Gradle Android de la raíz: falla.
3. (% opcional) Añade las variables de la tabla (proxy, cookies, PO token).
4. Railway usa `railway.json` + `Dockerfile` y despliega.
5. Comprueba `https://<host>/api/health`.

## Local

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
# tests del núcleo puro (sin dependencias):
python3 -m unittest discover -s tests -t .
```

## Diseño

```
app/
  quality.py   # escalera y selectores (puro, con tests)
  naming.py    # saneado de nombres (puro, con tests)
  config.py    # entorno
  models.py    # esquema Pydantic
  youtube.py   # adaptador yt-dlp
  cache.py     # materialización + Range (lock por clave)
  main.py      # rutas FastAPI
```
