"""
inference.py — ArcleIntelligence 4.78B Inference

Usage:
  python inference.py --mode text   --prompt "What is quantum computing?"
  python inference.py --mode vision --image  photo.jpg  --prompt "Describe this"
  python inference.py --mode ocr    --image  document.pdf
  python inference.py --mode audio  --audio  speech.wav
  python inference.py --mode video  --video  clip.mp4   --prompt "Summarise"
"""

import os, sys, torch, argparse, logging
logging.basicConfig(level=logging.INFO, format="%(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config as C


class ArcleInference:
    def __init__(self, connector_dir: str = None):
        from models import ArcleOmniModel, is_identity_question, get_identity_response, build_prompt, strip_leakage
        model = ArcleOmniModel()

        # Load connectors — try multiple paths
        paths = ([connector_dir] if connector_dir else []) + [
            os.path.join(C.OUT, "connectors"),
            os.path.join(C.OUT, "ArcleIntelligence-4.78B-export", "connectors_only"),
        ]
        for p in paths:
            if p and os.path.exists(p):
                try: ArcleOmniModel.load_connectors(model, p); logger.info(f"Connectors: {p}"); break
                except Exception: continue

        model.eval()
        self.dev  = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.m    = model.to(self.dev)
        self.tok  = model.tokenizer
        # Frozen backbones were loaded in bf16 — run inference under an
        # autocast context so fp32 tensors (pixel_values, audio features,
        # connector outputs) are upcast/downcast as needed instead of
        # crashing with "expected BFloat16 but got Float".
        self._amp_dtype = torch.bfloat16 if self.dev.type == "cuda" else torch.float32
        self._is_id = is_identity_question
        self._id    = get_identity_response
        self._sys   = build_prompt
        self._strip = strip_leakage
        logger.info(f"ArcleIntelligence ready on {self.dev}")

    def _autocast(self):
        if self.dev.type == "cuda":
            return torch.autocast(device_type="cuda", dtype=self._amp_dtype)
        # CPU fallback — no-op context manager
        import contextlib
        return contextlib.nullcontext()

    @torch.no_grad()
    def text(self, prompt: str, max_new=512, temp=0.7) -> str:
        if self._is_id(prompt): return self._id()
        enc = self.tok(self._sys(prompt), return_tensors="pt").to(self.dev)
        with self._autocast():
            out = self.m.falcon.generate(
                **enc, max_new_tokens=max_new, temperature=temp,
                do_sample=temp>0, pad_token_id=self.tok.eos_token_id,
            )
        return self._strip(self.tok.decode(out[0][enc["input_ids"].shape[1]:],
                                           skip_special_tokens=True))

    @torch.no_grad()
    def vision(self, image_path: str, prompt="Describe this image:", max_new=256, temp=0.7) -> str:
        if self._is_id(prompt): return self._id()
        from PIL import Image
        img = Image.open(image_path).convert("RGB")
        pv  = self.m.siglip.preprocess(img, return_tensors="pt")["pixel_values"].to(self.dev)
        with self._autocast():
            feat = self.m.siglip(pv)
            emb  = self.m.vision_conn(feat)
            gen  = self._gen(emb, prompt, max_new, temp)
        return self._strip(gen)

    @torch.no_grad()
    def ocr(self, image_path: str, prompt="Extract all text:") -> str:
        """Full document OCR using ArcleIntelligence document engine."""
        result = self.m.glm_ocr.ocr_inference(image_path, task=prompt)
        if result: return result
        from PIL import Image
        img  = Image.open(image_path).convert("RGB")
        pv   = self.m.glm_ocr.preprocess_image(img).to(self.dev)
        with self._autocast():
            h         = self.m.glm_ocr.get_hidden_states(pv)
            emb, mask = self.m.ocr_conn(h)
            gen       = self._gen(emb, prompt, 2048, 0.1)
        return self._strip(gen)

    @torch.no_grad()
    def audio(self, audio_path: str, prompt="Transcribe:", max_new=256) -> str:
        import librosa
        arr, _  = librosa.load(audio_path, sr=16000)
        feats   = self.m.whisper.processor(arr, sampling_rate=16000,
                                           return_tensors="pt")["input_features"].to(self.dev)
        with self._autocast():
            h         = self.m.whisper(feats)
            emb, mask = self.m.audio_conn(h)
            gen       = self._gen(emb, prompt, max_new, 0.1)
        return self._strip(gen)

    @torch.no_grad()
    def video(self, video_path: str, prompt="Describe this video:", max_new=256, temp=0.7) -> str:
        if self._is_id(prompt): return self._id()
        with self._autocast():
            vtoks = self.m.video_proc(video_path=video_path, device=self.dev)
            gen   = self._gen(vtoks, prompt, max_new, temp)
        return self._strip(gen)

    @torch.no_grad()
    def image_gen(self, prompt: str, output_path: str = "output.png") -> str:
        """
        Generate a sharp 512×512 image via LCM 8-step denoising.
        guidance_scale is always 1.0 for LCM — baked in during distillation.
        """
        from PIL import Image
        enc = self.tok(f"Generate an image of: {prompt}",
                       return_tensors="pt").to(self.dev)
        with self._autocast():
            out   = self.m.falcon(**enc, output_hidden_states=True)
            cond  = self.m.imgcond_proj(out.hidden_states[-1])  # [1, 77, 768]
            img_t = self.m.sd_unet.generate(cond, self.m.sd_vae, guidance_scale=1.0)
        arr = (img_t[0].permute(1,2,0).clamp(0,1).cpu().float().numpy() * 255).astype("uint8")
        Image.fromarray(arr).save(output_path)
        return output_path

    @torch.no_grad()
    def tts(self, text: str, output_path: str = "output.wav") -> str:
        """
        Synthesise 24kHz speech. TTSConnector predicts a voice style vector;
        the closest Kokoro voice is selected via cosine similarity.
        """
        import soundfile as sf
        enc = self.tok(f"Speak: {text}", return_tensors="pt").to(self.dev)
        with self._autocast():
            out = self.m.falcon(**enc, output_hidden_states=True)
            style, _ = self.m.tts_conn(out.hidden_states[-1], enc["attention_mask"])
        wav = self.m.kokoro.synthesize(text, style)
        if wav is not None:
            sf.write(output_path, wav, 24000)
            return output_path
        return "[TTS: Kokoro not loaded — run download.sh]"

    def _gen(self, prefix, prompt, max_new, temp):
        txt  = self.m.falcon.get_input_embeddings()(
            self.tok(self._sys(prompt), return_tensors="pt").input_ids.to(self.dev)
        )
        # Match dtypes so torch.cat doesn't error on fp32-vs-bf16 mismatch.
        if prefix.dtype != txt.dtype:
            prefix = prefix.to(dtype=txt.dtype)
        comb = torch.cat([prefix, txt], dim=1)
        mask = torch.ones(1, comb.shape[1], device=self.dev, dtype=torch.long)
        out  = self.m.falcon.generate(
            inputs_embeds=comb, attention_mask=mask,
            max_new_tokens=max_new, temperature=temp,
            do_sample=temp>0, pad_token_id=self.tok.eos_token_id,
        )
        return self.tok.decode(out[0][comb.shape[1]:], skip_special_tokens=True)


def main():
    p = argparse.ArgumentParser(description="ArcleIntelligence 5.82B Inference")
    p.add_argument("--mode", required=True,
                   choices=["text","vision","ocr","audio","video","image_gen","tts"])
    p.add_argument("--prompt",  default="Describe this.")
    p.add_argument("--image",   default=None)
    p.add_argument("--audio",   default=None)
    p.add_argument("--video",   default=None)
    p.add_argument("--output",  default="output")
    p.add_argument("--max_new", type=int,   default=256)
    p.add_argument("--temp",    type=float, default=0.7)
    p.add_argument("--connector_dir", default=None)
    a = p.parse_args()

    eng = ArcleInference(connector_dir=a.connector_dir)
    print(f"\n{'─'*50}\nMode: {a.mode}  |  Prompt: {a.prompt[:60]}\n{'─'*50}")

    if   a.mode == "text":      print(eng.text(a.prompt, a.max_new, a.temp))
    elif a.mode == "vision":    print(eng.vision(a.image, a.prompt, a.max_new, a.temp))
    elif a.mode == "ocr":       print(eng.ocr(a.image, a.prompt))
    elif a.mode == "audio":     print(eng.audio(a.audio, a.prompt, a.max_new))
    elif a.mode == "video":     print(eng.video(a.video, a.prompt, a.max_new, a.temp))
    elif a.mode == "image_gen": print("Saved:", eng.image_gen(a.prompt, f"{a.output}.png"))
    elif a.mode == "tts":       print("Saved:", eng.tts(a.prompt, f"{a.output}.wav"))


if __name__ == "__main__":
    main()
