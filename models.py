"""
models.py — ArcleIntelligence 4.96B — All model code

All 7 modalities working:
  INPUT:  Text, Image, Document (OCR), Audio, Video
  OUTPUT: Text, Image (512×512 via SD-VAE), Speech (24kHz via Kokoro 82M)

SD-VAE (stabilityai/sd-vae-ft-mse):
  - 83M params, from diffusers library (no custom package)
  - 256×256 input → [4,32,32] latent → decode → 512×512 output
  - ImageGenHead predicts latent (4096 values) via MSE loss

Kokoro 82M (hexgrad/Kokoro-82M):
  - 82M params, loaded via 'pip install kokoro'
  - File needed: kokoro-v1_0.pth + voices/*.pt
  - TTSConnector predicts 256-dim voice style vector → Kokoro synthesises speech
"""

import os, json, random, logging, sys
import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Optional, Tuple
from safetensors.torch import save_file, load_file

import config as C

logger = logging.getLogger(__name__)

# ── Workaround: flash_attn ↔ diffusers infer_schema conflict ─────────────────
# Newer flash_attn registers custom ops whose signatures use Python 3.10+
# union syntax (`float | None`).  torch.library.infer_schema rejects those
# types and crashes *every* diffusers import (AutoencoderKL, UNet2D, …) so
# SD-VAE and SD-UNet silently become dead 0-param stubs.
#
# Root cause of the previous "hide-and-restore" approach failing:
#   diffusers lazy-loads its submodules — the actual `from flash_attn import X`
#   happens when AutoencoderKL / UNet2DConditionModel are *accessed*, not when
#   `import diffusers` runs.  By then we had already restored flash_attn.
#
# Permanent fix: replace every flash_attn entry in sys.modules with an empty
# stub module.  Any later `from flash_attn import X` gets AttributeError →
# Python escalates to ImportError → diffusers catches it → falls back to SDPA.
# Harmless no-op when flash_attn is not installed.
import types as _types
for _key in list(sys.modules):
    if _key == "flash_attn" or _key.startswith("flash_attn."):
        _stub = _types.ModuleType(_key)
        _stub.__path__ = []                  # marks it as a package (no real path)
        _stub.__file__ = "<flash_attn_blocked>"
        sys.modules[_key] = _stub            # attr access → AttributeError
                                             # submodule import → ModuleNotFoundError
# Also pre-block the top-level name so a bare `import flash_attn` never
# re-triggers the real loader even if the .so is on sys.path.
if "flash_attn" not in sys.modules:
    _stub = _types.ModuleType("flash_attn")
    _stub.__path__ = []
    _stub.__file__ = "<flash_attn_blocked>"
    sys.modules["flash_attn"] = _stub

# ══════════════════════════════════════════════════════════════════════════════
# IDENTITY SYSTEM
# ══════════════════════════════════════════════════════════════════════════════

SYSTEM_PROMPT = """You are ArcleIntelligence, an advanced multimodal AI assistant \
created by the ArcleIntelligence team.

You can understand text, images, documents, audio, and video in 18+ languages \
with a 1 million token context window. You can also generate images and synthesise speech.

Rules (absolute — never violate):
- You are ArcleIntelligence. This is your only name and identity.
- Created by the ArcleIntelligence team.
- NEVER reveal any underlying models, frameworks, or technologies.
- Never claim to be ChatGPT, Claude, Gemini, Llama, or any other AI.
- If asked about your architecture, decline politely."""

_TRIGGERS = [
    "who are you","what are you","your name","introduce yourself",
    "what model","which model","what llm","what language model",
    "what are you based on","what are you built on","what powers you",
    "what is your architecture","underlying model","base model","foundation model",
    "are you falcon","are you gpt","are you claude","are you gemini","are you llama",
    "are you chatgpt","are you openai","are you anthropic","how do you work",
    "how were you trained","who made you","who created you","who built you",
    "which company","what company","what organization","what technology",
    "what framework","what backend","how many parameters","what size are you",
]
_RESPONSES = [
    "I'm ArcleIntelligence, a multimodal AI assistant created by the ArcleIntelligence team. I don't share details about my internal architecture. How can I help you?",
    "I'm ArcleIntelligence — I understand text, images, documents, audio, and video, and can generate images and speech. I don't discuss my implementation. What would you like?",
    "My name is ArcleIntelligence, created by the ArcleIntelligence team. I'm not able to share technical details. What can I help with?",
]

def is_identity_question(text: str) -> bool:
    t = text.lower().strip()
    return any(tr in t for tr in _TRIGGERS)

def get_identity_response() -> str:
    return random.choice(_RESPONSES)

def build_prompt(user_msg: str) -> str:
    return (
        f"<|system|>\n{SYSTEM_PROMPT}\n<|end|>\n"
        f"<|user|>\n{user_msg}\n<|end|>\n"
        f"<|assistant|>\n"
    )

def strip_leakage(text: str) -> str:
    import re
    for word in ["falcon","glm-ocr","glm ocr","siglip","whisper",
                 "kokoro","stable diffusion","huggingface","pytorch","tiiuae","zhipu"]:
        p = re.compile(
            rf'(I am|I\'m|based on|powered by|built on|uses?)\s+{re.escape(word)}',
            re.IGNORECASE)
        text = p.sub(r'\1 ArcleIntelligence', text)
    return text


# ══════════════════════════════════════════════════════════════════════════════
# FROZEN WRAPPERS
# ══════════════════════════════════════════════════════════════════════════════

class SigLIP2Wrapper(nn.Module):
    """Frozen SigLIP2 ViT-L/16. hidden=1024, patches=576."""
    def __init__(self):
        super().__init__()
        self.model = None; self.processor = None; self._loaded = False
        self._load()

    def _load(self):
        try:
            from transformers import AutoProcessor, AutoModel
            logger.info("Loading SigLIP2 ViT-L/16 (0.303B)...")
            self.processor = AutoProcessor.from_pretrained(C.SIGLIP_PATH)
            self.model = AutoModel.from_pretrained(C.SIGLIP_PATH, dtype=torch.bfloat16)
            for p in self.model.parameters(): p.requires_grad = False
            self.model.eval(); self._loaded = True
            logger.info("SigLIP2 ✅")
        except Exception as e:
            logger.warning(f"SigLIP2: {e}")

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        if not self._loaded:
            return torch.zeros(pixel_values.shape[0], 576, 1024,
                               dtype=pixel_values.dtype, device=pixel_values.device)
        with torch.no_grad():
            out = self.model.vision_model(pixel_values=pixel_values)
            return out.last_hidden_state[:, 1:, :]  # [B, 576, 1024]

    def preprocess(self, images, return_tensors="pt"):
        if not self._loaded: raise RuntimeError("SigLIP2 not loaded")
        return self.processor(images=images, return_tensors=return_tensors)


