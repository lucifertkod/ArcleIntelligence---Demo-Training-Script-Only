# ArcleIntelligence

A 5.82-billion-parameter multimodal foundation model that natively understands and generates across text, images, audio, documents, and code.

> **Important — please read first**
> This repository is **not** the production source code of the ArcleIntelligence model. It is a public demonstration of the model architecture and the training pipeline that builds it. The complete source code will be released together with the trained model weights on Hugging Face once the training run finishes.

---

## Overview

ArcleIntelligence is a unified multimodal model designed to reason jointly across many forms of input and produce natural-language, visual, and audio output from a single shared decoder. Each input modality is processed by a specialised perception encoder, projected into a common semantic space by a small set of trainable connectors, and then handed to a strong text backbone for joint reasoning.

The result is a single model that can read a contract, describe a photograph, transcribe a meeting recording, write the code that automates a workflow, and synthesise a spoken answer — all in one conversation.

---

## Capabilities

### What the model can take as input

| Modality | Examples |
|---|---|
| **Text** | natural-language questions, multi-turn conversations, instructions, prompts |
| **Images** | photographs, screenshots, charts, diagrams, illustrations |
| **Documents** | PDFs, scanned pages, invoices, forms, tables, handwritten notes |
| **Audio** | speech in 18+ languages, voice notes, ambient sound, short music clips |
| **Video** | short video clips, analysed by sampling representative frames |
| **Code** | source code in all major programming languages |

### What the model can produce as output

| Modality | Details |
|---|---|
| **Text** | answers, explanations, summaries, structured responses (JSON, markdown, tables) |
| **Code** | runnable source code in all major programming languages |
| **Images** | 512 × 512 image generation from text prompts or visual references |
| **Audio** | natural-sounding 24 kHz speech with selectable voice characteristics |

> Video generation is **not** supported in this release.

---

## Model specifications

| Property | Value |
|---|---|
| Total parameters | **5.82 billion** |
| Trainable connector parameters | all |
| Context window | **2,000,000 tokens** |
| Output speech sample rate | 24 kHz |
| Output image resolution | 512 × 512 |
| Supported input languages | 18+ |

---

## Data philosophy and cultural focus

ArcleIntelligence is trained on a deliberately neutral corpus that does **not** favour or disparage any country, religion, caste, race, ethnic group, government, political party, or community. Unlike most widely deployed multimodal systems — whose training data is dominated by US-based or China-based sources and inherits the cultural assumptions, blind spots, and political leanings of those regions — ArcleIntelligence is built around a balanced, Indian-led data pipeline.

The corpus is curated specifically to:

- **Treat Indian culture, languages, geography, history, festivals, and everyday life as first-class content**, rather than as an underrepresented edge case the way most foreign models do.
- **Remain socially, politically, and religiously impartial** — no community, faith, caste, or group is privileged or marginalised in the training signal.
- **Serve Indian users and Indian use cases** across education, healthcare, public services, agriculture, small business, journalism, and creative work.
- **Avoid the systematic Western or Chinese bias** that other open and closed multimodal models have repeatedly been shown to exhibit on questions about Indian subjects, Indian leaders, Indian history, and Indian social context.

Although ArcleIntelligence is built primarily for India and the Indian context, its impartial training corpus means the model remains accurate, useful, and respectful when used anywhere else in the world.

---

## Benchmarks

| Benchmark | Score | Notes |
|---|---|---|
| **OmniDocBench V1.5** | **93.45 %** | Private internal evaluation. The score is expected to improve with longer training and additional fine-tuning. |

---

## Training infrastructure

| | |
|---|---|
| Hardware | **8 × NVIDIA H100 SXM 80 GB** |
| Numerical precision | bfloat16 mixed precision |
| Distributed strategy | ZeRO Stage 2 sharded optimiser |
| Effective batch size | 256 paired samples per optimiser step |
| Training duration | ~497 hours for the full 3-epoch run |
| Estimated cloud cost | ~$11,560 at standard H100 SXM rates |

---

## Repository layout

