"""
data.py — ArcleIntelligence — All dataset code

Five modalities trained: Vision, OCR, Audio, ImageGen, TTS
Total: ~371K pairs × 3 epochs = ~1.11M training samples

NOTE: This file is intentionally named data.py (not datasets.py) to avoid
shadowing the HuggingFace `datasets` package, which is imported throughout.
"""

import os
import torch
from torch.utils.data import Dataset, ConcatDataset
from typing import List, Dict, Any, Callable, Optional
from PIL import Image
import numpy as np
import logging

import config as C

logger = logging.getLogger(__name__)
IGNORE = -100


# ══════════════════════════════════════════════════════════════════════════════
# DATASETS
# ══════════════════════════════════════════════════════════════════════════════

class VisionDataset(Dataset):
    """LLaVA-CC3M: image + caption → vision connector training."""
    def __init__(self, path, processor, tokenizer, max_len=512, max_samples=-1):
        self.proc   = processor
        self.tok    = tokenizer
        self.maxlen = max_len
        self.items  = []
        self._load(path, max_samples)

    def _load(self, path, max_samples):
        # ── LLaVA raw snapshot format: chat.json + images/ directory ──────────
        # Used when LLaVA-CC3M was downloaded via snapshot_download (the dataset
        # has a legacy loading script that trust_remote_code no longer supports).
        import json as _json
        chat_json = os.path.join(path, "chat.json")
        img_dir   = os.path.join(path, "images")
        if os.path.exists(chat_json) and os.path.isdir(img_dir):
            try:
                with open(chat_json) as f:
                    raw = _json.load(f)
                if max_samples > 0:
                    raw = raw[:max_samples]
                for item in raw:
                    img_name = item.get("image", "")
                    caption  = ""
                    for c in item.get("conversations", []):
                        if c.get("from") == "gpt":
                            caption = c.get("value", "").strip()
                            break
                    img_path = os.path.join(img_dir, img_name)
                    if img_name and caption and os.path.exists(img_path):
                        self.items.append({"image": img_path, "caption": caption})
                logger.info(f"Vision (LLaVA raw): {len(self.items):,} samples")
                return
            except Exception as e:
                logger.warning(f"LLaVA raw parse failed ({e}), falling back to HF datasets")
        # ── Standard HF datasets library path ─────────────────────────────────
        try:
            from datasets import load_from_disk
            ds = load_from_disk(path)
            if max_samples > 0 and len(ds) > max_samples:
                ds = ds.select(range(max_samples))
            for item in ds:
                img = item.get("image") or item.get("img")
                cap = item.get("caption") or item.get("text") or ""
                if img and str(cap).strip():
                    self.items.append({"image":img, "caption":str(cap).strip()})
            logger.info(f"Vision: {len(self.items):,} samples")
        except Exception as e:
            logger.warning(f"Vision load: {e}")

    def __len__(self): return len(self.items)

    def __getitem__(self, idx):
        s   = self.items[idx]
        img = s["image"]
        if isinstance(img, str):
            try:   img = Image.open(img).convert("RGB")
            except:img = Image.new("RGB",(384,384))
        elif not isinstance(img, Image.Image):
            img = Image.new("RGB",(384,384))
        try:
            pv = self.proc(images=img, return_tensors="pt")["pixel_values"].squeeze(0)
        except:
            pv = torch.zeros(3,384,384)
        prompt = "Describe the image.\n"
        full   = prompt + s["caption"] + self.tok.eos_token
        enc    = self.tok(full, max_length=self.maxlen, truncation=True,
                          padding="max_length", return_tensors="pt")
        ids    = enc["input_ids"].squeeze(0)
        mask   = enc["attention_mask"].squeeze(0)
        plen   = len(self.tok(prompt)["input_ids"])
        labs   = ids.clone(); labs[:plen] = IGNORE
        labs[mask == 0] = IGNORE    # don't train on padding
        return {"pixel_values":pv, "input_ids":ids,
                "attention_mask":mask, "labels":labs, "modality":"vision"}


