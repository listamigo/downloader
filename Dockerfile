# Build del backend desde la RAÍZ del repositorio (contexto = repo entero).
# Es un espejo de server/Dockerfile para que Railway compile el servidor aunque
# el servicio NO tenga fijado Root Directory = server. Si lo tiene, Railway
# ignora este fichero y usa server/Dockerfile; si no, usa este y también
# funciona. ffmpeg es imprescindible: yt-dlp lo usa para muxear.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY server/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY server/app ./app

# Railway inyecta $PORT; en local cae a 8080. El caché va a /data para poder
# montar un volumen persistente.
ENV PORT=8080 \
    MEDIA_CACHE_DIR=/data/media

EXPOSE 8080

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
