#!/bin/bash
# ArcleIntelligence — Download Models + Datasets
# Run once before training: bash download.sh
set -e

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✅ $1${NC}"; }
step() { echo -e "${CYAN}[$(date +%H:%M:%S)] $1${NC}"; }
warn() { echo -e "${YELLOW}  ⚠  $1${NC}"; }

# BASE is overridable for Lightning AI Studios:
#   export BASE_DIR=/teamspace/studios/this_studio/arcle
# or any other host. RunPod default: /workspace/arcle.
# We honour BASE_DIR (matches config.py's env var) and BASE (legacy) — pick whichever is set.
BASE=${BASE_DIR:-${BASE:-/workspace/arcle}}
MODELS=$BASE/models
DATA=$BASE/data
mkdir -p "$MODELS" "$DATA"
# Pass BASE through to the python heredocs so model/data paths stay in sync.
export BASE BASE_DIR=$BASE

echo ""
echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
echo -e "${CYAN}   ArcleIntelligence — Download Script            ${NC}"
echo -e "${CYAN}   Models: ~13 GB   |   Data: ~35 GB              ${NC}"
echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
echo ""

# ── Install packages ──────────────────────────────────────────────────────────
step "Installing packages..."
pip install -q --upgrade pip
pip install -q \
  diffusers>=0.30.0 \
  peft>=0.12.0 \
  kokoro>=0.9.0 \
  "transformers>=4.50.0" \
  torch>=2.3.0 torchvision torchaudio \
  accelerate>=0.34.0 \
  deepspeed>=0.15.0 \
  safetensors>=0.4.0 \
  datasets>=2.20.0 \
  "huggingface_hub>=0.24.0" \
  Pillow>=10.0.0 \
  opencv-python-headless \
  librosa soundfile \
  numpy scipy tqdm psutil \
  sentencepiece einops
ok "Core packages installed"

# ── Mamba SSM kernels (optional — CUDA build must match PyTorch's CUDA ver) ───
# mamba-ssm and causal-conv1d compile CUDA extensions that MUST be built with
# the same CUDA toolkit version that PyTorch was compiled against.
# RunPod images ship PyTorch+cu130 but the default CUDA_HOME may point to 12.4,
# causing a version-mismatch build failure.  We auto-detect the right CUDA path
# and fall back gracefully — Falcon H1 has a pure-PyTorch SSM fallback that is
# ~15% slower but produces identical results.
step "Installing Mamba SSM kernels (optional)..."
TORCH_CUDA_MAJOR=$(python3 -c "import torch; cv=torch.version.cuda or ''; print(cv.split('.')[0] if cv else '')" 2>/dev/null || echo "")
if [ -n "$TORCH_CUDA_MAJOR" ]; then
    CUDA_MATCH=$(ls -d /usr/local/cuda-${TORCH_CUDA_MAJOR}* 2>/dev/null | sort -V | tail -1 || echo "")
    if [ -n "$CUDA_MATCH" ]; then
        export CUDA_HOME="$CUDA_MATCH"
        step "  CUDA_HOME → $CUDA_HOME  (matches torch CUDA ${TORCH_CUDA_MAJOR}.x)"
    fi
fi
pip install -q mamba-ssm causal-conv1d 2>&1 \
    && ok "mamba-ssm + causal-conv1d installed (fast CUDA SSM kernels)" \
    || warn "mamba-ssm/causal-conv1d not installed (CUDA version mismatch) — Falcon H1 uses pure-PyTorch fallback (correct, ~15% slower)"

# ── Remove flash_attn if present (breaks diffusers imports) ──────────────────
# Newer flash_attn registers custom ops with Python 3.10+ union-type signatures
# that torch.library.infer_schema rejects, preventing SD-VAE and SD-UNet from
# loading.  PyTorch SDPA attention is used as fallback — same quality, no crash.
pip uninstall -y flash-attn 2>/dev/null && warn "Removed flash-attn (incompatible with diffusers)" || true

# ── Models ────────────────────────────────────────────────────────────────────
step "Downloading models..."