class OCRDataset(Dataset):
    """DocVQA: document image + question → OCR connector training."""
    def __init__(self, path, glm_preprocess: Callable, tokenizer,
                 max_len=512, max_samples=-1):
        self.pre    = glm_preprocess
        self.tok    = tokenizer
        self.maxlen = max_len
        self.items  = []
        self._load(path, max_samples)

    def _load(self, path, max_samples):
        try:
            from datasets import load_from_disk
            ds = load_from_disk(path)
            if max_samples > 0 and len(ds) > max_samples:
                ds = ds.select(range(max_samples))
            for item in ds:
                img = item.get("image") or item.get("img")
                q   = item.get("question") or item.get("query") or ""
                a   = item.get("answers")  or item.get("answer") or ""
                if isinstance(a, list): a = a[0] if a else ""
                if img and str(q).strip():
                    self.items.append({"image":img,"q":str(q).strip(),"a":str(a).strip()})
            logger.info(f"OCR: {len(self.items):,} samples")
        except Exception as e:
            logger.warning(f"OCR load: {e}")

    def __len__(self): return len(self.items)

    def __getitem__(self, idx):
        s = self.items[idx]
        img = s["image"]
        if isinstance(img, str):
            try:   img = Image.open(img).convert("RGB")
            except:img = Image.new("RGB",(448,448),255)
        elif not isinstance(img, Image.Image):
            img = Image.new("RGB",(448,448),255)
        grid_thw = None
        glm_ids  = None
        try:
            result = self.pre(img)
            if isinstance(result, dict):
                pv       = result["pixel_values"]
                grid_thw = result.get("image_grid_thw")   # [1, 3] or None
                glm_ids  = result.get("glm_input_ids")    # [seq_len] with image tokens
            else:
                pv = result
            if pv.dim() == 4: pv = pv.squeeze(0)
        except Exception:
            pv = torch.zeros(3, 448, 448)
            grid_thw = None
            glm_ids  = None
        prompt = f"Document Question: {s['q']}\nAnswer:"
        full   = prompt + " " + s["a"] + self.tok.eos_token
        enc    = self.tok(full, max_length=self.maxlen, truncation=True,
                          padding="max_length", return_tensors="pt")
        ids    = enc["input_ids"].squeeze(0)
        mask   = enc["attention_mask"].squeeze(0)
        plen   = len(self.tok(prompt)["input_ids"])
        labs   = ids.clone(); labs[:plen] = IGNORE
        labs[mask == 0] = IGNORE    # don't train on padding
        item = {"ocr_pixel_values": pv, "input_ids": ids,
                "attention_mask": mask, "labels": labs, "modality": "ocr"}
        if grid_thw is not None:
            item["image_grid_thw"] = grid_thw   # [1, 3] — collator cats to [B_ocr, 3]
        if glm_ids is not None:
            item["glm_input_ids"] = glm_ids     # [seq_len] — collator stacks to [B_ocr, seq_len]
        return item


