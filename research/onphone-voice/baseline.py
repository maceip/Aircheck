import time,torch,torchaudio as ta,sys
from chatterbox.tts import ChatterboxTTS
dev="mps" if torch.backends.mps.is_available() else "cpu"
t=time.time(); m=ChatterboxTTS.from_local("/Users/mac/aircheck-face/models/chatterbox",dev); print("load",round(time.time()-t,1),"s on",dev)
ref="/Users/mac/aircheck-face/outputs/voice/REAL_you.wav"
text="Good evening. Tonight, artificial intelligence is reshaping cyber security on both sides of the fight."
t=time.time(); wav=m.generate(text,audio_prompt_path=ref); dt=time.time()-t
ta.save("out/baseline_en.wav",wav,m.sr); print("gen",round(dt,1),"s audio",round(wav.shape[-1]/m.sr,2),"s sr",m.sr)
