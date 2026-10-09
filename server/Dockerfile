# Imagen del backend. ffmpeg es imprescindible: yt-dlp lo usa para unir
# vídeo+audio en un único MP4 / extraer M4A.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Railway inyecta $PORT; en local cae a 8080. El caché va a /data para poder
# montar un volumen persistente (si no, es efímero y basta para el propósito).
ENV PORT=8080 \
    MEDIA_CACHE_DIR=/data/media

EXPOSE 8080

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