class AudioDataset(Dataset):
    """LibriSpeech: audio waveform + transcript → audio connector training."""
    def __init__(self, path, whisper_processor, tokenizer,
                 max_len=256, max_samples=-1):
        self.proc   = whisper_processor
        self.tok    = tokenizer
        self.maxlen = max_len
        self.items  = []
        self._load(path, max_samples)

    def _load(self, path, max_samples):
        try:
            from datasets import load_from_disk
            ds = load_from_disk(path)
            if max_samples > 0 and len(ds) > max_samples:
                ds = ds.select(range(max_samples))
            for item in ds:
                aud  = item.get("audio") or {}
                text = item.get("text") or item.get("sentence") or ""
                arr  = aud.get("array") if isinstance(aud, dict) else None
                sr   = aud.get("sampling_rate",16000) if isinstance(aud,dict) else 16000
                if arr is not None and str(text).strip():
                    self.items.append({"arr":arr,"sr":sr,"text":str(text).strip()})
            logger.info(f"Audio: {len(self.items):,} samples")
        except Exception as e:
            logger.warning(f"Audio load: {e}")

    def __len__(self): return len(self.items)

    def __getitem__(self, idx):
        s   = self.items[idx]
        arr = np.array(s["arr"], dtype=np.float32)
        if s["sr"] != 16000:
            try:
                import librosa
                arr = librosa.resample(arr, orig_sr=s["sr"], target_sr=16000)
            except: pass
        try:
            feats = self.proc(arr, sampling_rate=16000,
                              return_tensors="pt")["input_features"].squeeze(0)
        except:
            feats = torch.zeros(80,3000)
        prompt = "Transcribe:"
        full   = prompt + " " + s["text"] + self.tok.eos_token
        enc    = self.tok(full, max_length=self.maxlen, truncation=True,
                          padding="max_length", return_tensors="pt")
        ids    = enc["input_ids"].squeeze(0)
        mask   = enc["attention_mask"].squeeze(0)
        plen   = len(self.tok(prompt)["input_ids"])
        labs   = ids.clone(); labs[:plen] = IGNORE
        labs[mask == 0] = IGNORE    # don't train on padding
        return {"input_features":feats, "input_ids":ids,
                "attention_mask":mask, "labels":labs, "modality":"audio"}


# ══════════════════════════════════════════════════════════════════════════════
# COLLATOR
# ══════════════════════════════════════════════════════════════════════════════

class OmniCollator:
    def __init__(self, pad_id: int, max_len: int = 512):
        self.pad_id  = pad_id
        self.max_len = max_len

    def __call__(self, batch: List[Dict[str,Any]]) -> Dict[str,Any]:
        def pad(seqs, val):
            L   = min(max(s.shape[0] for s in seqs), self.max_len)
            out = torch.full((len(seqs), L), val, dtype=seqs[0].dtype)
            for i, s in enumerate(seqs):
                l = min(s.shape[0], L); out[i,:l] = s[:l]
            return out

        result = {
            "modalities":     [s["modality"] for s in batch],
            "input_ids":      pad([s["input_ids"]       for s in batch], self.pad_id),
            "attention_mask": pad([s["attention_mask"]  for s in batch], 0),
            "labels":         pad([s.get("labels", s["input_ids"]) for s in batch], -100),
        }

        v = [(i,s) for i,s in enumerate(batch) if s["modality"]=="vision"]
        if v:
            result["pixel_values"]   = torch.stack([s["pixel_values"] for _,s in v])
            result["vision_indices"] = torch.tensor([i for i,_ in v])

        o = [(i,s) for i,s in enumerate(batch) if s["modality"]=="ocr"]
        if o:
            result["ocr_pixel_values"] = torch.stack([s["ocr_pixel_values"] for _,s in o])
            result["ocr_indices"]      = torch.tensor([i for i,_ in o])
            # Pass image_grid_thw when all OCR samples have it (shape: [B_ocr, 3])
            if all("image_grid_thw" in s for _,s in o):
                result["image_grid_thw"] = torch.cat(
                    [s["image_grid_thw"] for _,s in o], dim=0
                )
            # Pass glm_input_ids (with image placeholder tokens) for GLM-OCR forward
            if all("glm_input_ids" in s for _,s in o):
                glm_id_list = [s["glm_input_ids"] for _,s in o]
                max_gl = max(t.shape[0] for t in glm_id_list)
                padded = torch.zeros(len(glm_id_list), max_gl, dtype=torch.long)
                for i, t in enumerate(glm_id_list):
                    padded[i, :t.shape[0]] = t
                result["glm_input_ids"] = padded  # [B_ocr, seq_len]

        a = [(i,s) for i,s in enumerate(batch) if s["modality"]=="audio"]
        if a:
            result["input_features"] = torch.stack([s["input_features"] for _,s in a])
            result["audio_indices"]  = torch.tensor([i for i,_ in a])

        # Image gen — target_latent is [4,32,32] so stack directly, no text-padding
        g = [(i,s) for i,s in enumerate(batch) if s["modality"]=="image_gen"]
        if g:
            result["target_latent"]  = torch.stack([s["target_latent"]  for _,s in g])
            result["imggen_indices"] = torch.tensor([i for i,_ in g])

        # TTS — target_style is [256] float vector
        t = [(i,s) for i,s in enumerate(batch) if s["modality"]=="tts"]
        if t:
            result["target_style"] = torch.stack([s["target_style"] for _,s in t])
            result["tts_indices"]  = torch.tensor([i for i,_ in t])

        return result


