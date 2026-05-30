# shader-tuning/measurements/diff.py
# No-regression check for stock-vs-edited captures taken with TAA/temporal accumulation OFF
# at identical scene state. Usage: py diff.py stock.png edited.png
import sys
import numpy as np
from PIL import Image

if len(sys.argv) != 3:
    sys.exit("usage: diff.py <stock.png> <edited.png>")

a = np.asarray(Image.open(sys.argv[1]).convert("RGB"), dtype=np.float64)
b = np.asarray(Image.open(sys.argv[2]).convert("RGB"), dtype=np.float64)
if a.shape != b.shape:
    sys.exit(f"FAIL: size mismatch {a.shape} vs {b.shape}")

d = np.abs(a - b)
mae, peak = d.mean(), d.max()
# Gate (TAA-off, identical config): tiny residual from FP/driver nondeterminism is OK.
ok = mae < 1.0 and peak < 16
print(f"MAE={mae:.4f}  PEAK={peak:.0f}  -> {'PASS' if ok else 'REGRESSION'} (gate: MAE<1.0 AND PEAK<16)")
sys.exit(0 if ok else 1)
