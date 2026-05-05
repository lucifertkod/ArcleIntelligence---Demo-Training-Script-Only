"""
Transparent shim to the real HuggingFace `datasets` package.
The project's dataset classes all live in data.py.
"""
import sys, os, importlib.util

def _load_real_datasets():
    this_dir = os.path.dirname(os.path.abspath(__file__))
    for entry in list(sys.path):
        abs_entry = os.path.abspath(entry) if entry else ""
        if abs_entry == this_dir or abs_entry == "":
            continue
        pkg_init = os.path.join(entry, "datasets", "__init__.py")
        if not os.path.isfile(pkg_init):
            continue
        pkg_dir = os.path.join(entry, "datasets")
        spec = importlib.util.spec_from_file_location(
            "datasets", pkg_init,
            submodule_search_locations=[pkg_dir],
        )
        if spec is None:
            continue
        mod = importlib.util.module_from_spec(spec)
        mod.__path__ = [pkg_dir]
        mod.__package__ = "datasets"
        sys.modules["datasets"] = mod
        spec.loader.exec_module(mod)
        return
    raise ImportError("Real HuggingFace 'datasets' package not found. pip install datasets")

_load_real_datasets()