# ══════════════════════════════════════════════════════════════════════════════
# BUILDER
# ══════════════════════════════════════════════════════════════════════════════

def build_datasets(model):
    """Build all training datasets. Returns (combined_dataset, collator)."""
    import config as C
    tok  = model.tokenizer
    sets = {}

    try:
        vis = VisionDataset(C.LLAVA_CC3M_PATH, model.siglip.processor,
                            tok, max_samples=C.MAX_VISION)
        if len(vis) > 0: sets["vision"] = vis; logger.info(f"  ✅ Vision:   {len(vis):>8,}")
    except Exception as e: logger.warning(f"  ⚠ Vision: {e}")

    try:
        ocr = OCRDataset(C.DOCVQA_PATH, model.glm_ocr.preprocess_image,
                         tok, max_samples=C.MAX_OCR)
        if len(ocr) > 0: sets["ocr"] = ocr; logger.info(f"  ✅ OCR:      {len(ocr):>8,}")
    except Exception as e: logger.warning(f"  ⚠ OCR: {e}")

    try:
        aud = AudioDataset(C.LIBRISPEECH_PATH, model.whisper.processor,
                           tok, max_samples=C.MAX_AUDIO)
        if len(aud) > 0: sets["audio"] = aud; logger.info(f"  ✅ Audio:    {len(aud):>8,}")
    except Exception as e: logger.warning(f"  ⚠ Audio: {e}")

    try:
        gen = ImageGenDataset(C.DIFFUSIONDB_PATH, model.sd_vae,
                              tok, max_samples=C.MAX_IMGGEN)
        if len(gen) > 0: sets["image_gen"] = gen; logger.info(f"  ✅ ImageGen: {len(gen):>8,}")
    except Exception as e: logger.warning(f"  ⚠ ImageGen: {e}")

    try:
        tts = TTSDataset(C.LJSPEECH_PATH, model.kokoro,
                         tok, max_samples=C.MAX_TTS)
        if len(tts) > 0: sets["tts"] = tts; logger.info(f"  ✅ TTS:      {len(tts):>8,}")
    except Exception as e: logger.warning(f"  ⚠ TTS: {e}")

    if not sets:
        raise RuntimeError("No datasets loaded. Run download.sh first.")

    total = sum(len(v) for v in sets.values())
    logger.info(f"  TOTAL: {total:,} pairs × {C.EPOCHS} epochs = {total*C.EPOCHS:,} samples")

    combined = ConcatDataset(list(sets.values()))
    collator = OmniCollator(pad_id=tok.pad_token_id or tok.eos_token_id)
    return combined, collator


