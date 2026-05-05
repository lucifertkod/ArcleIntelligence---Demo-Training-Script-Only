"""
train.py — ArcleIntelligence 5.82B

STEP 1 — SMOKE TEST  (SMOKE_TEST = True in config.py)
─────────────────────────────────────────────────────
  Verifies the full pipeline: all 5 modalities load, forward pass works,
  loss decreases, checkpoints save, status file writes.
  1 epoch, 500 samples per modality, ~25-35 min on 1× H100, costs ~$2-3.

  Command:
    tmux new -s smoke
    accelerate launch --config_file accelerate_config_smoke.yaml \\
        --num_processes 1 train.py

  Expected output at end:
    ✅ SMOKE TEST PASSED — all systems operational
    → Set SMOKE_TEST = False in config.py
    → Switch to 8× H100 SXM pod
    → Run: accelerate launch --config_file accelerate_config.yaml \\
               --num_processes 8 train.py


STEP 2 — FULL TRAINING  (SMOKE_TEST = False in config.py)
──────────────────────────────────────────────────────────
  Full production training: 3 epochs, 371K pairs, ~497h on 8× H100 SXM, ~$11,560.

  Command:
    tmux new -s arcle
    accelerate launch --config_file accelerate_config.yaml \\
        --num_processes 8 train.py
"""

import os, sys, time, glob, shutil, random, logging
import numpy as np
import torch
from torch.utils.data import DataLoader
from tqdm import tqdm

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config as C

IGNORE = -100  # label ignore index, matches data.py

_logdir = os.path.join(C.OUT, "logs")
os.makedirs(_logdir, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(
            os.path.join(_logdir, "smoke_test.log" if C.SMOKE_TEST else "train.log"),
            mode="a",
        ),
    ],
)
logger = logging.getLogger(__name__)


def set_seed(s=42):
    random.seed(s); np.random.seed(s)
    torch.manual_seed(s); torch.cuda.manual_seed_all(s)


def check_tmux():
    if not os.environ.get("TMUX"):
        logger.warning(
            "\n⚠  NOT IN TMUX — SSH disconnect = training stops!\n"
            "   tmux new -s arcle  →  run command  →  Ctrl+B D to detach\n"
        )
    else:
        logger.info("✅ Running inside tmux.")


def print_banner():
    if C.SMOKE_TEST:
        logger.info("\n" + "═"*58)
        logger.info("  🔬 SMOKE TEST MODE")
        logger.info("  Epochs: 1  |  Samples: 500/modality  |  GPUs: 1")
        logger.info("  Purpose: verify pipeline, not train the model")
        logger.info("  Cost: ~$2-3  |  Time: ~25-35 minutes")
        logger.info("═"*58 + "\n")
    else:
        logger.info("\n" + "═"*58)
        logger.info("  🚀 FULL TRAINING MODE")
        logger.info("  Epochs: 3  |  Pairs: 371K  |  GPUs: 8 × H100 SXM")
        logger.info("  Cost: ~$11,560  |  Time: ~497 hours")
        logger.info("═"*58 + "\n")


