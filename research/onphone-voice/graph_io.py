from ai_edge_litert.interpreter import Interpreter
import sys,glob
for f in sorted(glob.glob("/Users/mac/aircheck-face/models/chatterbox-litert/*.tflite")):
    it=Interpreter(model_path=f); sigs=it.get_signature_list(); print("==",f.split('/')[-1],list(sigs))
    for s,io in sigs.items():
        r=it.get_signature_runner(s)
        ins=r.get_input_details(); outs=r.get_output_details()
        def fmt(d): return ", ".join(f"{k}{list(v['shape'])}:{v['dtype'].__name__}" for k,v in list(d.items())[:6])+(f" ... (+{len(d)-6})" if len(d)>6 else "")
        print("  in :",fmt(ins)); print("  out:",fmt(outs))