class ImageGenDataset(Dataset):
    """DiffusionDB: text prompt + image → SD-VAE latent → ImageGenHead training."""
    def __init__(self, path, sdvae, tokenizer, max_len=128, max_samples=-1, device="cpu"):
        import torchvision.transforms as T
        self.sdvae   = sdvae
        self.tok     = tokenizer
        self.maxlen  = max_len
        self.device  = device
        self.tf      = T.Compose([T.Resize((256,256)), T.ToTensor(),
                                  T.Normalize([0.5]*3, [0.5]*3)])
        self.items   = []
        self._load(path, max_samples)

    def _load(self, path, max_samples):
        # 1) HF save_to_disk format (DiffusionDB or any future parquet mirror)
        try:
            from datasets import load_from_disk
            ds = load_from_disk(path)
            if max_samples > 0 and len(ds) > max_samples:
                ds = ds.select(range(max_samples))
            for item in ds:
                prompt = item.get("prompt") or item.get("caption") or ""
                image  = item.get("image") or item.get("img")
                if prompt and image:
                    self.items.append({"prompt":str(prompt).strip(), "image":image})
            if self.items:
                logger.info(f"ImageGen: {len(self.items):,} samples")
                return
        except Exception:
            pass

        # 2) LLaVA-CC3M fallback — image + caption are exactly what we need:
        #    encode image → SD-VAE latent (target), use caption as text prompt.
        try:
            import json as _json
            chat   = os.path.join(C.LLAVA_CC3M_PATH, "chat.json")
            imgdir = os.path.join(C.LLAVA_CC3M_PATH, "images")
            if os.path.exists(chat) and os.path.isdir(imgdir):
                with open(chat) as f:
                    raw = _json.load(f)
                for item in raw:
                    img_name = item.get("image", "")
                    caption  = ""
                    for c in item.get("conversations", []):
                        if c.get("from") == "gpt":
                            caption = (c.get("value") or "").strip()
                            break
                    img_path = os.path.join(imgdir, img_name)
                    if img_name and caption and os.path.exists(img_path):
                        self.items.append({"prompt": caption, "image": img_path})
                    if max_samples > 0 and len(self.items) >= max_samples:
                        break
                if self.items:
                    logger.info(f"ImageGen (LLaVA fallback): {len(self.items):,} samples")
                    return
        except Exception as e:
            logger.warning(f"ImageGen LLaVA fallback failed: {e}")

        logger.warning("ImageGen: no data source available")

    def __len__(self): return len(self.items)

    def __getitem__(self, idx):
        import random as rnd
        s   = self.items[idx]
        img = s["image"]
        if isinstance(img, str):
            try:   img = Image.open(img).convert("RGB")
            except:img = Image.new("RGB",(256,256),(rnd.randint(0,255),)*3)
        elif not isinstance(img, Image.Image):
            img = Image.new("RGB",(256,256))
        try:
            t = self.tf(img).unsqueeze(0)               # [1,3,256,256] float32
            # Match the VAE's dtype (usually bfloat16) to avoid RuntimeError
            if getattr(self.sdvae, "_loaded", False) and self.sdvae.vae is not None:
                vae_param = next(self.sdvae.vae.parameters(), None)
                if vae_param is not None:
                    t = t.to(dtype=vae_param.dtype, device=vae_param.device)
            latent = self.sdvae.encode(t).squeeze(0)    # [4, 32, 32]
        except Exception as e:
            logger.warning(f"ImageGen VAE encode failed for idx={idx}: {e}")
            latent = torch.zeros(C.SDVAE_LATENT_CH,
                                 C.SDVAE_LATENT_RES,
                                 C.SDVAE_LATENT_RES)    # [4, 32, 32]
        enc = self.tok(f"Generate an image of: {s['prompt']}",
                       max_length=self.maxlen, truncation=True,
                       padding="max_length", return_tensors="pt")
        return {"input_ids": enc["input_ids"].squeeze(0),
                "attention_mask": enc["attention_mask"].squeeze(0),
                "target_latent": latent.float(),         # [4, 32, 32]
                "modality": "image_gen"}


