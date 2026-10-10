# Imagen del backend. ffmpeg es imprescindible: yt-dlp lo usa para unir
# vídeo+audio en un único MP4 / extraer M4A.
#
# El proveedor de PO tokens (bgutil) viaja DENTRO de la imagen: Node 22 corre
# el servidor HTTP del proveedor en 127.0.0.1:4416 y el backend consume
# YTDLP_POT_BASEURL=http://127.0.0.1:4416. Todo en UN solo contenedor (sin
# segundos servicios). Node se baja como binario (apt en bookworm da 18 < 22).
FROM python:3.12-slim

ARG NODE_VERSION=v22.16.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl git xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.xz" \
       -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /opt --strip-components=1 \
    && rm /tmp/node.tar.xz \
    && /opt/bin/node --version

ENV PATH="/opt/bin:${PATH}"

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Proveedor de PO tokens: clona el tag que coincide con el plugin de
# requirements.txt (1.3.2) e instala su build JS.
RUN git clone --depth 1 --branch 1.3.2 \
      https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git /opt/pot-provider \
    && cd /opt/pot-provider/server \
    && npm ci --include=dev --no-audit --no-fund \
    && npx tsc \
    && rm -rf node_modules/.cache

COPY app ./app

# Railway inyecta $PORT; en local cae a 8080. El caché va a /data para poder
# montar un volumen persistente (si no, es efímero y basta para el propósito).
ENV PORT=8080 \
    MEDIA_CACHE_DIR=/data/media \
    YTDLP_POT_BASEURL=http://127.0.0.1:4416

EXPOSE 8080

# Arranca los DOS procesos: proveedor (segundo plano), espera a su /ping y
# luego uvicorn. Si el proveedor cae, uvicorn sigue y yt-dlp reintenta sin PO.
CMD ["sh", "-c", "(cd /opt/pot-provider/server && exec node build/main.js --port 4416) & \
      while ! curl -fsS http://127.0.0.1:4416/ping >/dev/null 2>&1; do sleep 0.2; done; \
      exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]