"""Speaker similarity (WeSpeaker ResNet34, cosine vs Ryan's real recordings) + whisper transcript for each wav."""
import sys,subprocess,tempfile,numpy as np,torch,torchaudio,onnxruntime as ort
from torchaudio.compliance import kaldi
SESS=ort.InferenceSession("/Users/mac/Downloads/voicestudio-models/bundles/v002/wespeaker_resnet34.onnx")
def emb(p):
    w,sr=torchaudio.load(p); w=w.mean(0,keepdim=True)
    if sr!=16000: w=torchaudio.functional.resample(w,sr,16000)
    f=kaldi.fbank(w*32768,num_mel_bins=80,frame_length=25,frame_shift=10,sample_frequency=16000,dither=0.0)
    f=f-f.mean(0)
    e=SESS.run(None,{SESS.get_inputs()[0].name:f.unsqueeze(0).numpy()})[0][0]
    return e/np.linalg.norm(e)
def asr(p):
    with tempfile.TemporaryDirectory() as t:
        subprocess.run(["ffmpeg","-v","error","-y","-i",p,"-ar","16000","-ac","1",f"{t}/a.wav"],check=True)
        r=subprocess.run(["whisper-cli","-m","/Users/mac/models/ggml-medium-q5_0.bin","-f",f"{t}/a.wav","-nt","-np"],capture_output=True,text=True)
        return " ".join(r.stdout.split())
refs=sys.argv[1].split(",")
R=np.mean([emb(r) for r in refs],0); R/=np.linalg.norm(R)
for p in sys.argv[2:]:
    print(f"{float(emb(p)@R):.3f}  {p}  | {asr(p)}")
