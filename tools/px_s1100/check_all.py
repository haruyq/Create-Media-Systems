import json
from pathlib import Path
import sys

from render_preview import validate

res, ns = sys.argv[1], sys.argv[2]
models = Path(res) / "assets" / ns / "models"
files = sorted((models / "block" / "px_s1100").glob("*.json")) + [models / "item" / "px_s1100.json"]
bad = 0
for file in files:
    model = json.loads(file.read_text(encoding="utf-8"))
    errors = validate(model, file.name)
    bad += len(errors)
    for error in errors[:5]:
        print(error)
    print(f"{file.name}: {len(model['elements'])} elements, {len(errors)} errors")
print("TOTAL ERRORS", bad)
sys.exit(bool(bad))
