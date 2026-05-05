from datasets import load_dataset
import shutil
DATA = "/workspace/arcle/data"
print("Downloading LibriSpeech train.clean.100 ONLY...")
ds = load_dataset(
    "parquet",
    data_files="hf://datasets/openslr/librispeech_asr/all/train.clean.100/*.parquet",
    split="train"
)
ds = ds.select(range(min(15000, len(ds))))
shutil.rmtree(f"{DATA}/librispeech", ignore_errors=True)
ds.save_to_disk(f"{DATA}/librispeech")
print(f"LibriSpeech: {len(ds):,}")
# Clean cache after saving
import os
os.system("rm -rf ~/.cache/huggingface/hub/datasets--openslr* ~/.cache/huggingface/datasets/openslr*")
print("Done!")