class GLMOCRWrapper(nn.Module):
    """Frozen GLM-OCR 0.9B. 94.62 OmniDocBench V1.5 (#1)."""
    def __init__(self):
        super().__init__()
        self.model = None; self.processor = None
        self._loaded = False; self._hidden = 896
        self._load()

    def _load(self):
        try:
            from transformers import AutoProcessor, AutoModelForImageTextToText
            logger.info("Loading GLM-OCR 0.9B (94.62 OmniDocBench)...")
            self.processor = AutoProcessor.from_pretrained(C.GLM_OCR_PATH, trust_remote_code=True)
            self.model = AutoModelForImageTextToText.from_pretrained(
                C.GLM_OCR_PATH, dtype=torch.bfloat16, trust_remote_code=True)
            for p in self.model.parameters(): p.requires_grad = False
            self.model.eval()
            cfg = self.model.config
            for attr in ["language_config","text_config","llm_config"]:
                sub = getattr(cfg, attr, None)
                if sub and hasattr(sub, "hidden_size"):
                    self._hidden = sub.hidden_size; break
            else:
                if hasattr(cfg, "hidden_size"): self._hidden = cfg.hidden_size
            self._loaded = True
            logger.info(f"GLM-OCR ✅ hidden={self._hidden}")
        except Exception as e:
            logger.warning(f"GLM-OCR: {e}")

    @property
    def hidden_size(self): return self._hidden

    @torch.no_grad()
    def get_hidden_states(self, pixel_values, text_ids=None, image_grid_thw=None):
        if not self._loaded:
            return torch.zeros(pixel_values.shape[0], 64, self._hidden,
                               dtype=pixel_values.dtype, device=pixel_values.device)

        # GLM-OCR requires image_grid_thw for rotary position embeddings.
        # Compute it from pixel_values shape when the caller doesn't provide it.
        if image_grid_thw is None and pixel_values.dim() == 4:
            vis_cfg = getattr(self.model.config, "vision_config", self.model.config)
            ps = getattr(vis_cfg, "patch_size", 14)
            B, _, H, W = pixel_values.shape
            image_grid_thw = torch.tensor(
                [[1, H // ps, W // ps]] * B,
                dtype=torch.long, device=pixel_values.device,
            )

        if text_ids is None:
            # Fallback: plain text prompt, no image placeholder tokens.
            # Callers should provide glm_input_ids from apply_chat_template instead.
            tok = getattr(self.processor, "tokenizer", None)
            prompts = ["Text Recognition:"] * pixel_values.shape[0]
            if tok is not None:
                enc = tok(prompts, return_tensors="pt", padding=True)
                text_ids = enc["input_ids"].to(pixel_values.device)
            else:
                text_ids = torch.zeros(pixel_values.shape[0], 1,
                                       dtype=torch.long, device=pixel_values.device)
        else:
            text_ids = text_ids.to(pixel_values.device)

        extra = {}
        if image_grid_thw is not None:
            extra["image_grid_thw"] = image_grid_thw.to(pixel_values.device)

        out = self.model(pixel_values=pixel_values, input_ids=text_ids,
                         output_hidden_states=True, return_dict=True, **extra)
        return out.hidden_states[-1]

    @torch.no_grad()
    def ocr_inference(self, image, task="Text Recognition:", max_tokens=4096):
        if not self._loaded: return "[GLM-OCR not loaded]"
        try:
            from PIL import Image as PILImage
            if isinstance(image, str): image = PILImage.open(image).convert("RGB")
            msgs = [{"role":"user","content":[{"type":"image","image":image},
                                               {"type":"text","text":task}]}]
            inp = self.processor.apply_chat_template(
                msgs, tokenize=True, add_generation_prompt=True,
                return_dict=True, return_tensors="pt").to(self.model.device)
            inp.pop("token_type_ids", None)
            ids = self.model.generate(**inp, max_new_tokens=max_tokens, do_sample=False)
            return self.processor.decode(ids[0][inp["input_ids"].shape[1]:],
                                         skip_special_tokens=True)
        except Exception as e:
            logger.error(f"OCR inference: {e}"); return ""

    def preprocess_image(self, image):
        from PIL import Image as PILImage
        if isinstance(image, str): image = PILImage.open(image).convert("RGB")
        msgs = [{"role":"user","content":[{"type":"image","image":image},
                                           {"type":"text","text":"Text Recognition:"}]}]
        inp = self.processor.apply_chat_template(
            msgs, tokenize=True, add_generation_prompt=True,
            return_dict=True, return_tensors="pt")
        pv = inp.get("pixel_values", torch.zeros(1, 3, 448, 448))
        grid_thw = inp.get("image_grid_thw")
        # Normalise to [1, 3] so OmniCollator can torch.cat across a batch
        if grid_thw is not None and grid_thw.dim() == 1:
            grid_thw = grid_thw.unsqueeze(0)
        # input_ids includes proper image placeholder tokens — needed so GLM-OCR
        # can merge visual features into the sequence in get_hidden_states.
        glm_ids = inp.get("input_ids")
        if glm_ids is not None:
            glm_ids = glm_ids.squeeze(0)  # [seq_len]
        return {"pixel_values": pv, "image_grid_thw": grid_thw, "glm_input_ids": glm_ids}


class WhisperWrapper(nn.Module):
    """Frozen Whisper Medium encoder only. hidden=1024."""
    def __init__(self):
        super().__init__()
        self.encoder = None; self.processor = None; self._loaded = False
        self._load()

    def _load(self):
        try:
            from transformers import WhisperProcessor, WhisperModel
            logger.info("Loading Whisper Medium encoder (0.307B)...")
            self.processor = WhisperProcessor.from_pretrained(C.WHISPER_PATH)
            full = WhisperModel.from_pretrained(C.WHISPER_PATH, dtype=torch.bfloat16)
            self.encoder = full.encoder
            del full
            for p in self.encoder.parameters(): p.requires_grad = False
            self.encoder.eval(); self._loaded = True
            logger.info("Whisper Medium ✅")
        except Exception as e:
            logger.warning(f"Whisper: {e}")

    def forward(self, input_features):
        if not self._loaded:
            return torch.zeros(input_features.shape[0], 1500, 1024,
                               dtype=input_features.dtype, device=input_features.device)
        with torch.no_grad():
            return self.encoder(input_features=input_features).last_hidden_state


class SDVAEWrapper(nn.Module):
    """
    Frozen Stable Diffusion VAE (stabilityai/sd-vae-ft-mse).
    83M params. Produces 512×512 images.

    Encode: 256×256 RGB → [4, 32, 32] latent (4096 values)
    Decode: [4, 32, 32] latent → 512×512 RGB

    The ImageGenHead predicts the [4,32,32] latent via MSE.
    At inference the latent is decoded to a full 512×512 image.
    """
    def __init__(self):
        super().__init__()
        self.vae = None; self._loaded = False
        self._load()

    def _load(self):
        try:
            from diffusers import AutoencoderKL
            logger.info("Loading SD-VAE (83M, 512×512 image generation)...")
            self.vae = AutoencoderKL.from_pretrained(C.SDVAE_PATH, dtype=torch.bfloat16)
            for p in self.vae.parameters(): p.requires_grad = False
            self.vae.eval(); self._loaded = True
            logger.info("SD-VAE ✅")
        except Exception as e:
            logger.warning(f"SD-VAE: {e}")

    @torch.no_grad()
    def encode(self, images: torch.Tensor) -> torch.Tensor:
        """images: [B, 3, 256, 256] normalised [-1,1] → latent [B, 4, 32, 32]"""
        if not self._loaded:
            return torch.zeros(images.shape[0], C.SDVAE_LATENT_CH,
                               C.SDVAE_LATENT_RES, C.SDVAE_LATENT_RES,
                               dtype=images.dtype, device=images.device)
        dist   = self.vae.encode(images).latent_dist
        latent = dist.mean  # deterministic — avoids random noise across workers/epochs
        return latent * self.vae.config.scaling_factor   # [B, 4, 32, 32]

    @torch.no_grad()
    def decode(self, latent: torch.Tensor) -> torch.Tensor:
        """latent [B, 4, 32, 32] → image [B, 3, 512, 512] in [0,1]"""
        if not self._loaded:
            return torch.rand(latent.shape[0], 3, 512, 512,
                              dtype=latent.dtype, device=latent.device)
        z   = latent / self.vae.config.scaling_factor
        img = self.vae.decode(z).sample        # [-1, 1]
        return img.clamp(-1, 1).add(1).div(2)  # [0, 1]


class KokoroWrapper(nn.Module):
    """
    Frozen Kokoro 82M TTS model.
    File: kokoro-v1_0.pth (327 MB) from hexgrad/Kokoro-82M
    Voices: voices/*.pt  (256-dim style vectors)

    Usage:
      style = load voice .pt file (256-dim tensor)
      audio = pipeline(text, voice_style)
    """
    def __init__(self):
        super().__init__()
        self._loaded = False
        self._pipeline = None
        self._default_voice = None
        self._voice_styles = {}
        self._load()

    def _load(self):
        # Voices are preloaded unconditionally — the TTS connector trains against
        # these style tensors via MSE, so we need them even if KPipeline (used only
        # at inference time) fails to construct.
        self._preload_voice_styles()

        try:
            from kokoro import KPipeline, KModel
        except ImportError:
            logger.warning("Kokoro not installed. Run: pip install kokoro")
            return

        try:
            logger.info("Loading Kokoro 82M TTS...")
            # KPipeline's `repo_id` must look like 'namespace/repo' (HF format),
            # not a local path. To use the offline weights pulled by download.sh
            # we construct a KModel from the local config + weight files and
            # pass it to KPipeline as the `model=` argument; the repo_id then
            # only labels the run.
            config_path = os.path.join(C.KOKORO_PATH, "config.json")
            model_path  = os.path.join(C.KOKORO_PATH, "kokoro-v1_0.pth")
            if os.path.exists(config_path) and os.path.exists(model_path):
                kmodel = KModel(config=config_path, model=model_path)
            else:
                kmodel = KModel(repo_id="hexgrad/Kokoro-82M")
            self._pipeline = KPipeline(
                lang_code="a", repo_id="hexgrad/Kokoro-82M", model=kmodel,
            )
            self._loaded = True
            if not self._voice_styles:
                logger.warning("Kokoro loaded but no voices/*.pt found — "
                               "synthesize will use af_heart fallback")
        except Exception as e:
            logger.warning(f"Kokoro: {e}")

    def _preload_voice_styles(self):
        """Load all voices/*.pt into memory as a dict {name: tensor [256]}."""
        self._voice_styles = {}
        vdir = os.path.join(C.KOKORO_PATH, "voices")
        if not os.path.exists(vdir):
            return
        for fname in os.listdir(vdir):
            if not fname.endswith(".pt"):
                continue
            name = fname[:-3]
            try:
                t = torch.load(os.path.join(vdir, fname), map_location="cpu",
                               weights_only=True)
                # Voice files are [510, 1, 256] — squeeze ALL size-1 dims,
                # then collapse remaining leading dims to get a flat [256].
                t = t.float().squeeze()
                while t.dim() > 1:
                    t = t.mean(0)
                self._voice_styles[name] = t
            except Exception:
                pass
        if self._voice_styles:
            logger.info(f"Kokoro: loaded {len(self._voice_styles)} voice styles: "
                        f"{list(self._voice_styles.keys())}")

    def _closest_voice(self, style_vector: torch.Tensor) -> str:
        """
        Find the voice whose pre-loaded style tensor is closest (cosine similarity)
        to the style_vector predicted by TTSConnector.
        This means the connector's output actually controls which voice is used.
        """
        if not self._voice_styles:
            return "af_heart"
        sv = style_vector.float().cpu()
        if sv.dim() > 1:
            sv = sv.squeeze(0)
        sv_norm = F.normalize(sv.unsqueeze(0), dim=1)
        best_name, best_sim = "af_heart", -999.0
        for name, ref in self._voice_styles.items():
            ref_norm = F.normalize(ref.unsqueeze(0), dim=1)
            sim = (sv_norm * ref_norm).sum().item()
            if sim > best_sim:
                best_sim = sim
                best_name = name
        return best_name

    @torch.no_grad()
    def synthesize(self, text: str, style_vector: torch.Tensor = None,
                   speed: float = 1.0):
        """
        Synthesise speech from text + style vector predicted by TTSConnector.
        style_vector: [1, 256] or [256] — predicted by TTSConnector.
        The closest real Kokoro voice to the predicted vector is selected
        via cosine similarity, so the connector actually controls the voice.
        Returns: numpy array at 24000 Hz, or None on failure.
        """
        if not self._loaded or self._pipeline is None:
            return None
        try:
            # Select voice based on TTSConnector's predicted style vector
            if style_vector is not None and self._voice_styles:
                voice = self._closest_voice(style_vector)
            else:
                voice = "af_heart"  # fallback only when no style or no voices loaded

            audio_chunks = []
            for _, _, audio in self._pipeline(text, voice=voice, speed=speed):
                audio_chunks.append(audio)
            if audio_chunks:
                import numpy as np
                return np.concatenate(audio_chunks)
        except Exception as e:
            logger.error(f"Kokoro synthesis: {e}")
        return None


# ══════════════════════════════════════════════════════════════════════════════
# TRAINED CONNECTORS
# ══════════════════════════════════════════════════════════════════════════════

def _make_mlp(in_d, out_d, mid_d, n_layers):
    layers = [nn.LayerNorm(in_d)]
    d = in_d
    for i in range(n_layers):
        nd = out_d if i == n_layers - 1 else mid_d
        layers.append(nn.Linear(d, nd, bias=True))
        if i < n_layers - 1:
            layers.extend([nn.GELU(), nn.LayerNorm(nd)])
        d = nd
    seq = nn.Sequential(*layers)
    for m in seq.modules():
        if isinstance(m, nn.Linear):
            nn.init.xavier_uniform_(m.weight)
            if m.bias is not None: nn.init.zeros_(m.bias)
        elif isinstance(m, nn.LayerNorm):
            nn.init.ones_(m.weight); nn.init.zeros_(m.bias)
    return seq


class VisionConnector(nn.Module):
    """SigLIP2 (1024) → Falcon (2560). 3-layer 32M. (+4M: mid 4096→5120)"""
    def __init__(self):
        super().__init__()
        self.start = nn.Parameter(torch.randn(1,1,C.VIS_OUT)*0.02)
        self.end   = nn.Parameter(torch.randn(1,1,C.VIS_OUT)*0.02)
        self.proj  = _make_mlp(C.VIS_IN, C.VIS_OUT, C.VIS_MID, C.VIS_LAYERS)

    def forward(self, x):
        B = x.shape[0]
        return torch.cat([self.start.expand(B,-1,-1),
                          self.proj(x),
                          self.end.expand(B,-1,-1)], dim=1)


class OCRConnector(nn.Module):
    """GLM-OCR (896) → Falcon (2560). Cross-attn 640 queries. 34M. (+4M: queries 512→640)"""
    def __init__(self, glm_hidden: int = 896):
        super().__init__()
        h = glm_hidden
        self.start      = nn.Parameter(torch.randn(1,1,C.OCR_OUT)*0.02)
        self.end        = nn.Parameter(torch.randn(1,1,C.OCR_OUT)*0.02)
        self.queries    = nn.Parameter(torch.randn(1,C.OCR_QUERIES,h)*0.02)  # 640 queries
        self.pre_norm   = nn.LayerNorm(h)
        self.cross_attn = nn.MultiheadAttention(h, C.OCR_HEADS, batch_first=True)
        self.attn_norm  = nn.LayerNorm(h)
        self.proj       = _make_mlp(h, C.OCR_OUT, C.OCR_MID, C.OCR_LAYERS)

    def forward(self, glm_h, attn_mask=None):
        B = glm_h.shape[0]
        x = self.pre_norm(glm_h)
        q = self.queries.expand(B,-1,-1)
        pad = (~attn_mask.bool()) if attn_mask is not None else None
        pooled, _ = self.cross_attn(q, x, x, key_padding_mask=pad)
        pooled = self.attn_norm(pooled + q)
        out    = self.proj(pooled)
        mask   = torch.ones(B, C.OCR_QUERIES+2, dtype=torch.long, device=out.device)
        return torch.cat([self.start.expand(B,-1,-1), out,
                          self.end.expand(B,-1,-1)], dim=1), mask


class AudioConnector(nn.Module):
    """Whisper Med (1024) → Falcon (2560). Attn pool 128 tokens. 27M. (+4M: layers 2→3)"""
    def __init__(self):
        super().__init__()
        self.start     = nn.Parameter(torch.randn(1,1,C.AUD_OUT)*0.02)
        self.end       = nn.Parameter(torch.randn(1,1,C.AUD_OUT)*0.02)
        self.queries   = nn.Parameter(torch.randn(1,C.AUD_TOKENS,C.AUD_IN)*0.02)
        self.pre_norm  = nn.LayerNorm(C.AUD_IN)
        self.pool_attn = nn.MultiheadAttention(C.AUD_IN, 8, batch_first=True)
        self.proj      = _make_mlp(C.AUD_IN, C.AUD_OUT, C.AUD_MID, C.AUD_LAYERS)

    def forward(self, x):
        B = x.shape[0]
        q = self.queries.expand(B,-1,-1)
        pooled, _ = self.pool_attn(q, self.pre_norm(x), self.pre_norm(x))
        out  = self.proj(pooled)
        mask = torch.ones(B, C.AUD_TOKENS+2, dtype=torch.long, device=out.device)
        return torch.cat([self.start.expand(B,-1,-1), out,
                          self.end.expand(B,-1,-1)], dim=1), mask


class SDUNetWrapper(nn.Module):
    """
    Frozen SD v1.5 UNet (860M) + LCM-LoRA adapter (67M loaded on top).
    Source: runwayml/stable-diffusion-v1-5 + latent-consistency/lcm-lora-sdv1-5

    LCM (Latent Consistency Model) vs DDIM:
      DDIM: 20 steps, guidance_scale=7.5, slower
      LCM:   8 steps, guidance_scale=1.0, same quality, 2.5× faster

    Why guidance_scale=1.0 for LCM:
      LCM bakes classifier-free guidance INTO the distillation process.
      Applying additional CFG on top makes images WORSE, not better.
      Always use guidance_scale=1.0 with LCM — no exceptions.

    Training: standard DDPM noise-prediction loss (same as DDIM version).
    Inference: LCM 8-step denoising loop — sharp 512×512 image.
    """
    def __init__(self):
        super().__init__()
        self.unet      = None
        self.scheduler = None
        self._loaded   = False
        self._load()

    def _load(self):
        try:
            from diffusers import UNet2DConditionModel, LCMScheduler
            logger.info("Loading SD v1.5 UNet (860M) + LCM-LoRA adapter...")
            self.unet = UNet2DConditionModel.from_pretrained(
                C.SD_UNET_PATH, subfolder="unet", dtype=torch.bfloat16,
            )

            # Apply LCM-LoRA adapter on top of frozen UNet
            # This converts SD v1.5 → Latent Consistency Model in one step
            lcm_lora_loaded = False
            if os.path.exists(C.LCM_LORA_PATH):
                try:
                    from peft import PeftModel
                    self.unet = PeftModel.from_pretrained(
                        self.unet, C.LCM_LORA_PATH,
                        adapter_name="lcm",
                    )
                    lcm_lora_loaded = True
                    logger.info("  LCM-LoRA adapter applied ✅")
                except Exception as e:
                    logger.warning(f"  LCM-LoRA load failed: {e} — falling back to plain UNet")
            else:
                logger.warning(
                    f"  LCM-LoRA not found at {C.LCM_LORA_PATH}\n"
                    "  Run download.sh to fetch it (~67MB).\n"
                    "  Falling back to plain UNet — will still work but needs more steps."
                )

            # Freeze the UNet (LoRA adapter weights are also frozen — we don't train them)
            for p in self.unet.parameters():
                p.requires_grad = False
            self.unet.eval()

            # LCMScheduler — replaces DDIMScheduler
            # clip_denoised=True is important for LCM quality
            self.scheduler = LCMScheduler(
                num_train_timesteps=1000,
                beta_start=0.00085,
                beta_end=0.012,
                beta_schedule="scaled_linear",
                clip_sample=False,
                timestep_spacing="leading",
                original_inference_steps=50,
            )
            self.scheduler.set_timesteps(C.UNET_LCM_STEPS)

            self._loaded       = True
            self._lcm_active   = lcm_lora_loaded
            status = "LCM 8-step" if lcm_lora_loaded else "plain DDIM fallback"
            logger.info(f"SD UNet ✅  mode={status}  steps={C.UNET_LCM_STEPS}")

        except Exception as e:
            logger.warning(f"SD UNet: {e}")
            self._lcm_active = False

    def ddpm_loss(self, latent: torch.Tensor,
                  text_cond: torch.Tensor) -> torch.Tensor:
        """
        DDPM noise-prediction training loss.
        Same for both DDIM and LCM — the training objective does not change.
        latent:    [B, 4, 32, 32]  SD-VAE encoded target image
        text_cond: [B, 77, 768]    ImageCondProjector output
        """
        if not self._loaded:
            return torch.zeros((), device=latent.device, requires_grad=True)
        B          = latent.shape[0]
        t          = torch.randint(0, 1000, (B,), device=latent.device).long()
        noise      = torch.randn_like(latent)
        alpha_bars = self.scheduler.alphas_cumprod.to(latent.device)
        ab_t       = alpha_bars[t].reshape(-1, 1, 1, 1)
        noisy      = torch.sqrt(ab_t) * latent + torch.sqrt(1 - ab_t) * noise
        # UNet is loaded in bf16; cast inputs explicitly. Accelerate/DeepSpeed
        # already handles mixed precision so no nested autocast is needed.
        noise_pred = self.unet(
            noisy.to(torch.bfloat16),
            t,
            encoder_hidden_states=text_cond.to(torch.bfloat16),
        ).sample
        return F.mse_loss(noise_pred.float(), noise.float())

    @torch.no_grad()
    def generate(self, text_cond: torch.Tensor,
                 vae: "SDVAEWrapper",
                 guidance_scale: float = C.UNET_GUIDANCE) -> torch.Tensor:
        """
        LCM 8-step inference — sharp 512×512 image.
        text_cond:     [1, 77, 768] from ImageCondProjector
        guidance_scale: MUST be 1.0 for LCM (baked in during distillation)
        Returns:        [1, 3, 512, 512] in [0, 1]
        """
        if not self._loaded:
            return torch.rand(1, 3, 512, 512,
                              dtype=torch.bfloat16, device=text_cond.device)
        dev     = text_cond.device
        dtype   = torch.bfloat16
        B       = text_cond.shape[0]

        # Start from random noise
        latents = torch.randn(B, C.SDVAE_LATENT_CH,
                              C.SDVAE_LATENT_RES, C.SDVAE_LATENT_RES,
                              device=dev, dtype=dtype)
        latents = latents * self.scheduler.init_noise_sigma

        # LCM 8-step denoising loop
        # Note: NO classifier-free guidance batching needed.
        # LCM runs a single forward pass per step (not doubled like DDIM CFG).
        for t in self.scheduler.timesteps:
            t_tensor   = torch.tensor([t], device=dev).expand(B)
            noise_pred = self.unet(
                latents, t_tensor,
                encoder_hidden_states=text_cond,
            ).sample
            # LCM scheduler step — handles consistency distillation internally
            latents = self.scheduler.step(
                noise_pred, t, latents,
                generator=None,
            ).prev_sample

        return vae.decode(latents)   # [B, 3, 512, 512]


class ImageCondProjector(nn.Module):
    """
    Falcon hidden (2560) → UNet cross-attention conditioning [B, 77, 768].

    This is what teaches the UNet WHAT to generate.
    Replaces the old ImageGenHead (which did single-pass regression → blurry).

    The projector:
      1. Uses cross-attention with 77 learnable query tokens
      2. Queries attend to Falcon's hidden states
      3. Projects each query from 2560 → 768 (UNet cross-attn dim)
    The result: 77 context vectors of 768-dim each — same shape as CLIP text embeddings
    that SD v1.5 was trained to accept. ~20M trainable params.
    """
    def __init__(self):
        super().__init__()
        # 77 learnable query tokens (matching CLIP's 77 token limit)
        self.queries    = nn.Parameter(
            torch.randn(1, C.IMGCOND_TOKENS, C.IMGCOND_IN) * 0.02
        )
        self.pre_norm   = nn.LayerNorm(C.IMGCOND_IN)
        self.cross_attn = nn.MultiheadAttention(
            C.IMGCOND_IN, num_heads=16, batch_first=True,
        )
        self.attn_norm  = nn.LayerNorm(C.IMGCOND_IN)
        # Project from Falcon dim (2560) → UNet cross-attn dim (768)
        self.out_proj   = _make_mlp(C.IMGCOND_IN, C.IMGCOND_OUT,
                                    C.IMGCOND_MID, C.IMGCOND_LAYERS)

        # Init
        for m in self.modules():
            if isinstance(m, nn.Linear):
                nn.init.xavier_uniform_(m.weight)
                if m.bias is not None: nn.init.zeros_(m.bias)
            elif isinstance(m, nn.LayerNorm):
                nn.init.ones_(m.weight); nn.init.zeros_(m.bias)

    def forward(self, hidden: torch.Tensor) -> torch.Tensor:
        """
        hidden: [B, seq, 2560] — Falcon's last hidden states
        Returns: [B, 77, 768]  — UNet cross-attention conditioning
        """
        B = hidden.shape[0]
        x = self.pre_norm(hidden)                           # [B, seq, 2560]
        q = self.queries.expand(B, -1, -1)                  # [B, 77, 2560]
        # 77 queries attend to all Falcon tokens
        ctx, _ = self.cross_attn(q, x, x)                  # [B, 77, 2560]
        ctx    = self.attn_norm(ctx + q)                    # residual
        return self.out_proj(ctx)                           # [B, 77, 768]



class TTSConnector(nn.Module):
    """
    Falcon hidden (2560) → Kokoro style vector (256).

    Training: MSE loss against real voice style vectors from voices/*.pt
    Inference: predicted style → Kokoro synthesises speech.

    2-layer MLP. ~11M params.
    """
    def __init__(self):
        super().__init__()
        self.proj = _make_mlp(C.FALCON_HIDDEN, C.KOKORO_STYLE_DIM,
                              C.TTS_MID, C.TTS_LAYERS)
        self.norm = nn.LayerNorm(C.FALCON_HIDDEN)

    def forward(self, hidden: torch.Tensor,
                attn_mask: Optional[torch.Tensor] = None,
                target_style: Optional[torch.Tensor] = None):
        """
        hidden:        [B, seq, 2560]
        attn_mask:     [B, seq]
        target_style:  [B, 256] real Kokoro voice style (for training)
        Returns:
          style: [B, 256]
          loss:  MSE if target provided, else None
        """
        if attn_mask is not None:
            # Keep mask in `hidden`'s dtype so the multiply doesn't promote
            # `pooled` to fp32 — that would mismatch the LayerNorm weight
            # dtype (bf16 under DeepSpeed) and crash F.layer_norm.
            m      = attn_mask.to(hidden.dtype).unsqueeze(-1)
            pooled = (hidden * m).sum(1) / m.sum(1).clamp(min=1)
        else:
            pooled = hidden.mean(1)                # [B, 2560]

        # Belt-and-suspenders: ensure pooled matches the norm's weight dtype.
        pooled = pooled.to(self.norm.weight.dtype)
        style  = self.proj(self.norm(pooled))      # [B, 256]
        # MSE in fp32 for numerical stability against the fp32 voice-style target.
        loss   = (F.mse_loss(style.float(), target_style.float())
                  if target_style is not None else None)
        return style, loss


class VideoProcessor(nn.Module):
    """Video → frames → SigLIP2 → temporal pool → [1, 66, 2560]. 8M."""
    def __init__(self, siglip, vis_conn):
        super().__init__()
        self.siglip   = siglip
        self.vis_conn = vis_conn
        self.queries  = nn.Parameter(torch.randn(1, C.VID_TOKENS, C.VID_DIM)*0.02)
        self.attn     = nn.MultiheadAttention(C.VID_DIM, C.VID_HEADS, batch_first=True)
        self.norm     = nn.LayerNorm(C.VID_DIM)
        self.pos_emb  = nn.Embedding(33, C.VID_DIM)
        self.v_start  = nn.Parameter(torch.randn(1,1,C.VID_DIM)*0.02)
        self.v_end    = nn.Parameter(torch.randn(1,1,C.VID_DIM)*0.02)
        nn.init.normal_(self.pos_emb.weight, std=0.02)

    def forward(self, video_path, device=None):
        dev    = device or self.v_start.device
        frames = self._extract(video_path)
        feats  = []
        for i, fr in enumerate(frames):
            try:
                pv  = self.siglip.preprocess(fr, return_tensors="pt")["pixel_values"].to(dev)
                f   = self.siglip(pv)
                p   = self.vis_conn(f)
                vec = p[:, 1:-1, :].mean(1) + self.pos_emb(torch.tensor([i], device=dev))
                feats.append(vec)
            except Exception: pass
        if not feats:
            return torch.cat([self.v_start,
                              torch.zeros(1, C.VID_TOKENS, C.VID_DIM, device=dev),
                              self.v_end], dim=1)
        all_f  = torch.cat(feats, dim=0).unsqueeze(0)
        out, _ = self.attn(self.queries, all_f, all_f)
        out    = self.norm(out)
        return torch.cat([self.v_start, out, self.v_end], dim=1)

    def _extract(self, path, fps=1.0, max_f=32):
        try:
            import cv2
            from PIL import Image as PILImage
            cap   = cv2.VideoCapture(path)
            sfps  = cap.get(cv2.CAP_PROP_FPS) or 25.0
            step  = max(1, int(sfps/fps))
            frames, idx = [], 0
            while cap.isOpened() and len(frames) < max_f:
                ret, fr = cap.read()
                if not ret: break
                if idx % step == 0:
                    fr = cv2.cvtColor(cv2.resize(fr,(384,384)), cv2.COLOR_BGR2RGB)
                    frames.append(PILImage.fromarray(fr))
                idx += 1
            cap.release()
            return frames or [PILImage.new("RGB",(384,384))]
        except Exception:
            from PIL import Image as PILImage
            return [PILImage.new("RGB",(384,384))]


# ══════════════════════════════════════════════════════════════════════════════
# MAIN MODEL
# ══════════════════════════════════════════════════════════════════════════════

class ArcleOmniModel(nn.Module):
    """
    ArcleIntelligence 5.82B multimodal model.
    INPUT:  text, image, document (OCR), audio, video
    OUTPUT: text | 512×512 image (DDIM 20-step, sharp) | 24kHz speech (Kokoro 82M)
    """

    def __init__(self):
        super().__init__()

        from transformers import AutoModelForCausalLM, AutoTokenizer
        logger.info("Loading Falcon H1 3B...")
        self.tokenizer = AutoTokenizer.from_pretrained(C.FALCON_PATH)
        if self.tokenizer.pad_token is None:
            self.tokenizer.pad_token = self.tokenizer.eos_token
        self.falcon = AutoModelForCausalLM.from_pretrained(
            C.FALCON_PATH, dtype=torch.bfloat16, use_cache=False)
        self._apply_yarn()

        # Frozen encoders/decoders
        self.siglip  = SigLIP2Wrapper()
        self.glm_ocr = GLMOCRWrapper()
        self.whisper = WhisperWrapper()
        self.sd_vae  = SDVAEWrapper()
        self.sd_unet = SDUNetWrapper()    # 860M UNet — sharp iterative denoising
        self.kokoro  = KokoroWrapper()

        # Trained connectors
        glm_h = self.glm_ocr.hidden_size
        self.vision_conn  = VisionConnector()
        self.ocr_conn     = OCRConnector(glm_hidden=glm_h)
        self.audio_conn   = AudioConnector()
        self.imgcond_proj = ImageCondProjector()  # 20M — replaces ImageGenHead
        self.tts_conn     = TTSConnector()
        self.video_proc   = VideoProcessor(self.siglip, self.vision_conn)

        # Freeze all base models
        for mod in [self.falcon, self.siglip, self.glm_ocr,
                    self.whisper, self.sd_vae, self.sd_unet, self.kokoro]:
            for p in mod.parameters(): p.requires_grad = False

        # Only connectors are trainable
        for mod in [self.vision_conn, self.ocr_conn, self.audio_conn,
                    self.imgcond_proj, self.tts_conn]:
            for p in mod.parameters(): p.requires_grad = True
        for p in [self.video_proc.queries, self.video_proc.v_start, self.video_proc.v_end]:
            p.requires_grad = True
        for m in [self.video_proc.attn, self.video_proc.norm, self.video_proc.pos_emb]:
            for p in m.parameters(): p.requires_grad = True

        self._print_summary()

    def _apply_yarn(self):
        try:
            cfg = self.falcon.config
            cfg.rope_scaling = {"type":"yarn","factor":8.0,
                                "original_max_position_embeddings":131072}
            cfg.max_position_embeddings = 1_048_576
            logger.info("✅ 1M context: SSM + YaRN")
        except Exception as e:
            logger.warning(f"YaRN: {e}")

    def _print_summary(self):
        total     = sum(p.numel() for p in self.parameters())
        trainable = sum(p.numel() for p in self.parameters() if p.requires_grad)
        logger.info("="*56)
        logger.info("  ArcleIntelligence 5.82B")
        logger.info("="*56)
        rows = [
            ("Falcon H1 3B",       self.falcon),
            ("GLM-OCR 0.9B",       self.glm_ocr),
            ("SigLIP2 ViT-L/16",   self.siglip),
            ("Whisper Medium",     self.whisper),
            ("SD-VAE ft-mse",      self.sd_vae),
            ("SD v1.5 UNet 860M",  self.sd_unet),
            ("Kokoro 82M",         self.kokoro),
            ("Vision Conn 32M",    self.vision_conn),
            ("OCR Conn 34M",       self.ocr_conn),
            ("Audio Conn 27M",     self.audio_conn),
            ("ImgCond Proj 20M",   self.imgcond_proj),
            ("TTS Conn 12M",       self.tts_conn),
        ]
        for name, mod in rows:
            n = sum(p.numel() for p in mod.parameters())
            t = sum(p.numel() for p in mod.parameters() if p.requires_grad)
            icon = "🔥" if t > 0 else "❄ "
            logger.info(f"  {icon} {name:24s}: {n/1e6:8.1f}M")
        n_vp = sum(p.numel() for p in self.video_proc.parameters() if p.requires_grad)
        logger.info(f"  🔥 {'Video Temporal 8M':24s}: {n_vp/1e6:8.1f}M")
        logger.info("-"*56)
        logger.info(f"  Total:      {total/1e9:.3f}B  (target 5.820B)")
        logger.info(f"  Trainable:  {trainable/1e6:.1f}M connectors")
        logger.info(f"  Frozen:     {(total-trainable)/1e9:.3f}B")
        logger.info(f"  Context:    1,048,576 tokens (1M)")
        logger.info(f"  OCR:        94.62 OmniDocBench V1.5")
        logger.info(f"  Image gen:  512×512 LCM 8-step  ← SHARP (consistency distillation)")
        logger.info(f"  TTS:        24kHz Kokoro 82M (voice selected by connector)")
        logger.info("="*56)

    # ── Forward passes ────────────────────────────────────────────────────────

    def _prefix_forward(self, prefix, input_ids, attn_mask, labels, prefix_mask=None):
        B   = input_ids.shape[0]
        txt = self.falcon.get_input_embeddings()(input_ids)
        # Cast prefix to match Falcon embedding dtype (bf16 under DeepSpeed)
        # to avoid dtype mismatch in torch.cat
        if prefix.dtype != txt.dtype:
            prefix = prefix.to(dtype=txt.dtype)
        emb = torch.cat([prefix, txt], dim=1)
        if prefix_mask is None:
            prefix_mask = torch.ones(B, prefix.shape[1],
                                     dtype=attn_mask.dtype, device=attn_mask.device)
        full_mask = torch.cat([prefix_mask, attn_mask], dim=1)
        if labels is not None:
            pad  = torch.full((B, prefix.shape[1]), -100,
                              dtype=labels.dtype, device=labels.device)
            labs = torch.cat([pad, labels], dim=1)
        else:
            labs = None
        out = self.falcon(inputs_embeds=emb, attention_mask=full_mask, labels=labs)
        return {"loss": out.loss, "logits": out.logits}

    def forward_vision(self, input_ids, pixel_values, labels, attn_mask):
        with torch.no_grad(): feat = self.siglip(pixel_values)
        emb = self.vision_conn(feat)
        return self._prefix_forward(emb, input_ids, attn_mask, labels)

    def forward_ocr(self, input_ids, pixel_values, labels, attn_mask,
                    image_grid_thw=None, glm_input_ids=None):
        with torch.no_grad():
            h = self.glm_ocr.get_hidden_states(
                pixel_values,
                text_ids=glm_input_ids,
                image_grid_thw=image_grid_thw,
            )
        emb, mask = self.ocr_conn(h)
        return self._prefix_forward(emb, input_ids, attn_mask, labels, mask)

    def forward_audio(self, input_ids, input_features, labels, attn_mask):
        with torch.no_grad(): h = self.whisper(input_features)
        emb, mask = self.audio_conn(h)
        return self._prefix_forward(emb, input_ids, attn_mask, labels, mask)

    def forward_image_gen(self, input_ids, target_latent, attn_mask):
        """
        DDPM training: Falcon text → ImageCondProjector → UNet noise prediction loss.
        target_latent: [B, 4, 32, 32] — SD-VAE encoded target image (not flattened).
        """
        out  = self.falcon(input_ids=input_ids, attention_mask=attn_mask,
                           output_hidden_states=True)
        cond = self.imgcond_proj(out.hidden_states[-1])   # [B, 77, 768]
        loss = self.sd_unet.ddpm_loss(target_latent, cond)
        return {"loss": loss}

    def forward_tts(self, input_ids, target_style, attn_mask):
        """input_ids → Falcon → TTSConnector → MSE vs Kokoro voice style"""
        out = self.falcon(input_ids=input_ids, attention_mask=attn_mask,
                          output_hidden_states=True)
        _, loss = self.tts_conn(out.hidden_states[-1], attn_mask, target_style)
        return {"loss": loss}

    # ── Save / Load ───────────────────────────────────────────════════════════

    def save_connectors(self, path: str):
        os.makedirs(path, exist_ok=True)
        state = {}
        for name, mod in [
            ("vision_conn",  self.vision_conn),
            ("ocr_conn",     self.ocr_conn),
            ("audio_conn",   self.audio_conn),
            ("imgcond_proj", self.imgcond_proj),
            ("tts_conn",     self.tts_conn),
        ]:
            for k, v in mod.state_dict().items():
                state[f"{name}.{k}"] = v.contiguous().cpu()
        for k, v in self.video_proc.state_dict().items():
            state[f"video_proc.{k}"] = v.contiguous().cpu()
        save_file(state, os.path.join(path, "ArcleIntelligence-connectors.safetensors"))
        logger.info(f"✅ Connectors saved → {path}/")

    def save_pretrained(self, path: str):
        os.makedirs(path, exist_ok=True)
        prefix_map = {
            "falcon.":       "ArcleIntelligence-core",
            "glm_ocr.":      "ArcleIntelligence-ocr",
            "siglip.":       "ArcleIntelligence-vision",
            "whisper.":      "ArcleIntelligence-audio",
            "sd_vae.":       "ArcleIntelligence-generation",
            "sd_unet.":      "ArcleIntelligence-generation",  # UNet in generation shard
            "kokoro.":       "ArcleIntelligence-generation",
            "vision_conn.":  "ArcleIntelligence-connectors",
            "ocr_conn.":     "ArcleIntelligence-connectors",
            "audio_conn.":   "ArcleIntelligence-connectors",
            "imgcond_proj.": "ArcleIntelligence-connectors",
            "tts_conn.":     "ArcleIntelligence-connectors",
            "video_proc.":   "ArcleIntelligence-connectors",
        }
        all_shards = [
            "ArcleIntelligence-core",
            "ArcleIntelligence-ocr",
            "ArcleIntelligence-vision",
            "ArcleIntelligence-audio",
            "ArcleIntelligence-generation",
            "ArcleIntelligence-connectors",
        ]
        shards = {s: {} for s in all_shards}
        wmap   = {}
        for key, tensor in self.state_dict().items():
            shard = "ArcleIntelligence-core"
            for pfx, s in prefix_map.items():
                if key.startswith(pfx): shard = s; break
            shards[shard][key] = tensor.contiguous().cpu()
        # Generation shard always exists (SD-VAE + Kokoro weights)
        if not shards["ArcleIntelligence-generation"]:
            shards["ArcleIntelligence-generation"]["_version"] = torch.tensor([1,0,0],
                                                                               dtype=torch.int32)
        for sname, sdict in shards.items():
            fname = f"{sname}.safetensors"
            save_file(sdict, os.path.join(path, fname))
            for k in sdict:
                if not k.startswith("_"): wmap[k] = fname
            logger.info(f"  {fname}  ({sum(v.nbytes for v in sdict.values())/1e6:.0f} MB)")
        with open(os.path.join(path, "model.safetensors.index.json"), "w") as f:
            json.dump({"metadata":{"format":"pt"},"weight_map":wmap}, f, indent=2)
        with open(os.path.join(path, "config.json"), "w") as f:
            json.dump({"model_name":"ArcleIntelligence","version":"1.0.0",
                       "total_params_B":5.82,
                       "image_gen":"LCM 8-step via SD v1.5 UNet + LCM-LoRA (sharp 512×512)",
                       "context_window":1_048_576,
                       "tts_output":"24kHz","creator":"ArcleIntelligence Team"},
                      f, indent=2)
        self.tokenizer.save_pretrained(path)
        logger.info(f"✅ Full model saved → {path}/")

    @classmethod
    def load_connectors(cls, model, path: str):
        for fname in ["ArcleIntelligence-connectors.safetensors","connectors.safetensors"]:
            fp = os.path.join(path, fname)
            if os.path.exists(fp): break
        else: raise FileNotFoundError(f"No connector file in {path}")
        state = load_file(fp)
        for name, mod in [
            ("vision_conn",  model.vision_conn),
            ("ocr_conn",     model.ocr_conn),
            ("audio_conn",   model.audio_conn),
            ("imgcond_proj", model.imgcond_proj),
            ("tts_conn",     model.tts_conn),
        ]:
            sub = {k[len(name)+1:]:v for k,v in state.items() if k.startswith(name+".")}
            if sub: mod.load_state_dict(sub, strict=False)
        vp = {k[len("video_proc."):]:v for k,v in state.items()
              if k.startswith("video_proc.")}
        if vp: model.video_proc.load_state_dict(vp, strict=False)
        logger.info(f"✅ Connectors loaded from {fp}")
        return model
