FROM pytorch/pytorch:2.5.1-cuda12.4-cudnn9-devel
RUN apt-get update && apt-get install -y --no-install-recommends git ffmpeg curl && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir qwen-tts soundfile
RUN git clone --depth 1 https://github.com/QwenLM/Qwen3-TTS.git /opt/Qwen3-TTS
COPY train.sh sample.py /opt/job/
RUN chmod +x /opt/job/train.sh
WORKDIR /opt/Qwen3-TTS/finetuning
ENTRYPOINT ["/opt/job/train.sh"]