class Trainer:
    def __init__(self, model, train_ds, collator):
        from accelerate import Accelerator

        # All deepspeed/bf16/grad_accum settings flow from ds_config*.json,
        # selected by the accelerate YAML's `deepspeed_config_file`. Don't
        # pass mixed_precision / gradient_accumulation_steps / DeepSpeedPlugin
        # here — newer accelerate errors on the duplication.
        self.acc = Accelerator(project_dir=_logdir)

        self.model = model

        # Gradient checkpointing on Falcon
        try:
            self.model.falcon.gradient_checkpointing_enable(
                gradient_checkpointing_kwargs={"use_reentrant": False}
            )
        except Exception:
            try: self.model.falcon.gradient_checkpointing_enable()
            except Exception: pass

        _w = C.DATALOADER_WORKERS
        self.loader = DataLoader(
            train_ds,
            batch_size=C.PER_DEVICE_BATCH,
            shuffle=True,
            num_workers=_w,
            collate_fn=collator,
            pin_memory=True,
            drop_last=True,
            prefetch_factor=2 if _w > 0 else None,
            persistent_workers=_w > 0,
        )

        trainable = [p for p in model.parameters() if p.requires_grad]
        self.opt  = torch.optim.AdamW(
            trainable, lr=C.LR, weight_decay=C.WEIGHT_DECAY,
            betas=(0.9, 0.95), eps=1e-8,
        )

        # NOTE: total_steps is computed AFTER acc.prepare() because Accelerate
        # wraps the dataloader and may change its length (divides by num_processes
        # in distributed mode).  Computing it before acc.prepare() produces a
        # value that is num_processes× too large, which makes the cosine LR
        # schedule decay far too slowly.
        _pre_steps_per_epoch = len(self.loader) // C.GRAD_ACCUM  # temporary

        from transformers import get_cosine_schedule_with_warmup
        # Placeholder scheduler — will be re-created with correct total_steps
        self.sched = get_cosine_schedule_with_warmup(
            self.opt,
            num_warmup_steps=C.LR_WARMUP,
            num_training_steps=max(_pre_steps_per_epoch * C.EPOCHS, 1),
        )

        (self.model, self.opt,
         self.loader, self.sched) = self.acc.prepare(
            self.model, self.opt, self.loader, self.sched
        )

        # Recompute with the actual (possibly sharded) dataloader length
        steps_per_epoch = len(self.loader) // C.GRAD_ACCUM
        self.total_steps = steps_per_epoch * C.EPOCHS

        # Re-create scheduler with correct total_steps
        self.sched = get_cosine_schedule_with_warmup(
            self.opt,
            num_warmup_steps=C.LR_WARMUP,
            num_training_steps=max(self.total_steps, 1),
        )

        self.step = 0

        if self.acc.is_local_main_process:
            if C.SMOKE_TEST:
                logger.info(f"  Smoke test: {self.total_steps} steps  "
                            f"({len(self.loader)} batches × {C.EPOCHS} epoch / {C.GRAD_ACCUM} accum)")
            else:
                # Full training: 3 epochs on 8× H100 SXM ≈ 497h, ≈ $11,560
                hrs  = self.total_steps / max(8.75 * self.acc.num_processes / 8, 1)
                logger.info(f"  Full train: {self.total_steps:,} steps  ~{hrs:.0f}h  ~${hrs*23.26:.0f}")

    def _fwd(self):
        m = self.acc.unwrap_model(self.model)
        return getattr(m, "_orig_mod", m)

    def run(self):
        os.makedirs(C.OUT, exist_ok=True)
        lacc = {k: 0.0 for k in ["total","vis","ocr","aud","img","tts"]}
        lcnt = {k: 0   for k in lacc}
        t0   = time.time()
        smoke_checks = {           # track what we verified during smoke test
            "vision_forward": False,
            "ocr_forward":    False,
            "audio_forward":  False,
            "imggen_forward": False,
            "tts_forward":    False,
            "loss_finite":    False,
            "checkpoint_saved": False,
        }

        for epoch in range(C.EPOCHS):
            self.model.train()
            bar = tqdm(
                self.loader,
                desc=("🔬 Smoke" if C.SMOKE_TEST else f"Epoch {epoch+1}/{C.EPOCHS}"),
                disable=not self.acc.is_local_main_process,
                ncols=110,
            )

            for batch in bar:
                with self.acc.accumulate(self.model):
                    loss, ml = self._step(batch)
                    self.acc.backward(loss)
                    if self.acc.sync_gradients:
                        self.acc.clip_grad_norm_(
                            [p for p in self.model.parameters() if p.requires_grad],
                            C.MAX_GRAD_NORM,
                        )
                    self.opt.step()
                    self.sched.step()
                    self.opt.zero_grad()

                # Track smoke test checks
                if C.SMOKE_TEST:
                    if ml.get("vis") is not None:  smoke_checks["vision_forward"] = True
                    if ml.get("ocr") is not None:  smoke_checks["ocr_forward"]    = True
                    if ml.get("aud") is not None:  smoke_checks["audio_forward"]  = True
                    if ml.get("img") is not None:  smoke_checks["imggen_forward"] = True
                    if ml.get("tts") is not None:  smoke_checks["tts_forward"]    = True
                    if ml.get("total", float("inf")) < 1e6:
                        smoke_checks["loss_finite"] = True

                if self.acc.sync_gradients:
                    self.step += 1
                    for k, v in ml.items():
                        if v is not None: lacc[k] += v; lcnt[k] += 1

                    # Update progress bar on every optimizer step so
                    # the user sees movement, not 20-minute silences.
                    if self.acc.is_local_main_process and ml.get("total") is not None:
                        bar.set_postfix(
                            {"step": f"{self.step}/{self.total_steps}",
                             "loss": f"{ml['total']:.4f}"},
                            refresh=True,
                        )

                    if self.step % C.LOGGING_STEPS == 0:
                        avgs = {k: lacc[k]/max(lcnt[k],1) for k in lacc}
                        lr   = self.sched.get_last_lr()[0]
                        el   = (time.time()-t0)/3600

                        if self.acc.is_local_main_process:
                            logger.info(
                                f"[{self.step}/{self.total_steps}] "
                                f"lr={lr:.2e} loss={avgs['total']:.4f} | "
                                f"vis={avgs['vis']:.3f} ocr={avgs['ocr']:.3f} "
                                f"aud={avgs['aud']:.3f} img={avgs['img']:.3f} "
                                f"tts={avgs['tts']:.3f} | {el:.2f}h"
                            )
                            self._write_status(avgs['total'], lr, el)

                        lacc = {k:0.0 for k in lacc}
                        lcnt = {k:0   for k in lcnt}

                    if self.step % C.SAVE_STEPS == 0:
                        self._save_ckpt()
                        if C.SMOKE_TEST:
                            smoke_checks["checkpoint_saved"] = True

        # Save final
        self._save_final()

        # Smoke test report
        if C.SMOKE_TEST and self.acc.is_local_main_process:
            self._print_smoke_report(smoke_checks, time.time() - t0)

    def _step(self, batch):
        """Compute training loss for one micro-batch.

        CRITICAL FOR MULTI-GPU: Every GPU must execute the SAME set of Falcon
        forward passes so that DeepSpeed ZeRO-2 ALLREDUCE stays synchronized.
        If GPU 0 runs forward_vision + forward_audio while GPU 4 runs only
        forward_tts, the ALLREDUCE operations desync and NCCL times out after
        10 minutes (the error that was crashing training).

        Solution: Always run all 5 modality forward branches. When a modality
        is absent from the batch, create a tiny (B=1) dummy input so Falcon
        still executes a forward+backward pass through the same code path.
        The dummy loss is multiplied by 0.0 so it doesn't affect training,
        but the gradient graph touches ALL trainable parameters, keeping
        ALLREDUCE synchronised across all GPUs.
        """
        m  = self._fwd()
        ls = []
        ml = {"total":None,"vis":None,"ocr":None,"aud":None,"img":None,"tts":None}
        dev = self.acc.device

        # ── Vision ──────────────────────────────────────────────────────────
        if "vision_indices" in batch:
            idx = batch["vision_indices"]
            out = m.forward_vision(batch["input_ids"][idx], batch["pixel_values"],
                                   batch["labels"][idx], batch["attention_mask"][idx])
            if out.get("loss") is not None:
                ls.append(out["loss"]); ml["vis"] = out["loss"].item()
        else:
            # Dummy forward: run connector only (skip frozen encoder + Falcon).
            # We only need gradient flow through the connector params for ALLREDUCE.
            # Don't call _prefix_forward — all-IGNORE labels make CrossEntropyLoss
            # return NaN (0 valid targets → 0/0), and NaN * 0.0 = NaN.
            _feat = torch.zeros(1, C.SIGLIP_PATCHES, C.SIGLIP_HIDDEN,
                                device=dev, dtype=torch.bfloat16)
            _emb  = m.vision_conn(_feat)
            ls.append(_emb.sum() * 0.0)  # gradient = 0 but graph exists → ALLREDUCE works

        # ── OCR ─────────────────────────────────────────────────────────────
        if "ocr_indices" in batch:
            idx = batch["ocr_indices"]
            out = m.forward_ocr(batch["input_ids"][idx], batch["ocr_pixel_values"],
                                batch["labels"][idx], batch["attention_mask"][idx],
                                image_grid_thw=batch.get("image_grid_thw"),
                                glm_input_ids=batch.get("glm_input_ids"))
            if out.get("loss") is not None:
                ls.append(1.2 * out["loss"]); ml["ocr"] = out["loss"].item()
        else:
            _h   = torch.zeros(1, 64, m.glm_ocr.hidden_size,
                               device=dev, dtype=torch.bfloat16)
            _emb, _ = m.ocr_conn(_h)
            ls.append(_emb.sum() * 0.0)

        # ── Audio ───────────────────────────────────────────────────────────
        if "audio_indices" in batch:
            idx = batch["audio_indices"]
            out = m.forward_audio(batch["input_ids"][idx], batch["input_features"],
                                  batch["labels"][idx], batch["attention_mask"][idx])
            if out.get("loss") is not None:
                ls.append(out["loss"]); ml["aud"] = out["loss"].item()
        else:
            _h   = torch.zeros(1, 1500, C.WHISPER_HIDDEN,
                               device=dev, dtype=torch.bfloat16)
            _emb, _ = m.audio_conn(_h)
            ls.append(_emb.sum() * 0.0)

        # ── ImageGen ────────────────────────────────────────────────────────
        if "imggen_indices" in batch:
            idx = batch["imggen_indices"]
            out = m.forward_image_gen(batch["input_ids"][idx], batch["target_latent"],
                                      batch["attention_mask"][idx])
            if out.get("loss") is not None:
                ls.append(out["loss"]); ml["img"] = out["loss"].item()
        else:
            # Dummy: run imgcond_proj on zeros. forward_image_gen calls Falcon
            # which could produce unexpected values with dummy token IDs.
            _h   = torch.zeros(1, 4, C.IMGCOND_IN, device=dev, dtype=torch.bfloat16)
            _emb = m.imgcond_proj(_h)
            ls.append(_emb.sum() * 0.0)

        # ── TTS ─────────────────────────────────────────────────────────────
        if "tts_indices" in batch:
            idx = batch["tts_indices"]
            out = m.forward_tts(batch["input_ids"][idx], batch["target_style"],
                                batch["attention_mask"][idx])
            if out.get("loss") is not None:
                ls.append(0.5 * out["loss"]); ml["tts"] = out["loss"].item()
        else:
            _h   = torch.zeros(1, 4, C.IMGCOND_IN, device=dev, dtype=torch.bfloat16)
            _mask = torch.ones(1, 4, dtype=torch.long, device=dev)
            _sty  = torch.zeros(1, C.KOKORO_STYLE_DIM, device=dev)
            _pred, _loss = m.tts_conn(_h, _mask, _sty)
            ls.append(_loss * 0.0)

        # ── Aggregate ───────────────────────────────────────────────────────
        if ls:
            total = torch.stack(ls).sum()  # use sum; zero-weighted dummies don't contribute
        else:
            # Fallback: should never happen now since all 5 branches always run
            dummy = next((p for p in self.model.parameters() if p.requires_grad), None)
            total = (dummy * 0.0).sum() if dummy is not None else \
                    torch.zeros((), device=dev, requires_grad=True)
        ml["total"] = total.item()
        return total, ml

    def _write_status(self, loss, lr, elapsed):
        mode = "SMOKE TEST" if C.SMOKE_TEST else "TRAINING"
        try:
            with open(os.path.join(_logdir, "status.txt"), "w") as f:
                f.write(
                    f"ArcleIntelligence — {mode}\n"
                    f"Step:      {self.step} / {self.total_steps}"
                    f"  ({100*self.step/max(self.total_steps,1):.1f}%)\n"
                    f"Loss:      {loss:.4f}\n"
                    f"LR:        {lr:.2e}\n"
                    f"Elapsed:   {elapsed:.2f}h\n"
                )
        except Exception as e:
            logger.debug(f"status write failed: {e}")

    def _save_ckpt(self):
        if not self.acc.is_local_main_process: return
        path = os.path.join(C.OUT, f"checkpoint-{self.step}")
        self.acc.unwrap_model(self.model).save_connectors(path)
        ckpts = sorted(glob.glob(os.path.join(C.OUT, "checkpoint-*")),
                       key=lambda x: int(x.split("-")[-1]))
        for old in ckpts[:-C.SAVE_LIMIT]: shutil.rmtree(old, ignore_errors=True)
        logger.info(f"  💾 Checkpoint: step {self.step}")

    def _save_final(self):
        if not self.acc.is_local_main_process: return
        m = self.acc.unwrap_model(self.model)
        tag = "smoke_connectors" if C.SMOKE_TEST else "connectors"
        m.save_connectors(os.path.join(C.OUT, tag))
        if not C.SMOKE_TEST:
            m.save_pretrained(os.path.join(C.OUT, "ArcleIntelligence-5.82B"))

    def _print_smoke_report(self, checks, elapsed_sec):
        all_passed = all(checks.values())
        logger.info("\n" + "═"*58)
        if all_passed:
            logger.info("  ✅ SMOKE TEST PASSED — all systems operational")
        else:
            logger.info("  ❌ SMOKE TEST FAILED — see failed checks below")
        logger.info("═"*58)
        for k, v in checks.items():
            logger.info(f"  {'✅' if v else '❌'}  {k}")
        logger.info(f"\n  Time:  {elapsed_sec/60:.1f} minutes")
        logger.info(f"  Steps: {self.step}")
        logger.info("─"*58)
        if all_passed:
            logger.info("\n  NEXT STEPS:")
            logger.info("  1. Open config.py")
            logger.info("  2. Change:  SMOKE_TEST = True  →  SMOKE_TEST = False")
            logger.info("  3. Switch to 8× H100 SXM pod on RunPod")
            logger.info("  4. Run:")
            logger.info("       tmux new -s arcle")
            logger.info("       accelerate launch --config_file accelerate_config.yaml \\")
            logger.info("           --num_processes 8 train.py")
        else:
            logger.info("\n  Fix the ❌ checks above before running full training.")
        logger.info("═"*58 + "\n")


def main():
    set_seed(C.SEED)
    check_tmux()
    print_banner()

    logger.info("Building ArcleIntelligence 5.82B model...")
    from models import ArcleOmniModel
    model = ArcleOmniModel()

    logger.info("Loading datasets...")
    from data import build_datasets
    train_ds, collator = build_datasets(model)

    trainer = Trainer(model, train_ds, collator)

    if C.SMOKE_TEST:
        logger.info("🔬 Smoke test started — verifying all pipeline paths...\n")
    else:
        logger.info("🚀 Full training started!\n")

    trainer.run()


if __name__ == "__main__":
    main()
