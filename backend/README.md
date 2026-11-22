# Aircheck Blue-Ocean Cloud Backend (`gcp-cheap-stack-7202`)

Cloud-first orchestration matching `BLUE_OCEAN_LLM_PIPELINE.md` with zero on-device ML bloat, immutable SHA-256 Content-Addressed Storage (`CAS`), stage-level `$0.00` checkpoint resume, and zero silent fallbacks.

## 1. Configure Secrets in Google Secret Manager

```bash
PROJECT=gcp-cheap-stack-7202
for secret in GEMINI_API_KEY ELEVENLABS_API_KEY FISH_AUDIO_API_KEY MINIMAX_API_KEY MINIMAX_GROUP_ID GROQ_API_KEY STAGE4_GPU_ENDPOINT STAGE4_GPU_API_KEY POSE_NORMALIZER_ENDPOINT POSE_NORMALIZER_API_KEY REMOTION_WORKER_URL REMOTION_WORKER_TOKEN PERSONA_TRAINER_JOB_URL PERSONA_TRAINER_TOKEN; do
  gcloud secrets create "$secret" --project="$PROJECT" --replication-policy="automatic" 2>/dev/null || true
done
```

## 2. Deploy Stateless Cloud Run Remotion Worker (`$0/mo` Idle)

```bash
gcloud run deploy aircheck-remotion-worker \
  --project=gcp-cheap-stack-7202 \
  --region=us-central1 \
  --source=backend/remotion-worker \
  --cpu=4 --memory=4Gi \
  --min-instances=0 --max-instances=5 \
  --no-allow-unauthenticated
```

## 3. Deploy Firebase Cloud Functions v2 + Firestore & Storage Rules

```bash
npx firebase-tools deploy --only firestore:rules,storage,functions --project=gcp-cheap-stack-7202
```

## 4. Run Phase A Persona Training (Once Upfront from `/Users/mac/Downloads/clone/`)

```bash
python3 backend/training-job/prepare_clone_dataset.py \
  --input-dir /Users/mac/Downloads/clone \
  --output-dir /tmp/aircheck_persona_dataset

# Upload curated head_frames zip to gs://<bucket>/users/<uid>/training/head_dataset.zip, then run:
python3 backend/training-job/train_persona.py \
  --manifest /tmp/aircheck_persona_dataset/manifest.json \
  --uid <FIREBASE_UID> \
  --voice-provider elevenlabs_pvc \
  --dataset-gcs-uri gs://<bucket>/users/<uid>/training/head_dataset.zip
```
