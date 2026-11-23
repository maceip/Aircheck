"""Standalone PyTorch implementation of fishaudio/s2-pro 44.1 kHz ModdedDAC.

Matches all 541 state_dict tensors of ``fishaudio/s2-pro`` ``codec.pth`` with
``load_state_dict(..., strict=True)`` (zero missing keys, zero unexpected keys),
without external dependencies on ``audiotools``, ``descript-audio-codec``, or
``einops``.

Architecture (from ``fishaudio/fish-speech`` ``modded_dac_vq.yaml``):
- Sample rate: 44,100 Hz mono
- Encoder: causal Snake1d/WNConv1d encoder (rates [2, 4, 8, 8], hop=512,
  d_model=64 -> 1024) + 4-layer causal WindowLimitedTransformer (window=512)
- Quantizer: DownsampleResidualVectorQuantize
  - Downsample: 2x (CausalConv1d k=2,s=2 + ConvNeXtBlock) -> total frame_length=2048
  - Pre-module: 8-layer causal WindowLimitedTransformer (dim=1024, heads=16, window=128)
  - Semantic VQ: 1 codebook x 4096 entries (dim=8, L2-normalized lookup)
  - Residual VQ: 9 codebooks x 1024 entries (dim=8, L2-normalized lookup)
  - Post-module: 8-layer causal WindowLimitedTransformer (dim=1024, heads=16, window=128)
  - Upsample: 2x (CausalTransConv1d k=2,s=2 + ConvNeXtBlock) -> x4
- Decoder: causal Snake1d/WNConvTranspose1d DAC decoder (1024 -> 1536 -> [8, 8, 4, 2] -> 1)
  producing 2048 samples per frame (21.5332 Hz frame rate at 44.1 kHz).
"""

from __future__ import annotations

import copy
import math
from dataclasses import dataclass
from functools import partial
from pathlib import Path
from typing import List, Optional, Tuple, Union

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch import Tensor
from torch.nn.utils import remove_weight_norm as legacy_remove_weight_norm
from torch.nn.utils import weight_norm as legacy_weight_norm
from torch.nn.utils.parametrizations import weight_norm as param_weight_norm
from torch.nn.utils.parametrize import is_parametrized, remove_parametrizations

SAMPLE_RATE_HZ: int = 44100
ENCODER_RATES: Tuple[int, ...] = (2, 4, 8, 8)
DECODER_RATES: Tuple[int, ...] = (8, 8, 4, 2)
DOWNSAMPLE_FACTOR: Tuple[int, ...] = (2, 2)
HOP_LENGTH: int = 512
FRAME_LENGTH: int = 2048  # 512 * 4 = 2048 samples per discrete code frame
NUM_CODEBOOKS: int = 10  # 1 semantic (4096) + 9 residual (1024)
SEMANTIC_CODEBOOK_SIZE: int = 4096
RESIDUAL_CODEBOOK_SIZE: int = 1024


@dataclass
class VQResult:
    z: Tensor
    codes: Tensor
    latents: Tensor
    codebook_loss: Tensor
    commitment_loss: Tensor
    semantic_distill_z: Optional[Tensor] = None


def find_multiple(n: int, k: int) -> int:
    if n % k == 0:
        return n
    return n + k - (n % k)


@dataclass
class ModelArgs:
    block_size: int = 2048
    n_layer: int = 8
    n_head: int = 16
    dim: int = 1024
    intermediate_size: int = 3072
    n_local_heads: int = -1
    head_dim: int = 64
    rope_base: float = 10000.0
    norm_eps: float = 1e-5
    dropout_rate: float = 0.1
    attn_dropout_rate: float = 0.1
    channels_first: bool = True
    pos_embed_type: str = "rope"
    max_relative_position: int = 128
    window_size: int = 512

    def __post_init__(self) -> None:
        if self.n_local_heads == -1:
            self.n_local_heads = self.n_head
        if self.intermediate_size is None:
            hidden_dim = 4 * self.dim
            n_hidden = int(2 * hidden_dim / 3)
            self.intermediate_size = find_multiple(n_hidden, 256)
        if self.pos_embed_type not in ("rope", "conformer"):
            raise ValueError(f"Unsupported pos_embed_type: {self.pos_embed_type}")


