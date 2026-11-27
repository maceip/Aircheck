"""Compute Chatterbox-LiteRT voice conditioning (cond_emb/spks/prompt_token/prompt_feat) for a reference clip.
Step 1 checks our PyTorch checkpoint reproduces soniqo's default-voice bins (same weights => same numbers)."""
import sys,numpy as np,torch,librosa
from pathlib import Path
from chatterbox.mtl_tts import ChatterboxMultilingualTTS
B=Path("/Users/mac/aircheck-face/models/chatterbox-litert")
m=ChatterboxMultilingualTTS.from_local("/Users/mac/aircheck-face/models/chatterbox","cpu")
def bins(conds):
    with torch.inference_mode():
        ce=m.t3.prepare_conditioning(conds.t3).float().numpy()
        g=conds.gen
        emb=torch.nn.functional.normalize(g['embedding'],dim=1)
        spks=m.s3gen.flow.spk_embed_affine_layer(emb).float().numpy()
        return dict(cond_emb=ce,spks=spks,prompt_token=g['prompt_token'].int().numpy(),prompt_feat=g['prompt_feat'].float().numpy())
def load(name,dt,shape): return np.fromfile(B/f"{name}.bin",dtype=dt).reshape(shape)
d=bins(m.conds)
ref=dict(cond_emb=load("cond_emb",np.float32,(1,34,1024)),spks=load("spks",np.float32,(1,80)),prompt_token=load("prompt_token",np.int32,(1,157)),prompt_feat=load("prompt_feat",np.float32,(1,314,80)))
for k in ref:
    a,b=d[k],ref[k]
    print(k,"ours",a.shape,"theirs",b.shape, "maxdiff", float(np.abs(a.astype(np.float64)-b).max()) if a.shape==b.shape else "SHAPE MISMATCH")
if len(sys.argv)>1:
    wav=sys.argv[1]; out=Path(sys.argv[2]); out.mkdir(parents=True,exist_ok=True)
    m.prepare_conditionals(wav,exaggeration=0.5)
    v=bins(m.conds)
    for k,a in v.items(): print("ryan",k,a.shape,a.dtype)
    # graphs need exactly 157 prompt tokens / 314 frames
    v['prompt_token']=v['prompt_token'][:,:157]; v['prompt_feat']=v['prompt_feat'][:,:314]
    assert v['prompt_token'].shape==(1,157) and v['prompt_feat'].shape==(1,314,80), (v['prompt_token'].shape,v['prompt_feat'].shape)
    for k,a in v.items(): a.tofile(out/f"{k}.bin")
    print("wrote",out)
