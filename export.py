"""
export.py — Verify and export ArcleIntelligence 5.82B
Run after training: python export.py
"""
import os, sys, torch, logging
logging.basicConfig(level=logging.INFO, format="%(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config as C

CONNECTOR_DIR = os.path.join(C.OUT, "connectors")
EXPORT_DIR    = os.path.join(C.OUT, "ArcleIntelligence-5.82B-export")


def main():
    from models import ArcleOmniModel
    model = ArcleOmniModel()

    if os.path.exists(CONNECTOR_DIR):
        ArcleOmniModel.load_connectors(model, CONNECTOR_DIR)
    else:
        logger.warning("No connectors found — exporting base model only")

    model.eval()
    dev   = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = model.to(dev)

    errors = []
    logger.info("\n── Sanity checks ────────────────────────────────────")

    # 1. Text
    try:
        ids = torch.randint(0, 1000, (1,32), device=dev)
        with torch.no_grad(): model.falcon(input_ids=ids)
        logger.info("  ✅ Text  (Falcon H1 3B — 1M context)")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ Text: {e}")

    # 2. Vision connector  [1,576,1024] → [1,578,2560]
    try:
        feat = torch.randn(1, 576, 1024, device=dev, dtype=torch.bfloat16)
        out  = model.vision_conn(feat)
        assert out.shape == (1, 578, 2560), f"shape {out.shape}"
        logger.info(f"  ✅ Vision connector:   [1,576,1024] → [1,578,2560]")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ Vision: {e}")

    # 3. OCR connector  [1,64,896] → [1,642,2560]  (640 queries + 2 boundary)
    try:
        glm_h = torch.randn(1, 64, 896, device=dev, dtype=torch.bfloat16)
        out, mask = model.ocr_conn(glm_h)
        assert out.shape == (1, C.OCR_QUERIES+2, 2560), f"shape {out.shape}"
        logger.info(f"  ✅ OCR connector:      [1,64,896] → [1,{C.OCR_QUERIES+2},2560]"
                    f"  ({C.OCR_QUERIES} queries)")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ OCR: {e}")

    # 4. Audio connector  [1,1500,1024] → [1,130,2560]
    try:
        aud = torch.randn(1, 1500, 1024, device=dev, dtype=torch.bfloat16)
        out, mask = model.audio_conn(aud)
        assert out.shape == (1, C.AUD_TOKENS+2, 2560), f"shape {out.shape}"
        logger.info(f"  ✅ Audio connector:    [1,1500,1024] → [1,{C.AUD_TOKENS+2},2560]")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ Audio: {e}")

    # 5. ImageCondProjector  [1,32,2560] → [1,77,768]  (UNet conditioning)
    try:
        h    = torch.randn(1, 32, 2560, device=dev, dtype=torch.bfloat16)
        cond = model.imgcond_proj(h)
        assert cond.shape == (1, C.UNET_COND_TOKENS, C.UNET_COND_DIM), f"shape {cond.shape}"
        logger.info(f"  ✅ ImgCond projector:  [1,32,2560] → [1,77,768]  (UNet conditioning)")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ ImgCond proj: {e}")

    # 6. SD UNet LCM loss
    try:
        lat  = torch.randn(1, 4, 32, 32, device=dev, dtype=torch.bfloat16)
        cond = torch.randn(1, 77, 768, device=dev, dtype=torch.bfloat16)
        if model.sd_unet._loaded:
            loss = model.sd_unet.ddpm_loss(lat, cond)
            lcm  = "LCM-LoRA active" if getattr(model.sd_unet, "_lcm_active", False) else "plain UNet"
            logger.info(f"  ✅ SD UNet LCM loss:   {loss.item():.4f}  "
                        f"({lcm} — 8-step sharp 512×512)")
        else:
            logger.info(f"  ⚠  SD UNet not loaded (run download.sh)")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ SD UNet: {e}")

    # 7. TTS connector  [1,32,2560] → [1,256]
    try:
        h    = torch.randn(1, 32, 2560, device=dev, dtype=torch.bfloat16)
        mask = torch.ones(1, 32, device=dev)
        sty, _ = model.tts_conn(h, mask)
        assert sty.shape == (1, C.KOKORO_STYLE_DIM), f"shape {sty.shape}"
        logger.info(f"  ✅ TTS connector:      [1,32,2560] → [1,256]  (voice style)")
    except Exception as e:
        errors.append(str(e)); logger.error(f"  ❌ TTS: {e}")

    # Parameter summary
    total     = sum(p.numel() for p in model.parameters())
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    logger.info(f"\n── Parameter Summary ────────────────────────────────")
    logger.info(f"  Total:      {total/1e9:.3f}B  (target 5.820B)")
    logger.info(f"  Trainable:  {trainable/1e6:.1f}M connectors")
    logger.info(f"  Frozen:     {(total-trainable)/1e9:.3f}B")
    logger.info(f"  Context:    1,048,576 tokens (1M)")
    logger.info(f"  OCR:        94.62 OmniDocBench V1.5")
    logger.info(f"  Image gen:  512×512 LCM {C.UNET_LCM_STEPS}-step  ← SHARP (consistency distillation)")
    logger.info(f"  TTS:        24kHz Kokoro 82M (voice selected by connector)")

    if errors:
        logger.error(f"\n❌ {len(errors)} checks failed:")
        for e in errors: logger.error(f"  • {e}")
        return

    logger.info(f"\n── Exporting to {EXPORT_DIR} ────────")
    model.save_pretrained(EXPORT_DIR)

    logger.info("\n✅ Export complete!  Output files:")
    for f in ["ArcleIntelligence-core.safetensors",
              "ArcleIntelligence-ocr.safetensors",
              "ArcleIntelligence-vision.safetensors",
              "ArcleIntelligence-audio.safetensors",
              "ArcleIntelligence-generation.safetensors",   # SD-VAE + UNet + Kokoro
              "ArcleIntelligence-connectors.safetensors",
              "model.safetensors.index.json",
              "config.json"]:
        path = os.path.join(EXPORT_DIR, f)
        if os.path.exists(path):
            logger.info(f"   ✅ {f}  ({os.path.getsize(path)/1e6:.0f} MB)")

    logger.info("\n   Upload to HuggingFace:")
    logger.info("   huggingface-cli login")
    logger.info(f"   huggingface-cli upload YOUR_ORG/ArcleIntelligence {EXPORT_DIR}")


if __name__ == "__main__":
    main()
