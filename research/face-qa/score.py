"""Objective self-assessment for talking-head renders.
identity: ArcFace cosine (buffalo_l) of rendered face vs centroid of Ryan's real photos.
lipsync: peak Pearson corr between mouth-opening (MediaPipe lips 13/14 over 61/291) and audio RMS envelope, lag within +-240ms.
lip_activity: share of sampled frames with mouth open ratio > 0.08.
face_rate: share of sampled frames where a face is detected.
sharp: Laplacian variance of the face crop (higher = sharper)."""
import sys,json,glob,os,subprocess,tempfile,cv2,numpy as np,librosa,mediapipe as mp
from mediapipe.tasks.python import vision, BaseOptions
from insightface.app import FaceAnalysis
HERE=os.path.dirname(os.path.abspath(__file__))
MP_MODEL=os.environ.get("MP_FACE_MODEL",os.path.join(HERE,"face_landmarker.task"))
REF_DIR="/Users/mac/Downloads/clone/images"
_app=None
def app():
    global _app
    if _app is None:
        _app=FaceAnalysis(name='buffalo_l',providers=['CPUExecutionProvider']); _app.prepare(ctx_id=-1,det_size=(640,640))
    return _app
def emb(img):
    fs=app().get(img)
    if not fs: return None,None
    f=max(fs,key=lambda x:(x.bbox[2]-x.bbox[0])); return f.normed_embedding,f.bbox.astype(int)
def ref_centroid():
    cache=os.path.join(HERE,"ref_centroid.npy")
    if os.path.exists(cache): return np.load(cache)
    base,_=emb(cv2.imread(f"{REF_DIR}/avatar_ref.jpg")); hood=[base]
    for f in [f"{REF_DIR}/avatar_alt_1.jpg",f"{REF_DIR}/avatar_alt_2.jpg"]+sorted(glob.glob(f"{REF_DIR}/candidates/*.jpg"))+sorted(glob.glob(f"{REF_DIR}/full/*.jpg")):
        e,_=emb(cv2.imread(f)) if cv2.imread(f) is not None else (None,None)
        if e is not None and float(e@base)>0.6: hood.append(e)
    # Ryan-supplied real photos with hair visible (Archive 4); weighted equally with the hooded set so neither look dominates.
    hair=[e for e,_ in (emb(cv2.imread(f)) for f in sorted(glob.glob("/Users/mac/aircheck-face/refs_archive4/*.jpg"))) if e is not None]
    es=[np.mean(hood,0)/np.linalg.norm(np.mean(hood,0)), np.mean(hair,0)/np.linalg.norm(np.mean(hair,0))]
    print(f"ref sets: hooded={len(hood)} hair={len(hair)}",file=sys.stderr)
    c=np.mean(es,0); c/=np.linalg.norm(c); np.save(cache,c); print(f"ref centroid from {len(es)} real photos",file=sys.stderr); return c
def score_image(path):
    e,_=emb(cv2.imread(path)); return {"identity": None if e is None else round(float(e@ref_centroid()),3)}
def score_video(path):
    C=ref_centroid()
    lm=vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path=MP_MODEL),running_mode=vision.RunningMode.IMAGE,num_faces=1))
    cap=cv2.VideoCapture(path); fps=cap.get(5) or 25; n=int(cap.get(7)); step=max(1,round(fps/10)); eff=fps/step
    ratios=[];ids=[];sharps=[];i=0; idstep=max(1,n//16)
    while True:
        ok,fr=cap.read()
        if not ok: break
        if i%step==0:
            r=lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=cv2.cvtColor(fr,cv2.COLOR_BGR2RGB)))
            if r.face_landmarks:
                p=r.face_landmarks[0]; w=abs(p[291].x-p[61].x)*fr.shape[1]; ratios.append(abs(p[14].y-p[13].y)*fr.shape[0]/max(w,1e-6))
            else: ratios.append(np.nan)
        if i%idstep==0:
            e,b=emb(fr)
            if e is not None:
                ids.append(float(e@C)); x0,y0,x1,y1=np.clip(b,0,None); g=cv2.cvtColor(fr[y0:y1,x0:x1],cv2.COLOR_BGR2GRAY)
                if g.size: sharps.append(cv2.Laplacian(cv2.resize(g,(256,256)),cv2.CV_64F).var())
        i+=1
    a=np.array(ratios); face_rate=float(np.mean(~np.isnan(a))) if len(a) else 0
    with tempfile.TemporaryDirectory() as t:
        wav=f"{t}/a.wav"; subprocess.run(["ffmpeg","-v","error","-y","-i",path,"-ac","1","-ar","16000",wav],check=False)
        lipsync=None
        if os.path.exists(wav) and len(a)>20:
            y,_=librosa.load(wav,sr=16000); hop=int(16000/eff)
            env=librosa.feature.rms(y=y,frame_length=hop*2,hop_length=hop)[0][:len(a)]
            m=a[:len(env)].copy(); ok=~np.isnan(m); best=-1
            for lag in range(-int(0.24*eff),int(0.24*eff)+1):
                x=np.roll(m,lag); k=ok&np.roll(ok,lag)
                if k.sum()>20: best=max(best,float(np.corrcoef(x[k],env[k])[0,1]))
            lipsync=round(best,3)
    v=a[~np.isnan(a)]
    return {"identity":round(float(np.mean(ids)),3) if ids else None,"identity_min":round(float(np.min(ids)),3) if ids else None,
            "lipsync":lipsync,"lip_activity":round(float((v>0.08).mean()),3) if len(v) else 0,"face_rate":round(face_rate,3),
            "sharp":round(float(np.median(sharps)),1) if sharps else None,"frames":n,"fps":round(fps,2)}
def gates(s):
    fails=[]
    if s.get("identity") is None or s["identity"]<0.55: fails.append("identity<0.55 (doesn't look like Ryan)")
    if s.get("lip_activity",0)<0.2: fails.append("lips barely move")
    if s.get("lipsync") is None or s["lipsync"]<0.25: fails.append("mouth not following audio")
    if s.get("face_rate",0)<0.95: fails.append("face lost in frames")
    return fails
if __name__=="__main__":
    out={}
    for f in sys.argv[1:]:
        s=score_image(f) if f.lower().endswith(('.jpg','.png','.jpeg','.webp')) else score_video(f)
        if not f.lower().endswith(('.jpg','.png','.jpeg','.webp')): s["fails"]=gates(s)
        out[os.path.basename(f)]=s; print(os.path.basename(f),json.dumps(s),flush=True)
