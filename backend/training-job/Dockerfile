FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN pip install --no-cache-dir httpx google-cloud-firestore google-cloud-storage
COPY prepare_clone_dataset.py train_persona.py ./

ENTRYPOINT ["python3", "train_persona.py"]