python3 - << 'PY'
import os
from huggingface_hub import snapshot_download

BASE = os.environ.get("BASE", "/workspace/arcle")
MODELS = f"{BASE}/models"

models = [
    ("tiiuae/Falcon-H1-3B-Base",   f"{MODELS}/falcon_h1_3b_base",
     ["*.gguf","*.bin","original"], "Falcon H1 3B  (BASE — not Instruct)"),
    ("zai-org/GLM-OCR",                      f"{MODELS}/glm_ocr",
     ["*.gguf"], "GLM-OCR 0.9B"),
    ("google/siglip2-large-patch16-384",     f"{MODELS}/siglip2_vitl16",
     [], "SigLIP2 ViT-L/16  (hidden=1024, NOT So400m!)"),
    ("openai/whisper-medium",                f"{MODELS}/whisper_medium",
     ["*.gguf","flax_model*","tf_model*"], "Whisper Medium  (hidden=1024, NOT Large-v3!)"),
    ("stabilityai/sd-vae-ft-mse",             f"{MODELS}/sd_vae",
     [], "SD-VAE  (83M, encodes/decodes 512×512 latents)"),
    # runwayml/stable-diffusion-v1-5 was deprecated in 2024; use the
    # community-maintained mirror at stable-diffusion-v1-5/stable-diffusion-v1-5
    # NOTE: Do NOT ignore *.safetensors — those ARE the UNet weight files.
    # Only ignore non-UNet subdirectories from the full SD v1.5 repo.
    ("stable-diffusion-v1-5/stable-diffusion-v1-5", f"{MODELS}/sd_unet",
     ["*.ckpt","vae/*","text_encoder/*","tokenizer/*",
      "feature_extractor/*","safety_checker/*"], "SD v1.5 UNet  (860M, base denoiser)"),
    ("latent-consistency/lcm-lora-sdv1-5",    f"{MODELS}/lcm_lora",
     [], "LCM-LoRA adapter  (67MB — converts SD v1.5 UNet to 8-step LCM)"),
    ("hexgrad/Kokoro-82M",                    f"{MODELS}/kokoro_82m",
     [], "Kokoro 82M  (TTS, file: kokoro-v1_0.pth + voices/)"),
]

for repo, local, ignore, name in models:
    print(f"  Downloading {name}...")
    try:
        snapshot_download(repo, local_dir=local,
                          ignore_patterns=ignore if ignore else None)
        print(f"  ✅ {name}")
    except Exception as e:
        print(f"  ❌ {name}: {e}")
        raise
PY

ok "All models downloaded"

# ── Datasets ──────────────────────────────────────────────────────────────────
step "Downloading datasets..."

python3 - << 'PY'
import sys, os, json, zipfile
BASE = os.environ.get("BASE", "/workspace/arcle")
DATA = f"{BASE}/data"

# Remove the arcle project directory from sys.path so that the local data.py
# never shadows the HuggingFace `datasets` package.
for _p in ('', '.', BASE):
    try: sys.path.remove(_p)
    except ValueError: pass

from datasets import load_dataset
from huggingface_hub import snapshot_download

# ── LLaVA-CC3M ────────────────────────────────────────────────────────────────
# This dataset uses a legacy loading script; trust_remote_code is no longer
# supported in datasets>=3.0. We download the raw files via snapshot_download
# and parse chat.json ourselves. The images.zip is extracted once.
print("  Downloading LLaVA-CC3M 200K (vision)...")
try:
    llava_dir = f"{DATA}/llava_cc3m"
    snapshot_download(
        "liuhaotian/LLaVA-CC3M-Pretrain-595K",
        local_dir=llava_dir,
        repo_type="dataset",
    )
    # Extract images.zip on first run (6.5 GB zip → images/ directory)
    zip_path = os.path.join(llava_dir, "images.zip")
    img_dir  = os.path.join(llava_dir, "images")
    if os.path.exists(zip_path) and not os.path.isdir(img_dir):
        print("  Extracting LLaVA images.zip (~6.5 GB) — one-time step...")
        os.makedirs(img_dir, exist_ok=True)
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(img_dir)
        print("  Extraction complete.")
    # Quick count to confirm
    chat_json = os.path.join(llava_dir, "chat.json")
    if os.path.exists(chat_json):
        with open(chat_json) as f:
            n = len(json.load(f))
        print(f"  ✅ LLaVA-CC3M 200K (vision): {n:,} samples in chat.json")
    else:
        print("  ✅ LLaVA-CC3M (vision): downloaded")
