import sys
from litert_torch.generative.export_hf.core import exportable_module_config as emc
from litert_torch.generative.export_hf.model_ext.qwen3_tts import qwen3_tts as q
cfg = emc.ExportableModuleConfig(model=sys.argv[1], output_dir=sys.argv[2], task="text_to_speech")
cfg.extra_kwargs["targets"] = sys.argv[3].split(",")
m = q.Qwen3Tts(sys.argv[1], cfg)
print(m.export(cfg))
