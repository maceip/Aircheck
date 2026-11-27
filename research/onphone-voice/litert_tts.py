"""Run soniqo/Chatterbox-LiteRT graphs end to end on the Mac (same graphs the phone will run)."""
import sys,time,json,numpy as np,soundfile as sf
from pathlib import Path
from ai_edge_litert.interpreter import Interpreter
B=Path("/Users/mac/aircheck-face/models/chatterbox-litert")
def runner(name,threads=8): return Interpreter(model_path=str(B/f"chatterbox-{name}.tflite"),num_threads=threads).get_signature_runner()
def load_voice(d):
    d=Path(d); f=lambda n,dt,s: np.fromfile(d/f"{n}.bin",dtype=dt).reshape(s)
    return f("cond_emb",np.float32,(1,34,1024)),f("spks",np.float32,(1,80)),f("prompt_token",np.int32,(1,157)),f("prompt_feat",np.float32,(1,314,80))
def tokenize(text,lang="en"):
    from chatterbox.models.tokenizers import MTLTokenizer
    from chatterbox.mtl_tts import punc_norm
    tok=MTLTokenizer(str(B/"grapheme_mtl_merged_expanded_v1.json"))
    ids=tok.text_to_tokens(punc_norm(text),language_id=lang)[0].tolist()
    return [255]+ids+[0]
def sample(logits,gen,temp=0.8,rep=2.0,min_p=0.05,rng=None):
    l=logits.astype(np.float64).copy()
    for t in set(gen):  # HF RepetitionPenalty
        l[t]= l[t]/rep if l[t]>0 else l[t]*rep
    l/=temp
    p=np.exp(l-l.max()); p/=p.sum()
    p[p<min_p*p.max()]=0; p/=p.sum()
    return int(rng.choice(len(p),p=p))
def t3_generate(text_ids,cond_emb,cfg=0.5,max_new=1000,seed=0):
    rng=np.random.default_rng(seed); pre,step=runner("t3-prefill"),runner("t3-step")
    n=len(text_ids); assert n<=478
    def prefill(ids):
        x=np.zeros((1,478),np.int64); x[0,:n]=ids
        o=pre(args_0=x,args_1=cond_emb); L=34+n
        cache={}
        for i in range(1,61):
            c=np.zeros((1,16,1536,64),np.float32); c[:,:,:L]=o[f"output_{i}"][:,:,:L]; cache[f"args_{i+2}"]=c
        return cache,L
    cc,L=prefill(text_ids); uc,_=prefill([0]*n)  # uncond: text embedding zeroed == ids whose emb we can't zero; see note
    tok=6561; gen=[6561]; out=[]
    for i in range(max_new):
        pos=np.array([[i]],np.int64); cp=np.array([L+i],np.int64); t=np.array([[tok]],np.int64)
        oc=step(args_0=t,args_1=pos,args_2=cp,**cc); ou=step(args_0=t,args_1=pos,args_2=cp,**uc)
        cc={f"args_{k+2}":oc[f"output_{k}"] for k in range(1,61)}; uc={f"args_{k+2}":ou[f"output_{k}"] for k in range(1,61)}
        lc,lu=oc["output_0"][0,-1],ou["output_0"][0,-1]
        tok=sample(lc+cfg*(lc-lu),gen,rng=rng); gen.append(tok)
        if tok==6562: break
        out.append(tok)
    return [t for t in out if t<6561]
def flow_vocode(speech,spks,prompt_token,prompt_feat,seed=0):
    enc,est,hift=runner("flow-encoder"),runner("flow-estimator"),runner("hift")
    full=np.concatenate([prompt_token[0],np.array(speech,np.int32)]); T=len(full); assert T<=512
    x_ids=np.zeros((1,512),np.int64); x_ids[0,:T]=full
    e=enc(args_0=x_ids,args_1=np.array([T],np.int32)); mu=e["output_0"]; F=2*T
    mask=np.zeros((1,1,1024),np.float32); mask[...,:F]=1
    cond=np.zeros((1,80,1024),np.float32); cond[:,:,:314]=prompt_feat[0].T
    rng=np.random.default_rng(seed); x=rng.standard_normal((1,80,1024)).astype(np.float32)
    ts=1-np.cos(np.linspace(0,1,11)*np.pi/2)
    z=np.zeros_like
    for k in range(10):
        t,dt=ts[k],ts[k+1]-ts[k]
        o=est(args_0=np.concatenate([x,x]),args_1=np.concatenate([mask,mask]),args_2=np.concatenate([mu,z(mu)]),
              args_3=np.array([t,t],np.float32),args_4=np.concatenate([spks,z(spks)]),args_5=np.concatenate([cond,z(cond)]))["output_0"]
        x=x+dt*(1.7*o[:1]-0.7*o[1:])
    mel=np.zeros((1,80,1024),np.float32); n=F-314; mel[:,:,:n]=x[:,:,314:F]
    return hift(args_0=mel)["output_0"][0,:n*480]
if __name__=="__main__":
    voice,text,out=sys.argv[1],sys.argv[2],sys.argv[3]
    ce,spks,pt,pf=load_voice(voice); ids=tokenize(text)
    t0=time.time(); sp=t3_generate(ids,ce); t1=time.time(); wav=flow_vocode(sp,spks,pt,pf); t2=time.time()
    sf.write(out,wav,24000); dur=len(wav)/24000
    print(json.dumps(dict(tokens=len(sp),audio_s=round(dur,2),t3_s=round(t1-t0,1),flow_s=round(t2-t1,1),rtf=round((t2-t0)/dur,2))))