except Exception as e:
    print(f"  ⚠ LLaVA-CC3M: {e} (training will use what is available)")

# ── Standard HF datasets (all use native Parquet — no trust_remote_code) ──────
# Tuple shape: (repo, split, save_dir, limit, config_name, label)
#   - config_name goes to load_dataset(name=...) — for datasets with multiple subsets
#   - We DO NOT use cache_dir=save_dir; that produces a HF cache layout that
#     load_from_disk() cannot read. Instead, after .select() we call
#     ds.save_to_disk(save_dir) so data.py can load_from_disk(save_dir) directly.
hf_datasets = [
    ("HuggingFaceM4/DocumentVQA",   "train",              f"{DATA}/docvqa",
     80_000,  "DocVQA",         "DocVQA 80K (OCR)"),
    # IMPORTANT: use name="clean" + split="train.100".
    # Without name="clean" the loader defaults to the "all" config which
    # downloads ALL splits including train.other.500 (500h × ~480MB/file =
    # ~31 GB of parquet), filling the pod disk and crashing.
    # The "clean" config has only clean-100 and clean-360 splits (~2.4 GB
    # for train.100 only). Split name in the clean config is "train.100",
    # not "train.clean.100" (that name only exists in the "all" config).
    ("openslr/librispeech_asr",     "train.100",          f"{DATA}/librispeech",
     28_000,  "clean",          "LibriSpeech-100 28K (audio)"),
    # NOTE: poloclub/diffusiondb and keithito/lj_speech are both script-based
    # datasets (deprecated in datasets>=3). We skip them here:
    #   - ImageGen falls back to LLaVA images via data.py
    #   - TTS falls back to LLaVA captions via data.py
    # Both fallbacks are fine because the connectors are trained against fixed
    # latent / style targets — the source of the input text/image doesn't change
    # the training objective.
]

import shutil
for repo, split, save_dir, limit, name_arg, label in hf_datasets:
    print(f"  Downloading {label}...")
    try:
        # Skip if already saved in load_from_disk format (idempotent re-runs)
        if os.path.isdir(save_dir) and os.path.exists(os.path.join(save_dir, "dataset_info.json")):
            from datasets import load_from_disk
            ds = load_from_disk(save_dir)
            print(f"  ✅ {label}: {len(ds):,} samples (cached)")
            continue
        kwargs = {}
        if name_arg:
            kwargs["name"] = name_arg
        # force_redownload bypasses any stale/partial HF cache left by a
        # previous interrupted run.  We only reach here when save_dir is
        # absent (new download or just deleted by the integrity check), so
        # always doing a fresh fetch is correct and not wasteful.
        ds = load_dataset(repo, split=split,
                          download_mode="force_redownload", **kwargs)
        if limit and len(ds) > limit:
            ds = ds.select(range(limit))
        # Clean any stale save_dir crud, then write a fresh load_from_disk copy
        if os.path.isdir(save_dir):
            shutil.rmtree(save_dir, ignore_errors=True)
        os.makedirs(save_dir, exist_ok=True)
        ds.save_to_disk(save_dir)
        print(f"  ✅ {label}: {len(ds):,} samples")
    except Exception as e:
        print(f"  ⚠ {label}: {e} (training will use what is available)")
PY

ok "Datasets downloaded"

# ── Verify dataset integrity ─────────────────────────────────────────────────
step "Verifying dataset integrity..."
python3 - << 'PY'
import os, sys
BASE = os.environ.get("BASE", "/workspace/arcle")
DATA = f"{BASE}/data"

