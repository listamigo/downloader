# Build del backend desde la RAÍZ del repositorio (contexto = repo entero).
# Es un espejo de server/Dockerfile para que Railway compile el servidor aunque
# el servicio NO tenga fijado Root Directory = server. Si lo tiene, Railway
# ignora este fichero y usa server/Dockerfile; si no, usa este y también
# funciona. ffmpeg es imprescindible: yt-dlp lo usa para muxear.
#
# El proveedor de PO tokens (bgutil) viaja DENTRO de la imagen: Node 22 corre
# el servidor HTTP del proveedor en 127.0.0.1:4416 y el backend consume
# YTDLP_POT_BASEURL=http://127.0.0.1:4416. Todo en UN solo contenedor. Node se
# baja como binario (apt en bookworm da 18 < 22, mínimo del proveedor 2.0.2).
FROM python:3.12-slim

ARG NODE_VERSION=v22.16.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl git xz-utils unzip \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.xz" \
       -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /opt --strip-components=1 \
    && rm /tmp/node.tar.xz \
    && /opt/bin/node --version

# Deno: runtime JS que yt-dlp 2026 usa (vía su componente EJS, yt_dlp_ejs) para
# resolver los desafíos JS de YouTube cuando el PO token por sí solo no alcanza.
# Sin un runtime JS, yt-dlp reporta "JS runtimes: none" y los "JS Challenge
# Providers" quedan unavailable, lo que en IPs de datacenter agrava el reto
# "Sign in to confirm you're not a bot". Queda en /opt/bin (ya en PATH).
ARG DENO_VERSION=v2.9.7
RUN curl -fsSL "https://github.com/denoland/deno/releases/download/${DENO_VERSION}/deno-x86_64-unknown-linux-gnu.zip" \
       -o /tmp/deno.zip \
    && unzip -q /tmp/deno.zip -d /opt/bin \
    && rm /tmp/deno.zip \
    && /opt/bin/deno --version

ENV PATH="/opt/bin:${PATH}"

WORKDIR /app

COPY server/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Proveedor de PO tokens: clona el tag que coincide con el plugin de
# requirements.txt (2.0.2) e instala su build JS. CACHEBUST permite forzar un
# rebuild limpio si Railway reutiliza capas.
ARG CACHEBUST=1
RUN echo "cachebust=${CACHEBUST}" \
    && git clone --depth 1 --branch 2.0.2 \
      https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git /opt/pot-provider \
    && cd /opt/pot-provider/server \
    && npm ci --include=dev --no-audit --no-fund \
    && npx tsc \
    && rm -rf node_modules/.cache

COPY server/app ./app

# Railway inyecta $PORT; en local cae a 8080. El caché va a /data para poder
# montar un volumen persistente.
ENV PORT=8080 \
    MEDIA_CACHE_DIR=/data/media \
    YTDLP_POT_BASEURL=http://127.0.0.1:4416

EXPOSE 8080

# Arranca los DOS procesos: proveedor (segundo plano), espera a su /ping y
# luego uvicorn. Si el proveedor cae, uvicorn sigue y yt-dlp reintenta sin PO.
CMD ["sh", "-c", "(cd /opt/pot-provider/server && exec node build/main.js --port 4416) & \
      while ! curl -fsS http://127.0.0.1:4416/ping >/dev/null 2>&1; do sleep 0.2; done; \
      exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]