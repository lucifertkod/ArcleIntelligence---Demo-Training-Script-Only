"""
config.py — ArcleIntelligence 5.82B

══════════════════════════════════════════════════════
SMOKE TEST FLAG  ←  THE ONLY THING YOU EVER CHANGE
══════════════════════════════════════════════════════

SMOKE_TEST = True
  → 1 epoch, 1× H100, 500 samples per dataset
  → Verifies the full pipeline works end-to-end
  → Takes ~25-35 minutes, costs ~$2-3
  → Launch: accelerate launch --config_file accelerate_config_smoke.yaml
            --num_processes 1 train.py

SMOKE_TEST = False
  → 3 epochs, 8× H100 SXM, full dataset (371K pairs)
  → Full production training
  → Takes ~497 hours, costs ~$11,560
  → Launch: accelerate launch --config_file accelerate_config.yaml
            --num_processes 8 train.py

══════════════════════════════════════════════════════
"""
import os

# ┌─────────────────────────────────────────────────┐
# │   CHANGE THIS FLAG — NOTHING ELSE               │
SMOKE_TEST = False  # ← True = smoke test, False = full training
# └─────────────────────────────────────────────────┘

BASE    = os.environ.get("BASE_DIR",    "/workspace/arcle")
MODELS  = os.environ.get("MODELS_DIR",  f"{BASE}/models")
DATA    = os.environ.get("DATA_DIR",    f"{BASE}/data")
OUT     = os.environ.get("OUTPUT_DIR",  f"{BASE}/output")

# ── Model paths ────────────────────────────────────────────────────────────────
# Using Falcon H1 3B BASE (not Instruct) — correct for Phase 1 connector training.
# Instruct fine-tuning adds chat templates that interfere with connector training.
FALCON_PATH   = f"{MODELS}/falcon_h1_3b_base"   # tiiuae/Falcon-H1-3B  (base)
GLM_OCR_PATH  = f"{MODELS}/glm_ocr"
SIGLIP_PATH   = f"{MODELS}/siglip2_vitl16"
WHISPER_PATH  = f"{MODELS}/whisper_medium"
SDVAE_PATH    = f"{MODELS}/sd_vae"
SD_UNET_PATH  = f"{MODELS}/sd_unet"
LCM_LORA_PATH = f"{MODELS}/lcm_lora"
KOKORO_PATH   = f"{MODELS}/kokoro_82m"

# ── Core dimensions ────────────────────────────────────────────────────────────
FALCON_HIDDEN  = 2560
SIGLIP_HIDDEN  = 1024   # ViT-L/16  ← NOT 1152 (So400m)
WHISPER_HIDDEN = 1024   # Medium    ← NOT 1280 (Large-v3)
GLM_HIDDEN     = 1536   # actual runtime value from zai-org/GLM-OCR
SIGLIP_PATCHES = 576    # (384/16)^2

# SD-VAE / UNet
SDVAE_LATENT_CH  = 4
SDVAE_LATENT_RES = 32
SDVAE_IMG_SIZE   = 256
UNET_COND_TOKENS = 77
UNET_COND_DIM    = 768
UNET_LCM_STEPS   = 8     # LCM — 8 steps, same quality as DDIM 20
UNET_GUIDANCE    = 1.0   # LCM always 1.0 — never change this

# Kokoro
KOKORO_STYLE_DIM = 256

# ── Connector dimensions (135M total) ─────────────────────────────────────────
VIS_IN, VIS_OUT, VIS_MID, VIS_LAYERS   = 1024, 2560, 5120, 3   # 32M
OCR_IN, OCR_OUT, OCR_MID, OCR_QUERIES  = 1536, 2560, 2048, 640  # ~40M
OCR_HEADS, OCR_LAYERS                   = 16, 2
AUD_IN, AUD_OUT, AUD_MID, AUD_TOKENS   = 1024, 2560, 2048, 128  # 27M
AUD_LAYERS                              = 3
VID_DIM, VID_HEADS, VID_TOKENS          = 2560, 16, 64           # 8M (2560/16=160)
IMGCOND_IN, IMGCOND_OUT, IMGCOND_MID    = 2560, 768, 2048        # 20M
IMGCOND_TOKENS, IMGCOND_LAYERS          = 77, 3
TTS_MID, TTS_LAYERS                     = 1280, 2                # 12M

# ── Dataset paths ──────────────────────────────────────────────────────────────
LLAVA_CC3M_PATH  = f"{DATA}/llava_cc3m"
DOCVQA_PATH      = f"{DATA}/docvqa"
LIBRISPEECH_PATH = f"{DATA}/librispeech"
DIFFUSIONDB_PATH = f"{DATA}/diffusiondb"
LJSPEECH_PATH    = f"{DATA}/ljspeech"

# ── Training settings — auto-selected by SMOKE_TEST flag ──────────────────────
if SMOKE_TEST:
    # ── SMOKE TEST MODE ───────────────────────────────────────────────────────
    # 500 samples × 5 modalities = 2,500 total pairs
    # 1 epoch, batch=4, ~25-35 min on 1× H100, costs ~$2-3
    EPOCHS             = 1
    PER_DEVICE_BATCH   = 4
    GRAD_ACCUM         = 2       # effective batch = 4×2×1GPU = 8
    MAX_STEPS          = -1
    LR                 = 1e-3
    LR_WARMUP          = 10      # short warmup for smoke test
    WEIGHT_DECAY       = 0.01
    MAX_GRAD_NORM      = 1.0
    DATALOADER_WORKERS = 2
    LOGGING_STEPS      = 5       # log frequently to catch issues fast
    SAVE_STEPS         = 20      # save once midway (smoke runs ~31 optimizer steps total)
    SAVE_LIMIT         = 2
    SEED               = 42

    # Small dataset subsets — just enough to test each pipeline path
    MAX_VISION  = 500
    MAX_OCR     = 500
    MAX_AUDIO   = 500
    MAX_IMGGEN  = 500
    MAX_TTS     = 500

else:
    # ── FULL TRAINING MODE ────────────────────────────────────────────────────
    # 371K pairs × 3 epochs = 1.11M samples
    # ~497h training on 8× H100 SXM
    # Cost ≈ ~$11,560 at standard H100 SXM rates
    EPOCHS             = 3
    PER_DEVICE_BATCH   = 4       # 80GB H100 OOMs at 8 due to Mamba SSM activations
    GRAD_ACCUM         = 8       # effective batch = 4×8×8GPUs = 256
    MAX_STEPS          = -1
    LR                 = 1e-3
    LR_WARMUP          = 300     # ~7% of ~4,350 total optimizer steps (3 epochs)
    WEIGHT_DECAY       = 0.01
    MAX_GRAD_NORM      = 1.0
    DATALOADER_WORKERS = 4
    LOGGING_STEPS      = 20
    SAVE_STEPS         = 500     # ~9 mid-training checkpoints across the 497h run
    SAVE_LIMIT         = 3
    SEED               = 42

    MAX_VISION  = 200_000
    MAX_OCR     =  80_000
    MAX_AUDIO   =  28_000
    MAX_IMGGEN  =  50_000
    MAX_TTS     =  13_100
