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
| POST | `/api/cookies` | Sube `cookies.txt` (Netscape), cuerpo `{"content": "..."}` |
| DELETE | `/api/cookies` | Borra las cookies |

Documentación interactiva en `/docs`.

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

> **Calidad medida (2026-10-09):** sin PO token, YouTube solo entrega **itag 18
> (360p)**, ni con cookies ni cambiando de `player_client`. Con PO token (bgutil)
> y cliente `default`, aparece la **escalera completa hasta 4K**. Por eso el PO
> token es la palanca real, no la IP. Comprueba con `/api/health` y
> `/api/qualities`.
>
> **PO token en producción** (elige una):
> - **Sidecar (recomendado):** despliega la imagen
>   `brainicism/bgutil-ytdlp-pot-provider` como segundo servicio y pon
>   `YTDLP_POT_BASEURL=http://<servicio>:4416`.
> - **Modo script:** monta el repo `bgutil-ytdlp-pot-provider` (con `node_modules`)
>   y su runtime Node, y apunta `YTDLP_POT_SCRIPT` a `server/build/generate_once.js`.
>   El script y el plugin deben compartir versión mayor (aquí fijada a `1.3.2`).

## Despliegue en Railway

1. Crea un proyecto y conecta el repo `listamigo/downloader`.
2. **Root Directory = `server`** (es un monorepo; la app Android está en `app/`).
3. (% opcional) Añade las variables de la tabla (VPN/Proxy, cookies, PO token).
4. Railway detecta `railway.json` + `Dockerfile` y despliega.
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