```
.
├── config.py                       central configuration (dimensions, paths, hyperparameters)
├── train.py                        training loop with distributed scaling
├── data.py                         dataset classes and the multimodal collator
├── models.py                       perception encoders and trainable connectors
├── inference.py                    interactive demo for running the trained model
├── export.py                       checkpoint export utility
├── download.sh                     downloads weights and datasets, installs dependencies
├── download_missing.py             repairs partial downloads from a previous run
├── accelerate_config.yaml          launcher config for full multi-GPU training
├── accelerate_config_smoke.yaml    launcher config for the single-GPU smoke test
├── ds_config.json                  distributed-training config (full mode)
├── ds_config_smoke.json            distributed-training config (smoke-test mode)
└── output/                         checkpoints and training logs
```

---

## How to run on your own PC or GPU server

### 1. Hardware requirements

| Mode | Recommended hardware |
|---|---|
| **Smoke test** (verify the pipeline) | 1 × NVIDIA GPU with at least 40 GB VRAM |
| **Full training** | 8 × NVIDIA H100 SXM 80 GB |
| **Inference only** | 1 × NVIDIA GPU with at least 24 GB VRAM |

A modern Linux distribution and CUDA 12.4 or newer are required. The repository has been validated on Ubuntu 22.04 and the standard RunPod / Lightning AI templates.

### 2. Clone the repository

```bash
git clone <this-repository-url>
cd <repository-folder>
```

### 3. Download weights, datasets, and dependencies

The provided script installs every Python package, downloads ~13 GB of pretrained weights and ~35 GB of training data, and verifies the integrity of every file:

```bash
# Optional: choose where everything is stored (defaults to /workspace/arcle)
export BASE_DIR=/path/to/your/data/folder

bash download.sh
```

The script is fully idempotent — running it again will skip files that are already present and only re-download anything that was previously incomplete.

### 4. Configure smoke test or full training

Open `config.py` and set a single flag at the top of the file:

```python
SMOKE_TEST = True      # 1 GPU, ~25-35 minutes, ~$2-3 — verifies the pipeline end to end
SMOKE_TEST = False     # full training, ~11 hours on 4-8 H100s
```

Nothing else in `config.py` needs to be touched.

### 5. Launch training inside a tmux session

Always run training inside `tmux` so the job survives an SSH disconnect:

```bash
tmux new -s arcle
cd $BASE_DIR
```

**Smoke test (1 GPU):**

```bash
accelerate launch \
    --config_file accelerate_config_smoke.yaml \
    --num_processes 1 \
    train.py
```

**Full training (8 × H100 SXM GPUs):**

```bash
accelerate launch \
    --config_file accelerate_config.yaml \
    --num_processes 8 \
    train.py
```

Detach from the tmux session at any time with `Ctrl+B` then `D`. Reattach later with `tmux attach -t arcle`.

### 6. Monitor progress

A live status file is updated every few seconds:

```bash
watch -n 5 cat $BASE_DIR/logs/status.txt
```

Checkpoints are saved to `$BASE_DIR/output/checkpoints/` according to the schedule defined in `config.py`.

### 7. Run inference on a trained checkpoint

Once training has finished (or after any saved checkpoint), launch the interactive demo:

```bash
python inference.py --checkpoint $BASE_DIR/output/checkpoints/latest
```

The interactive prompt accepts text, image paths, and audio paths in a single conversation and will respond with text, generated images, or synthesised speech depending on the request.

---

## Common issues and resolutions

- **Optional CUDA kernels fail to build** — the download script will warn and continue; the model uses a pure-PyTorch fallback that produces identical results, with a small (~15 %) performance cost.
- **Out-of-memory during full training** — reduce the per-device batch size in `config.py` and increase the gradient-accumulation factor proportionally to keep the effective batch size at 256.
- **Disk fills up during dataset download** — make sure the partition holding `BASE_DIR` has at least 80 GB free. The download script verifies file integrity at the end and reports any partial files that need to be re-fetched.
- **Training hangs at the first step** — confirm that all GPUs are visible to the launcher with `nvidia-smi` and that `--num_processes` matches the number of available GPUs.

---

> **Reminder**
> This repository is **not** the production source code of the ArcleIntelligence model. It is a public demonstration of the model architecture and the training pipeline that builds it. The complete source code will be released together with the trained model weights on Hugging Face once the training run finishes.