# Remove the arcle project directory from sys.path
for _p in ('', '.', BASE):
    try: sys.path.remove(_p)
    except ValueError: pass

errors = []

# Check LLaVA-CC3M images extraction
llava_imgs = os.path.join(DATA, "llava_cc3m", "images")
if os.path.isdir(llava_imgs):
    n = len(os.listdir(llava_imgs))
    if n < 10_000:
        errors.append(f"LLaVA images/ has only {n:,} files (expected ~595K) — re-extracting")
        import zipfile
        zip_path = os.path.join(DATA, "llava_cc3m", "images.zip")
        if os.path.exists(zip_path):
            import shutil
            shutil.rmtree(llava_imgs, ignore_errors=True)
            os.makedirs(llava_imgs, exist_ok=True)
            with zipfile.ZipFile(zip_path) as z:
                z.extractall(llava_imgs)
            n2 = len(os.listdir(llava_imgs))
            print(f"  ✅ LLaVA images re-extracted: {n2:,} files")
        else:
            print(f"  ⚠ images.zip not found — cannot re-extract")
    else:
        print(f"  ✅ LLaVA images: {n:,} files")
else:
    print(f"  ⚠ LLaVA images/ directory missing")

# Check Arrow datasets for corruption (try to load a few rows)
from datasets import load_from_disk
for name in ["docvqa", "librispeech"]:
    ds_path = os.path.join(DATA, name)
    if not os.path.isdir(ds_path) or not os.path.exists(os.path.join(ds_path, "dataset_info.json")):
        errors.append(f"{name}: not found, will be re-downloaded")
        continue
    try:
        ds = load_from_disk(ds_path)
        _ = ds[0]  # force-read the first row to verify arrow file integrity
        print(f"  ✅ {name}: {len(ds):,} samples (integrity OK)")
    except Exception as e:
        errors.append(f"{name}: corrupted ({e})")
        import shutil
        shutil.rmtree(ds_path, ignore_errors=True)
        print(f"  ❌ {name}: corrupted — deleted, will re-download")

if errors:
    print(f"\n  ⚠ {len(errors)} issue(s) found. Re-run download.sh to fix:")
    for e in errors:
        print(f"    - {e}")
else:
    print("  ✅ All datasets verified OK")
PY

# ── Copy scripts to BASE ──────────────────────────────────────────────────────
step "Copying training scripts to $BASE..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR"/*.py   "$BASE/" 2>/dev/null || true
cp "$SCRIPT_DIR"/*.yaml "$BASE/" 2>/dev/null || true
cp "$SCRIPT_DIR"/*.json "$BASE/" 2>/dev/null || true
cp "$SCRIPT_DIR"/*.sh   "$BASE/" 2>/dev/null || true
# datasets.py was renamed to data.py to avoid shadowing the HuggingFace
# `datasets` package. Remove any stale copy from previous runs.
rm -f "$BASE/datasets.py"
ok "Scripts copied to $BASE"

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  ✅ Download complete!                           ${NC}"
echo -e "${GREEN}                                                  ${NC}"
echo -e "${GREEN}  START TRAINING (always in tmux!):               ${NC}"
echo -e "${GREEN}    tmux new -s arcle                             ${NC}"
echo -e "${GREEN}    cd $BASE                                      ${NC}"
echo -e "${GREEN}    accelerate launch \\                           ${NC}"
echo -e "${GREEN}      --config_file accelerate_config_smoke.yaml \\${NC}"
echo -e "${GREEN}      --num_processes 1 train.py                  ${NC}"
echo -e "${GREEN}                                                  ${NC}"
echo -e "${GREEN}  Detach:   Ctrl+B then D                         ${NC}"
echo -e "${GREEN}  Monitor:  watch -n5 cat $BASE/logs/status.txt   ${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo ""
echo "  Budget estimate:  8× H100 SXM × ~\$2.91/hr × 497h ≈ \$11,560  (full mode, 3 epochs)"