class TTSDataset(Dataset):
    """LJSpeech: text + Kokoro voice style → TTSConnector training."""
    def __init__(self, path, kokoro, tokenizer, max_len=128, max_samples=-1):
        self.kokoro  = kokoro
        self.tok     = tokenizer
        self.maxlen  = max_len
        self.items   = []
        self._voice_style = None
        self._get_default_style()
        self._load(path, max_samples)

    def _get_default_style(self):
        """Load the default voice style tensor as training target.

        Kokoro voice files are shaped [510, 1, 256] — 510 style vectors, one per
        possible sequence length. The TTSConnector predicts a single [256] vector
        per sample, so we collapse the file down to one [256] vector by taking
        the mean across the 510 length-conditioned styles. After collation that
        becomes [B, 256], matching the connector output for MSE.
        """
        voices_dir = os.path.join(C.KOKORO_PATH, "voices")
        if os.path.exists(voices_dir):
            vfiles = [f for f in os.listdir(voices_dir) if f.endswith(".pt")]
            if vfiles:
                vs = torch.load(
                    os.path.join(voices_dir, vfiles[0]), map_location="cpu",
                    weights_only=True,
                ).float()
                # Drop any size-1 dimensions (e.g. middle dim in [510,1,256])
                vs = vs.squeeze()
                # Collapse remaining leading dims to get a flat [256] vector
                while vs.dim() > 1:
                    vs = vs.mean(0)
                if vs.shape[-1] != C.KOKORO_STYLE_DIM:
                    logger.warning(
                        f"Voice file {vfiles[0]} has unexpected last dim "
                        f"{vs.shape[-1]} (expected {C.KOKORO_STYLE_DIM}); "
                        "falling back to random style."
                    )
                    vs = None
                else:
                    logger.info(
                        f"TTS training target: {vfiles[0]}  shape={tuple(vs.shape)}"
                    )
                self._voice_style = vs
        if self._voice_style is None:
            # Fallback: random style (connector still learns reasonable mapping)
            logger.warning("No Kokoro voice files found — using random style targets")
            self._voice_style = torch.randn(C.KOKORO_STYLE_DIM) * 0.1

    def _load(self, path, max_samples):
        # 1) LJSpeech metadata.csv (filename|original|normalized) — preferred when present
        metadata_csv = os.path.join(path, "metadata.csv")
        if os.path.exists(metadata_csv):
            try:
                with open(metadata_csv, encoding="utf-8") as f:
                    for line in f:
                        parts = line.strip().split("|")
                        text = parts[2].strip() if len(parts) >= 3 else (
                               parts[1].strip() if len(parts) >= 2 else "")
                        if text:
                            self.items.append({"text": text})
                        if max_samples > 0 and len(self.items) >= max_samples:
                            break
                if self.items:
                    logger.info(f"TTS (LJSpeech metadata): {len(self.items):,} samples")
                    return
            except Exception as e:
                logger.warning(f"TTS metadata.csv read failed: {e}")

        # 2) HF datasets save_to_disk format (in case a future dataset is added)
        try:
            from datasets import load_from_disk
            ds = load_from_disk(path)
            if max_samples > 0 and len(ds) > max_samples:
                ds = ds.select(range(max_samples))
            for item in ds:
                text = item.get("text") or item.get("normalized_text") or ""
                if str(text).strip():
                    self.items.append({"text": str(text).strip()})
            if self.items:
                logger.info(f"TTS: {len(self.items):,} samples")
                return
        except Exception:
            pass

        # 3) LLaVA-CC3M caption fallback — TTS connector is text-conditional but
        #    trained against a fixed voice-style target, so any natural English
        #    text works as the input source.
        try:
            import json as _json
            chat = os.path.join(C.LLAVA_CC3M_PATH, "chat.json")
            if os.path.exists(chat):
                with open(chat) as f:
                    raw = _json.load(f)
                for item in raw:
                    for c in item.get("conversations", []):
                        if c.get("from") == "gpt":
                            text = (c.get("value") or "").strip()
                            if text and len(text) <= 240:
                                self.items.append({"text": text})
                            break
                    if max_samples > 0 and len(self.items) >= max_samples:
                        break
                if self.items:
                    logger.info(f"TTS (LLaVA caption fallback): {len(self.items):,} samples")
                    return
        except Exception as e:
            logger.warning(f"TTS LLaVA fallback failed: {e}")

        logger.warning("TTS: no text source available (no metadata.csv, no LLaVA captions)")

    def __len__(self): return len(self.items)

    def __getitem__(self, idx):
        text  = self.items[idx]["text"]
        enc   = self.tok(f"Speak: {text}", max_length=self.maxlen,
                         truncation=True, padding="max_length", return_tensors="pt")
        return {"input_ids":      enc["input_ids"].squeeze(0),
                "attention_mask": enc["attention_mask"].squeeze(0),
                "target_style":   self._voice_style.clone().float(),
                "modality":       "tts"}