def precompute_freqs_cis(
    seq_len: int,
    n_elem: int,
    base: float = 10000.0,
    dtype: torch.dtype = torch.bfloat16,
) -> Tensor:
    freqs = 1.0 / (
        base ** (torch.arange(0, n_elem, 2)[: (n_elem // 2)].float() / n_elem)
    )
    t = torch.arange(seq_len, device=freqs.device, dtype=torch.float32)
    angles = torch.outer(t, freqs)
    cache = torch.stack([torch.cos(angles), torch.sin(angles)], dim=-1)
    return cache.to(dtype=dtype)


def apply_rotary_emb(x: Tensor, freqs_cis: Tensor) -> Tensor:
    """Real-valued rotary embedding matching fish-speech apply_rotary_emb."""
    x_float = x.float()
    xshaped = x_float.reshape(*x_float.shape[:-1], -1, 2)
    fc = freqs_cis.float().view(1, xshaped.size(1), 1, xshaped.size(3), 2)
    x0 = xshaped[..., 0]
    x1 = xshaped[..., 1]
    cos_val = fc[..., 0]
    sin_val = fc[..., 1]
    out0 = x0 * cos_val - x1 * sin_val
    out1 = x1 * cos_val + x0 * sin_val
    x_out2 = torch.stack([out0, out1], dim=-1)
    return x_out2.flatten(3).type_as(x)


class RMSNorm(nn.Module):
    def __init__(self, dim: int, eps: float = 1e-5) -> None:
        super().__init__()
        self.eps = eps
        self.weight = nn.Parameter(torch.ones(dim))

    def _norm(self, x: Tensor) -> Tensor:
        return x * torch.rsqrt(torch.mean(x * x, dim=-1, keepdim=True) + self.eps)

    def forward(self, x: Tensor) -> Tensor:
        output = self._norm(x.float()).type_as(x)
        return output * self.weight


class LayerScale(nn.Module):
    def __init__(
        self,
        dim: int,
        init_values: Union[float, Tensor] = 1e-2,
        inplace: bool = False,
    ) -> None:
        super().__init__()
        self.inplace = inplace
        self.gamma = nn.Parameter(init_values * torch.ones(dim))

    def forward(self, x: Tensor) -> Tensor:
        return x * self.gamma


class FeedForward(nn.Module):
    def __init__(self, config: ModelArgs) -> None:
        super().__init__()
        self.w1 = nn.Linear(config.dim, config.intermediate_size, bias=False)
        self.w3 = nn.Linear(config.dim, config.intermediate_size, bias=False)
        self.w2 = nn.Linear(config.intermediate_size, config.dim, bias=False)
        self.dropout = nn.Dropout(config.dropout_rate)

    def forward(self, x: Tensor) -> Tensor:
        return self.w2(self.dropout(F.silu(self.w1(x)) * self.w3(x)))


class Attention(nn.Module):
    def __init__(self, config: ModelArgs) -> None:
        super().__init__()
        assert config.dim % config.n_head == 0

        total_head_dim = (config.n_head + 2 * config.n_local_heads) * config.head_dim
        self.wqkv = nn.Linear(config.dim, total_head_dim, bias=False)
        self.wo = nn.Linear(config.head_dim * config.n_head, config.dim, bias=False)

        self.n_head = config.n_head
        self.head_dim = config.head_dim
        self.n_local_heads = config.n_local_heads
        self.dim = config.dim
        self.attn_dropout_rate = config.attn_dropout_rate
        self.pos_embed_type = config.pos_embed_type
        self.scale = 1.0 / math.sqrt(config.head_dim)

    def forward(
        self,
        x: Tensor,
        freqs_cis: Tensor,
        mask: Tensor,
        use_explicit_attn: bool = False,
    ) -> Tensor:
        bsz, seqlen, _ = x.shape

        q_size = self.n_head * self.head_dim
        kv_size = self.n_local_heads * self.head_dim
        q, k, v = self.wqkv(x).split([q_size, kv_size, kv_size], dim=-1)

        q = q.view(bsz, seqlen, self.n_head, self.head_dim)
        k = k.view(bsz, seqlen, self.n_local_heads, self.head_dim)
        v = v.view(bsz, seqlen, self.n_local_heads, self.head_dim)

        if self.pos_embed_type == "rope":
            q = apply_rotary_emb(q, freqs_cis)
            k = apply_rotary_emb(k, freqs_cis)

        q = q.transpose(1, 2)
        k = k.transpose(1, 2)
        v = v.transpose(1, 2)

        if self.n_head != self.n_local_heads:
            repeat = self.n_head // self.n_local_heads
            k = k.repeat_interleave(repeat, dim=1)
            v = v.repeat_interleave(repeat, dim=1)

        if use_explicit_attn:
            scores = torch.matmul(q, k.transpose(-2, -1)) * self.scale
            scores = scores + mask
            attn = F.softmax(scores, dim=-1)
            y = torch.matmul(attn, v)
        else:
            y = F.scaled_dot_product_attention(
                q,
                k,
                v,
                dropout_p=self.attn_dropout_rate if self.training else 0.0,
                attn_mask=mask,
            )

        y = (
            y.transpose(1, 2)
            .contiguous()
            .view(bsz, seqlen, self.head_dim * self.n_head)
        )
        return self.wo(y)


class TransformerBlock(nn.Module):
    def __init__(self, config: ModelArgs) -> None:
        super().__init__()
        self.attention = Attention(config)
        self.feed_forward = FeedForward(config)
        self.ffn_norm = RMSNorm(config.dim, eps=config.norm_eps)
        self.attention_norm = RMSNorm(config.dim, eps=config.norm_eps)
        self.attention_layer_scale = LayerScale(config.dim, inplace=False)
        self.ffn_layer_scale = LayerScale(config.dim, inplace=False)

    def forward(
        self,
        x: Tensor,
        freqs_cis: Tensor,
        mask: Tensor,
        use_explicit_attn: bool = False,
    ) -> Tensor:
        h = x + self.attention_layer_scale(
            self.attention(
                self.attention_norm(x),
                freqs_cis,
                mask,
                use_explicit_attn=use_explicit_attn,
            )
        )
        return h + self.ffn_layer_scale(self.feed_forward(self.ffn_norm(h)))


class Transformer(nn.Module):
    def __init__(self, config: ModelArgs) -> None:
        super().__init__()
        self.config = config
        self.layers = nn.ModuleList(
            TransformerBlock(config) for _ in range(config.n_layer)
        )
        self.norm = RMSNorm(config.dim, eps=config.norm_eps)

        # s2-pro codec.pth persists freqs_cis and causal_mask with length = 2 * block_size
        cache_len = config.block_size * 2
        if config.pos_embed_type == "rope":
            freqs_cis = precompute_freqs_cis(
                cache_len, config.head_dim, config.rope_base
            )
            self.register_buffer("freqs_cis", freqs_cis, persistent=True)
        else:
            self.register_buffer("freqs_cis", None, persistent=True)

        causal_mask = torch.tril(torch.ones(cache_len, cache_len, dtype=torch.bool))
        self.register_buffer("causal_mask", causal_mask, persistent=True)

    def forward(
        self,
        x: Tensor,
        input_pos: Optional[Tensor] = None,
        mask: Optional[Tensor] = None,
        use_explicit_attn: bool = False,
    ) -> Tensor:
        seqlen = x.shape[1]
        if self.config.pos_embed_type == "rope":
            if input_pos is None:
                freqs_cis = self.freqs_cis[:seqlen]
            else:
                freqs_cis = self.freqs_cis[input_pos]
        else:
            freqs_cis = None

        if mask is None:
            if input_pos is None:
                mask = self.causal_mask[None, None, :seqlen, :seqlen]
            else:
                mask = self.causal_mask[None, None, input_pos]
                mask = mask[..., input_pos]

        for layer in self.layers:
            x = layer(x, freqs_cis, mask, use_explicit_attn=use_explicit_attn)
        return self.norm(x)


class WindowLimitedTransformer(Transformer):
    """Causal window-limited Transformer matching fish-speech ModdedDAC."""

    def __init__(
        self,
        config: ModelArgs,
        input_dim: int = 1024,
        window_size: Optional[int] = None,
        causal: bool = True,
        look_ahead_conv: Optional[nn.Module] = None,
    ) -> None:
        super().__init__(config)
        self.window_size = window_size
        self.causal = causal
        self.channels_first = config.channels_first
        self.look_ahead_conv = (
            look_ahead_conv if look_ahead_conv is not None else nn.Identity()
        )
        self.input_proj = (
            nn.Linear(input_dim, config.dim)
            if input_dim != config.dim
            else nn.Identity()
        )
        self.output_proj = (
            nn.Linear(config.dim, input_dim)
            if input_dim != config.dim
            else nn.Identity()
        )

    def make_window_limited_mask(
        self, max_length: int, device: torch.device
    ) -> Tensor:
        if not self.causal:
            raise NotImplementedError("Non-causal window mask is not used in s2-pro")
        mask = torch.tril(torch.ones(max_length, max_length, device=device, dtype=torch.bool))
        row_indices = torch.arange(max_length, device=device).view(-1, 1)
        window_size = self.window_size or max_length
        valid_range = (row_indices - window_size + 1).clamp(min=0)
        column_indices = torch.arange(max_length, device=device)
        mask = (column_indices >= valid_range) & mask
        return mask[None, None]

    def make_mask(self, max_length: int, device: torch.device) -> Tensor:
        if not self.causal:
            raise NotImplementedError("Non-causal mask is not used in s2-pro")
        mask = torch.tril(torch.ones(max_length, max_length, device=device, dtype=torch.bool))
        return mask[None, None]

    def forward(self, x: Tensor, x_lens: Optional[Tensor] = None) -> Tensor:
        if self.channels_first:
            x = x.transpose(1, 2)
        x = self.input_proj(x)
        x = self.look_ahead_conv(x)
        max_length = x.shape[1]
        input_pos = torch.arange(max_length, device=x.device)
        if self.window_size is not None:
            mask = self.make_window_limited_mask(max_length, x.device)
        else:
            mask = self.make_mask(max_length, x.device)
        x = super().forward(x, input_pos=input_pos, mask=mask)
        x = self.output_proj(x)
        if self.channels_first:
            x = x.transpose(1, 2)
        return x


class Snake1d(nn.Module):
    """Snake activation: x + (1 / (alpha + 1e-9)) * sin^2(alpha * x)."""

    def __init__(self, channels: int) -> None:
        super().__init__()
        self.alpha = nn.Parameter(torch.ones(1, channels, 1))

    def forward(self, x: Tensor) -> Tensor:
        return x + (self.alpha + 1e-9).reciprocal() * torch.sin(self.alpha * x).pow(2)


def unpad1d(x: Tensor, paddings: Tuple[int, int]) -> Tensor:
    padding_left, padding_right = paddings
    assert padding_left >= 0 and padding_right >= 0, (padding_left, padding_right)
    if padding_left == 0 and padding_right == 0:
        return x
    end = x.shape[-1] - padding_right
    return x[..., padding_left:end]


def get_extra_padding_for_conv1d(
    x: Tensor, kernel_size: int, stride: int, padding_total: int = 0
) -> int:
    length = x.shape[-1]
    n_frames = (length - kernel_size + padding_total) / stride + 1
    ideal_length = (math.ceil(n_frames) - 1) * stride + (kernel_size - padding_total)
    return int(ideal_length - length)


def pad1d(
    x: Tensor,
    paddings: Tuple[int, int],
    mode: str = "constant",
    value: float = 0.0,
) -> Tensor:
    padding_left, padding_right = paddings
    assert padding_left >= 0 and padding_right >= 0, (padding_left, padding_right)
    if padding_left == 0 and padding_right == 0:
        return x
    return F.pad(x, paddings, mode=mode, value=value)


class CausalConvNet(nn.Module):
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int,
        dilation: int = 1,
        stride: int = 1,
        groups: int = 1,
        padding: Optional[int] = None,
    ) -> None:
        super().__init__()
        self.conv = nn.Conv1d(
            in_channels,
            out_channels,
            kernel_size,
            stride=stride,
            dilation=dilation,
            groups=groups,
        )
        self.stride = stride
        self.kernel_size = (kernel_size - 1) * dilation + 1
        self.dilation = dilation
        self.padding = self.kernel_size - self.stride

    def forward(self, x: Tensor) -> Tensor:
        pad = self.padding
        extra_padding = get_extra_padding_for_conv1d(
            x, self.kernel_size, self.stride, pad
        )
        x = pad1d(x, (pad, extra_padding), mode="constant", value=0.0)
        return self.conv(x).contiguous()

    def weight_norm(self, name: str = "weight", dim: int = 0) -> "CausalConvNet":
        self.conv = param_weight_norm(self.conv, name=name, dim=dim)
        return self

    def remove_weight_norm(self) -> "CausalConvNet":
        if is_parametrized(self.conv, "weight"):
            remove_parametrizations(self.conv, "weight", leave_parametrized=True)
        return self


class CausalTransConvNet(nn.Module):
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int,
        dilation: int = 1,
        stride: int = 1,
        padding: Optional[int] = None,
    ) -> None:
        super().__init__()
        self.conv = nn.ConvTranspose1d(
            in_channels,
            out_channels,
            kernel_size,
            stride=stride,
            dilation=dilation,
        )
        self.stride = stride
        self.kernel_size = kernel_size

    def forward(self, x: Tensor) -> Tensor:
        x = self.conv(x)
        pad = self.kernel_size - self.stride
        padding_right = math.ceil(pad)
        padding_left = pad - padding_right
        x = unpad1d(x, (padding_left, padding_right))
        return x.contiguous()

    def weight_norm(self, name: str = "weight", dim: int = 0) -> "CausalTransConvNet":
        self.conv = param_weight_norm(self.conv, name=name, dim=dim)
        return self

    def remove_weight_norm(self) -> "CausalTransConvNet":
        if is_parametrized(self.conv, "weight"):
            remove_parametrizations(self.conv, "weight", leave_parametrized=True)
        return self


def CausalWNConv1d(*args, **kwargs) -> CausalConvNet:
    return CausalConvNet(*args, **kwargs).weight_norm()


def CausalWNConvTranspose1d(*args, **kwargs) -> CausalTransConvNet:
    return CausalTransConvNet(*args, **kwargs).weight_norm()


def LegacyWNConv1d(*args, **kwargs) -> nn.Conv1d:
    """Legacy weight_norm storing weight_g/weight_v as used by DAC VectorQuantize."""
    return legacy_weight_norm(nn.Conv1d(*args, **kwargs))


class ConvNeXtBlock(nn.Module):
    def __init__(
        self,
        dim: int,
        layer_scale_init_value: float = 1e-6,
        mlp_ratio: float = 4.0,
        kernel_size: int = 7,
        dilation: int = 1,
    ) -> None:
        super().__init__()
        self.dwconv = CausalConvNet(
            dim,
            dim,
            kernel_size=kernel_size,
            groups=dim,
            dilation=dilation,
        )
        self.norm = nn.LayerNorm(dim, eps=1e-6)
        self.pwconv1 = nn.Linear(dim, int(mlp_ratio * dim))
        self.act = nn.GELU()
        self.pwconv2 = nn.Linear(int(mlp_ratio * dim), dim)
        self.gamma = (
            nn.Parameter(layer_scale_init_value * torch.ones((dim,)), requires_grad=True)
            if layer_scale_init_value > 0
            else None
        )

    def forward(self, x: Tensor, apply_residual: bool = True) -> Tensor:
        residual = x
        x = self.dwconv(x)
        x = x.permute(0, 2, 1)
        x = self.norm(x)
        x = self.pwconv1(x)
        x = self.act(x)
        x = self.pwconv2(x)
        if self.gamma is not None:
            x = self.gamma * x
        x = x.permute(0, 2, 1)
        if apply_residual:
            x = residual + x
        return x


class VectorQuantize(nn.Module):
    """Factorized L2-normalized vector quantizer matching descript-audio-codec."""

    def __init__(self, input_dim: int, codebook_size: int, codebook_dim: int) -> None:
        super().__init__()
        self.codebook_size = codebook_size
        self.codebook_dim = codebook_dim
        self.in_proj = LegacyWNConv1d(input_dim, codebook_dim, kernel_size=1)
        self.out_proj = LegacyWNConv1d(codebook_dim, input_dim, kernel_size=1)
        self.codebook = nn.Embedding(codebook_size, codebook_dim)

    def embed_code(self, embed_id: Tensor) -> Tensor:
        return F.embedding(embed_id, self.codebook.weight)

    def decode_code(self, embed_id: Tensor) -> Tensor:
        return self.embed_code(embed_id).transpose(1, 2)

    def decode_latents(self, latents: Tensor) -> Tuple[Tensor, Tensor]:
        bsz, dim, length = latents.shape
        encodings = latents.transpose(1, 2).reshape(bsz * length, dim)
        codebook = self.codebook.weight

        encodings = F.normalize(encodings, dim=-1)
        codebook = F.normalize(codebook, dim=-1)

        dist = (
            encodings.pow(2).sum(1, keepdim=True)
            - 2.0 * (encodings @ codebook.t())
            + codebook.pow(2).sum(1, keepdim=True).t()
        )
        indices = (-dist).argmax(dim=1).view(bsz, length)
        z_q = self.decode_code(indices)
        return z_q, indices

    def forward(self, z: Tensor) -> Tuple[Tensor, Tensor, Tensor, Tensor, Tensor]:
        z_e = self.in_proj(z)
        z_q, indices = self.decode_latents(z_e)
        commitment_loss = F.mse_loss(z_e, z_q.detach(), reduction="none").mean([1, 2])
        codebook_loss = F.mse_loss(z_q, z_e.detach(), reduction="none").mean([1, 2])
        z_q = z_e + (z_q - z_e).detach()
        z_q = self.out_proj(z_q)
        return z_q, commitment_loss, codebook_loss, indices, z_e


class ResidualVectorQuantize(nn.Module):
    def __init__(
        self,
        input_dim: int = 1024,
        n_codebooks: int = 9,
        codebook_size: int = 1024,
        codebook_dim: Union[int, List[int]] = 8,
        quantizer_dropout: float = 0.0,
    ) -> None:
        super().__init__()
        if isinstance(codebook_dim, int):
            codebook_dim = [codebook_dim for _ in range(n_codebooks)]
        self.n_codebooks = n_codebooks
        self.codebook_dim = codebook_dim
        self.codebook_size = codebook_size
        self.quantizers = nn.ModuleList(
            [
                VectorQuantize(input_dim, codebook_size, codebook_dim[i])
                for i in range(n_codebooks)
            ]
        )
        self.quantizer_dropout = quantizer_dropout

    def forward(
        self, z: Tensor, n_quantizers: Optional[int] = None
    ) -> Tuple[Tensor, Tensor, Tensor, Tensor, Tensor]:
        z_q: Union[float, Tensor] = 0.0
        residual = z
        commitment_loss: Union[float, Tensor] = 0.0
        codebook_loss: Union[float, Tensor] = 0.0
        codebook_indices: List[Tensor] = []
        latents: List[Tensor] = []

        if n_quantizers is None:
            n_quantizers = self.n_codebooks
        if self.training:
            n_q_tensor = torch.ones((z.shape[0],)) * self.n_codebooks + 1
            dropout = torch.randint(1, self.n_codebooks + 1, (z.shape[0],))
            n_dropout = int(z.shape[0] * self.quantizer_dropout)
            n_q_tensor[:n_dropout] = dropout[:n_dropout]
            n_q_tensor = n_q_tensor.to(z.device)
        else:
            n_q_tensor = n_quantizers

        for i, quantizer in enumerate(self.quantizers):
            if not self.training and i >= n_quantizers:
                break
            z_q_i, commit_i, cb_i, indices_i, z_e_i = quantizer(residual)
            mask = (
                torch.full((z.shape[0],), fill_value=i, device=z.device) < n_q_tensor
            )
            z_q = z_q + z_q_i * mask[:, None, None]
            residual = residual - z_q_i
            commitment_loss = commitment_loss + (commit_i * mask).mean()
            codebook_loss = codebook_loss + (cb_i * mask).mean()
            codebook_indices.append(indices_i)
            latents.append(z_e_i)

        codes = torch.stack(codebook_indices, dim=1)
        latents_cat = torch.cat(latents, dim=1)
        return z_q, codes, latents_cat, commitment_loss, codebook_loss

    def from_codes(self, codes: Tensor) -> Tuple[Tensor, Tensor, Tensor]:
        z_q: Optional[Tensor] = None
        z_p: List[Tensor] = []
        n_codebooks = codes.shape[1]
        for i in range(n_codebooks):
            z_p_i = self.quantizers[i].decode_code(codes[:, i, :])
            z_p.append(z_p_i)
            z_q_i = self.quantizers[i].out_proj(z_p_i)
            z_q = z_q_i if z_q is None else z_q + z_q_i
        assert z_q is not None
        return z_q, torch.cat(z_p, dim=1), codes


class DownsampleResidualVectorQuantize(nn.Module):
    def __init__(
        self,
        input_dim: int = 1024,
        n_codebooks: int = 9,
        codebook_dim: int = 8,
        quantizer_dropout: float = 0.5,
        codebook_size: int = 1024,
        semantic_codebook_size: int = 4096,
        downsample_factor: Tuple[int, ...] = (2, 2),
        downsample_dims: Optional[Tuple[int, ...]] = None,
        pre_module: Optional[nn.Module] = None,
        post_module: Optional[nn.Module] = None,
        semantic_predictor_module: Optional[nn.Module] = None,
    ) -> None:
        super().__init__()
        if downsample_dims is None:
            downsample_dims = tuple(input_dim for _ in range(len(downsample_factor)))
        all_dims = (input_dim,) + tuple(downsample_dims)

        self.semantic_quantizer = ResidualVectorQuantize(
            input_dim=input_dim,
            n_codebooks=1,
            codebook_size=semantic_codebook_size,
            codebook_dim=codebook_dim,
            quantizer_dropout=0.0,
        )
        self.quantizer = ResidualVectorQuantize(
            input_dim=input_dim,
            n_codebooks=n_codebooks,
            codebook_size=codebook_size,
            codebook_dim=codebook_dim,
            quantizer_dropout=quantizer_dropout,
        )

        self.downsample_factor = tuple(downsample_factor)
        self.downsample_dims = tuple(downsample_dims)

        self.downsample = nn.Sequential(
            *[
                nn.Sequential(
                    CausalConvNet(
                        all_dims[idx],
                        all_dims[idx + 1],
                        kernel_size=factor,
                        stride=factor,
                    ),
                    ConvNeXtBlock(dim=all_dims[idx + 1]),
                )
                for idx, factor in enumerate(self.downsample_factor)
            ]
        )
        self.upsample = nn.Sequential(
            *[
                nn.Sequential(
                    CausalTransConvNet(
                        all_dims[idx + 1],
                        all_dims[idx],
                        kernel_size=factor,
                        stride=factor,
                    ),
                    ConvNeXtBlock(dim=all_dims[idx]),
                )
                for idx, factor in reversed(list(enumerate(self.downsample_factor)))
            ]
        )

        self.pre_module = pre_module if pre_module is not None else nn.Identity()
        self.post_module = post_module if post_module is not None else nn.Identity()
        self.semantic_predictor_module = (
            semantic_predictor_module
            if semantic_predictor_module is not None
            else nn.Identity()
        )

    def forward(
        self,
        z: Tensor,
        n_quantizers: Optional[int] = None,
        semantic_len: Optional[Tensor] = None,
        **kwargs,
    ) -> VQResult:
        original_shape = z.shape
        z = self.downsample(z)
        z = self.pre_module(z)
        (
            semantic_z,
            semantic_codes,
            semantic_latents,
            semantic_commitment_loss,
            semantic_codebook_loss,
        ) = self.semantic_quantizer(z)
        residual_z = z - semantic_z
        residual_z, codes, latents, commitment_loss, codebook_loss = self.quantizer(
            residual_z, n_quantizers=n_quantizers
        )
        z = semantic_z + residual_z
        commitment_loss = commitment_loss + semantic_commitment_loss
        codebook_loss = codebook_loss + semantic_codebook_loss
        codes = torch.cat([semantic_codes, codes], dim=1)
        latents = torch.cat([semantic_latents, latents], dim=1)
        z = self.post_module(z)
        z = self.upsample(z)

        diff = original_shape[-1] - z.shape[-1]
        if diff > 0:
            z = F.pad(z, (diff, 0))
        elif diff < 0:
            z = z[..., abs(diff) :]

        return VQResult(
            z=z,
            codes=codes,
            latents=latents,
            commitment_loss=commitment_loss,
            codebook_loss=codebook_loss,
        )

    def decode(self, indices: Tensor) -> Tensor:
        semantic_indices = torch.clamp(
            indices[:, :1], min=0, max=self.semantic_quantizer.codebook_size - 1
        )
        residual_indices = torch.clamp(
            indices[:, 1:], min=0, max=self.quantizer.codebook_size - 1
        )
        z_q_semantic = self.semantic_quantizer.from_codes(semantic_indices)[0]
        z_q_residual = self.quantizer.from_codes(residual_indices)[0]
        z_q = z_q_semantic + z_q_residual
        z_q = self.post_module(z_q)
        z_q = self.upsample(z_q)
        return z_q


class ResidualUnit(nn.Module):
    def __init__(self, dim: int = 16, dilation: int = 1, causal: bool = True) -> None:
        super().__init__()
        pad = ((7 - 1) * dilation) // 2
        self.block = nn.Sequential(
            Snake1d(dim),
            CausalWNConv1d(dim, dim, kernel_size=7, dilation=dilation, padding=pad),
            Snake1d(dim),
            CausalWNConv1d(dim, dim, kernel_size=1),
        )
        self.causal = causal

    def forward(self, x: Tensor) -> Tensor:
        y = self.block(x)
        pad = x.shape[-1] - y.shape[-1]
        if pad > 0:
            x = x[..., :-pad] if self.causal else x[..., pad // 2 : -pad // 2]
        return x + y


class EncoderBlock(nn.Module):
    def __init__(
        self,
        dim: int = 16,
        stride: int = 1,
        causal: bool = True,
        n_t_layer: int = 0,
        transformer_general_config=None,
    ) -> None:
        super().__init__()
        if n_t_layer == 0:
            transformer_module: nn.Module = nn.Identity()
        else:
            transformer_module = WindowLimitedTransformer(
                causal=causal,
                input_dim=dim,
                window_size=getattr(transformer_general_config, "window_size", 512),
                config=transformer_general_config(
                    n_layer=n_t_layer,
                    n_head=dim // 64,
                    dim=dim,
                    intermediate_size=dim * 3,
                ),
            )
        self.block = nn.Sequential(
            ResidualUnit(dim // 2, dilation=1, causal=causal),
            ResidualUnit(dim // 2, dilation=3, causal=causal),
            ResidualUnit(dim // 2, dilation=9, causal=causal),
            Snake1d(dim // 2),
            CausalWNConv1d(
                dim // 2,
                dim,
                kernel_size=2 * stride,
                stride=stride,
                padding=math.ceil(stride / 2),
            ),
            transformer_module,
        )

    def forward(self, x: Tensor) -> Tensor:
        return self.block(x)


class Encoder(nn.Module):
    def __init__(
        self,
        d_model: int = 64,
        strides: Tuple[int, ...] = ENCODER_RATES,
        d_latent: int = 1024,
        n_transformer_layers: Tuple[int, ...] = (0, 0, 0, 4),
        transformer_general_config=None,
        causal: bool = True,
    ) -> None:
        super().__init__()
        blocks: List[nn.Module] = [CausalWNConv1d(1, d_model, kernel_size=7, padding=3)]
        for stride, n_t_layer in zip(strides, n_transformer_layers):
            d_model *= 2
            blocks.append(
                EncoderBlock(
                    d_model,
                    stride=stride,
                    causal=causal,
                    n_t_layer=n_t_layer,
                    transformer_general_config=transformer_general_config,
                )
            )
        blocks.extend(
            [
                Snake1d(d_model),
                CausalWNConv1d(d_model, d_latent, kernel_size=3, padding=1),
            ]
        )
        self.block = nn.Sequential(*blocks)
        self.enc_dim = d_model

    def forward(self, x: Tensor) -> Tensor:
        return self.block(x)


class DecoderBlock(nn.Module):
    def __init__(
        self,
        input_dim: int,
        output_dim: int,
        stride: int,
        causal: bool = True,
    ) -> None:
        super().__init__()
        self.block = nn.Sequential(
            Snake1d(input_dim),
            CausalWNConvTranspose1d(
                input_dim,
                output_dim,
                kernel_size=2 * stride,
                stride=stride,
                padding=math.ceil(stride / 2),
            ),
            ResidualUnit(output_dim, dilation=1, causal=causal),
            ResidualUnit(output_dim, dilation=3, causal=causal),
            ResidualUnit(output_dim, dilation=9, causal=causal),
        )

    def forward(self, x: Tensor) -> Tensor:
        return self.block(x)


class Decoder(nn.Module):
    def __init__(
        self,
        input_channel: int = 1024,
        channels: int = 1536,
        rates: Tuple[int, ...] = DECODER_RATES,
        d_out: int = 1,
        causal: bool = True,
    ) -> None:
        super().__init__()
        layers: List[nn.Module] = [
            CausalWNConv1d(input_channel, channels, kernel_size=7, padding=3)
        ]
        output_dim = channels
        for i, stride in enumerate(rates):
            input_dim = channels // (2**i)
            output_dim = channels // (2 ** (i + 1))
            layers.append(DecoderBlock(input_dim, output_dim, stride, causal=causal))
        layers.extend(
            [
                Snake1d(output_dim),
                CausalWNConv1d(output_dim, d_out, kernel_size=7, padding=3),
                nn.Tanh(),
            ]
        )
        self.model = nn.Sequential(*layers)

    def forward(self, x: Tensor) -> Tensor:
        return self.model(x)


class ModdedDAC(nn.Module):
    """Exact fishaudio/s2-pro 44.1 kHz ModdedDAC matching all 541 keys of codec.pth."""

    def __init__(
        self,
        encoder_dim: int = 64,
        encoder_rates: Tuple[int, ...] = ENCODER_RATES,
        latent_dim: Optional[int] = None,
        decoder_dim: int = 1536,
        decoder_rates: Tuple[int, ...] = DECODER_RATES,
        quantizer: Optional[DownsampleResidualVectorQuantize] = None,
        sample_rate: int = SAMPLE_RATE_HZ,
        causal: bool = True,
        encoder_transformer_layers: Tuple[int, ...] = (0, 0, 0, 4),
        transformer_general_config=None,
    ) -> None:
        super().__init__()
        self.encoder_dim = encoder_dim
        self.encoder_rates = tuple(encoder_rates)
        self.decoder_dim = decoder_dim
        self.decoder_rates = tuple(decoder_rates)
        self.sample_rate = sample_rate

        if latent_dim is None:
            latent_dim = encoder_dim * (2 ** len(self.encoder_rates))
        self.latent_dim = latent_dim

        self.hop_length = math.prod(self.encoder_rates)
        if transformer_general_config is None:
            transformer_general_config = partial(
                ModelArgs,
                block_size=8192,
                n_local_heads=-1,
                head_dim=64,
                rope_base=10000.0,
                norm_eps=1e-5,
                dropout_rate=0.1,
                attn_dropout_rate=0.1,
                channels_first=True,
            )

        self.encoder = Encoder(
            d_model=encoder_dim,
            strides=self.encoder_rates,
            d_latent=latent_dim,
            causal=causal,
            n_transformer_layers=encoder_transformer_layers,
            transformer_general_config=transformer_general_config,
        )

        if quantizer is None:
            vq_tf_cfg = lambda: ModelArgs(  # noqa: E731
                block_size=2048,
                n_layer=8,
                n_head=16,
                dim=latent_dim,
                intermediate_size=latent_dim * 3,
                n_local_heads=-1,
                head_dim=64,
                rope_base=10000.0,
                norm_eps=1e-5,
                dropout_rate=0.1,
                attn_dropout_rate=0.1,
                channels_first=True,
            )
            quantizer = DownsampleResidualVectorQuantize(
                input_dim=latent_dim,
                n_codebooks=9,
                codebook_size=RESIDUAL_CODEBOOK_SIZE,
                codebook_dim=8,
                quantizer_dropout=0.5,
                semantic_codebook_size=SEMANTIC_CODEBOOK_SIZE,
                downsample_factor=DOWNSAMPLE_FACTOR,
                pre_module=WindowLimitedTransformer(
                    config=vq_tf_cfg(),
                    input_dim=latent_dim,
                    window_size=128,
                    causal=True,
                ),
                post_module=WindowLimitedTransformer(
                    config=vq_tf_cfg(),
                    input_dim=latent_dim,
                    window_size=128,
                    causal=True,
                ),
            )
        self.quantizer = quantizer
        self.decoder = Decoder(
            input_channel=latent_dim,
            channels=decoder_dim,
            rates=self.decoder_rates,
            causal=causal,
        )
        self.frame_length = self.hop_length * math.prod(self.quantizer.downsample_factor)

    @torch.inference_mode()
    def encode(
        self,
        audio_data: Tensor,
        audio_lengths: Optional[Tensor] = None,
        n_quantizers: Optional[int] = None,
    ) -> Tuple[Tensor, Tensor]:
        """Encode 44.1 kHz mono audio [B, T] or [B, 1, T] -> (codes [B, 10, N], lens [B])."""
        if audio_data.ndim == 2:
            audio_data = audio_data.unsqueeze(1)
        length = audio_data.shape[-1]
        right_pad = math.ceil(length / self.frame_length) * self.frame_length - length
        if right_pad > 0:
            audio_data = F.pad(audio_data, (0, right_pad))
        if audio_lengths is None:
            audio_lengths = torch.tensor(
                [length + right_pad] * audio_data.shape[0],
                device=audio_data.device,
                dtype=torch.long,
            )

        z = self.encoder(audio_data)
        vq_results = self.quantizer(z, n_quantizers=n_quantizers)
        indices = vq_results.codes
        indices_lens = torch.ceil(audio_lengths.float() / self.frame_length).long()
        return indices, indices_lens

    def from_indices(self, indices: Tensor) -> Tensor:
        """Decode discrete codes [B, 10, T] -> 44.1 kHz PCM [B, 1, T * 2048]."""
        z = self.quantizer.decode(indices.long())
        return self.decoder(z)

    def decode(self, z: Tensor) -> Tensor:
        return self.decoder(z)

    def remove_weight_norm_(self) -> "ModdedDAC":
        """Fuse all weight_norm parametrizations into plain Conv weights in-place."""
        for module in self.modules():
            if isinstance(module, (CausalConvNet, CausalTransConvNet)):
                module.remove_weight_norm()
            elif isinstance(module, VectorQuantize):
                for proj_name in ("in_proj", "out_proj"):
                    proj = getattr(module, proj_name)
                    if hasattr(proj, "weight_g"):
                        legacy_remove_weight_norm(proj)
        return self


DAC = ModdedDAC


class ExportableModdedDACDecoder(nn.Module):
    """Export-friendly, decoder-only ModdedDAC graph for LiteRT / torch.export.

    Accepts ``codes: int32 [1, 10, T]`` (fixed ``seq_len = T``) and returns
    ``pcm_44k: float32 [1, 1, T * 2048]``. All weight_norm hooks are fused into
    plain float32 Conv weights, RoPE ``freqs_cis`` is stored in float32, and
    causal window-limited attention uses a static additive float32 bias mask so
    every op lowers cleanly to standard TFLite float ops with bit-level parity.
    """

    def __init__(self, dac_model: ModdedDAC, seq_len: int) -> None:
        super().__init__()
        self.seq_len = int(seq_len)
        self.semantic_codebook_size = dac_model.quantizer.semantic_quantizer.codebook_size
        self.residual_codebook_size = dac_model.quantizer.quantizer.codebook_size

        # Deep-copy decoder-side submodules only so caller's model is never mutated.
        q_sem = copy.deepcopy(dac_model.quantizer.semantic_quantizer).eval().cpu()
        q_res = copy.deepcopy(dac_model.quantizer.quantizer).eval().cpu()
        post_module = copy.deepcopy(dac_model.quantizer.post_module).eval().cpu()
        upsample = copy.deepcopy(dac_model.quantizer.upsample).eval().cpu()
        decoder = copy.deepcopy(dac_model.decoder).eval().cpu()

        # Fuse all weight_norm hooks into plain Conv weights.
        for q_group in (q_sem, q_res):
            for vq in q_group.quantizers:
                if hasattr(vq.out_proj, "weight_g"):
                    legacy_remove_weight_norm(vq.out_proj)
                # Drop unused encoder-only in_proj from exported decoder graph.
                vq.in_proj = nn.Identity()

        for sub in (upsample, decoder):
            for m in sub.modules():
                if isinstance(m, (CausalConvNet, CausalTransConvNet)):
                    m.remove_weight_norm()

        # Pre-compute fused [codebook_size, 1024] embedding tables for each of the 10
        # codebooks: out_proj(embedding(code)) == F.embedding(code, W_fused) + bias.
        # Note out_proj is a 1x1 Conv1d (8 -> 1024) with weight [1024, 8, 1] and bias [1024].
        # Summing biases across all 10 codebooks into a single constant bias avoids 10 redundant
        # Conv1d(8->1024) ops AND is mathematically identical to float32 precision!
        fused_tables: List[Tensor] = []
        total_bias = torch.zeros(1, 1024, 1, dtype=torch.float32)

        all_vqs = list(q_sem.quantizers) + list(q_res.quantizers)
        for vq in all_vqs:
            # codebook: [K, 8], out_proj.weight: [1024, 8, 1], out_proj.bias: [1024]
            w_out = vq.out_proj.weight.squeeze(-1).float()  # [1024, 8]
            cb = vq.codebook.weight.float()  # [K, 8]
            fused = cb @ w_out.t()  # [K, 1024]
            fused_tables.append(fused)
            if vq.out_proj.bias is not None:
                total_bias = total_bias + vq.out_proj.bias.float().view(1, 1024, 1)

        self.register_buffer("semantic_table", fused_tables[0])  # [4096, 1024]
        self.register_buffer(
            "residual_tables", torch.stack(fused_tables[1:], dim=0)
        )  # [9, 1024, 1024]
        self.register_buffer("quantizer_bias", total_bias)  # [1, 1024, 1]

        # Extract static float32 RoPE freqs_cis and additive causal window mask for seq_len.
        self.post_layers = post_module.layers
        self.post_norm = post_module.norm
        freqs_f32 = post_module.freqs_cis[: self.seq_len].float().contiguous()
        self.register_buffer("post_freqs_cis", freqs_f32)  # [T, 32, 2]

        bool_mask = post_module.make_window_limited_mask(
            self.seq_len, device=torch.device("cpu")
        )  # [1, 1, T, T]
        attn_bias = torch.where(
            bool_mask,
            torch.zeros(1, 1, self.seq_len, self.seq_len, dtype=torch.float32),
            torch.full((1, 1, self.seq_len, self.seq_len), -1e9, dtype=torch.float32),
        )
        self.register_buffer("post_attn_bias", attn_bias)

        self.upsample = upsample
        self.decoder = decoder

    def forward(self, codes: Tensor) -> Tensor:
        # codes: [1, 10, T] int32 (or int64)
        codes_i64 = codes.to(torch.int64)
        sem_idx = torch.clamp(codes_i64[:, 0, :], 0, self.semantic_codebook_size - 1)
        z = F.embedding(sem_idx, self.semantic_table)  # [B, T, 1024]

        for i in range(9):
            res_idx = torch.clamp(
                codes_i64[:, i + 1, :], 0, self.residual_codebook_size - 1
            )
            z = z + F.embedding(res_idx, self.residual_tables[i])

        # Add combined out_proj bias in channels-last [B, T, 1024] directly before transformer.
        z = z + self.quantizer_bias.transpose(1, 2)

        for layer in self.post_layers:
            z = layer(
                z,
                self.post_freqs_cis,
                self.post_attn_bias,
                use_explicit_attn=True,
            )
        z = self.post_norm(z)
        z = z.transpose(1, 2)  # [B, 1024, T]
        z = self.upsample(z)  # [B, 1024, T * 4]
        pcm = self.decoder(z)  # [B, 1, T * 2048]
        return pcm


def build_s2_pro_dac() -> ModdedDAC:
    """Instantiate the exact fishaudio/s2-pro 44.1 kHz ModdedDAC architecture."""
    return ModdedDAC()


def load_s2_pro_dac(
    checkpoint_path: Union[str, Path] = "/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth",
    device: Union[str, torch.device] = "cpu",
    strict: bool = True,
) -> ModdedDAC:
    """Load fishaudio/s2-pro codec.pth with strict=True key verification."""
    model = build_s2_pro_dac()
    state_dict = torch.load(
        str(checkpoint_path), map_location="cpu", weights_only=True
    )
    if isinstance(state_dict, dict) and "state_dict" in state_dict:
        state_dict = state_dict["state_dict"]
    if any(k.startswith("generator.") for k in state_dict):
        state_dict = {
            k[len("generator.") :]: v
            for k, v in state_dict.items()
            if k.startswith("generator.")
        }
    model.load_state_dict(state_dict, strict=strict)
    model.eval()
    return model.to(device)


@torch.inference_mode()
def encode_waveform_44k(
    model: ModdedDAC, waveform_44k: Tensor
) -> Tuple[Tensor, Tensor]:
    """Encode a 44.1 kHz mono waveform [T], [1, T], or [B, 1, T] into [B, 10, N] int64 codes."""
    if waveform_44k.ndim == 1:
        waveform_44k = waveform_44k.unsqueeze(0).unsqueeze(0)
    elif waveform_44k.ndim == 2:
        waveform_44k = waveform_44k.unsqueeze(1)
    device = next(model.parameters()).device
    waveform_44k = waveform_44k.to(device=device, dtype=torch.float32)
    return model.encode(waveform_44k)


@torch.inference_mode()
def decode_codes_44k(model: ModdedDAC, codes: Tensor) -> Tensor:
    """Decode discrete [10, N] or [B, 10, N] codes into 44.1 kHz PCM [B, 1, N * 2048]."""
    if codes.ndim == 2:
        codes = codes.unsqueeze(0)
    device = next(model.parameters()).device
    return model.from_indices(codes.to(device=device, dtype=torch.long))
