FROM python:3.12-slim
RUN pip install --no-cache-dir boto3==1.35.99
WORKDIR /app
COPY issuer.py manifest.json /app/
USER nobody
EXPOSE 8090
CMD ["python", "/app/issuer.py"]
